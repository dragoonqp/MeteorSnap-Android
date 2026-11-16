package com.meteorsnap.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 手机内置 Camera2 采集器。
 *
 * - 优先后置摄像头，不存在时回退前置/其他非 external 摄像头；
 * - 默认优先 1280x720@30fps，保证手机侧 YUV 拷贝、硬编和预览稳定；能力不足时选择最接近 16:9 规格；
 * - 不创建预览 Surface，仅通过 ImageReader 获取 YUV_420_888，降低 GPU 负载；
 * - 在专用 HandlerThread 中把带 stride 的三个 Plane 归一化为紧凑 NV21。
 *
 * Camera2 的 Image 和 Plane Buffer 只在回调期间有效，因此 [Nv21Frame.data] 必须在这里完成
 * 独立复制后才能交给 Service 的协程队列。
 */
class InternalCameraManager(
    context: Context,
    private val callback: Callback
) : Closeable {

    interface Callback {
        fun onConnecting(cameraId: String, displayName: String) = Unit
        fun onConnected(
            cameraId: String,
            displayName: String,
            format: CameraStreamFormat
        ) = Unit

        fun onDisconnected(cameraId: String?) = Unit
        fun onFrame(frame: Nv21Frame)
        fun onError(message: String, throwable: Throwable? = null) = Unit
    }

    private data class CameraCandidate(
        val id: String,
        val characteristics: CameraCharacteristics,
        val displayName: String
    )

    private val appContext = context.applicationContext
    private val systemCameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("MeteorSnap-Camera2").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val released = AtomicBoolean(false)

    // 以下可变状态全部只在 cameraThread 访问。
    private var started = false
    private var requestedCameraId: String? = null
    private var activeCameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var opening = false
    private var availabilityRegistered = false
    private var generation = 0
    private var reconnectToken = 0
    private var reconnectAttempt = 0
    private var lastFrameTimestampNs = 0L

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (!started || released.get() || opening || cameraDevice != null) return
            if (requestedCameraId != null && requestedCameraId != cameraId) return
            if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) return

            // 相机可能被其他 App 长时间占用并耗尽有限重试；系统重新报告 available 时
            // 立即重置预算并恢复，而不是让前台服务永久停在“等待重连”。
            reconnectAttempt = 0
            reconnectToken++
            openSelectedCamera()
        }
    }

    /** preferredCameraId 为空时自动选择后置主摄。 */
    fun start(preferredCameraId: String? = null) {
        check(!released.get()) { "InternalCameraManager 已关闭" }
        cameraHandler.post {
            if (released.get()) return@post
            requestedCameraId = preferredCameraId
            started = true
            reconnectAttempt = 0
            reconnectToken++
            registerAvailabilityCallback()
            openSelectedCamera()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSelectedCamera() {
        if (!started || released.get() || opening || cameraDevice != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyError("缺少 CAMERA 权限，无法打开手机摄像头", null)
            return
        }

        closeCaptureResources()
        val candidate = try {
            selectCamera(requestedCameraId)
        } catch (error: Throwable) {
            notifyError("读取手机摄像头能力失败", error)
            scheduleReconnect()
            return
        }
        if (candidate == null) {
            notifyError("没有发现可输出 YUV_420_888 的内置摄像头", null)
            return
        }
        // 自动选择也固定为一个 cameraId，确保 AvailabilityCallback 不会因其他镜头变化
        // 重复打开或在重试期间意外切换摄像头。
        requestedCameraId = candidate.id

        val streamMap = candidate.characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )
        val size = chooseOutputSize(
            sizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty(),
            minimumFrameDurationNs = { candidateSize ->
                runCatching {
                    streamMap?.getOutputMinFrameDuration(
                        ImageFormat.YUV_420_888,
                        candidateSize
                    ) ?: 0L
                }.getOrDefault(0L)
            }
        )
        if (size == null) {
            notifyError("${candidate.displayName} 不支持 YUV_420_888", null)
            return
        }
        val minimumDurationNs = runCatching {
            streamMap?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size) ?: 0L
        }.getOrDefault(0L)
        val sizeMaximumFps = if (minimumDurationNs > 0L) {
            (NANOSECONDS_PER_SECOND / minimumDurationNs).toInt().coerceAtLeast(1)
        } else {
            TARGET_FPS
        }
        val desiredFps = min(TARGET_FPS, sizeMaximumFps)
        val fpsRange = chooseFpsRange(
            candidate.characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ).orEmpty(),
            desiredFps
        )
        val reportedFps = fpsRange?.let { desiredFps.coerceIn(it.lower, it.upper) } ?: desiredFps
        val format = CameraStreamFormat(
            width = size.width,
            height = size.height,
            fps = reportedFps,
            rotationDegrees = calculateOutputRotation(candidate.characteristics)
        )
        val openGeneration = ++generation
        lastFrameTimestampNs = 0L
        opening = true
        notifySafely { callback.onConnecting(candidate.id, candidate.displayName) }

        try {
            systemCameraManager.openCamera(
                candidate.id,
                createDeviceStateCallback(candidate, size, fpsRange, format, openGeneration),
                cameraHandler
            )
        } catch (error: SecurityException) {
            opening = false
            notifyError("手机摄像头权限被系统拒绝", error)
        } catch (error: CameraAccessException) {
            opening = false
            notifyError("打开 ${candidate.displayName} 失败", error)
            scheduleReconnect()
        } catch (error: IllegalArgumentException) {
            opening = false
            notifyError("摄像头 ${candidate.id} 已不可用", error)
            scheduleReconnect()
        }
    }

    private fun createDeviceStateCallback(
        candidate: CameraCandidate,
        size: Size,
        fpsRange: Range<Int>?,
        format: CameraStreamFormat,
        openGeneration: Int
    ) = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (!isCurrent(openGeneration)) {
                device.close()
                return
            }
            opening = false
            cameraDevice = device
            activeCameraId = candidate.id
            configureCaptureSession(
                candidate,
                device,
                size,
                fpsRange,
                format,
                openGeneration
            )
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (!isCurrent(openGeneration)) return
            closeCaptureResources()
            notifySafely { callback.onDisconnected(candidate.id) }
            scheduleReconnect()
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (!isCurrent(openGeneration)) return
            closeCaptureResources()
            notifyError(
                "${candidate.displayName} 发生错误：${cameraErrorName(error)}",
                null
            )
            notifySafely { callback.onDisconnected(candidate.id) }
            scheduleReconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun configureCaptureSession(
        candidate: CameraCandidate,
        device: CameraDevice,
        size: Size,
        fpsRange: Range<Int>?,
        format: CameraStreamFormat,
        openGeneration: Int
    ) {
        try {
            val reader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                IMAGE_READER_MAX_IMAGES
            )
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                image.use {
                    if (!isCurrent(openGeneration)) return@use
                    try {
                        val converted = imageToNv21(it)
                        notifySafely { callback.onFrame(converted) }
                    } catch (error: Throwable) {
                        notifyError("转换手机摄像头 YUV 帧失败", error)
                    }
                }
            }, cameraHandler)

            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isCurrent(openGeneration) || cameraDevice !== device) {
                            session.close()
                            return
                        }
                        try {
                            val request = buildCaptureRequest(
                                device,
                                reader,
                                candidate.characteristics,
                                fpsRange
                            )
                            captureSession = session
                            session.setRepeatingRequest(request, null, cameraHandler)
                            cancelReconnect()
                            notifySafely {
                                callback.onConnected(
                                    candidate.id,
                                    candidate.displayName,
                                    format
                                )
                            }
                        } catch (error: Throwable) {
                            session.close()
                            if (!isCurrent(openGeneration)) return
                            closeCaptureResources()
                            notifyError("启动 ${candidate.displayName} 视频流失败", error)
                            scheduleReconnect()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (!isCurrent(openGeneration)) return
                        closeCaptureResources()
                        notifyError("${candidate.displayName} 无法创建视频会话", null)
                        scheduleReconnect()
                    }
                },
                cameraHandler
            )
        } catch (error: Throwable) {
            closeCaptureResources()
            notifyError("创建 ${candidate.displayName} 视频会话失败", error)
            scheduleReconnect()
        }
    }

    private fun buildCaptureRequest(
        device: CameraDevice,
        reader: ImageReader,
        characteristics: CameraCharacteristics,
        fpsRange: Range<Int>?
    ): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }

        val afModes = characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        ) ?: intArrayOf()
        when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) -> {
                // Camera2 中 0 屈光度代表最远可用对焦位置，适合星空且避免连续 AF 来回搜索。
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val minimumFocusDistance = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                )
                if (minimumFocusDistance != null && minimumFocusDistance > 0f) {
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }
            }
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
        }

        val aeModes = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES
        ) ?: intArrayOf()
        val manualSensorSupported = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        if (manualSensorSupported &&
            exposureRange != null &&
            isoRange != null &&
            aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)
        ) {
            // 星空/银河监测默认值：固定曝光，避免夜间 AE 因云、屏幕、远处灯光反复跳变。
            // 这里保持 1/30s 以维持视频分析节奏；多秒堆栈式夜景不是实时流星监测能采用的模式。
            val exposureNs = NIGHT_SKY_EXPOSURE_NS.coerceIn(
                exposureRange.lower,
                exposureRange.upper
            )
            val iso = NIGHT_SKY_ISO.coerceIn(isoRange.lower, isoRange.upper)
            val frameDurationNs = maxOf(
                THIRTY_FPS_FRAME_DURATION_NS,
                exposureNs
            )

            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        } else if (aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
            // 低端/受限机型没有 MANUAL_SENSOR 时，只能让 AE 工作，并给一点正 EV 补偿。
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            applyPositiveExposureCompensation(builder, characteristics)
        }

        val awbModes = characteristics.get(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
        ) ?: intArrayOf()
        when {
            awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
            awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        applyQualityModes(builder, characteristics)
        return builder.build()
    }

    private fun applyPositiveExposureCompensation(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ) {
        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: return
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: return
        val stepValue = step.numerator.toDouble() / step.denominator.toDouble()
        if (!stepValue.isFinite() || stepValue <= 0.0) return
        val requestedSteps = (NIGHT_SKY_EV_BOOST / stepValue).roundToInt()
            .coerceIn(range.lower, range.upper)
        if (requestedSteps != 0) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, requestedSteps)
        }
    }

    private fun applyQualityModes(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ) {
        val noiseModes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        ) ?: intArrayOf()
        when {
            noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) ->
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) ->
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }

        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?: intArrayOf()
        when {
            edgeModes.contains(CaptureRequest.EDGE_MODE_OFF) ->
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            edgeModes.contains(CaptureRequest.EDGE_MODE_FAST) ->
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        }
    }

    private fun selectCamera(preferredId: String?): CameraCandidate? {
        val candidates = systemCameraManager.cameraIdList.mapNotNull { id ->
            val characteristics = systemCameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_EXTERNAL
            ) {
                return@mapNotNull null
            }
            val sizes = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.getOutputSizes(ImageFormat.YUV_420_888)
            if (sizes.isNullOrEmpty()) return@mapNotNull null
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val displayName = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "手机后置摄像头"
                CameraCharacteristics.LENS_FACING_FRONT -> "手机前置摄像头"
                else -> "手机内置摄像头"
            }
            CameraCandidate(id, characteristics, displayName)
        }
        return candidates.firstOrNull { it.id == preferredId }
            ?: candidates.firstOrNull {
                it.characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            ?: candidates.firstOrNull {
                it.characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }
            ?: candidates.firstOrNull()
    }

    private fun chooseOutputSize(
        sizes: Array<out Size>,
        minimumFrameDurationNs: (Size) -> Long
    ): Size? {
        val usable = sizes.filter { it.width > 0 && it.height > 0 && it.width % 2 == 0 && it.height % 2 == 0 }
        if (usable.isEmpty()) return null
        val thirtyFpsCapable = usable.filter { size ->
            val duration = minimumFrameDurationNs(size)
            duration <= 0L || duration <= THIRTY_FPS_FRAME_DURATION_NS
        }
        val candidates = thirtyFpsCapable.ifEmpty { usable }
        val targetArea = TARGET_WIDTH.toLong() * TARGET_HEIGHT
        val targetAspect = TARGET_WIDTH.toDouble() / TARGET_HEIGHT
        return candidates.minWithOrNull(
            compareBy<Size>(
                { if (it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT) 0 else 1 },
                {
                    if (max(it.width, it.height) <= TARGET_WIDTH &&
                        min(it.width, it.height) <= TARGET_HEIGHT
                    ) 0 else 1
                },
                { abs(it.width.toDouble() / it.height - targetAspect) },
                { abs(it.width.toLong() * it.height - targetArea) }
            )
        )
    }

    private fun chooseFpsRange(
        ranges: Array<out Range<Int>>,
        desiredFps: Int
    ): Range<Int>? {
        return ranges.minWithOrNull(
            compareBy<Range<Int>>(
                { if (it.contains(desiredFps)) 0 else 1 },
                { abs(it.upper - desiredFps) },
                { it.upper - it.lower },
                { abs(it.lower - desiredFps) }
            )
        )
    }

    /** 把任意合法 rowStride/pixelStride/cropRect 的 YUV_420_888 图像复制为紧凑 NV21。 */
    private fun imageToNv21(image: Image): Nv21Frame {
        require(image.format == ImageFormat.YUV_420_888) {
            "期望 YUV_420_888，实际 ${image.format}"
        }
        require(image.planes.size == 3) { "YUV_420_888 必须包含三个 Plane" }
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "NV21 要求正偶数尺寸，实际 ${width}x$height"
        }
        require(crop.left % 2 == 0 && crop.top % 2 == 0) {
            "YUV420 crop 起点必须色度对齐，实际 (${crop.left}, ${crop.top})"
        }

        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 3) / 2)
        copyLumaPlane(
            plane = image.planes[0],
            cropLeft = crop.left,
            cropTop = crop.top,
            width = width,
            height = height,
            output = output
        )

        val u = PlaneReader(image.planes[1].buffer, image.planes[1].rowStride, image.planes[1].pixelStride)
        val v = PlaneReader(image.planes[2].buffer, image.planes[2].rowStride, image.planes[2].pixelStride)
        val chromaLeft = crop.left / 2
        val chromaTop = crop.top / 2
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var destination = width * height
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                // NV21 的色度顺序严格为 V、U。
                output[destination++] = v.get(chromaTop + row, chromaLeft + column)
                output[destination++] = u.get(chromaTop + row, chromaLeft + column)
            }
        }

        val sourceTimestamp = image.timestamp
        val timestamp = when {
            sourceTimestamp > lastFrameTimestampNs -> sourceTimestamp
            lastFrameTimestampNs > 0L -> lastFrameTimestampNs + 1L
            else -> SystemClock.elapsedRealtimeNanos()
        }
        lastFrameTimestampNs = timestamp
        return Nv21Frame(output, timestamp, width, height)
    }

    private fun copyLumaPlane(
        plane: Image.Plane,
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
        output: ByteArray
    ) {
        val source = plane.buffer.duplicate()
        val base = source.position()
        if (plane.pixelStride == 1) {
            for (row in 0 until height) {
                val sourceOffset = base + (cropTop + row) * plane.rowStride + cropLeft
                require(sourceOffset >= base && sourceOffset + width <= source.limit()) {
                    "Y Plane 数据不足"
                }
                val rowBuffer = source.duplicate().apply {
                    position(sourceOffset)
                    limit(sourceOffset + width)
                }
                rowBuffer.get(output, row * width, width)
            }
            return
        }

        val reader = PlaneReader(plane.buffer, plane.rowStride, plane.pixelStride)
        var destination = 0
        for (row in 0 until height) {
            for (column in 0 until width) {
                output[destination++] = reader.get(cropTop + row, cropLeft + column)
            }
        }
    }

    private class PlaneReader(
        buffer: ByteBuffer,
        private val rowStride: Int,
        private val pixelStride: Int
    ) {
        private val data = buffer.duplicate()
        private val base = data.position()

        init {
            require(rowStride > 0 && pixelStride > 0) { "非法 Plane stride" }
        }

        fun get(row: Int, column: Int): Byte {
            val index = base + row * rowStride + column * pixelStride
            require(index >= base && index < data.limit()) { "Plane 数据越界：$index/${data.limit()}" }
            return data.get(index)
        }
    }

    private fun isCurrent(openGeneration: Int): Boolean =
        !released.get() && started && openGeneration == generation

    /**
     * Camera2 输出仍保持传感器原始像素方向；把相对当前屏幕方向写入 MP4 rotation metadata，
     * 避免竖屏安装的手机视频横倒。相机运行中不建议改变设备安装方向。
     */
    @Suppress("DEPRECATION")
    private fun calculateOutputRotation(characteristics: CameraCharacteristics): Int {
        val displayRotation = appContext.getSystemService(WindowManager::class.java)
            ?.defaultDisplay
            ?.rotation
            ?: Surface.ROTATION_0
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        return camera2VideoRotationDegrees(
            sensorOrientationDegrees = sensorDegrees,
            displayRotationDegrees = deviceDegrees,
            isFrontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT
        )
    }

    private fun scheduleReconnect() {
        if (!started || released.get() || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) return
        val attempt = reconnectAttempt++
        val delayMs = min(MAX_RECONNECT_DELAY_MS, (1 shl attempt).toLong() * 1_000L)
        val token = ++reconnectToken
        cameraHandler.postDelayed({
            if (!started || released.get() || token != reconnectToken) return@postDelayed
            openSelectedCamera()
        }, delayMs)
    }

    private fun cancelReconnect() {
        reconnectAttempt = 0
        reconnectToken++
    }

    private fun registerAvailabilityCallback() {
        if (availabilityRegistered) return
        systemCameraManager.registerAvailabilityCallback(availabilityCallback, cameraHandler)
        availabilityRegistered = true
    }

    private fun unregisterAvailabilityCallback() {
        if (!availabilityRegistered) return
        runCatching { systemCameraManager.unregisterAvailabilityCallback(availabilityCallback) }
        availabilityRegistered = false
    }

    private fun closeCaptureResources() {
        generation++
        opening = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { imageReader?.close() }
        imageReader = null
        activeCameraId = null
        lastFrameTimestampNs = 0L
    }

    private fun notifyError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
        notifySafely { callback.onError(message, throwable) }
    }

    private inline fun notifySafely(block: () -> Unit) {
        runCatching(block).onFailure { Log.e(TAG, "相机回调处理失败", it) }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        val releaseWork = {
            started = false
            reconnectToken++
            unregisterAvailabilityCallback()
            closeCaptureResources()
        }
        if (Looper.myLooper() == cameraThread.looper) {
            releaseWork()
        } else {
            val latch = CountDownLatch(1)
            if (cameraHandler.post {
                    try {
                        releaseWork()
                    } finally {
                        latch.countDown()
                    }
                }
            ) {
                runCatching { latch.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
        }
        cameraThread.quitSafely()
        if (Thread.currentThread() !== cameraThread) {
            runCatching { cameraThread.join(TimeUnit.SECONDS.toMillis(CLOSE_TIMEOUT_SECONDS)) }
        }
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "摄像头正在被其他应用使用"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "系统已打开过多摄像头"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "摄像头被系统策略禁用"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "摄像头设备故障"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "系统相机服务故障"
        else -> "未知错误($error)"
    }

    companion object {
        private const val TAG = "InternalCameraManager"
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
        private const val TARGET_FPS = 30
        private const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        private const val THIRTY_FPS_FRAME_DURATION_NS = 33_500_000L
        private const val NIGHT_SKY_EXPOSURE_NS = 33_333_333L
        private const val NIGHT_SKY_ISO = 1_600
        private const val NIGHT_SKY_EV_BOOST = 1.0
        private const val IMAGE_READER_MAX_IMAGES = 3
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val CLOSE_TIMEOUT_SECONDS = 2L
    }
}
