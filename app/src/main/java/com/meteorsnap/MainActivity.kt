package com.meteorsnap

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meteorsnap.databinding.ActivityMainBinding
import com.meteorsnap.camera.CameraSource
import com.meteorsnap.camera.PreviewFrame
import com.meteorsnap.cv.DetectionSensitivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.meteorsnap.ui.Nv21PreviewView
import kotlin.math.roundToInt

/**
 * 唯一可见页面：选择手机或 USB 摄像头、开始/停止监测，并提供系统省电与厂商自启动引导。
 *
 * USB 授权不是运行时危险权限。点击“开始”后，前台服务中的 USBMonitor 会在 Activity 仍可见时
 * 弹出系统 USB 授权框；CAMERA/旧版存储/通知权限则由本页面按 Android 版本动态申请。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var camera2Manager: CameraManager
    private var uvcDevices: List<UsbDevice> = emptyList()
    private var internalCameraAvailable = false
    private var usbReceiverRegistered = false
    private var pendingStart: PendingStart? = null
    private var hasPreviewFrame = false
    private var lastSavedToastStatus: String? = null
    private var fullScreenPreviewDialog: Dialog? = null
    private var fullScreenPreviewView: Nv21PreviewView? = null
    private var fullScreenPreviewPlaceholder: TextView? = null
    private var applyingDetectionPreset = false
    private var applyingOutputSelection = false

    private data class PendingStart(
        val source: CameraSource,
        val usbDeviceName: String?
    )

    private data class OutputSelection(
        val saveVideo: Boolean,
        val savePhoto: Boolean
    )

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                refreshUsbDevices()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val request = pendingStart ?: return@registerForActivityResult
        pendingStart = null
        if (hasRequiredCapturePermissions()) {
            startSelectedCamera(request)
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        camera2Manager = getSystemService(CameraManager::class.java)
        binding.textVersion.text = getString(R.string.version_hint, BuildConfig.VERSION_NAME)
        pendingStart = savedInstanceState?.getString(STATE_PENDING_SOURCE)?.let { savedSource ->
            PendingStart(
                source = CameraSource.fromPreference(savedSource),
                usbDeviceName = savedInstanceState.getString(STATE_PENDING_USB_DEVICE)
            )
        }

        val preferences = getSharedPreferences(
            MeteorDetectService.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        binding.switchPowerSaving.isChecked = preferences.getBoolean(
            MeteorDetectService.PREF_POWER_SAVING,
            true
        )
        val savedSensitivity = DetectionSensitivity.fromLevel(
            preferences.getInt(
                MeteorDetectService.PREF_DETECTION_SENSITIVITY,
                DetectionSensitivity.DEFAULT.level
            )
        )
        binding.sliderSensitivity.value = savedSensitivity.level.toFloat()
        renderSensitivity(savedSensitivity)
        val savedTrajectoryWidth = preferences.getInt(
            MeteorDetectService.PREF_TRAJECTORY_MAX_WIDTH,
            MeteorDetectService.DEFAULT_TRAJECTORY_MAX_WIDTH
        ).coerceIn(
            MeteorDetectService.MIN_TRAJECTORY_MAX_WIDTH,
            MeteorDetectService.MAX_TRAJECTORY_MAX_WIDTH
        )
        binding.sliderTrajectoryWidth.value = savedTrajectoryWidth.toFloat()
        renderTrajectoryWidth(savedTrajectoryWidth)
        val savedTrajectoryMinLength = preferences.getInt(
            MeteorDetectService.PREF_TRAJECTORY_MIN_LENGTH,
            MeteorDetectService.DEFAULT_TRAJECTORY_MIN_LENGTH
        ).coerceIn(
            MeteorDetectService.MIN_TRAJECTORY_MIN_LENGTH,
            MeteorDetectService.MAX_TRAJECTORY_MIN_LENGTH
        )
        binding.sliderTrajectoryMinLength.value = savedTrajectoryMinLength.toFloat()
        renderTrajectoryMinLength(savedTrajectoryMinLength)
        binding.switchSmallMeteorBoost.isChecked = preferences.getBoolean(
            MeteorDetectService.PREF_SMALL_METEOR_BOOST,
            MeteorDetectService.DEFAULT_SMALL_METEOR_BOOST
        )
        val savedOutput = loadSavedOutputSelection(preferences)
        applyingOutputSelection = true
        binding.checkboxSaveVideo.isChecked = savedOutput.saveVideo
        binding.checkboxSavePhoto.isChecked = savedOutput.savePhoto
        applyingOutputSelection = false
        renderCaptureOutput(
            saveVideo = savedOutput.saveVideo,
            savePhoto = savedOutput.savePhoto
        )
        setupUvcParameterControls(preferences)

        val savedDeviceName = preferences.getString(MeteorDetectService.PREF_DEVICE_NAME, null)
        val savedSource = CameraSource.fromPreference(
            preferences.getString(MeteorDetectService.PREF_CAMERA_SOURCE, null),
            savedDeviceName
        )
        binding.toggleCameraSource.check(
            if (savedSource == CameraSource.PHONE) {
                R.id.buttonSourcePhone
            } else {
                R.id.buttonSourceUsb
            }
        )
        if (savedSource == CameraSource.USB && shouldApplyInitialUvcDetectionDefaults(preferences)) {
            applyUsbRecommendedDetectionDefaults(preferences, notifyService = false)
        }

        binding.toggleCameraSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val source = if (checkedId == R.id.buttonSourceUsb) {
                CameraSource.USB
            } else {
                CameraSource.PHONE
            }
            preferences.edit { putString(MeteorDetectService.PREF_CAMERA_SOURCE, source.preferenceValue) }
            if (source == CameraSource.USB && !MeteorDetectService.running.get()) {
                applyUsbRecommendedDetectionDefaults(preferences, notifyService = false)
                Toast.makeText(this, "已切换为外接 UVC 推荐检测参数", Toast.LENGTH_SHORT).show()
            }
            renderControls()
        }
        binding.sliderSensitivity.addOnChangeListener { _, value, fromUser ->
            val sensitivity = DetectionSensitivity.fromLevel(value.roundToInt())
            preferences.edit {
                putInt(MeteorDetectService.PREF_DETECTION_SENSITIVITY, sensitivity.level)
                if (selectedCameraSource() == CameraSource.USB) {
                    putBoolean(MeteorDetectService.PREF_UVC_DETECTION_DEFAULTS_APPLIED, true)
                }
            }
            renderSensitivity(sensitivity)
            if (fromUser && !applyingDetectionPreset) {
                MeteorDetectService.updateDetectionSettings(
                    this,
                    sensitivity.level,
                    selectedTrajectoryMaxWidth(),
                    selectedTrajectoryMinLength(),
                    selectedSmallMeteorBoost()
                )
            }
        }
        binding.sliderTrajectoryWidth.addOnChangeListener { _, value, fromUser ->
            val width = value.roundToInt().coerceIn(
                MeteorDetectService.MIN_TRAJECTORY_MAX_WIDTH,
                MeteorDetectService.MAX_TRAJECTORY_MAX_WIDTH
            )
            preferences.edit {
                putInt(MeteorDetectService.PREF_TRAJECTORY_MAX_WIDTH, width)
                if (selectedCameraSource() == CameraSource.USB) {
                    putBoolean(MeteorDetectService.PREF_UVC_DETECTION_DEFAULTS_APPLIED, true)
                }
            }
            renderTrajectoryWidth(width)
            if (fromUser && !applyingDetectionPreset) {
                MeteorDetectService.updateDetectionSettings(
                    this,
                    selectedDetectionSensitivity().level,
                    width,
                    selectedTrajectoryMinLength(),
                    selectedSmallMeteorBoost()
                )
            }
        }
        binding.sliderTrajectoryMinLength.addOnChangeListener { _, value, fromUser ->
            val length = value.roundToInt().coerceIn(
                MeteorDetectService.MIN_TRAJECTORY_MIN_LENGTH,
                MeteorDetectService.MAX_TRAJECTORY_MIN_LENGTH
            )
            preferences.edit {
                putInt(MeteorDetectService.PREF_TRAJECTORY_MIN_LENGTH, length)
                if (selectedCameraSource() == CameraSource.USB) {
                    putBoolean(MeteorDetectService.PREF_UVC_DETECTION_DEFAULTS_APPLIED, true)
                }
            }
            renderTrajectoryMinLength(length)
            if (fromUser && !applyingDetectionPreset) {
                MeteorDetectService.updateDetectionSettings(
                    this,
                    selectedDetectionSensitivity().level,
                    selectedTrajectoryMaxWidth(),
                    length,
                    selectedSmallMeteorBoost()
                )
            }
        }
        binding.switchSmallMeteorBoost.setOnCheckedChangeListener { _, enabled ->
            preferences.edit {
                putBoolean(MeteorDetectService.PREF_SMALL_METEOR_BOOST, enabled)
                if (selectedCameraSource() == CameraSource.USB) {
                    putBoolean(MeteorDetectService.PREF_UVC_DETECTION_DEFAULTS_APPLIED, true)
                }
            }
            if (applyingDetectionPreset) return@setOnCheckedChangeListener
            MeteorDetectService.updateDetectionSettings(
                this,
                selectedDetectionSensitivity().level,
                selectedTrajectoryMaxWidth(),
                selectedTrajectoryMinLength(),
                enabled
            )
        }
        binding.cardCameraPreview.setOnClickListener {
            if (MeteorDetectService.running.get()) {
                showFullScreenPreview()
            } else {
                Toast.makeText(this, R.string.live_preview_waiting, Toast.LENGTH_SHORT).show()
            }
        }
        binding.previewCamera.setOnClickListener {
            if (MeteorDetectService.running.get()) {
                showFullScreenPreview()
            }
        }
        binding.textPreviewPlaceholder.setOnClickListener {
            if (MeteorDetectService.running.get()) {
                showFullScreenPreview()
            }
        }
        binding.checkboxSaveVideo.setOnCheckedChangeListener { _, _ ->
            onOutputSelectionChanged(preferences, changedVideo = true)
        }
        binding.checkboxSavePhoto.setOnCheckedChangeListener { _, _ ->
            onOutputSelectionChanged(preferences, changedVideo = false)
        }
        binding.buttonRefresh.setOnClickListener { refreshCameraSources() }
        binding.buttonStart.setOnClickListener { requestPermissionsAndStart() }
        binding.buttonStop.setOnClickListener { MeteorDetectService.stop(this) }
        binding.buttonBatteryGuide.setOnClickListener { openBatteryOptimizationSettings() }
        binding.buttonAutoStartGuide.setOnClickListener { showAutoStartGuide() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MeteorDetectService.statusText.collect { status ->
                        binding.textStatus.text = status
                        showSavedToastIfNeeded(status)
                        renderControls()
                    }
                }
                launch {
                    MeteorDetectService.previewGeneration.collect {
                        hasPreviewFrame = false
                        binding.previewCamera.clear()
                        fullScreenPreviewView?.clear()
                        binding.textPreviewPlaceholder.isVisible =
                            MeteorDetectService.running.get()
                        fullScreenPreviewPlaceholder?.isVisible =
                            MeteorDetectService.running.get()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                MeteorDetectService.previewFrames.collect(::renderPreview)
            }
        }
        refreshCameraSources()
    }

    override fun onStart() {
        super.onStart()
        if (!usbReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                usbDeviceReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            usbReceiverRegistered = true
        }
    }

    override fun onStop() {
        hasPreviewFrame = false
        binding.previewCamera.clear()
        fullScreenPreviewDialog?.dismiss()
        if (usbReceiverRegistered) {
            runCatching { unregisterReceiver(usbDeviceReceiver) }
            usbReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshCameraSources()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshCameraSources()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStart?.let { request ->
            outState.putString(STATE_PENDING_SOURCE, request.source.preferenceValue)
            outState.putString(STATE_PENDING_USB_DEVICE, request.usbDeviceName)
        }
        super.onSaveInstanceState(outState)
    }

    private fun requestPermissionsAndStart() {
        val request = buildPendingStart() ?: return

        val missing = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.CAMERA)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 通知权限被拒不妨碍 FGS 启动，但一并请求能确保用户看到“流星监测中”。
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missing.isEmpty()) {
            startSelectedCamera(request)
        } else {
            // 权限页面打开期间用户可能旋转屏幕或拔掉 USB；冻结本次来源，回来后再次校验。
            pendingStart = request
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasRequiredCapturePermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val storageGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && storageGranted
    }

    private fun buildPendingStart(): PendingStart? {
        return when (selectedCameraSource()) {
            CameraSource.PHONE -> {
                if (!internalCameraAvailable) {
                    Toast.makeText(this, R.string.no_internal_camera, Toast.LENGTH_LONG).show()
                    null
                } else {
                    PendingStart(CameraSource.PHONE, null)
                }
            }

            CameraSource.USB -> {
                val device = uvcDevices.getOrNull(binding.spinnerUsbDevices.selectedItemPosition)
                if (device == null) {
                    Toast.makeText(this, R.string.no_usb_device, Toast.LENGTH_LONG).show()
                    null
                } else {
                    PendingStart(CameraSource.USB, device.deviceName)
                }
            }
        }
    }

    private fun startSelectedCamera(request: PendingStart) {
        if (request.source == CameraSource.PHONE && !internalCameraAvailable) {
            Toast.makeText(this, R.string.no_internal_camera, Toast.LENGTH_LONG).show()
            return
        }
        if (request.source == CameraSource.USB &&
            uvcDevices.none { it.deviceName == request.usbDeviceName }
        ) {
            Toast.makeText(this, R.string.no_usb_device, Toast.LENGTH_LONG).show()
            refreshUsbDevices()
            return
        }
        // Camera2 的传感器旋转角在打开流时确定；监测期间锁定当前方向，避免预览中途转错。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        MeteorDetectService.start(
            context = this,
            cameraSource = request.source,
            preferredDeviceName = request.usbDeviceName,
            releaseWakeLockAfterIdle = binding.switchPowerSaving.isChecked,
            saveVideo = selectedSaveVideo(),
            savePhoto = selectedSavePhoto(),
            detectionSensitivityLevel = selectedDetectionSensitivity().level,
            trajectoryMaxWidth = selectedTrajectoryMaxWidth(),
            trajectoryMinLength = selectedTrajectoryMinLength(),
            smallMeteorBoost = selectedSmallMeteorBoost()
        )
    }

    private fun refreshCameraSources() {
        refreshInternalCameraAvailability()
        refreshUsbDevices()
    }

    private fun refreshInternalCameraAvailability() {
        internalCameraAvailable = runCatching {
            camera2Manager.cameraIdList.any { cameraId ->
                val characteristics = camera2Manager.getCameraCharacteristics(cameraId)
                val isInternal = characteristics.get(CameraCharacteristics.LENS_FACING) !=
                    CameraCharacteristics.LENS_FACING_EXTERNAL
                val supportsYuv = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )?.getOutputSizes(ImageFormat.YUV_420_888)?.isNotEmpty() == true
                isInternal && supportsYuv
            }
        }.getOrDefault(false)
    }

    private fun refreshUsbDevices() {
        val previousDeviceName = uvcDevices
            .getOrNull(binding.spinnerUsbDevices.selectedItemPosition)
            ?.deviceName
            ?: getSharedPreferences(MeteorDetectService.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(MeteorDetectService.PREF_DEVICE_NAME, null)
        uvcDevices = usbManager.deviceList.values
            .filter(::isUvcDevice)
            .sortedBy { it.deviceName }

        val labels = if (uvcDevices.isEmpty()) {
            listOf(getString(R.string.no_usb_device))
        } else {
            uvcDevices.map { device ->
                val product = runCatching { device.productName }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "UVC ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
                "$product  (${device.deviceName})"
            }
        }
        binding.spinnerUsbDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        uvcDevices.indexOfFirst { it.deviceName == previousDeviceName }
            .takeIf { it >= 0 }
            ?.let(binding.spinnerUsbDevices::setSelection)
        renderControls()
    }

    private fun selectedCameraSource(): CameraSource =
        if (binding.toggleCameraSource.checkedButtonId == R.id.buttonSourceUsb) {
            CameraSource.USB
        } else {
            CameraSource.PHONE
        }

    private fun selectedDetectionSensitivity(): DetectionSensitivity =
        DetectionSensitivity.fromLevel(binding.sliderSensitivity.value.roundToInt())

    private fun selectedTrajectoryMaxWidth(): Int =
        binding.sliderTrajectoryWidth.value.roundToInt().coerceIn(
            MeteorDetectService.MIN_TRAJECTORY_MAX_WIDTH,
            MeteorDetectService.MAX_TRAJECTORY_MAX_WIDTH
        )

    private fun selectedTrajectoryMinLength(): Int =
        binding.sliderTrajectoryMinLength.value.roundToInt().coerceIn(
            MeteorDetectService.MIN_TRAJECTORY_MIN_LENGTH,
            MeteorDetectService.MAX_TRAJECTORY_MIN_LENGTH
        )

    private fun selectedSmallMeteorBoost(): Boolean =
        binding.switchSmallMeteorBoost.isChecked

    private fun selectedSaveVideo(): Boolean = binding.checkboxSaveVideo.isChecked

    private fun selectedSavePhoto(): Boolean = binding.checkboxSavePhoto.isChecked

    private fun loadSavedOutputSelection(
        preferences: android.content.SharedPreferences
    ): OutputSelection {
        val hasSaveVideo = preferences.contains(MeteorDetectService.PREF_SAVE_VIDEO)
        val hasSavePhoto = preferences.contains(MeteorDetectService.PREF_SAVE_PHOTO)
        val legacyMode = CaptureMode.fromPreference(
            preferences.getString(MeteorDetectService.PREF_CAPTURE_MODE, null)
        )
        var saveVideo = if (hasSaveVideo) {
            preferences.getBoolean(MeteorDetectService.PREF_SAVE_VIDEO, true)
        } else {
            true
        }
        var savePhoto = if (hasSavePhoto) {
            preferences.getBoolean(MeteorDetectService.PREF_SAVE_PHOTO, false)
        } else {
            legacyMode == CaptureMode.PHOTO_TRAIL
        }
        if (!saveVideo && !savePhoto) {
            saveVideo = true
            savePhoto = false
        }
        preferences.edit {
            putBoolean(MeteorDetectService.PREF_SAVE_VIDEO, saveVideo)
            putBoolean(MeteorDetectService.PREF_SAVE_PHOTO, savePhoto)
        }
        return OutputSelection(saveVideo, savePhoto)
    }

    private fun renderSensitivity(sensitivity: DetectionSensitivity) {
        binding.textSensitivityValue.text = getString(
            R.string.detection_sensitivity_value,
            sensitivity.title
        )
        binding.textSensitivityHint.text = sensitivity.summary
    }

    private fun renderTrajectoryWidth(width: Int) {
        binding.textTrajectoryWidthValue.text = getString(
            R.string.trajectory_width_value,
            width
        )
    }

    private fun renderTrajectoryMinLength(length: Int) {
        binding.textTrajectoryMinLengthValue.text = getString(
            R.string.trajectory_min_length_value,
            length
        )
    }

    /**
     * 外接星空 UVC 摄像头通常比手机镜头更适合捕捉细线弱光目标，因此默认用更灵敏的检测档。
     * 用户切到 USB 时自动套用这组值；之后仍可手动把轨迹宽度在 16～20px 等范围微调。
     */
    private fun applyUsbRecommendedDetectionDefaults(
        preferences: android.content.SharedPreferences,
        notifyService: Boolean
    ) {
        val sensitivity = DetectionSensitivity.fromLevel(
            MeteorDetectService.DEFAULT_UVC_DETECTION_SENSITIVITY_LEVEL
        )
        val width = MeteorDetectService.DEFAULT_UVC_TRAJECTORY_MAX_WIDTH.coerceIn(
            MeteorDetectService.MIN_TRAJECTORY_MAX_WIDTH,
            MeteorDetectService.MAX_TRAJECTORY_MAX_WIDTH
        )
        val minLength = MeteorDetectService.DEFAULT_UVC_TRAJECTORY_MIN_LENGTH.coerceIn(
            MeteorDetectService.MIN_TRAJECTORY_MIN_LENGTH,
            MeteorDetectService.MAX_TRAJECTORY_MIN_LENGTH
        )
        val boostSmallMeteors = MeteorDetectService.DEFAULT_UVC_SMALL_METEOR_BOOST

        applyingDetectionPreset = true
        try {
            binding.sliderSensitivity.value = sensitivity.level.toFloat()
            binding.sliderTrajectoryWidth.value = width.toFloat()
            binding.sliderTrajectoryMinLength.value = minLength.toFloat()
            binding.switchSmallMeteorBoost.isChecked = boostSmallMeteors
        } finally {
            applyingDetectionPreset = false
        }

        preferences.edit {
            putInt(MeteorDetectService.PREF_DETECTION_SENSITIVITY, sensitivity.level)
            putInt(MeteorDetectService.PREF_TRAJECTORY_MAX_WIDTH, width)
            putInt(MeteorDetectService.PREF_TRAJECTORY_MIN_LENGTH, minLength)
            putBoolean(MeteorDetectService.PREF_SMALL_METEOR_BOOST, boostSmallMeteors)
            putBoolean(MeteorDetectService.PREF_UVC_DETECTION_DEFAULTS_APPLIED, true)
        }
        renderSensitivity(sensitivity)
        renderTrajectoryWidth(width)
        renderTrajectoryMinLength(minLength)

        if (notifyService && MeteorDetectService.running.get()) {
            MeteorDetectService.updateDetectionSettings(
                this,
                sensitivity.level,
                width,
                minLength,
                boostSmallMeteors
            )
        }
    }

    /**
     * 兼容已经安装过旧版的用户：如果保存来源就是 USB，但从未应用过 UVC 推荐检测参数，
     * 首次打开新版时自动迁移一次，避免仍停留在旧的保守默认值。
     */
    private fun shouldApplyInitialUvcDetectionDefaults(
        preferences: android.content.SharedPreferences
    ): Boolean {
        if (preferences.getBoolean(
                MeteorDetectService.PREF_UVC_DETECTION_DEFAULTS_APPLIED,
                false
            )
        ) {
            return false
        }
        return preferences.getInt(
            MeteorDetectService.PREF_DETECTION_SENSITIVITY,
            DetectionSensitivity.DEFAULT.level
        ) == DetectionSensitivity.DEFAULT.level &&
            preferences.getInt(
                MeteorDetectService.PREF_TRAJECTORY_MAX_WIDTH,
                MeteorDetectService.DEFAULT_TRAJECTORY_MAX_WIDTH
            ) == MeteorDetectService.DEFAULT_TRAJECTORY_MAX_WIDTH &&
            preferences.getInt(
                MeteorDetectService.PREF_TRAJECTORY_MIN_LENGTH,
                MeteorDetectService.DEFAULT_TRAJECTORY_MIN_LENGTH
            ) == MeteorDetectService.DEFAULT_TRAJECTORY_MIN_LENGTH &&
            !preferences.getBoolean(
                MeteorDetectService.PREF_SMALL_METEOR_BOOST,
                MeteorDetectService.DEFAULT_SMALL_METEOR_BOOST
            )
    }

    private fun renderCaptureOutput(saveVideo: Boolean, savePhoto: Boolean) {
        binding.textCaptureModeHint.text = when {
            saveVideo && savePhoto -> getString(R.string.capture_output_both_hint)
            saveVideo -> getString(R.string.capture_output_video_hint)
            savePhoto -> getString(R.string.capture_output_photo_hint)
            else -> getString(R.string.capture_output_required)
        }
    }

    private fun onOutputSelectionChanged(
        preferences: android.content.SharedPreferences,
        changedVideo: Boolean
    ) {
        if (applyingOutputSelection) return

        var saveVideo = selectedSaveVideo()
        var savePhoto = selectedSavePhoto()
        if (!saveVideo && !savePhoto) {
            applyingOutputSelection = true
            if (changedVideo) {
                binding.checkboxSavePhoto.isChecked = true
                savePhoto = true
            } else {
                binding.checkboxSaveVideo.isChecked = true
                saveVideo = true
            }
            applyingOutputSelection = false
            Toast.makeText(this, R.string.capture_output_required, Toast.LENGTH_SHORT).show()
        }

        preferences.edit {
            putBoolean(MeteorDetectService.PREF_SAVE_VIDEO, saveVideo)
            putBoolean(MeteorDetectService.PREF_SAVE_PHOTO, savePhoto)
            // 兼容旧版本：PHOTO_TRAIL 表示视频+图片，VIDEO_ONLY 表示仅视频。
            putString(
                MeteorDetectService.PREF_CAPTURE_MODE,
                if (savePhoto) {
                    CaptureMode.PHOTO_TRAIL.preferenceValue
                } else {
                    CaptureMode.VIDEO_ONLY.preferenceValue
                }
            )
        }
        renderCaptureOutput(saveVideo, savePhoto)
    }

    private fun renderControls() {
        val running = MeteorDetectService.running.get()
        val desiredOrientation = if (running) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != desiredOrientation) {
            requestedOrientation = desiredOrientation
        }
        val source = selectedCameraSource()
        val usbSelected = source == CameraSource.USB

        binding.groupUsbDevices.isVisible = usbSelected
        binding.cardUvcParameters.isVisible = usbSelected
        binding.cardCameraPreview.isVisible = running
        binding.textPreviewPlaceholder.isVisible = running && !hasPreviewFrame
        binding.textCameraSourceHint.setText(
            when {
                running && source == CameraSource.PHONE ->
                    R.string.phone_camera_running_hint
                running && source == CameraSource.USB ->
                    R.string.usb_camera_running_hint
                source == CameraSource.PHONE && !internalCameraAvailable ->
                    R.string.no_internal_camera
                source == CameraSource.PHONE -> R.string.phone_camera_hint
                else -> R.string.usb_camera_hint
            }
        )
        binding.buttonSourcePhone.isEnabled = !running && internalCameraAvailable
        binding.buttonSourceUsb.isEnabled = !running
        binding.spinnerUsbDevices.isEnabled = !running && uvcDevices.isNotEmpty()
        binding.buttonRefresh.isEnabled = !running
        binding.checkboxSaveVideo.isEnabled = !running
        binding.checkboxSavePhoto.isEnabled = !running
        binding.buttonStart.isEnabled = !running && when (source) {
            CameraSource.PHONE -> internalCameraAvailable
            CameraSource.USB -> uvcDevices.isNotEmpty()
        }
        binding.buttonStop.isEnabled = running
    }

    /**
     * 颜色转换和 TextureView 绘制都在 Default 线程完成；服务 SharedFlow 只有一帧容量，
     * 因此页面跟不上时只会跳帧，不会拖慢编码与运动检测。
     */
    private suspend fun renderPreview(preview: PreviewFrame) {
        if (preview.streamGeneration != MeteorDetectService.previewGeneration.value) return
        val rendered = withContext(Dispatchers.Default) {
            val cardRendered = binding.previewCamera.render(preview)
            val fullScreenRendered = fullScreenPreviewView?.render(preview) ?: false
            cardRendered || fullScreenRendered
        }
        if (preview.streamGeneration != MeteorDetectService.previewGeneration.value) {
            binding.previewCamera.clear()
            fullScreenPreviewView?.clear()
            hasPreviewFrame = false
            binding.textPreviewPlaceholder.isVisible = MeteorDetectService.running.get()
            fullScreenPreviewPlaceholder?.isVisible = MeteorDetectService.running.get()
            return
        }
        if (rendered) {
            hasPreviewFrame = true
            binding.textPreviewPlaceholder.isVisible = false
            fullScreenPreviewPlaceholder?.isVisible = false
        }
    }

    private fun showFullScreenPreview() {
        if (fullScreenPreviewDialog?.isShowing == true) return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val preview = Nv21PreviewView(this).apply {
            contentDescription = getString(R.string.live_preview_description)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val placeholder = TextView(this).apply {
            text = getString(R.string.live_preview_waiting)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            isVisible = !hasPreviewFrame
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val close = TextView(this).apply {
            text = getString(R.string.live_preview_exit_fullscreen)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            setPadding(28, 18, 28, 18)
            setOnClickListener { dialog.dismiss() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 42
                rightMargin = 24
            }
        }
        root.addView(preview)
        root.addView(placeholder)
        root.addView(close)
        root.setOnClickListener { dialog.dismiss() }
        preview.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            fullScreenPreviewView?.clear()
            fullScreenPreviewDialog = null
            fullScreenPreviewView = null
            fullScreenPreviewPlaceholder = null
        }
        fullScreenPreviewDialog = dialog
        fullScreenPreviewView = preview
        fullScreenPreviewPlaceholder = placeholder
        dialog.show()
    }

    private fun setupUvcParameterControls(preferences: android.content.SharedPreferences) {
        loadUvcParameterControls(preferences)
        renderUvcParameterLabels()

        binding.switchUvcAutoExposure.setOnCheckedChangeListener { _, _ ->
            binding.sliderUvcExposure.isEnabled = !binding.switchUvcAutoExposure.isChecked
            saveAndApplyUvcParameters(preferences)
        }
        binding.switchUvcAutoWhiteBalance.setOnCheckedChangeListener { _, _ ->
            binding.sliderUvcWhiteBalance.isEnabled =
                !binding.switchUvcAutoWhiteBalance.isChecked
            saveAndApplyUvcParameters(preferences)
        }

        listOf(
            binding.sliderUvcExposure,
            binding.sliderUvcGain,
            binding.sliderUvcWhiteBalance,
            binding.sliderUvcBrightness,
            binding.sliderUvcContrast,
            binding.sliderUvcGamma,
            binding.sliderUvcSharpness,
            binding.sliderUvcSaturation,
            binding.sliderUvcBacklight
        ).forEach { slider ->
            slider.addOnChangeListener { _, _, fromUser ->
                renderUvcParameterLabels()
                if (fromUser) saveAndApplyUvcParameters(preferences)
            }
        }

        binding.buttonResetUvcParameters.setOnClickListener {
            MeteorDetectService.resetUvcParameterPreferences(this)
            loadUvcParameterControls(preferences)
            renderUvcParameterLabels()
            Toast.makeText(this, "已恢复流星默认 UVC 参数", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUvcParameterControls(preferences: android.content.SharedPreferences) {
        binding.switchUvcAutoExposure.isChecked = preferences.getBoolean(
            MeteorDetectService.PREF_UVC_AUTO_EXPOSURE,
            MeteorDetectService.DEFAULT_UVC_AUTO_EXPOSURE
        )
        binding.sliderUvcExposure.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_EXPOSURE_MS,
            MeteorDetectService.DEFAULT_UVC_EXPOSURE_MS
        ).coerceIn(
            MeteorDetectService.MIN_UVC_EXPOSURE_MS,
            MeteorDetectService.MAX_UVC_EXPOSURE_MS
        ).toFloat()
        binding.sliderUvcGain.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_GAIN,
            MeteorDetectService.DEFAULT_UVC_GAIN
        ).coercePercent().toFloat()
        binding.switchUvcAutoWhiteBalance.isChecked = preferences.getBoolean(
            MeteorDetectService.PREF_UVC_AUTO_WHITE_BALANCE,
            MeteorDetectService.DEFAULT_UVC_AUTO_WHITE_BALANCE
        )
        binding.sliderUvcWhiteBalance.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_WHITE_BALANCE,
            MeteorDetectService.DEFAULT_UVC_WHITE_BALANCE
        ).coercePercent().toFloat()
        binding.sliderUvcBrightness.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_BRIGHTNESS,
            MeteorDetectService.DEFAULT_UVC_BRIGHTNESS
        ).coercePercent().toFloat()
        binding.sliderUvcContrast.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_CONTRAST,
            MeteorDetectService.DEFAULT_UVC_CONTRAST
        ).coercePercent().toFloat()
        binding.sliderUvcGamma.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_GAMMA,
            MeteorDetectService.DEFAULT_UVC_GAMMA
        ).coercePercent().toFloat()
        binding.sliderUvcSharpness.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_SHARPNESS,
            MeteorDetectService.DEFAULT_UVC_SHARPNESS
        ).coercePercent().toFloat()
        binding.sliderUvcSaturation.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_SATURATION,
            MeteorDetectService.DEFAULT_UVC_SATURATION
        ).coercePercent().toFloat()
        binding.sliderUvcBacklight.value = preferences.getInt(
            MeteorDetectService.PREF_UVC_BACKLIGHT,
            MeteorDetectService.DEFAULT_UVC_BACKLIGHT
        ).coerceIn(
            MeteorDetectService.MIN_UVC_BACKLIGHT,
            MeteorDetectService.MAX_UVC_BACKLIGHT
        ).toFloat()

        binding.sliderUvcExposure.isEnabled = !binding.switchUvcAutoExposure.isChecked
        binding.sliderUvcWhiteBalance.isEnabled = !binding.switchUvcAutoWhiteBalance.isChecked
    }

    private fun saveAndApplyUvcParameters(preferences: android.content.SharedPreferences) {
        preferences.edit {
            putBoolean(
                MeteorDetectService.PREF_UVC_AUTO_EXPOSURE,
                binding.switchUvcAutoExposure.isChecked
            )
            putInt(MeteorDetectService.PREF_UVC_EXPOSURE_MS, binding.sliderUvcExposure.intValue())
            putInt(MeteorDetectService.PREF_UVC_GAIN, binding.sliderUvcGain.intValue())
            putBoolean(
                MeteorDetectService.PREF_UVC_AUTO_WHITE_BALANCE,
                binding.switchUvcAutoWhiteBalance.isChecked
            )
            putInt(
                MeteorDetectService.PREF_UVC_WHITE_BALANCE,
                binding.sliderUvcWhiteBalance.intValue()
            )
            putInt(MeteorDetectService.PREF_UVC_BRIGHTNESS, binding.sliderUvcBrightness.intValue())
            putInt(MeteorDetectService.PREF_UVC_CONTRAST, binding.sliderUvcContrast.intValue())
            putInt(MeteorDetectService.PREF_UVC_GAMMA, binding.sliderUvcGamma.intValue())
            putInt(MeteorDetectService.PREF_UVC_SHARPNESS, binding.sliderUvcSharpness.intValue())
            putInt(MeteorDetectService.PREF_UVC_SATURATION, binding.sliderUvcSaturation.intValue())
            putInt(MeteorDetectService.PREF_UVC_BACKLIGHT, binding.sliderUvcBacklight.intValue())
        }
        renderUvcParameterLabels()
        MeteorDetectService.updateUvcParameters(this)
    }

    private fun renderUvcParameterLabels() {
        binding.textUvcExposure.text = getString(
            R.string.uvc_exposure_value,
            binding.sliderUvcExposure.intValue()
        )
        binding.textUvcGain.text = percentLabel("增益", binding.sliderUvcGain.intValue())
        binding.textUvcWhiteBalance.text = percentLabel(
            "白平衡",
            binding.sliderUvcWhiteBalance.intValue()
        )
        binding.textUvcBrightness.text = percentLabel("亮度", binding.sliderUvcBrightness.intValue())
        binding.textUvcContrast.text = percentLabel("对比度", binding.sliderUvcContrast.intValue())
        binding.textUvcGamma.text = percentLabel("Gamma", binding.sliderUvcGamma.intValue())
        binding.textUvcSharpness.text = percentLabel("锐度", binding.sliderUvcSharpness.intValue())
        binding.textUvcSaturation.text = percentLabel("饱和度", binding.sliderUvcSaturation.intValue())
        binding.textUvcBacklight.text = getString(
            R.string.uvc_backlight_value,
            binding.sliderUvcBacklight.intValue()
        )
    }

    private fun percentLabel(name: String, value: Int): String =
        getString(R.string.uvc_percent_value, name, value)

    private fun com.google.android.material.slider.Slider.intValue(): Int =
        value.roundToInt()

    private fun Int.coercePercent(): Int = coerceIn(0, 100)

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private fun showSavedToastIfNeeded(status: String) {
        if (!status.startsWith(SAVED_STATUS_PREFIX)) return
        if (lastSavedToastStatus == status) return
        lastSavedToastStatus = status
        Toast.makeText(
            this,
            "抓拍成功：${status.removePrefix(SAVED_STATUS_PREFIX)}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openBatteryOptimizationSettings() {
        val powerManager = getSystemService(PowerManager::class.java)
        val exempt = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        if (exempt) {
            Toast.makeText(this, "MeteorSnap 已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure { openApplicationDetails() }
    }

    /**
     * Android 没有统一的“自启动”API。这里用编号和图标式文字给出厂商无关步骤，再打开应用详情页。
     */
    private fun showAutoStartGuide() {
        val guide = """
            ① 🔋 在“电池”中把 MeteorSnap 设为“不限制”

            ② 🚀 在“自启动/后台启动”中允许本应用

            ③ 🔒 在最近任务中锁定 MeteorSnap（若系统支持）

            ④ 🔌 保持 OTG 供电，并确认通知栏显示“流星监测中”

            不同品牌的菜单名称会不同；Android 没有可统一跳转的厂商自启动页面。
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("后台监测图文引导")
            .setMessage(guide)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("打开应用详情") { _, _ -> openApplicationDetails() }
            .show()
    }

    private fun openApplicationDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        runCatching { startActivity(intent) }
    }

    companion object {
        private const val STATE_PENDING_SOURCE = "pending_camera_source"
        private const val STATE_PENDING_USB_DEVICE = "pending_usb_device"
        private const val SAVED_STATUS_PREFIX = "当前状态：已保存 "
    }
}
