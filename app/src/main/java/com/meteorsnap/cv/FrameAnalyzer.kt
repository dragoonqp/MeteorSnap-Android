package com.meteorsnap.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 输入 [Mat] 的像素布局。
 *
 * [AUTO] 无法区分单通道灰度图和以 `H * 3 / 2` 行存放的 NV21；UVC 回调路径必须显式使用
 * [NV21]。`YUV_420_888` 是三平面格式，必须先在相机层按 stride 归一化，不能直接伪装成 Mat。
 */
enum class FrameLayout {
    AUTO,
    GRAY,
    BGR,
    RGB,
    BGRA,
    RGBA,
    NV21
}

data class AnalysisResult(
    /** 本次是否真正执行了 OpenCV 分析；false 表示空帧或被跳帧策略忽略。 */
    val analyzed: Boolean,
    /** 本次已分析样本中，变化像素比例是否严格大于阈值。 */
    val rawMotion: Boolean,
    /** 连续命中指定次数时产生的一次性确认脉冲；持续运动期间不会重复触发。 */
    val confirmedEvent: Boolean,
    val changedPixelRatio: Double,
    /** 调试信息：本次参与分析的 ROI 像素总数。 */
    val totalPixels: Long = 0L,
    /** 调试信息：超过亮度差阈值的像素数。 */
    val changedPixels: Long = 0L,
    /** 调试信息：当前使用的普通变化比例阈值。 */
    val changedRatioThreshold: Double = 0.0,
    /** 调试信息：当前使用的单帧强线比例阈值。 */
    val strongChangedRatioThreshold: Double = 0.0,
    /** 调试信息：候选线段长度；0 表示未找到 Hough 线。 */
    val candidateLength: Double = 0.0,
    /** 调试信息：候选线角度，单位度；未找到候选时为 0。 */
    val candidateAngleDegrees: Double = 0.0,
    /** 调试信息：HoughLinesP 找到的线条数量。 */
    val houghLineCount: Int = 0,
    /** 调试信息：参与候选判断的最大连通域面积。 */
    val largestCandidateArea: Int = 0,
    /** 调试信息：最大候选连通域长边。 */
    val largestCandidateLongSide: Int = 0,
    /** 调试信息：最大候选连通域短边。 */
    val largestCandidateShortSide: Int = 0,
    /** 调试信息：最大候选连通域占全部变化像素比例。 */
    val dominantFraction: Double = 0.0,
    /** 调试信息：连续普通候选计数。 */
    val consecutiveHits: Int = 0,
    /** 调试信息：连续轨迹确认计数。 */
    val trajectoryHits: Int = 0,
    /** 调试信息：单帧强亮线是否通过。 */
    val singleFrameStrongStreak: Boolean = false,
    /** 调试信息：跨帧轨迹是否通过。 */
    val trajectoryConfirmed: Boolean = false,
    /** 调试信息：本帧主要卡在哪个门槛。 */
    val rejectReason: String = "未分析",
    /** 调试信息：如果触发保存，说明触发来源。 */
    val triggerReason: String = ""
) {
    companion object {
        val SKIPPED = AnalysisResult(
            analyzed = false,
            rawMotion = false,
            confirmedEvent = false,
            changedPixelRatio = 0.0
        )
        val BASELINE = AnalysisResult(
            analyzed = true,
            rawMotion = false,
            confirmedEvent = false,
            changedPixelRatio = 0.0
        )
    }
}

data class DetectionConfig(
    val differenceThreshold: Double,
    val changedRatioThreshold: Double,
    val strongChangedRatioThreshold: Double,
    val requiredConsecutiveHits: Int,
    val minStreakPixels: Int,
    val minStreakLongSide: Int,
    val minHoughVotes: Int,
    val maxStreakShortSide: Int,
    val minStreakAspectRatio: Double,
    val maxStreakPixelRatio: Double,
    val maxTotalChangedRatio: Double,
    val minDominantStreakFraction: Double,
    val allowSingleFrameStrongStreak: Boolean
)

enum class DetectionSensitivity(
    val level: Int,
    val title: String,
    val summary: String,
    val config: DetectionConfig
) {
    MEDIUM_LOW(
        level = 4,
        title = "较低",
        summary = "新版最低档；等同旧版最高敏感度，适合先控制误报",
        config = DetectionConfig(
            differenceThreshold = 22.0,
            changedRatioThreshold = 0.00045,
            strongChangedRatioThreshold = 0.0014,
            requiredConsecutiveHits = 3,
            minStreakPixels = 12,
            minStreakLongSide = 10,
            minHoughVotes = 6,
            maxStreakShortSide = 32,
            minStreakAspectRatio = 2.4,
            maxStreakPixelRatio = 0.035,
            maxTotalChangedRatio = 0.055,
            minDominantStreakFraction = 0.35,
            allowSingleFrameStrongStreak = false
        )
    ),
    STANDARD(
        level = 5,
        title = "标准",
        summary = "比旧版最高档更灵敏，推荐用于细小流星实测",
        config = DetectionConfig(
            differenceThreshold = 19.0,
            changedRatioThreshold = 0.00032,
            strongChangedRatioThreshold = 0.0010,
            requiredConsecutiveHits = 3,
            minStreakPixels = 9,
            minStreakLongSide = 8,
            minHoughVotes = 5,
            maxStreakShortSide = 36,
            minStreakAspectRatio = 2.0,
            maxStreakPixelRatio = 0.045,
            maxTotalChangedRatio = 0.075,
            minDominantStreakFraction = 0.28,
            allowSingleFrameStrongStreak = false
        )
    ),
    MEDIUM_HIGH(
        level = 6,
        title = "较高",
        summary = "更容易抓到短而暗的细线流星；建议配合较小轨迹宽度使用",
        config = DetectionConfig(
            differenceThreshold = 17.0,
            changedRatioThreshold = 0.00024,
            strongChangedRatioThreshold = 0.00075,
            requiredConsecutiveHits = 3,
            minStreakPixels = 7,
            minStreakLongSide = 6,
            minHoughVotes = 4,
            maxStreakShortSide = 40,
            minStreakAspectRatio = 1.7,
            maxStreakPixelRatio = 0.060,
            maxTotalChangedRatio = 0.100,
            minDominantStreakFraction = 0.22,
            allowSingleFrameStrongStreak = true
        )
    ),
    HIGH(
        level = 7,
        title = "高",
        summary = "最容易抓到微弱/很短流星；若普通移动也触发，请回到标准或较低",
        config = DetectionConfig(
            differenceThreshold = 15.0,
            changedRatioThreshold = 0.00018,
            strongChangedRatioThreshold = 0.00055,
            requiredConsecutiveHits = 3,
            minStreakPixels = 5,
            minStreakLongSide = 4,
            minHoughVotes = 3,
            maxStreakShortSide = 40,
            minStreakAspectRatio = 1.4,
            maxStreakPixelRatio = 0.080,
            maxTotalChangedRatio = 0.140,
            minDominantStreakFraction = 0.16,
            allowSingleFrameStrongStreak = true
        )
    );

    companion object {
        val DEFAULT = STANDARD

        fun fromLevel(level: Int): DetectionSensitivity =
            entries.firstOrNull { it.level == level } ?: DEFAULT
    }
}

/**
 * OpenCV 帧差运动分析器。
 *
 * 每个真正参与分析的输入帧都会先缩放到 320×240，再截取中央宽、高各 70% 的 ROI。
 * 分析器将 ROI 转为灰度图，与上一个“已分析帧”做正向亮度差和二值化，再检查差分区域是否
 * 能通过连通域与 Hough 线段过滤。只有像流星轨迹的变化才会记作一次命中；连续两次命中，或单帧强轨迹，
 * 才会产生一次 [AnalysisResult.confirmedEvent]。星点闪烁、曝光波动、播放器压缩噪声和大面积
 * 画面变化会被过滤掉。
 *
 * 这是有状态对象。虽然公开方法使用同步锁避免并发执行，但多个生产者仍可能以错误的时间顺序
 * 抢到锁，因此业务层必须由一个按时间有序的 consumer 调用。分析器不会持有调用方传入的 Mat。
 * 构造本类之前必须已成功加载 OpenCV native 库。
 */
class FrameAnalyzer(
    private val inputLayout: FrameLayout = FrameLayout.AUTO,
    initialConfig: DetectionConfig = DetectionSensitivity.DEFAULT.config,
    initialAnalyzeEveryNFrames: Int = NORMAL_ANALYZE_EVERY_N_FRAMES
) : Closeable {

    companion object {
        private const val ANALYSIS_WIDTH = 320
        private const val ANALYSIS_HEIGHT = 240
        private const val ROI_SCALE = 0.70

        private const val NORMAL_ANALYZE_EVERY_N_FRAMES = 1
        private const val HOT_ANALYZE_EVERY_N_FRAMES = 5
        private const val HOUGH_RHO = 1.0
        private val HOUGH_THETA = PI / 180.0
        private const val HOUGH_MAX_LINE_GAP = 8.0
        private const val STRONG_STREAK_LENGTH_MULTIPLIER = 1.8
        private const val MAX_TRACK_ANGLE_DELTA_DEGREES = 35.0
        private const val MAX_TRACK_CENTER_DISTANCE = 90.0
    }

    private val analysisSize = Size(
        ANALYSIS_WIDTH.toDouble(),
        ANALYSIS_HEIGHT.toDouble()
    )
    private val roiRect = run {
        // “中央 70%”按宽和高分别取 70% 解释，对应 224×168。
        val width = (ANALYSIS_WIDTH * ROI_SCALE).roundToInt()
        val height = (ANALYSIS_HEIGHT * ROI_SCALE).roundToInt()
        Rect(
            (ANALYSIS_WIDTH - width) / 2,
            (ANALYSIS_HEIGHT - height) / 2,
            width,
            height
        )
    }

    // 下列 Mat 全部由本类独占，并在 close() 中逐一释放；调用方不得接管或释放。
    private val resizedColor = Mat()
    private val currentGray = Mat()
    private val previousGrayRoi = Mat()
    private val difference = Mat()
    private val binaryMask = Mat()
    private val componentLabels = Mat()
    private val componentStats = Mat()
    private val componentCentroids = Mat()
    private val houghLines = Mat()

    private var differenceThreshold = initialConfig.differenceThreshold
    private var changedRatioThreshold = initialConfig.changedRatioThreshold
    private var strongChangedRatioThreshold = initialConfig.strongChangedRatioThreshold
    private var requiredConsecutiveHits = initialConfig.requiredConsecutiveHits
    private var minStreakPixels = initialConfig.minStreakPixels
    private var minStreakLongSide = initialConfig.minStreakLongSide
    private var minHoughVotes = initialConfig.minHoughVotes
    private var maxStreakShortSide = initialConfig.maxStreakShortSide
    private var minStreakAspectRatio = initialConfig.minStreakAspectRatio
    private var maxStreakPixelRatio = initialConfig.maxStreakPixelRatio
    private var maxTotalChangedRatio = initialConfig.maxTotalChangedRatio
    private var minDominantStreakFraction = initialConfig.minDominantStreakFraction
    private var allowSingleFrameStrongStreak = initialConfig.allowSingleFrameStrongStreak

    private var hasBaseline = false
    private var consecutiveHits = 0
    private var trajectoryHits = 0
    private var previousCandidate: StreakCandidate? = null
    private var eventLatched = false
    private var closed = false

    private var analyzeEveryNFrames = initialAnalyzeEveryNFrames
    private var framesToSkip = 0

    private data class StreakCandidate(
        val centerX: Double,
        val centerY: Double,
        val length: Double,
        val angleRadians: Double
    )

    private data class StreakDetection(
        val candidate: StreakCandidate?,
        val rejectReason: String,
        val componentCount: Int = 0,
        val largestCandidateArea: Int = 0,
        val largestCandidateLongSide: Int = 0,
        val largestCandidateShortSide: Int = 0,
        val dominantFraction: Double = 0.0,
        val houghLineCount: Int = 0
    )

    init {
        validateConfig(initialConfig)
        require(initialAnalyzeEveryNFrames > 0) {
            "initialAnalyzeEveryNFrames 必须大于 0"
        }
    }

    private fun validateConfig(config: DetectionConfig) {
        require(config.differenceThreshold in 0.0..255.0) {
            "differenceThreshold 必须位于 [0, 255]"
        }
        require(config.changedRatioThreshold in 0.0..1.0) {
            "changedRatioThreshold 必须位于 [0, 1]"
        }
        require(config.strongChangedRatioThreshold in config.changedRatioThreshold..1.0) {
            "strongChangedRatioThreshold 必须位于 [changedRatioThreshold, 1]"
        }
        require(config.requiredConsecutiveHits > 0) {
            "requiredConsecutiveHits 必须大于 0"
        }
        require(config.minStreakPixels > 0) {
            "minStreakPixels 必须大于 0"
        }
        require(config.minStreakLongSide > 0) {
            "minStreakLongSide 必须大于 0"
        }
        require(config.minHoughVotes > 0) {
            "minHoughVotes 必须大于 0"
        }
        require(config.maxStreakShortSide > 0) {
            "maxStreakShortSide 必须大于 0"
        }
        require(config.minStreakAspectRatio >= 1.0) {
            "minStreakAspectRatio 必须大于等于 1"
        }
        require(config.maxStreakPixelRatio in 0.0..1.0) {
            "maxStreakPixelRatio 必须位于 [0, 1]"
        }
        require(config.maxTotalChangedRatio in 0.0..1.0) {
            "maxTotalChangedRatio 必须位于 [0, 1]"
        }
        require(config.minDominantStreakFraction in 0.0..1.0) {
            "minDominantStreakFraction 必须位于 [0, 1]"
        }
    }

    @Synchronized
    fun applyConfig(config: DetectionConfig) {
        check(!closed) { "FrameAnalyzer 已关闭" }
        validateConfig(config)
        differenceThreshold = config.differenceThreshold
        changedRatioThreshold = config.changedRatioThreshold
        strongChangedRatioThreshold = config.strongChangedRatioThreshold
        requiredConsecutiveHits = config.requiredConsecutiveHits
        minStreakPixels = config.minStreakPixels
        minStreakLongSide = config.minStreakLongSide
        minHoughVotes = config.minHoughVotes
        maxStreakShortSide = config.maxStreakShortSide
        minStreakAspectRatio = config.minStreakAspectRatio
        maxStreakPixelRatio = config.maxStreakPixelRatio
        maxTotalChangedRatio = config.maxTotalChangedRatio
        minDominantStreakFraction = config.minDominantStreakFraction
        allowSingleFrameStrongStreak = config.allowSingleFrameStrongStreak
        resetStateLocked()
    }

    /**
     * 切换热降频状态：过热时每 5 帧分析一次，恢复时逐帧分析。
     * 只有目标间隔真正改变时才会重置 [framesToSkip]，因此周期性重复上报相同温控状态
     * 不会退化成逐帧分析。
     */
    @Synchronized
    fun setThermalThrottled(hot: Boolean) {
        check(!closed) { "FrameAnalyzer 已关闭" }
        val newInterval = if (hot) {
            HOT_ANALYZE_EVERY_N_FRAMES
        } else {
            NORMAL_ANALYZE_EVERY_N_FRAMES
        }
        updateAnalyzeIntervalLocked(newInterval)
    }

    /**
     * 手动设置分析间隔，主要用于调试或设备能力适配。重复设置相同值不会重置采样节奏。
     */
    @Synchronized
    fun setAnalyzeEveryNFrames(value: Int) {
        check(!closed) { "FrameAnalyzer 已关闭" }
        require(value > 0) { "分析间隔必须大于 0" }
        updateAnalyzeIntervalLocked(value)
    }

    /**
     * 简洁接口：仅在一次流星候选被连续确认时返回 true，而不是返回当前 raw motion 状态。
     */
    @Synchronized
    fun analyze(frame: Mat): Boolean = analyzeDetailedLocked(frame).confirmedEvent

    /**
     * 返回详细结果。Service 应使用 [AnalysisResult.rawMotion] 更新最后运动时间，不能对同一帧再调用
     * 一次 [analyze]，否则会让跳帧计数前进两次。
     */
    @Synchronized
    fun analyzeDetailed(frame: Mat): AnalysisResult = analyzeDetailedLocked(frame)

    private fun analyzeDetailedLocked(frame: Mat): AnalysisResult {
        check(!closed) { "FrameAnalyzer 已关闭" }
        if (frame.empty()) return AnalysisResult.SKIPPED

        if (framesToSkip > 0) {
            framesToSkip--
            return AnalysisResult.SKIPPED
        }
        framesToSkip = analyzeEveryNFrames - 1

        convertAndResizeToGray(frame)

        // submat() 只创建共享底层数据的 native header；即使不复制像素也必须在 finally 中释放。
        val currentRoi = currentGray.submat(roiRect)
        try {
            if (!hasBaseline) {
                // copyTo() 创建独立基线，后续 currentGray 被复用时不会覆盖它。
                currentRoi.copyTo(previousGrayRoi)
                hasBaseline = true
                return AnalysisResult.BASELINE
            }

            // 流星是“突然变亮”的细长轨迹；只看 current - previous 的正向亮度差。
            // 旧的 absdiff 会把画面变暗、亮轨消失、压缩闪烁也算作运动，误报更高。
            Core.subtract(currentRoi, previousGrayRoi, difference)
            Imgproc.threshold(
                difference,
                binaryMask,
                differenceThreshold,
                255.0,
                Imgproc.THRESH_BINARY
            )

            val changedPixels = Core.countNonZero(binaryMask).toLong()
            val totalPixels = roiRect.width.toLong() * roiRect.height.toLong()
            val changedRatio = changedPixels.toDouble() / totalPixels.toDouble()

            // 使用严格大于阈值；等于阈值的边界样本不算命中，避免抖动反复触发。
            val enoughChangedPixels = changedRatio > changedRatioThreshold
            val streakDetection = when {
                !enoughChangedPixels -> StreakDetection(
                    candidate = null,
                    rejectReason = "亮度变化不足"
                )
                changedRatio > maxTotalChangedRatio -> StreakDetection(
                    candidate = null,
                    rejectReason = "变化面积过大，像画面移动/闪烁"
                )
                else -> detectMeteorLikeStreakLocked(binaryMask, totalPixels, changedPixels)
            }
            val meteorCandidate = streakDetection.candidate
            val meteorLikeStreak = meteorCandidate != null
            val trajectoryConfirmed = updateTrajectoryStateLocked(meteorCandidate)
            val singleFrameStrongStreak = meteorCandidate != null &&
                allowSingleFrameStrongStreak &&
                changedRatio > strongChangedRatioThreshold &&
                meteorCandidate.length >= minStreakLongSide * STRONG_STREAK_LENGTH_MULTIPLIER
            val rawMotion = meteorLikeStreak
            val strongMotion = trajectoryConfirmed || singleFrameStrongStreak
            val confirmedEvent = updateConsecutiveStateLocked(
                rawMotion = rawMotion,
                // 高灵敏度档允许“足够长且足够亮”的单帧强线触发，用来减少 UVC/30fps
                // 下短促真实流星漏拍；较低/标准档仍只接受跨帧轨迹确认以控制误报。
                strongMotion = strongMotion
            )
            val triggerReason = when {
                confirmedEvent && singleFrameStrongStreak -> "单帧强亮线"
                confirmedEvent && trajectoryConfirmed -> "跨帧轨迹确认"
                else -> ""
            }
            val rejectReason = when {
                confirmedEvent -> "已触发：$triggerReason"
                meteorCandidate != null && !strongMotion -> "有细线候选，但未达到触发确认"
                else -> streakDetection.rejectReason
            }

            // 始终与“上一个已分析帧”比较；被跳过的输入帧不会成为基线。
            currentRoi.copyTo(previousGrayRoi)
            return AnalysisResult(
                analyzed = true,
                rawMotion = rawMotion,
                confirmedEvent = confirmedEvent,
                changedPixelRatio = changedRatio,
                totalPixels = totalPixels,
                changedPixels = changedPixels,
                changedRatioThreshold = changedRatioThreshold,
                strongChangedRatioThreshold = strongChangedRatioThreshold,
                candidateLength = meteorCandidate?.length ?: 0.0,
                candidateAngleDegrees = meteorCandidate
                    ?.let { it.angleRadians * 180.0 / PI }
                    ?: 0.0,
                houghLineCount = streakDetection.houghLineCount,
                largestCandidateArea = streakDetection.largestCandidateArea,
                largestCandidateLongSide = streakDetection.largestCandidateLongSide,
                largestCandidateShortSide = streakDetection.largestCandidateShortSide,
                dominantFraction = streakDetection.dominantFraction,
                consecutiveHits = consecutiveHits,
                trajectoryHits = trajectoryHits,
                singleFrameStrongStreak = singleFrameStrongStreak,
                trajectoryConfirmed = trajectoryConfirmed,
                rejectReason = rejectReason,
                triggerReason = triggerReason
            )
        } finally {
            currentRoi.release()
        }
    }

    /** 将支持的 8-bit 输入统一缩放、转换为 320×240 CV_8UC1 灰度图。 */
    private fun convertAndResizeToGray(frame: Mat) {
        val resolvedLayout = when (inputLayout) {
            FrameLayout.AUTO -> resolveAutoLayout(frame)
            else -> inputLayout
        }

        when (resolvedLayout) {
            FrameLayout.GRAY -> {
                requireMatType(frame, CvType.CV_8UC1, "GRAY")
                Imgproc.resize(
                    frame,
                    currentGray,
                    analysisSize,
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
            }

            FrameLayout.NV21 -> resizeNv21Luma(frame)

            FrameLayout.BGR,
            FrameLayout.RGB,
            FrameLayout.BGRA,
            FrameLayout.RGBA -> resizeColorToGray(frame, resolvedLayout)

            FrameLayout.AUTO -> error("AUTO 布局应已解析")
        }

        check(currentGray.type() == CvType.CV_8UC1) {
            "内部灰度图必须为 CV_8UC1，实际 type=${currentGray.type()}"
        }
    }

    /** AUTO 只根据精确的 8-bit Mat 类型判断；单通道一律解释为普通灰度图。 */
    private fun resolveAutoLayout(frame: Mat): FrameLayout = when (frame.type()) {
        CvType.CV_8UC1 -> FrameLayout.GRAY
        CvType.CV_8UC3 -> FrameLayout.BGR
        CvType.CV_8UC4 -> FrameLayout.RGBA
        else -> throw IllegalArgumentException(
            "AUTO 仅支持 CV_8UC1/CV_8UC3/CV_8UC4，实际 type=${frame.type()}"
        )
    }

    private fun resizeColorToGray(frame: Mat, layout: FrameLayout) {
        val expectedType = when (layout) {
            FrameLayout.BGR, FrameLayout.RGB -> CvType.CV_8UC3
            FrameLayout.BGRA, FrameLayout.RGBA -> CvType.CV_8UC4
            else -> error("$layout 不是彩色布局")
        }
        requireMatType(frame, expectedType, layout.name)

        Imgproc.resize(
            frame,
            resizedColor,
            analysisSize,
            0.0,
            0.0,
            Imgproc.INTER_AREA
        )
        val conversionCode = when (layout) {
            FrameLayout.BGR -> Imgproc.COLOR_BGR2GRAY
            FrameLayout.RGB -> Imgproc.COLOR_RGB2GRAY
            FrameLayout.BGRA -> Imgproc.COLOR_BGRA2GRAY
            FrameLayout.RGBA -> Imgproc.COLOR_RGBA2GRAY
            else -> error("$layout 不是彩色布局")
        }
        Imgproc.cvtColor(resizedColor, currentGray, conversionCode)
    }

    /**
     * NV21 Mat 必须以单个 `CV_8UC1` 矩阵表示，尺寸为 `width × (height * 3 / 2)`。
     * 前 H 行是完整 Y 平面；运动检测只使用亮度，省去全尺寸 YUV 转 RGBA 的开销。
     */
    private fun resizeNv21Luma(frame: Mat) {
        requireMatType(frame, CvType.CV_8UC1, "NV21")
        require(frame.rows() % 3 == 0) {
            "NV21 Mat 行数必须能表示 H*3/2，实际 rows=${frame.rows()}"
        }
        require(frame.cols() % 2 == 0) {
            "NV21 图像宽度必须是偶数，实际 cols=${frame.cols()}"
        }

        val imageHeight = frame.rows() / 3 * 2
        require(imageHeight > 0 && imageHeight % 2 == 0) {
            "NV21 图像高度必须是正偶数，计算得到 height=$imageHeight"
        }

        val yPlane = frame.rowRange(0, imageHeight)
        try {
            Imgproc.resize(
                yPlane,
                currentGray,
                analysisSize,
                0.0,
                0.0,
                Imgproc.INTER_AREA
            )
        } finally {
            // rowRange() 与 submat() 一样只创建 native view/header，必须显式释放。
            yPlane.release()
        }
    }

    private fun requireMatType(frame: Mat, expectedType: Int, layoutName: String) {
        require(frame.type() == expectedType) {
            "$layoutName 必须使用 type=$expectedType，实际 type=${frame.type()}"
        }
    }

    /**
     * 在二值正向亮度差图中寻找“细长亮轨”。这是手机版 RMS 裁剪思路：
     * 先用连通域排除点状噪声/大面积闪烁，再用 HoughLinesP 支持任意角度的线段。
     */
    private fun detectMeteorLikeStreakLocked(
        mask: Mat,
        totalPixels: Long,
        changedPixels: Long
    ): StreakDetection {
        val componentCount = Imgproc.connectedComponentsWithStats(
            mask,
            componentLabels,
            componentStats,
            componentCentroids,
            8,
            CvType.CV_32S
        )
        if (componentCount <= 1) {
            return StreakDetection(
                candidate = null,
                rejectReason = "没有连通亮点"
            )
        }

        val maxComponentPixels = (totalPixels * maxStreakPixelRatio).toInt()
            .coerceAtLeast(minStreakPixels)
        var hasCandidatePixels = false
        var largestCandidateArea = 0
        var largestCandidateLongSide = 0
        var largestCandidateShortSide = 0

        for (label in 1 until componentCount) {
            val width = componentStats.get(label, Imgproc.CC_STAT_WIDTH)?.firstOrNull()?.toInt()
                ?: continue
            val height = componentStats.get(label, Imgproc.CC_STAT_HEIGHT)?.firstOrNull()?.toInt()
                ?: continue
            val area = componentStats.get(label, Imgproc.CC_STAT_AREA)?.firstOrNull()?.toInt()
                ?: continue

            if (area < minStreakPixels || area > maxComponentPixels) continue

            val longSide = max(width, height)
            val shortSide = max(1, min(width, height))
            if (longSide >= minStreakLongSide / 2) {
                hasCandidatePixels = true
                if (area > largestCandidateArea) {
                    largestCandidateArea = area
                    largestCandidateLongSide = longSide
                    largestCandidateShortSide = shortSide
                }
            }
        }
        if (!hasCandidatePixels) {
            return StreakDetection(
                candidate = null,
                rejectReason = "连通域太小/太大",
                componentCount = componentCount - 1
            )
        }
        val dominantFraction = largestCandidateArea.toDouble() / max(1L, changedPixels).toDouble()
        if (dominantFraction < minDominantStreakFraction) {
            return StreakDetection(
                candidate = null,
                rejectReason = "亮点太分散",
                componentCount = componentCount - 1,
                largestCandidateArea = largestCandidateArea,
                largestCandidateLongSide = largestCandidateLongSide,
                largestCandidateShortSide = largestCandidateShortSide,
                dominantFraction = dominantFraction
            )
        }

        Imgproc.HoughLinesP(
            mask,
            houghLines,
            HOUGH_RHO,
            HOUGH_THETA,
            minHoughVotes,
            minStreakLongSide.toDouble(),
            HOUGH_MAX_LINE_GAP
        )

        var best: StreakCandidate? = null
        val houghLineCount = houghLines.rows()
        for (row in 0 until houghLines.rows()) {
            val line = houghLines.get(row, 0) ?: continue
            if (line.size < 4) continue
            val x1 = line[0]
            val y1 = line[1]
            val x2 = line[2]
            val y2 = line[3]
            val dx = x2 - x1
            val dy = y2 - y1
            val length = sqrt(dx * dx + dy * dy)
            if (length < minStreakLongSide) continue
            val candidate = StreakCandidate(
                centerX = (x1 + x2) / 2.0,
                centerY = (y1 + y2) / 2.0,
                length = length,
                angleRadians = atan2(dy, dx).normalizeLineAngle()
            )
            if (best == null || candidate.length > best!!.length) {
                best = candidate
            }
        }
        return StreakDetection(
            candidate = best,
            rejectReason = if (best == null) {
                "Hough未找到足够长直线"
            } else {
                "找到细线候选"
            },
            componentCount = componentCount - 1,
            largestCandidateArea = largestCandidateArea,
            largestCandidateLongSide = largestCandidateLongSide,
            largestCandidateShortSide = largestCandidateShortSide,
            dominantFraction = dominantFraction,
            houghLineCount = houghLineCount
        )
    }

    /**
     * 连续帧的线段方向接近时，才认为是短轨迹。流星可能只出现一帧，所以强长线仍可单帧触发；
     * 但普通噪声/播放器压缩闪烁通常无法在相邻分析帧形成稳定方向。
     */
    private fun updateTrajectoryStateLocked(candidate: StreakCandidate?): Boolean {
        if (candidate == null) {
            previousCandidate = null
            trajectoryHits = 0
            return false
        }

        val previous = previousCandidate
        trajectoryHits = if (previous != null && previous.isCompatibleWith(candidate)) {
            minOf(trajectoryHits + 1, requiredConsecutiveHits)
        } else {
            1
        }
        previousCandidate = candidate
        return trajectoryHits >= requiredConsecutiveHits
    }

    private fun StreakCandidate.isCompatibleWith(next: StreakCandidate): Boolean {
        val angleDelta = angleDistanceDegrees(angleRadians, next.angleRadians)
        if (angleDelta > MAX_TRACK_ANGLE_DELTA_DEGREES) return false

        val dx = next.centerX - centerX
        val dy = next.centerY - centerY
        val distance = sqrt(dx * dx + dy * dy)
        return distance <= MAX_TRACK_CENTER_DISTANCE
    }

    /** 把方向归一到 [0, PI)，因为同一条线 0° 和 180° 等价。 */
    private fun Double.normalizeLineAngle(): Double {
        var value = this % PI
        if (value < 0.0) value += PI
        return value
    }

    private fun angleDistanceDegrees(first: Double, second: Double): Double {
        val direct = abs(first - second)
        val wrapped = PI - direct
        return min(direct, wrapped) * 180.0 / PI
    }

    private fun updateConsecutiveStateLocked(rawMotion: Boolean, strongMotion: Boolean): Boolean {
        if (!rawMotion) {
            // 只有真正参与分析且未命中的样本才会清零；跳帧不会来到这里。
            consecutiveHits = 0
            eventLatched = false
            return false
        }

        consecutiveHits = minOf(
            consecutiveHits + 1,
            requiredConsecutiveHits
        )
        // 连续普通候选只作为状态计数；真正保存必须来自跨帧轨迹确认。
        // 这样能过滤掉手机/支架轻微移动产生的连续边缘变化。
        val confirmed = strongMotion
        if (!confirmed || eventLatched) return false

        eventLatched = true
        return true
    }

    private fun updateAnalyzeIntervalLocked(newInterval: Int) {
        if (newInterval == analyzeEveryNFrames) return
        analyzeEveryNFrames = newInterval
        // 仅在“有效间隔真正改变”时重置，使下一帧立即采用新策略。
        framesToSkip = 0
    }

    /** 断线重连、分辨率切换或曝光/增益大幅改变后重建基线，避免旧画面制造误报。 */
    @Synchronized
    fun reset() {
        check(!closed) { "FrameAnalyzer 已关闭" }
        resetStateLocked()
    }

    private fun resetStateLocked() {
        // release() 会清空旧 native 数据，但 Mat header 仍可被后续 copyTo() 重新分配。
        previousGrayRoi.release()
        hasBaseline = false
        consecutiveHits = 0
        trajectoryHits = 0
        previousCandidate = null
        eventLatched = false
        framesToSkip = 0
    }

    /**
     * 释放全部由本类拥有的 OpenCV native 资源。方法幂等，并会尝试释放每一个 Mat；如果某次
     * release 异常，会在其余资源处理完后重新抛出首个异常。
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        hasBaseline = false
        consecutiveHits = 0
        trajectoryHits = 0
        previousCandidate = null
        eventLatched = false
        framesToSkip = 0

        releaseAllOwnedMats()
    }

    private fun releaseAllOwnedMats() {
        var firstError: Throwable? = null
        val ownedMats = arrayOf(
            resizedColor,
            currentGray,
            previousGrayRoi,
            difference,
            binaryMask,
            componentLabels,
            componentStats,
            componentCentroids,
            houghLines
        )

        ownedMats.forEach { mat ->
            try {
                mat.release()
            } catch (error: Throwable) {
                if (firstError == null) {
                    firstError = error
                } else {
                    firstError?.addSuppressed(error)
                }
            }
        }

        firstError?.let { throw it }
    }
}
