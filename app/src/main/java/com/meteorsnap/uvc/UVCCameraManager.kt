package com.meteorsnap.uvc

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.meteorsnap.camera.CameraStreamFormat
import com.meteorsnap.camera.Nv21Frame
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCParam
import com.serenegiant.usb.UVCCamera
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * UVC 摄像头封装：负责发现、USB 授权、打开、参数调节、帧回调和有限退避重连。
 *
 * 本实现严格对应 `com.herohan:UVCAndroid:1.0.13`。该版本与旧版 saki4510t API
 * 不兼容：连接回调是 `onDeviceOpen`，构造函数需要 [UVCParam]，并且
 * [USBMonitor.requestPermission] 没有 Boolean 返回值。
 *
 * 所有 USB 事件和相机状态变更都在 [cameraThread] 串行执行。这样可以保证关闭旧相机后
 * 才打开新相机，也满足 UVCCamera“必须在带 Looper 的线程创建”的要求。
 */
class UVCCameraManager(
    context: Context,
    private val callback: Callback
) : Closeable {

    interface Callback {
        fun onDeviceListChanged(devices: List<UsbDevice>) = Unit
        fun onConnecting(device: UsbDevice) = Unit
        fun onConnected(device: UsbDevice, format: CameraStreamFormat) = Unit
        fun onDisconnected(device: UsbDevice?) = Unit
        fun onPermissionCancelled(device: UsbDevice) = Unit
        fun onFrame(frame: Nv21Frame)
        fun onError(message: String, throwable: Throwable? = null) = Unit
    }

    data class NightParameters(
        /** null 表示保留设备当前设置；false 时才应用 exposureAbsolute。 */
        val autoExposure: Boolean? = null,
        /** UVC 单位为 0.0001 秒，例如 100 表示 10ms。 */
        val exposureAbsolute: Int? = null,
        val gain: Int? = null,
        val gainPercent: Int? = null,
        val autoWhiteBalance: Boolean? = null,
        val whiteBalance: Int? = null,
        val whiteBalancePercent: Int? = null,
        val brightnessPercent: Int? = null,
        val contrastPercent: Int? = null,
        val saturationPercent: Int? = null,
        val sharpnessPercent: Int? = null,
        val gammaPercent: Int? = null,
        val backlightCompensation: Int? = null
    )

    private val appContext = context.applicationContext
    private val cameraThread = HandlerThread("MeteorSnap-UVC").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    /**
     * 每次打开、切换或关闭相机都递增。帧回调捕获打开时的值，因此旧相机迟到的帧
     * 不会被误当成新相机的帧。
     */
    private val generation = AtomicInteger(0)

    @Volatile
    private var started = false

    @Volatile
    private var released = false

    @Volatile
    private var nativeLibraryUnavailable = false

    /** 用户选择的当前 USB 路径；物理重插后路径可能改变。 */
    @Volatile
    private var selectedDeviceName: String? = null

    /**
     * VID/PID 用作物理重插后的后备匹配条件。它不能区分两台同型号摄像头，但比只记
     * deviceName 更可靠，因为 `/dev/bus/usb/...` 在重插后通常会变化。
     */
    private var selectedVendorId: Int? = null
    private var selectedProductId: Int? = null
    private var autoConnectEnabled = true

    /** onDetach 已确认选中设备被物理拔出；即使重插后 USB 路径恰好相同也能正确识别。 */
    private var waitingForPhysicalReattach = false

    /** USB 授权或 UsbControlBlock 打开请求正在进行时的设备键，用于去重。 */
    private var pendingConnectionKey: String? = null

    /**
     * 用户明确拒绝过的设备。自动连接不得清除此集合，否则 USBMonitor 的轮询 attach
     * 回调会反复弹授权框；只有用户再次主动 connect 或设备真正 detach 后才清除。
     */
    private val permissionDeniedKeys = mutableSetOf<String>()

    private var currentDevice: UsbDevice? = null
    private var currentCamera: UVCCamera? = null
    private var currentPreviewDrain: ImageReader? = null

    /** 有限指数退避状态。token 用来让已经入队的旧 Runnable 自动失效。 */
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var reconnectToken = 0

    /**
     * 使用三参数构造函数，把 USBMonitor 的全部设备事件直接投递到相机线程。
     * USBMonitor 1.0.13 内部已处理 Android 12+ PendingIntent 可变性。
     */
    private val deviceListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            if (released || !isVideoDevice(device)) return
            notifyDeviceList()
            if (!started || !autoConnectEnabled || nativeLibraryUnavailable) return

            val devices = getDeviceList()
            val oldSelectedName = selectedDeviceName
            val oldPathStillPresent = oldSelectedName != null &&
                devices.any { it.deviceName == oldSelectedName }
            val canAdoptDevice = when {
                oldSelectedName == null -> true
                isSelectedDevice(device) -> true
                // 旧路径已经消失且型号一致，说明很可能是未收到 detach 广播的物理重插。
                matchesSelectedProduct(device) && !oldPathStillPresent && currentCamera == null -> true
                // start() 只恢复了旧 deviceName，但重插后路径已变化：仅有一个 UVC 设备时采用它。
                selectedVendorId == null && devices.size == 1 -> true
                else -> false
            }
            if (!canAdoptDevice) return

            val physicalReplug = waitingForPhysicalReattach ||
                (oldSelectedName != null && oldSelectedName != device.deviceName)
            waitingForPhysicalReattach = false
            rememberSelection(device)
            // USBMonitor 也会在“刚获得权限”时再次上报 attach；仅由 detach 标记或 USB 路径
            // 变化确认物理重插，权限变化产生的重复 attach 不会重置退避/授权状态。
            if (physicalReplug) cancelReconnect(resetAttempts = true)
            requestConnection(device, userInitiated = false)
        }

        override fun onDetach(device: UsbDevice) {
            if (!isVideoDevice(device)) return
            val key = deviceKey(device)
            permissionDeniedKeys.remove(key)
            if (pendingConnectionKey == key) pendingConnectionKey = null
            notifyDeviceList()

            val wasCurrentDevice = currentDevice?.let { deviceKey(it) == key } == true
            if (wasCurrentDevice) {
                generation.incrementAndGet()
                closeCurrentCameraOnCameraThread(notify = true)
            }

            // 保留 VID/PID 选择信息。相机以新的 USB 路径重插时，onAttach 可重新认领它。
            if (isSelectedDevice(device)) {
                waitingForPhysicalReattach = true
                scheduleReconnect()
            }
        }

        override fun onDeviceOpen(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            val key = deviceKey(device)
            if (pendingConnectionKey == key) pendingConnectionKey = null
            if (
                released || !started || nativeLibraryUnavailable || !isVideoDevice(device) ||
                !isSelectedDevice(device)
            ) {
                // 这是 USBMonitor 持有的原始 UsbControlBlock，不由相机管理；生命周期交还
                // monitor，避免关闭一个当前仍可能被新的有效回调使用的共享 control block。
                return
            }

            permissionDeniedKeys.remove(key)
            rememberSelection(device)
            // 打开成功后再清零计数；若 native 打开失败，需要继续沿用有限退避预算。
            cancelReconnect(resetAttempts = false)

            // 同一 requestPermission 可能因为 attach 轮询或重复调用产生 createNew=false 回调。
            // 相机已经打开时直接忽略，避免同一 UsbControlBlock 被重复克隆和 nativeConnect。
            if (currentCamera != null && currentDevice?.let { deviceKey(it) == key } == true) return

            val openGeneration = generation.incrementAndGet()
            val hadCurrentCamera = currentCamera != null
            closeCurrentCameraOnCameraThread(notify = hadCurrentCamera)
            openCamera(device, ctrlBlock, openGeneration)
        }

        override fun onDeviceClose(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock
        ) {
            if (released || !isVideoDevice(device)) return
            val key = deviceKey(device)
            if (pendingConnectionKey == key) pendingConnectionKey = null

            // 必须按精确 USB 路径比较，不能只按 VID/PID；否则旧设备的迟到 close 回调
            // 可能误关掉刚刚重插并已打开的新设备。
            if (currentDevice?.let { deviceKey(it) == key } == true) {
                generation.incrementAndGet()
                closeCurrentCameraOnCameraThread(notify = true)
            }
            if (isSelectedDevice(device)) scheduleReconnect()
        }

        override fun onCancel(device: UsbDevice) {
            if (!isVideoDevice(device)) return
            val key = deviceKey(device)
            if (pendingConnectionKey == key) pendingConnectionKey = null
            if (!isSelectedDevice(device)) return

            // 保留选择，但抑制所有自动重试。用户再次点击开始/连接时 connect() 会显式清除。
            val firstCancellation = permissionDeniedKeys.add(key)
            cancelReconnect(resetAttempts = false)
            if (firstCancellation) callback.onPermissionCancelled(device)
        }

        override fun onError(device: UsbDevice, error: USBMonitor.USBException) {
            if (!isVideoDevice(device)) return
            val key = deviceKey(device)
            if (pendingConnectionKey == key) pendingConnectionKey = null
            callback.onError("USB 设备 ${device.deviceName} 发生错误", error)
            if (isSelectedDevice(device)) scheduleReconnect()
        }
    }

    private val monitor: USBMonitor

    init {
        monitor = try {
            USBMonitor(appContext, deviceListener, cameraHandler)
        } catch (error: LinkageError) {
            // 依赖损坏或类链接失败也应给出明确错误，而不是让 Service 静默崩溃。
            cameraThread.quitSafely()
            throw UvcLibraryException("UVC 库链接失败，请检查依赖与 APK ABI", error)
        }
    }

    /** 注册 USB 监听。服务存活期间只调用一次。 */
    @Synchronized
    fun start(preferredDeviceName: String? = null) {
        if (released || started) return
        selectedDeviceName = preferredDeviceName
        selectedVendorId = null
        selectedProductId = null
        waitingForPhysicalReattach = false
        autoConnectEnabled = true
        try {
            monitor.register()
            started = true
            cameraHandler.post {
                if (released || !started) return@post
                notifyDeviceList()
                autoConnectIfPossible()
            }
        } catch (error: LinkageError) {
            nativeLibraryUnavailable = true
            callback.onError("UVC 库链接异常", error)
        } catch (error: Throwable) {
            callback.onError("USB 监听启动失败", error)
        }
    }

    /**
     * 只向 UI 暴露包含 USB Video class(0x0e) 接口的设备。
     * 很多复合 UVC 设备的 deviceClass 为 0，因此不能只检查 UsbDevice.deviceClass。
     */
    fun getDeviceList(): List<UsbDevice> = try {
        monitor.getDeviceList().orEmpty().filter(::isVideoDevice)
    } catch (error: Throwable) {
        callback.onError("读取 UVC 设备列表失败", error)
        emptyList()
    }

    /**
     * 用户主动选择设备并请求 USB 权限。主动调用会清除该设备上一次“用户拒绝”的抑制，
     * 但若同一次请求仍在处理中，不会重复调用 requestPermission。
     */
    fun connect(device: UsbDevice) {
        if (!started || released) return
        if (!isVideoDevice(device)) {
            callback.onError("所选 USB 设备不包含 UVC Video 接口：${device.deviceName}")
            return
        }
        cameraHandler.post {
            if (released || !started) return@post
            val key = deviceKey(device)
            val changingDevice = currentDevice?.let { deviceKey(it) != key } == true

            autoConnectEnabled = true
            rememberSelection(device)
            waitingForPhysicalReattach = false
            permissionDeniedKeys.remove(key)
            cancelReconnect(resetAttempts = true)

            if (changingDevice) {
                generation.incrementAndGet()
                closeCurrentCameraOnCameraThread(notify = true)
            }
            requestConnection(device, userInitiated = true)
        }
    }

    /** 主动断开后关闭自动连接；后续只有再次显式 connect 才会启动连接。 */
    fun disconnect() {
        if (released) return
        cameraHandler.post {
            autoConnectEnabled = false
            selectedDeviceName = null
            selectedVendorId = null
            selectedProductId = null
            waitingForPhysicalReattach = false
            pendingConnectionKey = null
            permissionDeniedKeys.clear()
            cancelReconnect(resetAttempts = true)
            generation.incrementAndGet()
            closeCurrentCameraOnCameraThread(notify = true)
        }
    }

    /**
     * 调节夜间参数。每个能力都先探测并按设备范围裁剪，不支持的控制项会被跳过。
     * UVCAndroid 源码提示刚打开时能力位可能暂时不准，因此调用方宜在 onConnected 后
     * 延迟数百毫秒再应用参数。
     */
    fun applyNightParameters(parameters: NightParameters) {
        if (released || nativeLibraryUnavailable) return
        cameraHandler.post {
            val control = currentCamera?.control ?: return@post
            try {
                parameters.autoExposure?.let { auto ->
                    if (control.isAutoExposureModeEnable) control.setExposureTimeAuto(auto)
                }
                parameters.exposureAbsolute?.let { requested ->
                    if (parameters.autoExposure != true && control.isExposureTimeAbsoluteEnable) {
                        control.setExposureTimeAbsolute(
                            requested.clampTo(control.updateExposureTimeAbsoluteLimit())
                        )
                    }
                }
                parameters.gain?.let { requested ->
                    if (control.isGainEnable) {
                        control.setGain(requested.clampTo(control.updateGainLimit()))
                    }
                }
                parameters.gainPercent?.let { requested ->
                    if (control.isGainEnable) control.setGainPercent(requested.clampPercent())
                }
                parameters.autoWhiteBalance?.let { auto ->
                    if (control.isWhiteBalanceAutoEnable) control.setWhiteBalanceAuto(auto)
                }
                parameters.whiteBalance?.let { requested ->
                    if (parameters.autoWhiteBalance != true && control.isWhiteBalanceEnable) {
                        control.setWhiteBalance(
                            requested.clampTo(control.updateWhiteBalanceLimit())
                        )
                    }
                }
                parameters.whiteBalancePercent?.let { requested ->
                    if (parameters.autoWhiteBalance != true && control.isWhiteBalanceEnable) {
                        control.setWhiteBalancePercent(requested.clampPercent())
                    }
                }
                parameters.brightnessPercent?.let { requested ->
                    if (control.isBrightnessEnable) control.setBrightnessPercent(requested.clampPercent())
                }
                parameters.contrastPercent?.let { requested ->
                    if (control.isContrastEnable) control.setContrastPercent(requested.clampPercent())
                }
                parameters.saturationPercent?.let { requested ->
                    if (control.isSaturationEnable) control.setSaturationPercent(requested.clampPercent())
                }
                parameters.sharpnessPercent?.let { requested ->
                    if (control.isSharpnessEnable) control.setSharpnessPercent(requested.clampPercent())
                }
                parameters.gammaPercent?.let { requested ->
                    if (control.isGammaEnable) control.setGammaPercent(requested.clampPercent())
                }
                parameters.backlightCompensation?.let { requested ->
                    if (control.isBacklightCompEnable) {
                        control.setBacklightComp(
                            requested.clampTo(control.updateBacklightCompLimit())
                        )
                    }
                }
            } catch (error: LinkageError) {
                handleNativeLinkageError("UVC 控制接口链接失败", error)
            } catch (error: Throwable) {
                callback.onError("部分 UVC 夜间参数设置失败", error)
            }
        }
    }

    /**
     * 统一的授权入口。pendingConnectionKey 同时覆盖“等待系统授权”和“等待 onDeviceOpen”阶段，
     * 从而消除 start()->autoConnect 与 USBMonitor.onAttach 并发造成的重复授权。
     */
    private fun requestConnection(device: UsbDevice, userInitiated: Boolean) {
        if (released || !started || nativeLibraryUnavailable || !autoConnectEnabled) return
        if (!isVideoDevice(device) || !isSelectedDevice(device)) return

        val key = deviceKey(device)
        if (!userInitiated && key in permissionDeniedKeys) return
        if (currentCamera != null && currentDevice?.let { deviceKey(it) == key } == true) return
        if (pendingConnectionKey == key) return

        // 若用户在另一个权限框返回前切换设备，旧结果会因 isSelectedDevice=false 被忽略。
        pendingConnectionKey = key
        callback.onConnecting(device)
        try {
            monitor.requestPermission(device)
        } catch (error: LinkageError) {
            pendingConnectionKey = null
            handleNativeLinkageError("USB/UVC 库链接失败", error)
        } catch (error: Throwable) {
            pendingConnectionKey = null
            callback.onError("请求 USB 设备权限失败", error)
            scheduleReconnect()
        }
    }

    private fun openCamera(
        device: UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock,
        openGeneration: Int
    ) {
        if (
            released || nativeLibraryUnavailable || openGeneration != generation.get() ||
            !isSelectedDevice(device)
        ) {
            return
        }

        var camera: UVCCamera? = null
        var previewDrain: ImageReader? = null
        try {
            val param = UVCParam().apply {
                quirks = UVCCamera.getRecommendedPlatformQuirks()
            }
            camera = UVCCamera(param)
            val openResult = camera.open(ctrlBlock)
            check(openResult == 0) { "UVCCamera.open 返回 $openResult" }

            val supportedSizes = camera.getSupportedSizeList().orEmpty()
            Log.i(TAG, "UVC supported sizes: ${supportedSizes.joinToString()}")
            val selectedSize = choosePreviewSize(supportedSizes)
                ?: error("摄像头没有可用预览尺寸")
            Log.i(TAG, "Selected UVC preview size: $selectedSize")
            camera.setPreviewSize(selectedSize)
            val fps = selectedSize.fps.takeIf { it > 0 } ?: 30
            val format = CameraStreamFormat(selectedSize.width, selectedSize.height, fps)
            previewDrain = attachPreviewDrainSurface(camera, format)

            if (released || openGeneration != generation.get() || !isSelectedDevice(device)) {
                releasePreviewDrain(previewDrain)
                destroyCameraSilently(camera)
                return
            }

            camera.setFrameCallback(
                createFrameCallback(format, openGeneration),
                UVCCamera.PIXEL_FORMAT_NV21
            )
            // 部分工业 UVC 相机只有在存在预览 Surface 时才稳定开始推流；离屏 Surface
            // 只负责驱动 UVC 预览管线，真正的 UI/检测/保存仍使用 NV21 帧回调。
            camera.startPreview()

            // close() 可从其他线程并发到达；startPreview 返回后再次检查，避免释放途中发布连接。
            if (released || openGeneration != generation.get() || !isSelectedDevice(device)) {
                releasePreviewDrain(previewDrain)
                destroyCameraSilently(camera)
                return
            }

            // 原生配置及 startPreview 全部成功后才发布状态，避免异常分支留下“已连接”假象。
            currentDevice = device
            currentCamera = camera
            currentPreviewDrain = previewDrain
            previewDrain = null

            cancelReconnect(resetAttempts = true)
            callback.onConnected(device, format)
        } catch (error: LinkageError) {
            releasePreviewDrain(previewDrain)
            clearPublishedCamera(camera)
            destroyCameraSilently(camera)
            handleNativeLinkageError("UVC 原生库初始化失败", error)
        } catch (error: Throwable) {
            releasePreviewDrain(previewDrain)
            clearPublishedCamera(camera)
            destroyCameraSilently(camera)
            callback.onError("打开 UVC 摄像头失败：${error.message.orEmpty()}", error)
            scheduleReconnect()
        }
    }

    private fun attachPreviewDrainSurface(
        camera: UVCCamera,
        format: CameraStreamFormat
    ): ImageReader? {
        val reader = try {
            ImageReader.newInstance(
                format.width,
                format.height,
                PixelFormat.RGBA_8888,
                PREVIEW_DRAIN_MAX_IMAGES
            )
        } catch (error: Throwable) {
            Log.w(TAG, "创建 UVC 离屏预览 Surface 失败，继续仅使用帧回调", error)
            return null
        }

        return try {
            reader.setOnImageAvailableListener({ imageReader ->
                runCatching { imageReader.acquireLatestImage()?.close() }
            }, cameraHandler)
            camera.setPreviewDisplay(reader.surface)
            Log.i(TAG, "Attached offscreen UVC preview drain surface")
            reader
        } catch (error: Throwable) {
            Log.w(TAG, "挂载 UVC 离屏预览 Surface 失败，继续仅使用帧回调", error)
            releasePreviewDrain(reader)
            null
        }
    }

    /** 每个相机会创建自己的帧回调，并捕获格式与 generation，避免迟到帧串台。 */
    private fun createFrameCallback(
        format: CameraStreamFormat,
        frameGeneration: Int
    ): IFrameCallback = IFrameCallback { nativeBuffer: ByteBuffer? ->
        if (released || frameGeneration != generation.get()) return@IFrameCallback
        val buffer = nativeBuffer?.duplicate() ?: return@IFrameCallback
        buffer.rewind()
        val expected = format.width * format.height * 3 / 2
        if (buffer.remaining() < expected) {
            Log.w(TAG, "忽略短帧: ${buffer.remaining()} < $expected")
            return@IFrameCallback
        }

        // 原生层会复用 DirectByteBuffer；离开回调后仍需使用的数据必须立即复制。
        val bytes = ByteArray(expected)
        buffer.get(bytes, 0, expected)
        if (frameGeneration != generation.get()) return@IFrameCallback
        callback.onFrame(
            Nv21Frame(bytes, SystemClock.elapsedRealtimeNanos(), format.width, format.height)
        )
    }

    private fun choosePreviewSize(sizes: List<Size>): Size? {
        fun Size.supports30Fps(): Boolean = fps == 30 || fpsList?.contains(30) == true
        fun Size.copyWithFps(targetFps: Int): Size = clone().apply {
            fps = targetFps
        }

        val preferred = listOf(
            1280 to 720,
            1920 to 1080,
            640 to 480
        )
        for ((width, height) in preferred) {
            sizes.firstOrNull {
                it.type == UVCCamera.UVC_VS_FRAME_MJPEG &&
                    it.width == width && it.height == height && it.supports30Fps()
            }?.let { return it.copyWithFps(30) }
            sizes.firstOrNull {
                it.type == UVCCamera.UVC_VS_FRAME_MJPEG &&
                    it.width == width && it.height == height
            }?.let { return it }
        }

        return sizes
            .filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes
                .filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    /**
     * 必须在 cameraThread 直接同步关闭，不能再次 post；否则后续 openCamera 会先于旧相机销毁执行。
     * destroy(true) 会静默关闭 UVCCamera 自己克隆的 UsbControlBlock，避免制造伪 onDeviceClose 回调。
     */
    private fun closeCurrentCameraOnCameraThread(notify: Boolean) {
        val device = currentDevice
        val camera = currentCamera
        currentDevice = null
        currentCamera = null
        destroyCameraSilently(camera)
        releaseCurrentPreviewDrain()
        if (notify && (device != null || camera != null)) callback.onDisconnected(device)
    }

    private fun clearPublishedCamera(camera: UVCCamera?) {
        if (camera != null && currentCamera === camera) {
            currentDevice = null
            currentCamera = null
        }
    }

    private fun destroyCameraSilently(camera: UVCCamera?) {
        if (camera == null) return
        runCatching { camera.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_NV21) }
        runCatching { camera.stopPreview() }
        runCatching { camera.destroy(true) }
    }

    private fun releaseCurrentPreviewDrain() {
        releasePreviewDrain(currentPreviewDrain)
        currentPreviewDrain = null
    }

    private fun releasePreviewDrain(reader: ImageReader?) {
        if (reader == null) return
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { reader.close() }
    }

    /**
     * 退避序列为 1、2、4、8、16、30 秒，最多六次。这里先用 Int 移位再转 Long，
     * 避免 Kotlin 中对 Long 使用 shl 导致编译失败。设备重新 attach 会重置额度。
     */
    private fun scheduleReconnect() {
        if (
            released || !started || nativeLibraryUnavailable || !autoConnectEnabled ||
            selectedDeviceName == null || currentCamera != null || reconnectScheduled ||
            reconnectAttempt >= MAX_RECONNECT_ATTEMPTS
        ) {
            return
        }

        val attemptIndex = reconnectAttempt++
        val delayMs = min(
            MAX_RECONNECT_DELAY_MS,
            BASE_RECONNECT_DELAY_MS * (1 shl attemptIndex).toLong()
        )
        val token = ++reconnectToken
        reconnectScheduled = true
        cameraHandler.postDelayed({
            if (released || token != reconnectToken) return@postDelayed
            reconnectScheduled = false
            if (currentCamera != null || pendingConnectionKey != null) return@postDelayed

            val device = findSelectedDevice()
            if (device != null) requestConnection(device, userInitiated = false)

            // 设备尚未重插时继续有限轮询；一旦请求已发出，则等待 open/cancel/error 回调决定后续。
            if (currentCamera == null && pendingConnectionKey == null) scheduleReconnect()
        }, delayMs)
    }

    private fun cancelReconnect(resetAttempts: Boolean) {
        reconnectToken++
        reconnectScheduled = false
        if (resetAttempts) reconnectAttempt = 0
    }

    private fun autoConnectIfPossible() {
        if (!autoConnectEnabled || nativeLibraryUnavailable) return
        val devices = getDeviceList()
        val selected = findSelectedDevice(devices)
            ?: if (selectedDeviceName == null || devices.size == 1) {
                devices.firstOrNull()
            } else {
                null
            }
        val device: UsbDevice = selected ?: return
        rememberSelection(device)
        requestConnection(device, userInitiated = false)
    }

    private fun findSelectedDevice(devices: List<UsbDevice> = getDeviceList()): UsbDevice? {
        selectedDeviceName?.let { name ->
            devices.firstOrNull { it.deviceName == name }?.let { return it }
        }
        val vendorId = selectedVendorId
        val productId = selectedProductId
        if (vendorId != null && productId != null) {
            devices.firstOrNull {
                it.vendorId == vendorId && it.productId == productId
            }?.let { return it }
        }
        return null
    }

    private fun rememberSelection(device: UsbDevice) {
        selectedDeviceName = device.deviceName
        selectedVendorId = device.vendorId
        selectedProductId = device.productId
    }

    private fun isSelectedDevice(device: UsbDevice): Boolean {
        return selectedDeviceName == device.deviceName
    }

    private fun matchesSelectedProduct(device: UsbDevice): Boolean {
        val vendorId = selectedVendorId ?: return false
        val productId = selectedProductId ?: return false
        return device.vendorId == vendorId && device.productId == productId
    }

    private fun notifyDeviceList() {
        callback.onDeviceListChanged(getDeviceList())
    }

    private fun handleNativeLinkageError(message: String, error: LinkageError) {
        if (nativeLibraryUnavailable) return
        nativeLibraryUnavailable = true
        pendingConnectionKey = null
        cancelReconnect(resetAttempts = false)
        generation.incrementAndGet()
        closeCurrentCameraOnCameraThread(notify = true)
        callback.onError(
            message,
            UvcLibraryException("$message；请检查 APK ABI 与 UVCAndroid native .so", error)
        )
    }

    /** deviceClass 常为 0（复合设备），所以还必须逐个检查 UsbInterface.interfaceClass。 */
    private fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    /** 不读取序列号，避免在 USB 授权前触发 SecurityException。 */
    private fun deviceKey(device: UsbDevice): String =
        "${device.deviceName}#${device.vendorId}#${device.productId}"

    /**
     * 释放顺序固定为：使 generation 失效 -> 静默销毁相机克隆连接 -> 销毁 USBMonitor -> 退出线程。
     * postAtFrontOfQueue 可越过尚未执行的重连/open 任务，而 released 标记会让迟到回调直接返回。
     */
    override fun close() {
        synchronized(this) {
            if (released) return
            released = true
            started = false
        }

        val releaseAction = Runnable {
            cameraHandler.removeCallbacksAndMessages(null)
            autoConnectEnabled = false
            waitingForPhysicalReattach = false
            pendingConnectionKey = null
            permissionDeniedKeys.clear()
            cancelReconnect(resetAttempts = true)
            generation.incrementAndGet()
            closeCurrentCameraOnCameraThread(notify = false)
            runCatching { monitor.unregister() }
            runCatching { monitor.destroy() }
            cameraThread.quitSafely()
        }

        if (Looper.myLooper() == cameraThread.looper) {
            releaseAction.run()
        } else if (!cameraHandler.postAtFrontOfQueue(releaseAction)) {
            // 正常生命周期不会走到这里；若 Looper 已意外退出，至少释放 USBMonitor。
            runCatching { monitor.unregister() }
            runCatching { monitor.destroy() }
            cameraThread.quitSafely()
        }
    }

    private fun Int.clampTo(range: IntArray?): Int {
        if (range == null || range.size < 2 || range[0] > range[1]) return this
        return coerceIn(range[0], range[1])
    }

    private fun Int.clampPercent(): Int = coerceIn(0, 100)

    class UvcLibraryException(message: String, cause: Throwable) :
        IllegalStateException(message, cause)

    companion object {
        private const val TAG = "UVCCameraManager"
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val PREVIEW_DRAIN_MAX_IMAGES = 2
    }
}
