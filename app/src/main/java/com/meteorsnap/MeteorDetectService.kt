package com.meteorsnap

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.meteorsnap.buffer.RingBuffer
import com.meteorsnap.camera.CameraSource
import com.meteorsnap.camera.CameraStreamFormat
import com.meteorsnap.camera.InternalCameraManager
import com.meteorsnap.camera.Nv21Frame
import com.meteorsnap.camera.PreviewFrame
import com.meteorsnap.cv.AnalysisResult
import com.meteorsnap.cv.DetectionSensitivity
import com.meteorsnap.cv.FrameAnalyzer
import com.meteorsnap.cv.FrameLayout
import com.meteorsnap.media.HardwareH264Encoder
import com.meteorsnap.media.MeteorClipWriter
import com.meteorsnap.media.MeteorPhotoWriter
import com.meteorsnap.media.PhotoTrailComposer
import com.meteorsnap.media.PhotoTrailFrame
import com.meteorsnap.uvc.UVCCameraManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * MeteorSnap 的常驻编排服务。
 *
 * 数据流以“录像优先”为原则：手机 Camera2 或 UVC 都先归一化为 NV21，随后分别送入
 * 编码队列与分析队列。编码队列只做 MediaCodec 写入和 MP4 事件收集，正常情况下保留每一帧；
 * 极端背压时只丢当前新帧，不回头删除已排队帧，尽量保持 MP4 时间线连续。
 * OpenCV 分析和流星照片采样走独立队列，压力大时允许丢旧帧，避免把保存视频拖成卡顿。
 */
class MeteorDetectService : Service() {

    private val serviceJob = SupervisorJob()
    /** UI/磁盘辅助任务使用普通 IO；相机流水线另由 pipelineDispatcher 串行拥有。 */
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    /** MediaCodec 与事件状态必须严格按帧顺序访问；磁盘写入仍使用普通 IO 池。 */
    private val pipelineDispatcher = Dispatchers.IO.limitedParallelism(1)
    /** OpenCV 分析单独串行执行，慢了只丢检测帧，不再阻塞视频编码。 */
    private val analysisDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val encodeFrameChannel = Channel<Nv21Frame>(
        capacity = ENCODE_FRAME_CHANNEL_CAPACITY
    )
    private val analysisFrameChannel = Channel<Nv21Frame>(
        capacity = ANALYSIS_FRAME_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var batteryReceiverRegistered = false
    @Volatile private var batteryHot = false
    @Volatile private var systemHot = false
    private var releaseWakeLockAfterIdle = true

    private var uvcCameraManager: UVCCameraManager? = null
    private var internalCameraManager: InternalCameraManager? = null
    private var activeCameraSource: CameraSource? = null
    private val lastPreviewEmittedTimestampNs = AtomicLong(Long.MIN_VALUE)
    @Volatile private var acceptingFrames = false
    @Volatile private var expectedFrameWidth = 0
    @Volatile private var expectedFrameHeight = 0
    @Volatile private var previewRotationDegrees = 0
    private var analyzer: FrameAnalyzer? = null
    private var saveVideoOutput = true
    private var savePhotoOutput = false
    private var detectionSensitivity = DetectionSensitivity.DEFAULT
    private var trajectoryMaxWidth = DEFAULT_TRAJECTORY_MAX_WIDTH
    private var trajectoryMinLength = DEFAULT_TRAJECTORY_MIN_LENGTH
    private var smallMeteorBoost = DEFAULT_SMALL_METEOR_BOOST
    private var encoder: HardwareH264Encoder? = null
    private var encoderFormat: MediaFormat? = null
    private var encoderGeneration = -1
    private var encoderRotationDegrees = 0
    private val encoderFatalReported = AtomicBoolean(false)
    private val encodedRing = RingBuffer<HardwareH264Encoder.EncodedSample>(
        maxDurationNanos = ENCODED_RING_DURATION_NS,
        maxEntries = ENCODED_RING_MAX_SAMPLES,
        copier = { sample: HardwareH264Encoder.EncodedSample -> sample.copy() },
        onReleaseError = { error -> Log.w(TAG, "释放编码缓存失败", error) }
    )
    private val photoRing = RingBuffer<PhotoTrailFrame>(
        maxDurationNanos = PHOTO_RING_DURATION_NS,
        maxEntries = PHOTO_RING_MAX_FRAMES,
        copier = { frame: PhotoTrailFrame -> frame.copy() },
        onReleaseError = { error -> Log.w(TAG, "释放流星照片缓存失败", error) }
    )
    private var lastPhotoTrailSampleNs = Long.MIN_VALUE

    @Volatile private var eventCapture: EventCapture? = null
    private var captureTimeoutJob: Job? = null
    private var frameWatchdogJob: Job? = null
    private val usbFirstFrameReceived = AtomicBoolean(false)
    private var wakeMaintenanceJob: Job? = null
    @Volatile private var lastMotionElapsedMs = 0L
    private var lastMotionCandidateStatusElapsedMs = 0L
    private var lastWakeRenewElapsedMs = 0L
    private var monitoringStarted = false
    private var initializationFailed = false

    private data class EventCapture(
        val triggerTimestampNs: Long,
        val endTimestampNs: Long,
        val wallClockMillis: Long,
        val generation: Int,
        val rotationDegrees: Int,
        val format: MediaFormat,
        val samples: MutableList<HardwareH264Encoder.EncodedSample>,
        val saveVideo: Boolean,
        val savePhoto: Boolean,
        val photoFrames: MutableList<PhotoTrailFrame>
    )

    private data class CaptureOutputs(
        val saveVideo: Boolean,
        val savePhoto: Boolean
    )

    override fun onCreate() {
        super.onCreate()
        publishStatus("当前状态：正在初始化")

        powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:MeteorDetection"
        ).apply { setReferenceCounted(false) }

        // startForegroundService 后必须尽快前台化，不能先加载 OpenCV/UVC native 库。
        // 此时还没有 USB grant，所以只声明 camera 类型；connectedDevice 在 onConnected 后追加。
        createNotificationChannels()
        if (!startForegroundNow(
                notification = buildMonitoringNotification("正在初始化摄像头…"),
                includeConnectedDevice = false
            )
        ) {
            initializationFailed = true
            return
        }
        running.set(true)
        lastHeartbeatElapsedMs.set(SystemClock.elapsedRealtime())

        registerThermalObservers()

        try {
            check(OpenCVLoader.initLocal()) { "OpenCVLoader.initLocal 返回 false" }
            // UVC 路径必须显式指定 NV21，AUTO 无法区分灰度图和 H*3/2 的 NV21 Mat。
            analyzer = FrameAnalyzer(inputLayout = FrameLayout.NV21)
        } catch (error: Throwable) {
            reportFatalError("OpenCV 加载失败", error)
            return
        }

        // 相机管理器必须等用户选定来源后再创建。这样 UVC native 库不可用时，手机
        // Camera2 模式仍能正常工作；反之亦然。
        serviceScope.launch(pipelineDispatcher) { consumeEncodingFrames() }
        serviceScope.launch(analysisDispatcher) { consumeAnalysisFrames() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (initializationFailed) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit {
                putBoolean(PREF_MONITORING_WANTED, false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (intent?.action == ACTION_UPDATE_UVC_PARAMETERS) {
            if (monitoringStarted && activeCameraSource == CameraSource.USB) {
                applySavedUvcParameters(delayMillis = 0L, publishChange = true)
            }
            return START_STICKY
        }

        val requestedSensitivity = DetectionSensitivity.fromLevel(
            intent?.takeIf { it.hasExtra(EXTRA_DETECTION_SENSITIVITY) }
                ?.getIntExtra(EXTRA_DETECTION_SENSITIVITY, DetectionSensitivity.DEFAULT.level)
                ?: preferences.getInt(
                    PREF_DETECTION_SENSITIVITY,
                    DetectionSensitivity.DEFAULT.level
                )
        )
        val requestedTrajectoryMaxWidth = (
            intent?.takeIf { it.hasExtra(EXTRA_TRAJECTORY_MAX_WIDTH) }
                ?.getIntExtra(EXTRA_TRAJECTORY_MAX_WIDTH, DEFAULT_TRAJECTORY_MAX_WIDTH)
                ?: preferences.getInt(PREF_TRAJECTORY_MAX_WIDTH, DEFAULT_TRAJECTORY_MAX_WIDTH)
            ).coerceIn(MIN_TRAJECTORY_MAX_WIDTH, MAX_TRAJECTORY_MAX_WIDTH)
        val requestedTrajectoryMinLength = (
            intent?.takeIf { it.hasExtra(EXTRA_TRAJECTORY_MIN_LENGTH) }
                ?.getIntExtra(EXTRA_TRAJECTORY_MIN_LENGTH, DEFAULT_TRAJECTORY_MIN_LENGTH)
                ?: preferences.getInt(PREF_TRAJECTORY_MIN_LENGTH, DEFAULT_TRAJECTORY_MIN_LENGTH)
            ).coerceIn(MIN_TRAJECTORY_MIN_LENGTH, MAX_TRAJECTORY_MIN_LENGTH)
        val requestedSmallMeteorBoost = intent
            ?.takeIf { it.hasExtra(EXTRA_SMALL_METEOR_BOOST) }
            ?.getBooleanExtra(EXTRA_SMALL_METEOR_BOOST, DEFAULT_SMALL_METEOR_BOOST)
            ?: preferences.getBoolean(PREF_SMALL_METEOR_BOOST, DEFAULT_SMALL_METEOR_BOOST)
        val requestedOutputs = readRequestedCaptureOutputs(
            intent = intent,
            preferences = preferences
        )
        if (intent?.action == ACTION_UPDATE_SENSITIVITY) {
            preferences.edit {
                putInt(PREF_DETECTION_SENSITIVITY, requestedSensitivity.level)
                putInt(PREF_TRAJECTORY_MAX_WIDTH, requestedTrajectoryMaxWidth)
                putInt(PREF_TRAJECTORY_MIN_LENGTH, requestedTrajectoryMinLength)
                putBoolean(PREF_SMALL_METEOR_BOOST, requestedSmallMeteorBoost)
            }
            serviceScope.launch(pipelineDispatcher) {
                applyDetectionSettings(
                    requestedSensitivity,
                    requestedTrajectoryMaxWidth,
                    requestedTrajectoryMinLength,
                    requestedSmallMeteorBoost,
                    publishChange = true
                )
            }
            return START_STICKY
        }

        val preferredDeviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
            ?: preferences.getString(PREF_DEVICE_NAME, null)
        val cameraSource = CameraSource.fromPreference(
            value = intent?.getStringExtra(EXTRA_CAMERA_SOURCE)
                ?: preferences.getString(PREF_CAMERA_SOURCE, null),
            legacyUsbDeviceName = preferredDeviceName
        )
        releaseWakeLockAfterIdle = intent?.takeIf { it.hasExtra(EXTRA_POWER_SAVING) }
            ?.getBooleanExtra(EXTRA_POWER_SAVING, true)
            ?: preferences.getBoolean(PREF_POWER_SAVING, true)

        if (intent?.action == ACTION_START) {
            preferences.edit {
                putBoolean(PREF_MONITORING_WANTED, true)
                putBoolean(PREF_POWER_SAVING, releaseWakeLockAfterIdle)
                putString(PREF_CAMERA_SOURCE, cameraSource.preferenceValue)
                putBoolean(PREF_SAVE_VIDEO, requestedOutputs.saveVideo)
                putBoolean(PREF_SAVE_PHOTO, requestedOutputs.savePhoto)
                putString(
                    PREF_CAPTURE_MODE,
                    if (requestedOutputs.savePhoto) {
                        CaptureMode.PHOTO_TRAIL.preferenceValue
                    } else {
                        CaptureMode.VIDEO_ONLY.preferenceValue
                    }
                )
                putInt(PREF_DETECTION_SENSITIVITY, requestedSensitivity.level)
                putInt(PREF_TRAJECTORY_MAX_WIDTH, requestedTrajectoryMaxWidth)
                putInt(PREF_TRAJECTORY_MIN_LENGTH, requestedTrajectoryMinLength)
                putBoolean(PREF_SMALL_METEOR_BOOST, requestedSmallMeteorBoost)
                if (cameraSource == CameraSource.USB) {
                    putString(PREF_DEVICE_NAME, preferredDeviceName)
                } else {
                    remove(PREF_DEVICE_NAME)
                }
            }
        }

        if (!hasCameraPermission()) {
            reportFatalError("缺少 CAMERA 权限，请返回应用重新授权", null)
            return START_NOT_STICKY
        }

        if (!monitoringStarted) {
            monitoringStarted = true
            activeCameraSource = cameraSource
            saveVideoOutput = requestedOutputs.saveVideo
            savePhotoOutput = requestedOutputs.savePhoto
            applyDetectionSettings(
                requestedSensitivity,
                requestedTrajectoryMaxWidth,
                requestedTrajectoryMinLength,
                requestedSmallMeteorBoost,
                publishChange = false
            )
            lastMotionElapsedMs = SystemClock.elapsedRealtime()
            acquireOrRenewWakeLock()
            startWakeMaintenance()
            startCameraSource(cameraSource, preferredDeviceName)
        } else if (activeCameraSource != cameraSource) {
            publishStatus("当前状态：请先停止监测，再切换摄像头来源")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 根据用户选择按需创建且只创建一种相机实现。 */
    private fun startCameraSource(source: CameraSource, preferredDeviceName: String?) {
        when (source) {
            CameraSource.PHONE -> {
                publishStatus("当前状态：正在打开手机摄像头")
                try {
                    internalCameraManager = InternalCameraManager(this, internalCameraCallback)
                        .also { it.start() }
                } catch (error: Throwable) {
                    reportFatalError("手机摄像头初始化失败", error)
                }
            }

            CameraSource.USB -> {
                publishStatus("当前状态：等待 USB 授权/连接")
                try {
                    uvcCameraManager = UVCCameraManager(this, uvcCallback)
                        .also { it.start(preferredDeviceName) }
                } catch (error: Throwable) {
                    reportFatalError("UVC 库加载失败", error)
                }
            }
        }
    }

    private val uvcCallback = object : UVCCameraManager.Callback {
        override fun onDeviceListChanged(devices: List<UsbDevice>) = Unit

        override fun onConnecting(device: UsbDevice) {
            if (!monitoringStarted || activeCameraSource != CameraSource.USB) return
            publishStatus("当前状态：正在连接 ${device.deviceName}")
        }

        override fun onConnected(
            device: UsbDevice,
            format: CameraStreamFormat
        ) {
            if (!monitoringStarted || activeCameraSource != CameraSource.USB) return
            publishStatus("当前状态：监测中 ${format.width}×${format.height}@${format.fps}fps")
            lastHeartbeatElapsedMs.set(SystemClock.elapsedRealtime())
            acquireOrRenewWakeLock()
            promoteConnectedNotification("${format.width}×${format.height}@${format.fps}fps")
            configureConnectedStream(format)
            startUsbFrameWatchdog(device, format)
            serviceScope.launch {
                applySavedUvcParameters(delayMillis = 500L, publishChange = false)
            }
        }

        override fun onDisconnected(device: UsbDevice?) {
            if (!monitoringStarted || activeCameraSource != CameraSource.USB) return
            publishStatus("当前状态：摄像头已断开，等待重连")
            stopFrameWatchdog()
            disconnectStream()
        }

        override fun onPermissionCancelled(device: UsbDevice) {
            if (!monitoringStarted || activeCameraSource != CameraSource.USB) return
            publishStatus("当前状态：USB 授权已取消，请返回页面重试")
        }

        override fun onFrame(frame: Nv21Frame) {
            if (usbFirstFrameReceived.compareAndSet(false, true)) {
                publishStatus(
                    "当前状态：监测中 ${frame.width}×${frame.height}；已收到视频帧，USB 供电至少已能启动推流"
                )
            }
            // 相机回调不能阻塞；编码队列若已满会拒绝当前新帧，避免删除已排队帧造成 MP4 时间线跳变。
            enqueueFrame(frame)
        }

        override fun onError(message: String, throwable: Throwable?) {
            Log.e(TAG, message, throwable)
            if (!monitoringStarted || activeCameraSource != CameraSource.USB) return
            publishStatus("当前状态：$message")
            updateForegroundNotification(message)
        }
    }

    private val internalCameraCallback = object : InternalCameraManager.Callback {
        override fun onConnecting(cameraId: String, displayName: String) {
            if (!monitoringStarted || activeCameraSource != CameraSource.PHONE) return
            publishStatus("当前状态：正在连接 $displayName")
        }

        override fun onConnected(
            cameraId: String,
            displayName: String,
            format: CameraStreamFormat
        ) {
            if (!monitoringStarted || activeCameraSource != CameraSource.PHONE) return
            val detail = "$displayName ${format.width}×${format.height}@${format.fps}fps"
            publishStatus("当前状态：监测中 $detail")
            lastHeartbeatElapsedMs.set(SystemClock.elapsedRealtime())
            acquireOrRenewWakeLock()
            // 手机模式只有 camera 类型，不声明 connectedDevice。
            startForegroundNow(buildMonitoringNotification(detail), includeConnectedDevice = false)
            configureConnectedStream(format)
        }

        override fun onDisconnected(cameraId: String?) {
            if (!monitoringStarted || activeCameraSource != CameraSource.PHONE) return
            publishStatus("当前状态：手机摄像头已断开，等待重连")
            disconnectStream()
        }

        override fun onFrame(frame: Nv21Frame) {
            enqueueFrame(frame)
        }

        override fun onError(message: String, throwable: Throwable?) {
            Log.e(TAG, message, throwable)
            if (!monitoringStarted || activeCameraSource != CameraSource.PHONE) return
            publishStatus("当前状态：$message")
            updateForegroundNotification(message)
        }
    }

    /** 连接阶段先关闭入口，待编码器/分析器在单线程完成换代后才接受该规格的帧。 */
    private fun configureConnectedStream(format: CameraStreamFormat) {
        acceptingFrames = false
        expectedFrameWidth = 0
        expectedFrameHeight = 0
        val token = streamGeneration.incrementAndGet()
        _previewGeneration.value = NO_PREVIEW_GENERATION
        lastPreviewEmittedTimestampNs.set(Long.MIN_VALUE)
        serviceScope.launch(pipelineDispatcher) {
            if (token != streamGeneration.get()) return@launch
            try {
                drainQueuedFrames()
                photoRing.clear()
                lastPhotoTrailSampleNs = Long.MIN_VALUE
                restartEncoder(format)
                analyzer?.reset()
                expectedFrameWidth = format.width
                expectedFrameHeight = format.height
                previewRotationDegrees = format.rotationDegrees
                if (token == streamGeneration.get()) {
                    _previewGeneration.value = token
                    acceptingFrames = true
                }
            } catch (error: Throwable) {
                acceptingFrames = false
                _previewGeneration.value = NO_PREVIEW_GENERATION
                encoderCallback.onEncoderError(error)
            }
        }
    }

    private fun disconnectStream() {
        stopFrameWatchdog()
        acceptingFrames = false
        expectedFrameWidth = 0
        expectedFrameHeight = 0
        previewRotationDegrees = 0
        _previewGeneration.value = NO_PREVIEW_GENERATION
        lastPreviewEmittedTimestampNs.set(Long.MIN_VALUE)
        val token = streamGeneration.incrementAndGet()
        serviceScope.launch(pipelineDispatcher) {
            if (token == streamGeneration.get()) {
                drainQueuedFrames()
                photoRing.clear()
                lastPhotoTrailSampleNs = Long.MIN_VALUE
                stopEncoderAndClearGeneration()
            }
        }
    }

    private fun startUsbFrameWatchdog(device: UsbDevice, format: CameraStreamFormat) {
        stopFrameWatchdog()
        usbFirstFrameReceived.set(false)
        val token = streamGeneration.get()
        val powerHint = device.declaredMaxPowerMilliamps()
            ?.let { "；设备声明最大电流约 ${it}mA" }
            .orEmpty()
        publishStatus(
            "当前状态：UVC 已打开 ${format.width}×${format.height}@${format.fps}fps，等待首帧$powerHint"
        )
        frameWatchdogJob = serviceScope.launch {
            delay(UVC_FIRST_FRAME_TIMEOUT_MS)
            if (!monitoringStarted ||
                activeCameraSource != CameraSource.USB ||
                token != streamGeneration.get() ||
                usbFirstFrameReceived.get()
            ) {
                return@launch
            }
            val message = "UVC 已枚举并打开，但 ${UVC_FIRST_FRAME_TIMEOUT_MS / 1000} 秒未收到视频帧；不是完全无供电，更像供电不足、线缆只支持充电、UVC格式不兼容或摄像头未开始推流。请先用带外接供电的 USB-C Hub 验证。"
            Log.w(TAG, message)
            publishStatus("当前状态：$message")
            updateForegroundNotification("UVC 已打开但无视频帧")
        }
    }

    private fun stopFrameWatchdog() {
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        usbFirstFrameReceived.set(false)
    }

    private fun drainQueuedFrames() {
        while (encodeFrameChannel.tryReceive().isSuccess) {
            // Nv21Frame 只拥有 ByteArray，离开作用域后由 GC 回收。
        }
        while (analysisFrameChannel.tryReceive().isSuccess) {
            // Nv21Frame 只拥有 ByteArray，离开作用域后由 GC 回收。
        }
    }

    private fun enqueueFrame(frame: Nv21Frame) {
        if (!acceptingFrames ||
            frame.width != expectedFrameWidth || frame.height != expectedFrameHeight
        ) {
            return
        }
        emitPreviewFrameIfObserved(frame)
        encodeFrameChannel.trySend(frame)
        analysisFrameChannel.trySend(frame)
    }

    /**
     * 页面有订阅者时才复用当前不可变 NV21 ByteArray 发布低帧率预览；不复制 1080p 数据，
     * 也不阻塞相机回调。SharedFlow 容量只有 1，UI 变慢时始终丢旧保新。
     */
    private fun emitPreviewFrameIfObserved(frame: Nv21Frame) {
        if (_previewFrames.subscriptionCount.value <= 0) return
        val generation = streamGeneration.get()
        if (generation != _previewGeneration.value) return
        val previewIntervalNs = if (batteryHot || systemHot) {
            HOT_PREVIEW_INTERVAL_NS
        } else {
            PREVIEW_INTERVAL_NS
        }

        while (true) {
            val previous = lastPreviewEmittedTimestampNs.get()
            if (previous != Long.MIN_VALUE &&
                frame.timestampNs >= previous &&
                frame.timestampNs - previous < previewIntervalNs
            ) {
                return
            }
            if (lastPreviewEmittedTimestampNs.compareAndSet(previous, frame.timestampNs)) break
        }

        _previewFrames.tryEmit(
            PreviewFrame(
                frame = frame,
                rotationDegrees = previewRotationDegrees,
                streamGeneration = generation
            )
        )
    }

    private val encoderCallback = object : HardwareH264Encoder.Callback {
        override fun onOutputFormat(format: MediaFormat, generation: Int) {
            // MediaFormat 已由编码器深复制，包含 mp4 所需的 csd-0/csd-1。
            if (generation != encoderGeneration) return
            encoderFormat = format
        }

        override fun onEncodedSample(sample: HardwareH264Encoder.EncodedSample) {
            if (sample.generation != encoderGeneration || sample.isCodecConfig) return
            val timestampNs = sample.timestampNs
            // 活动事件先取一份独立副本，再把原对象的所有权交给 ring；否则 ring 淘汰策略一旦
            // 改为池化释放，事件列表就可能悬挂。
            val capture = eventCapture
            if (capture != null && sample.generation == capture.generation &&
                timestampNs <= capture.endTimestampNs
            ) {
                capture.samples += sample.copy()
            }
            encodedRing.addOwned(timestampNs, sample)

            if (capture == null || sample.generation != capture.generation) return
            if (timestampNs >= capture.endTimestampNs) finishCapture(capture)
        }

        override fun onEncoderError(error: Throwable) {
            Log.e(TAG, "MediaCodec 编码失败", error)
            if (!monitoringStarted) return
            if (!encoderFatalReported.compareAndSet(false, true)) return
            acceptingFrames = false
            reportFatalError("硬件编码失败：${error.message.orEmpty()}", error)
        }
    }

    /** 录像优先 consumer：只负责硬编、心跳和 WakeLock，不再被 OpenCV 分析拖慢。 */
    private suspend fun consumeEncodingFrames() {
        for (frame in encodeFrameChannel) {
            if (!serviceScope.isActive) break
            if (!acceptingFrames) continue
            try {
                lastHeartbeatElapsedMs.set(SystemClock.elapsedRealtime())
                val encoded = encoder?.encodeNv21(frame.data, frame.timestampNs) ?: false
                if (!encoded) {
                    Log.w(TAG, "编码器暂时没有输入槽，本帧未写入视频")
                }
                maintainWakeLock()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "编码视频帧失败", error)
                publishStatus("当前状态：编码异常：${error.message.orEmpty()}")
            }
        }
    }

    /** 分析 consumer：允许落后和丢帧，不能反向影响保存视频的连续性。 */
    private suspend fun consumeAnalysisFrames() {
        for (frame in analysisFrameChannel) {
            if (!serviceScope.isActive) break
            if (!acceptingFrames) continue
            try {
                samplePhotoTrailFrameIfNeeded(frame)
                analyzeFrame(frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "分析视频帧失败", error)
                publishStatus("当前状态：分析异常：${error.message.orEmpty()}")
            }
        }
    }

    private fun analyzeFrame(frame: Nv21Frame) {
        val frameAnalyzer = analyzer ?: return
        frameAnalyzer.setThermalThrottled(batteryHot || systemHot)

        val nv21Mat = Mat(frame.height * 3 / 2, frame.width, CvType.CV_8UC1)
        try {
            nv21Mat.put(0, 0, frame.data)
            val result = frameAnalyzer.analyzeDetailed(nv21Mat)
            if (!result.analyzed) return
            publishAnalysisDebug(result)

            if (result.rawMotion) {
                lastMotionElapsedMs = SystemClock.elapsedRealtime()
                if (!wakeLock.isHeld) acquireOrRenewWakeLock()
            }
            if (result.confirmedEvent) {
                beginCapture(frame.timestampNs)
            } else if (result.rawMotion && eventCapture == null) {
                publishMotionCandidateStatus(result.changedPixelRatio)
            }
        } finally {
            nv21Mat.release()
        }
    }

    private fun publishMotionCandidateStatus(changedPixelRatio: Double) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMotionCandidateStatusElapsedMs < MOTION_STATUS_INTERVAL_MS) return
        lastMotionCandidateStatusElapsedMs = now
        val percent = String.format(Locale.US, "%.2f", changedPixelRatio * 100.0)
        publishStatus("当前状态：检测到运动变化 ${percent}%，正在确认流星候选")
    }

    private fun publishAnalysisDebug(result: AnalysisResult) {
        val changedPercent = String.format(Locale.US, "%.4f", result.changedPixelRatio * 100.0)
        val thresholdPercent = String.format(
            Locale.US,
            "%.4f",
            result.changedRatioThreshold * 100.0
        )
        val strongPercent = String.format(
            Locale.US,
            "%.4f",
            result.strongChangedRatioThreshold * 100.0
        )
        val length = String.format(Locale.US, "%.1f", result.candidateLength)
        val angle = String.format(Locale.US, "%.0f", result.candidateAngleDegrees)
        val dominant = String.format(Locale.US, "%.0f", result.dominantFraction * 100.0)
        val triggerText = if (result.confirmedEvent) {
            "触发=${result.triggerReason.ifBlank { "已确认" }}"
        } else {
            "未触发=${result.rejectReason}"
        }
        _analysisDebugText.value = buildString {
            append("调试：")
            append(triggerText)
            append('\n')
            append("变化 ")
            append(result.changedPixels)
            append('/')
            append(result.totalPixels)
            append(" = ")
            append(changedPercent)
            append("%；阈值 ")
            append(thresholdPercent)
            append("%，强线 ")
            append(strongPercent)
            append('%')
            append('\n')
            append("线长 ")
            append(length)
            append("px，角度 ")
            append(angle)
            append("°，Hough ")
            append(result.houghLineCount)
            append("；连通域 面积=")
            append(result.largestCandidateArea)
            append(" 长×短=")
            append(result.largestCandidateLongSide)
            append('×')
            append(result.largestCandidateShortSide)
            append("，集中度 ")
            append(dominant)
            append('%')
            append('\n')
            append("连续候选 ")
            append(result.consecutiveHits)
            append('/')
            append(detectionSensitivity.config.requiredConsecutiveHits)
            append("，轨迹 ")
            append(result.trajectoryHits)
            append('/')
            append(detectionSensitivity.config.requiredConsecutiveHits)
            append("，单帧强线=")
            append(if (result.singleFrameStrongStreak) "是" else "否")
            append("，跨帧轨迹=")
            append(if (result.trajectoryConfirmed) "是" else "否")
        }
    }

    private fun applyDetectionSettings(
        sensitivity: DetectionSensitivity,
        maxWidth: Int,
        minLength: Int,
        boostSmallMeteors: Boolean,
        publishChange: Boolean
    ) {
        detectionSensitivity = sensitivity
        trajectoryMaxWidth = maxWidth.coerceIn(MIN_TRAJECTORY_MAX_WIDTH, MAX_TRAJECTORY_MAX_WIDTH)
        trajectoryMinLength =
            minLength.coerceIn(MIN_TRAJECTORY_MIN_LENGTH, MAX_TRAJECTORY_MIN_LENGTH)
        smallMeteorBoost = boostSmallMeteors
        analyzer?.applyConfig(buildDetectionConfig())
        lastMotionCandidateStatusElapsedMs = 0L
        if (publishChange && monitoringStarted) {
            val boostText = if (smallMeteorBoost) "、细小流星增强已开" else ""
            publishStatus(
                "当前状态：检测敏感度「${sensitivity.title}」、轨迹宽度 ${trajectoryMaxWidth}px、最小长度 ${trajectoryMinLength}px$boostText，继续监测"
            )
        }
    }

    private fun buildDetectionConfig(): com.meteorsnap.cv.DetectionConfig {
        val base = detectionSensitivity.config.copy(
            maxStreakShortSide = trajectoryMaxWidth,
            minStreakLongSide = trajectoryMinLength
        )
        if (!smallMeteorBoost) return base

        return base.copy(
            differenceThreshold = minOf(
                base.differenceThreshold,
                (base.differenceThreshold - 4.0).coerceAtLeast(10.0)
            ),
            changedRatioThreshold = minOf(
                base.changedRatioThreshold,
                (base.changedRatioThreshold * 0.65).coerceAtLeast(0.00008)
            ),
            minStreakPixels = minOf(
                base.minStreakPixels,
                maxOf(2, trajectoryMinLength / 2)
            ),
            minStreakLongSide = minOf(base.minStreakLongSide, trajectoryMinLength),
            minHoughVotes = minOf(
                base.minHoughVotes,
                maxOf(2, trajectoryMinLength / 2)
            ),
            minDominantStreakFraction = minOf(
                base.minDominantStreakFraction,
                (base.minDominantStreakFraction * 0.85).coerceAtLeast(0.10)
            )
        )
    }

    private fun samplePhotoTrailFrameIfNeeded(frame: Nv21Frame) {
        if (!savePhotoOutput) return
        val previous = lastPhotoTrailSampleNs
        if (previous != Long.MIN_VALUE &&
            frame.timestampNs >= previous &&
            frame.timestampNs - previous < PHOTO_SAMPLE_INTERVAL_NS
        ) {
            return
        }
        val sampled = PhotoTrailComposer.sampleNv21(frame) ?: return
        lastPhotoTrailSampleNs = frame.timestampNs
        photoRing.addCopy(sampled.timestampNs, sampled)

        val capture = eventCapture
        if (capture != null &&
            capture.savePhoto &&
            sampled.timestampNs <= capture.endTimestampNs
        ) {
            capture.photoFrames += sampled.copy()
        }
    }

    private fun applySavedUvcParameters(delayMillis: Long, publishChange: Boolean) {
        serviceScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            if (!monitoringStarted || activeCameraSource != CameraSource.USB) return@launch
            val parameters = buildUvcParametersFromPreferences()
            uvcCameraManager?.applyNightParameters(parameters)
            if (publishChange) {
                publishStatus("当前状态：UVC 相机参数已应用，继续监测")
            }
        }
    }

    private fun buildUvcParametersFromPreferences(): UVCCameraManager.NightParameters {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val autoExposure = preferences.getBoolean(
            PREF_UVC_AUTO_EXPOSURE,
            DEFAULT_UVC_AUTO_EXPOSURE
        )
        val exposureMs = preferences.getInt(
            PREF_UVC_EXPOSURE_MS,
            DEFAULT_UVC_EXPOSURE_MS
        ).coerceIn(MIN_UVC_EXPOSURE_MS, MAX_UVC_EXPOSURE_MS)
        val autoWhiteBalance = preferences.getBoolean(
            PREF_UVC_AUTO_WHITE_BALANCE,
            DEFAULT_UVC_AUTO_WHITE_BALANCE
        )
        return UVCCameraManager.NightParameters(
            autoExposure = autoExposure,
            exposureAbsolute = if (autoExposure) null else exposureMs * 10,
            gainPercent = preferences.getInt(PREF_UVC_GAIN, DEFAULT_UVC_GAIN).coercePercent(),
            autoWhiteBalance = autoWhiteBalance,
            whiteBalancePercent = if (autoWhiteBalance) {
                null
            } else {
                preferences.getInt(PREF_UVC_WHITE_BALANCE, DEFAULT_UVC_WHITE_BALANCE)
                    .coercePercent()
            },
            brightnessPercent = preferences.getInt(
                PREF_UVC_BRIGHTNESS,
                DEFAULT_UVC_BRIGHTNESS
            ).coercePercent(),
            contrastPercent = preferences.getInt(
                PREF_UVC_CONTRAST,
                DEFAULT_UVC_CONTRAST
            ).coercePercent(),
            saturationPercent = preferences.getInt(
                PREF_UVC_SATURATION,
                DEFAULT_UVC_SATURATION
            ).coercePercent(),
            sharpnessPercent = preferences.getInt(
                PREF_UVC_SHARPNESS,
                DEFAULT_UVC_SHARPNESS
            ).coercePercent(),
            gammaPercent = preferences.getInt(PREF_UVC_GAMMA, DEFAULT_UVC_GAMMA)
                .coercePercent(),
            backlightCompensation = preferences.getInt(
                PREF_UVC_BACKLIGHT,
                DEFAULT_UVC_BACKLIGHT
            ).coerceIn(MIN_UVC_BACKLIGHT, MAX_UVC_BACKLIGHT)
        )
    }

    private fun readRequestedCaptureOutputs(
        intent: Intent?,
        preferences: android.content.SharedPreferences
    ): CaptureOutputs {
        var saveVideo = intent?.takeIf { it.hasExtra(EXTRA_SAVE_VIDEO) }
            ?.getBooleanExtra(EXTRA_SAVE_VIDEO, true)
            ?: preferences.takeIf { it.contains(PREF_SAVE_VIDEO) }
                ?.getBoolean(PREF_SAVE_VIDEO, true)
            ?: true
        var savePhoto = intent?.takeIf { it.hasExtra(EXTRA_SAVE_PHOTO) }
            ?.getBooleanExtra(EXTRA_SAVE_PHOTO, false)
            ?: preferences.takeIf { it.contains(PREF_SAVE_PHOTO) }
                ?.getBoolean(PREF_SAVE_PHOTO, false)
            ?: (CaptureMode.fromPreference(
                preferences.getString(PREF_CAPTURE_MODE, null)
            ) == CaptureMode.PHOTO_TRAIL)

        if (!saveVideo && !savePhoto) {
            saveVideo = true
            savePhoto = false
        }
        return CaptureOutputs(saveVideo, savePhoto)
    }

    private fun Int.coercePercent(): Int = coerceIn(0, 100)

    /**
     * 第三个连续命中一出现就立即冻结前录，而不是等后录结束后再查 live ring。
     * 为保证 MP4 从 IDR 开始，先多查 1 秒并选择 T-3s 之前最近的关键帧。
     */
    private fun beginCapture(triggerTimestampNs: Long) {
        if (eventCapture != null) return
        val format = encoderFormat ?: return
        val generation = encoderGeneration
        if (generation < 0) return

        val desiredStart = max(0L, triggerTimestampNs - PRE_TRIGGER_NS)
        val searchStart = max(0L, desiredStart - KEY_FRAME_LOOKBACK_NS)
        val photoPreFrames = if (savePhotoOutput) {
            photoRing.extract(desiredStart, triggerTimestampNs).use { snapshot ->
                snapshot.entries.map { it.value.copy() }
            }
        } else {
            emptyList()
        }
        val snapshot = encodedRing.extract(searchStart, triggerTimestampNs)
        val preSamples = snapshot.use { owned ->
            val compatible = owned.entries.map { it.value }
                .filter { it.generation == generation && !it.isCodecConfig }
            val startIndex = compatible.indexOfLast {
                it.isKeyFrame && it.timestampNs <= desiredStart
            }.takeIf { it >= 0 }
                ?: compatible.indexOfFirst { it.isKeyFrame }.takeIf { it >= 0 }
                ?: run {
                    return@use emptyList()
                }
            compatible.drop(startIndex).map { it.copy() }
        }
        if (preSamples.isEmpty()) {
            // 没有 IDR 的 H.264 片段不能从触发点前开始独立解码。这里不再直接放弃本次
            // 流星事件，而是立刻请求关键帧并继续收后 3 秒；最终 Writer 会从第一个 IDR
            // 开始写入。这样前录可能不足 3 秒，但至少不会漏掉已经确认的流星。
            encoder?.requestSyncFrame()
            publishStatus("当前状态：检测到疑似流星，预录关键帧不足；已请求关键帧，继续收集后 3 秒")
            updateForegroundNotification("已触发流星抓拍，等待关键帧")
        } else {
            encoder?.requestSyncFrame()
            publishStatus("当前状态：检测到疑似流星，正在收集后 3 秒")
        }

        val capture = EventCapture(
            triggerTimestampNs = triggerTimestampNs,
            endTimestampNs = triggerTimestampNs + POST_TRIGGER_NS,
            wallClockMillis = System.currentTimeMillis(),
            generation = generation,
            rotationDegrees = encoderRotationDegrees,
            format = format,
            samples = preSamples.toMutableList(),
            saveVideo = saveVideoOutput,
            savePhoto = savePhotoOutput,
            photoFrames = photoPreFrames.toMutableList()
        )
        eventCapture = capture

        captureTimeoutJob?.cancel()
        captureTimeoutJob = serviceScope.launch(pipelineDispatcher) {
            delay(POST_TRIGGER_MS + CAPTURE_TIMEOUT_MARGIN_MS)
            if (eventCapture === capture) finishCapture(capture)
        }
    }

    private fun finishCapture(capture: EventCapture) {
        if (eventCapture !== capture) return
        eventCapture = null
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        rearmAnalyzerAfterCapture()

        val samples = capture.samples
            .filter { it.generation == capture.generation && !it.isCodecConfig }
            .sortedBy { it.presentationTimeUs }
        if (capture.saveVideo && samples.isEmpty() && !capture.savePhoto) {
            publishStatus("当前状态：本次疑似流星没有可写入的视频帧，已重新进入监测")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val savedClip = if (capture.saveVideo) {
                    if (samples.isEmpty()) {
                        null
                    } else {
                        MeteorClipWriter(applicationContext).write(
                            format = capture.format,
                            samples = samples,
                            wallClockMillis = capture.wallClockMillis,
                            orientationHintDegrees = capture.rotationDegrees
                        )
                    }
                } else {
                    null
                }
                val savedPhotoResult = if (capture.savePhoto && capture.photoFrames.isNotEmpty()) {
                    runCatching {
                        val bitmap = PhotoTrailComposer.composeMaxBrightness(
                            capture.photoFrames.sortedBy { it.timestampNs }
                        )
                        try {
                            MeteorPhotoWriter(applicationContext).write(
                                bitmap = bitmap,
                                wallClockMillis = capture.wallClockMillis
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } else {
                    null
                }
                savedPhotoResult?.exceptionOrNull()
                    ?.let { Log.w(TAG, "流星叠加照片保存失败", it) }
                if (savedClip == null && savedPhotoResult?.getOrNull() == null) {
                    error(
                        if (capture.savePhoto) {
                            "本次疑似流星没有可写入的输出帧"
                        } else {
                            "本次疑似流星没有可写入的视频帧"
                        }
                    )
                }
                savedClip to savedPhotoResult?.getOrNull()
            }.onSuccess { saved ->
                val savedClip = saved.first
                val savedPhoto = saved.second
                val detail = buildList {
                    savedClip?.let { add(it.displayName) }
                    savedPhoto?.let { add(it.displayName) }
                }.joinToString(" + ")
                if (detail.isBlank()) {
                    publishStatus("当前状态：本次疑似流星未生成文件，已重新进入监测")
                    return@onSuccess
                }
                publishStatus("当前状态：已保存 $detail")
                updateForegroundNotification("已保存 $detail，继续监测")
            }.onFailure { error ->
                Log.e(TAG, "保存 MP4 失败", error)
                publishStatus("当前状态：保存失败：${error.message.orEmpty()}")
            }
        }
    }

    /**
     * 一次抓拍窗口结束后主动解除 FrameAnalyzer 的事件锁存。
     *
     * 真实夜空里，流星后通常会自然出现“无运动帧”来重新武装；但用户用屏幕视频测试时，
     * 播放器刷新、压缩噪点或循环视频会让 raw motion 长时间不归零，导致只保存第一段。
     * 重置后下一帧先建立新基线，再继续寻找下一次流星候选。
     */
    private fun rearmAnalyzerAfterCapture() {
        runCatching { analyzer?.reset() }
            .onFailure { Log.w(TAG, "重置流星检测器失败", it) }
        lastMotionCandidateStatusElapsedMs = 0L
    }

    private fun restartEncoder(format: CameraStreamFormat) {
        stopEncoderAndClearGeneration()
        val newEncoder = HardwareH264Encoder(encoderCallback)
        encoderGeneration = newEncoder.start(format.width, format.height, format.fps)
        encoderRotationDegrees = format.rotationDegrees
        encoderFatalReported.set(false)
        encoder = newEncoder
    }

    private fun stopEncoderAndClearGeneration() {
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        eventCapture = null
        encoderFormat = null
        encoderGeneration = -1
        encoderRotationDegrees = 0
        encodedRing.clear()
        photoRing.clear()
        lastPhotoTrailSampleNs = Long.MIN_VALUE
        runCatching { encoder?.close() }
        encoder = null
    }

    /** 默认需求模式：连续五分钟没有 raw motion 就释放；任一新运动帧会重新获取。 */
    private fun maintainWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (releaseWakeLockAfterIdle && now - lastMotionElapsedMs >= IDLE_RELEASE_MS) {
            releaseWakeLock()
            return
        }
        if (wakeLock.isHeld && now - lastWakeRenewElapsedMs >= WAKE_RENEW_MS) {
            acquireOrRenewWakeLock()
        }
    }

    /**
     * 独立于视频帧的 30 秒巡检：即使 UVC 暂时不回帧，也能在省电模式下按时释放，或在可靠
     * 模式下续租带超时的 WakeLock。该任务本身在锁释放后不承诺准时唤醒，这正是 UI 中提示
     * “省电模式可能漏拍”的原因。
     */
    private fun startWakeMaintenance() {
        if (wakeMaintenanceJob?.isActive == true) return
        wakeMaintenanceJob = serviceScope.launch {
            while (isActive) {
                delay(WAKE_MAINTENANCE_INTERVAL_MS)
                maintainWakeLock()
            }
        }
    }

    private fun acquireOrRenewWakeLock() {
        runCatching {
            // 带超时租约，避免异常退出路径留下永久锁；健康流水线会定期续租。
            wakeLock.acquire(WAKE_LEASE_MS)
            lastWakeRenewElapsedMs = SystemClock.elapsedRealtime()
        }.onFailure { Log.w(TAG, "WakeLock 获取失败", it) }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    private val batteryTemperatureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?: return
            if (raw == Int.MIN_VALUE) return
            val celsius = raw / 10f
            batteryHot = when {
                celsius > 45f -> true
                celsius < 42f -> false
                else -> batteryHot
            }
        }
    }

    private fun registerThermalObservers() {
        registerReceiver(batteryTemperatureReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryReceiverRegistered = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                systemHot = status >= PowerManager.THERMAL_STATUS_MODERATE
            }
            thermalListener = listener
            powerManager.addThermalStatusListener(mainExecutor, listener)
        }
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun UsbDevice.declaredMaxPowerMilliamps(): Int? {
        return (0 until configurationCount)
            .mapNotNull { index ->
                runCatching { getConfiguration(index).maxPower }
                    .getOrNull()
                    ?.takeIf { it > 0 }
            }
            .maxOrNull()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildMonitoringNotification(detail: String): Notification {
        val openActivity = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            1002,
            Intent(this, MeteorDetectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_meteor)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(detail)
            .setContentIntent(openActivity)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop_monitoring), stopService)
            .build()
    }

    private fun startForegroundNow(
        notification: Notification,
        includeConnectedDevice: Boolean
    ): Boolean {
        val types = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    if (includeConnectedDevice) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else {
                        0
                    }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeConnectedDevice ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            return true
        } catch (error: SecurityException) {
            // Android 14+ 在这里即时校验 CAMERA while-in-use；只能回到可见 Activity 重试。
            Log.e(TAG, "无法启动 camera 前台服务", error)
            stopSelf()
            return false
        }
    }

    /** USBMonitor 已成功打开 UsbControlBlock 后，connectedDevice 的运行前提才成立。 */
    private fun promoteConnectedNotification(detail: String) {
        startForegroundNow(
            notification = buildMonitoringNotification(detail),
            includeConnectedDevice = true
        )
    }

    private fun updateForegroundNotification(detail: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildMonitoringNotification(detail))
    }

    private fun reportFatalError(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        acceptingFrames = false
        _previewGeneration.value = NO_PREVIEW_GENERATION
        publishStatus("当前状态：$message")
        updateForegroundNotification(message)
        serviceScope.launch {
            delay(1_500)
            stopSelf()
        }
    }

    private fun publishStatus(value: String) {
        _statusText.value = value
    }

    override fun onDestroy() {
        monitoringStarted = false
        acceptingFrames = false
        activeCameraSource = null
        streamGeneration.incrementAndGet()
        _previewGeneration.value = NO_PREVIEW_GENERATION
        lastPreviewEmittedTimestampNs.set(Long.MIN_VALUE)
        running.set(false)
        lastHeartbeatElapsedMs.set(0L)
        captureTimeoutJob?.cancel()
        stopFrameWatchdog()
        wakeMaintenanceJob?.cancel()
        eventCapture = null

        runCatching { uvcCameraManager?.close() }
        uvcCameraManager = null
        runCatching { internalCameraManager?.close() }
        internalCameraManager = null
        encodeFrameChannel.close()
        analysisFrameChannel.close()
        stopEncoderAndClearGeneration()
        runCatching { analyzer?.close() }
        analyzer = null
        encodedRing.close()
        photoRing.close()
        _analysisDebugText.value = "调试：未监测"

        if (batteryReceiverRegistered) runCatching {
            unregisterReceiver(batteryTemperatureReceiver)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener ->
                runCatching { powerManager.removeThermalStatusListener(listener) }
            }
        }
        releaseWakeLock()
        serviceScope.cancel()
        publishStatus("当前状态：未监测")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MeteorDetectService"
        private const val NOTIFICATION_CHANNEL_ID = "meteor_monitoring"
        private const val NOTIFICATION_ID = 1101

        const val PREFERENCES_NAME = "meteor_monitoring"
        const val PREF_MONITORING_WANTED = "monitoring_wanted"
        const val PREF_DEVICE_NAME = "device_name"
        const val PREF_CAMERA_SOURCE = "camera_source"
        const val PREF_POWER_SAVING = "power_saving"
        const val PREF_CAPTURE_MODE = "capture_mode"
        const val PREF_SAVE_VIDEO = "save_video"
        const val PREF_SAVE_PHOTO = "save_photo"
        const val PREF_DETECTION_SENSITIVITY = "detection_sensitivity"
        const val PREF_TRAJECTORY_MAX_WIDTH = "trajectory_max_width"
        const val PREF_TRAJECTORY_MIN_LENGTH = "trajectory_min_length"
        const val PREF_SMALL_METEOR_BOOST = "small_meteor_boost"
        const val PREF_UVC_DETECTION_DEFAULTS_APPLIED = "uvc_detection_defaults_applied"
        const val PREF_UVC_AUTO_EXPOSURE = "uvc_auto_exposure"
        const val PREF_UVC_EXPOSURE_MS = "uvc_exposure_ms"
        const val PREF_UVC_GAIN = "uvc_gain"
        const val PREF_UVC_AUTO_WHITE_BALANCE = "uvc_auto_white_balance"
        const val PREF_UVC_WHITE_BALANCE = "uvc_white_balance"
        const val PREF_UVC_BRIGHTNESS = "uvc_brightness"
        const val PREF_UVC_CONTRAST = "uvc_contrast"
        const val PREF_UVC_SATURATION = "uvc_saturation"
        const val PREF_UVC_SHARPNESS = "uvc_sharpness"
        const val PREF_UVC_GAMMA = "uvc_gamma"
        const val PREF_UVC_BACKLIGHT = "uvc_backlight"

        private const val ACTION_START = "com.meteorsnap.action.START"
        private const val ACTION_STOP = "com.meteorsnap.action.STOP"
        private const val ACTION_UPDATE_SENSITIVITY = "com.meteorsnap.action.UPDATE_SENSITIVITY"
        private const val ACTION_UPDATE_UVC_PARAMETERS = "com.meteorsnap.action.UPDATE_UVC_PARAMETERS"
        private const val EXTRA_CAMERA_SOURCE = "camera_source"
        private const val EXTRA_DEVICE_NAME = "device_name"
        private const val EXTRA_POWER_SAVING = "power_saving"
        private const val EXTRA_CAPTURE_MODE = "capture_mode"
        private const val EXTRA_SAVE_VIDEO = "save_video"
        private const val EXTRA_SAVE_PHOTO = "save_photo"
        private const val EXTRA_DETECTION_SENSITIVITY = "detection_sensitivity"
        private const val EXTRA_TRAJECTORY_MAX_WIDTH = "trajectory_max_width"
        private const val EXTRA_TRAJECTORY_MIN_LENGTH = "trajectory_min_length"
        private const val EXTRA_SMALL_METEOR_BOOST = "small_meteor_boost"

        private const val ENCODE_FRAME_CHANNEL_CAPACITY = 90
        private const val ANALYSIS_FRAME_CHANNEL_CAPACITY = 2
        private const val PRE_TRIGGER_NS = 3_000_000_000L
        private const val POST_TRIGGER_NS = 3_000_000_000L
        private const val POST_TRIGGER_MS = 3_000L
        private const val KEY_FRAME_LOOKBACK_NS = 1_200_000_000L
        private const val ENCODED_RING_DURATION_NS = 8_000_000_000L
        private const val ENCODED_RING_MAX_SAMPLES = 480
        private const val PHOTO_RING_DURATION_NS = 6_500_000_000L
        private const val PHOTO_RING_MAX_FRAMES = 40
        private const val PHOTO_SAMPLE_INTERVAL_NS = 200_000_000L // 流星照片模式最多约 5fps 采样
        private const val CAPTURE_TIMEOUT_MARGIN_MS = 750L
        private const val IDLE_RELEASE_MS = 5 * 60_000L
        private const val WAKE_LEASE_MS = 10 * 60_000L
        private const val WAKE_RENEW_MS = 9 * 60_000L
        private const val WAKE_MAINTENANCE_INTERVAL_MS = 30_000L
        private const val HEALTHY_HEARTBEAT_MS = 2 * 60_000L
        private const val MOTION_STATUS_INTERVAL_MS = 1_000L
        private const val UVC_FIRST_FRAME_TIMEOUT_MS = 6_000L
        private const val PREVIEW_INTERVAL_NS = 125_000_000L // 最多约 8fps
        private const val HOT_PREVIEW_INTERVAL_NS = 250_000_000L // 过热时约 4fps
        private const val NO_PREVIEW_GENERATION = -1
        const val MIN_UVC_EXPOSURE_MS = 1
        const val MAX_UVC_EXPOSURE_MS = 100
        const val DEFAULT_UVC_AUTO_EXPOSURE = false
        const val DEFAULT_UVC_EXPOSURE_MS = 25
        const val DEFAULT_UVC_GAIN = 52
        const val DEFAULT_UVC_AUTO_WHITE_BALANCE = false
        const val DEFAULT_UVC_WHITE_BALANCE = 50
        const val DEFAULT_UVC_BRIGHTNESS = 50
        const val DEFAULT_UVC_CONTRAST = 52
        const val DEFAULT_UVC_SATURATION = 45
        const val DEFAULT_UVC_SHARPNESS = 32
        const val DEFAULT_UVC_GAMMA = 50
        const val MIN_UVC_BACKLIGHT = 0
        const val MAX_UVC_BACKLIGHT = 4
        const val DEFAULT_UVC_BACKLIGHT = 0
        const val MIN_TRAJECTORY_MAX_WIDTH = 2
        const val MAX_TRAJECTORY_MAX_WIDTH = 40
        const val DEFAULT_TRAJECTORY_MAX_WIDTH = 26
        const val MIN_TRAJECTORY_MIN_LENGTH = 4
        const val MAX_TRAJECTORY_MIN_LENGTH = 36
        const val DEFAULT_TRAJECTORY_MIN_LENGTH = 18
        const val DEFAULT_SMALL_METEOR_BOOST = false
        const val DEFAULT_UVC_DETECTION_SENSITIVITY_LEVEL = 7
        const val DEFAULT_UVC_TRAJECTORY_MAX_WIDTH = 18
        const val DEFAULT_UVC_TRAJECTORY_MIN_LENGTH = 4
        const val DEFAULT_UVC_SMALL_METEOR_BOOST = true

        val running = AtomicBoolean(false)
        /** 跨 Service 重建仍保持单调，防止旧预览帧冒充新连接。 */
        private val streamGeneration = AtomicInteger(0)
        private val lastHeartbeatElapsedMs = AtomicLong(0L)
        private val _statusText = MutableStateFlow("当前状态：未监测")
        val statusText: StateFlow<String> = _statusText.asStateFlow()
        private val _analysisDebugText = MutableStateFlow("调试：未监测")
        val analysisDebugText: StateFlow<String> = _analysisDebugText.asStateFlow()
        private val _previewFrames = MutableSharedFlow<PreviewFrame>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val previewFrames: SharedFlow<PreviewFrame> = _previewFrames.asSharedFlow()
        private val _previewGeneration = MutableStateFlow(NO_PREVIEW_GENERATION)
        val previewGeneration: StateFlow<Int> = _previewGeneration.asStateFlow()

        fun start(
            context: Context,
            cameraSource: CameraSource,
            preferredDeviceName: String?,
            releaseWakeLockAfterIdle: Boolean,
            saveVideo: Boolean,
            savePhoto: Boolean,
            detectionSensitivityLevel: Int,
            trajectoryMaxWidth: Int,
            trajectoryMinLength: Int,
            smallMeteorBoost: Boolean
        ) {
            val intent = Intent(context, MeteorDetectService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CAMERA_SOURCE, cameraSource.preferenceValue)
                .putExtra(EXTRA_DEVICE_NAME, preferredDeviceName)
                .putExtra(EXTRA_POWER_SAVING, releaseWakeLockAfterIdle)
                .putExtra(EXTRA_SAVE_VIDEO, saveVideo)
                .putExtra(EXTRA_SAVE_PHOTO, savePhoto)
                .putExtra(
                    EXTRA_CAPTURE_MODE,
                    if (savePhoto) {
                        CaptureMode.PHOTO_TRAIL.preferenceValue
                    } else {
                        CaptureMode.VIDEO_ONLY.preferenceValue
                    }
                )
                .putExtra(EXTRA_DETECTION_SENSITIVITY, detectionSensitivityLevel)
                .putExtra(EXTRA_TRAJECTORY_MAX_WIDTH, trajectoryMaxWidth)
                .putExtra(EXTRA_TRAJECTORY_MIN_LENGTH, trajectoryMinLength)
                .putExtra(EXTRA_SMALL_METEOR_BOOST, smallMeteorBoost)
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateDetectionSettings(
            context: Context,
            detectionSensitivityLevel: Int,
            trajectoryMaxWidth: Int,
            trajectoryMinLength: Int,
            smallMeteorBoost: Boolean
        ) {
            if (!running.get()) return
            val intent = Intent(context, MeteorDetectService::class.java)
                .setAction(ACTION_UPDATE_SENSITIVITY)
                .putExtra(EXTRA_DETECTION_SENSITIVITY, detectionSensitivityLevel)
                .putExtra(EXTRA_TRAJECTORY_MAX_WIDTH, trajectoryMaxWidth)
                .putExtra(EXTRA_TRAJECTORY_MIN_LENGTH, trajectoryMinLength)
                .putExtra(EXTRA_SMALL_METEOR_BOOST, smallMeteorBoost)
            runCatching { context.startService(intent) }
        }

        fun updateUvcParameters(context: Context) {
            if (!running.get()) return
            val intent = Intent(context, MeteorDetectService::class.java)
                .setAction(ACTION_UPDATE_UVC_PARAMETERS)
            runCatching { context.startService(intent) }
        }

        fun resetUvcParameterPreferences(context: Context) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(PREF_UVC_AUTO_EXPOSURE, DEFAULT_UVC_AUTO_EXPOSURE)
                putInt(PREF_UVC_EXPOSURE_MS, DEFAULT_UVC_EXPOSURE_MS)
                putInt(PREF_UVC_GAIN, DEFAULT_UVC_GAIN)
                putBoolean(PREF_UVC_AUTO_WHITE_BALANCE, DEFAULT_UVC_AUTO_WHITE_BALANCE)
                putInt(PREF_UVC_WHITE_BALANCE, DEFAULT_UVC_WHITE_BALANCE)
                putInt(PREF_UVC_BRIGHTNESS, DEFAULT_UVC_BRIGHTNESS)
                putInt(PREF_UVC_CONTRAST, DEFAULT_UVC_CONTRAST)
                putInt(PREF_UVC_SATURATION, DEFAULT_UVC_SATURATION)
                putInt(PREF_UVC_SHARPNESS, DEFAULT_UVC_SHARPNESS)
                putInt(PREF_UVC_GAMMA, DEFAULT_UVC_GAMMA)
                putInt(PREF_UVC_BACKLIGHT, DEFAULT_UVC_BACKLIGHT)
            }
            updateUvcParameters(context)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeteorDetectService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(intent) }
        }

        fun isPipelineHealthy(): Boolean {
            if (!running.get()) return false
            val heartbeat = lastHeartbeatElapsedMs.get()
            return heartbeat > 0L &&
                SystemClock.elapsedRealtime() - heartbeat <= HEALTHY_HEARTBEAT_MS
        }
    }
}
