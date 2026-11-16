package com.meteorsnap

/**
 * 流星触发后的输出模式。
 *
 * [preferenceValue] 是持久化到 SharedPreferences/Intent 的稳定值，避免将来重命名枚举项
 * 破坏老版本用户设置。
 */
enum class CaptureMode(
    val preferenceValue: String,
    val title: String,
    val summary: String
) {
    VIDEO_ONLY(
        preferenceValue = "video_only",
        title = "流星检测模式",
        summary = "抓到疑似流星后保存前后 3 秒 MP4，优先稳定、低内存。"
    ),
    PHOTO_TRAIL(
        preferenceValue = "photo_trail",
        title = "流星照片模式",
        summary = "除 MP4 外，额外生成一张最大亮度叠加 JPG，让流星轨迹更像一条线。"
    );

    companion object {
        val DEFAULT = VIDEO_ONLY

        fun fromPreference(value: String?): CaptureMode =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
