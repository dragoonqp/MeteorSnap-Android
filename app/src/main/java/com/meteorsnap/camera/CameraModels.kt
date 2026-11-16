package com.meteorsnap.camera

/**
 * MeteorSnap 支持的采集来源。
 *
 * [preferenceValue] 是写入 SharedPreferences/Intent 的稳定值，不要直接持久化 enum.name，
 * 这样以后重命名 Kotlin 枚举项也不会破坏已安装版本的恢复逻辑。
 */
enum class CameraSource(val preferenceValue: String) {
    PHONE("phone"),
    USB("usb");

    companion object {
        /** 兼容旧版本：曾保存过 USB 路径但没有来源字段时仍恢复为外挂相机。 */
        fun fromPreference(value: String?, legacyUsbDeviceName: String? = null): CameraSource {
            return entries.firstOrNull { it.preferenceValue == value }
                ?: if (!legacyUsbDeviceName.isNullOrBlank()) USB else PHONE
        }
    }
}

/** 相机已经协商成功的实际输出规格。 */
data class CameraStreamFormat(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** 写入 MP4 旋转矩阵；UVC 默认 0，Camera2 根据传感器与启动时屏幕方向计算。 */
    val rotationDegrees: Int = 0
)

/**
 * 两种相机共享的帧格式：紧凑 NV21，即 Y 平面后接交错 VU 平面。
 * data 的所有权转移给回调接收方；时间戳必须单调递增且单位为纳秒。
 */
data class Nv21Frame(
    val data: ByteArray,
    val timestampNs: Long,
    val width: Int,
    val height: Int
)

/**
 * 仅供前台页面预览的轻量包装。frame.data 与编码/检测共享只读所有权，不再次复制 1080p 帧。
 * [streamGeneration] 用于丢弃断线重连后迟到的旧画面。
 */
data class PreviewFrame(
    val frame: Nv21Frame,
    val rotationDegrees: Int,
    val streamGeneration: Int
)

/** Camera2 官方相对旋转公式的纯函数版本，便于在本地单元测试中覆盖。 */
internal fun camera2VideoRotationDegrees(
    sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
    isFrontFacing: Boolean
): Int {
    require(sensorOrientationDegrees in setOf(0, 90, 180, 270))
    require(displayRotationDegrees in setOf(0, 90, 180, 270))
    return if (isFrontFacing) {
        (sensorOrientationDegrees + displayRotationDegrees) % 360
    } else {
        (sensorOrientationDegrees - displayRotationDegrees + 360) % 360
    }
}
