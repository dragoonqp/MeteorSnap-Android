package com.meteorsnap.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.TextureView
import com.meteorsnap.camera.PreviewFrame
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * 在专用后台协程中把抽样后的 NV21 帧直接画入 TextureView。
 *
 * 该 View 复用一张最多 480×270 的 Bitmap 和一个 IntArray，不会逐帧创建 Bitmap、JPEG，
 * 也不会把预览 Surface 挂进 Camera2/UVC 会话，因此页面旋转或退到后台不会打断监测。
 * 为避免预览和硬编码抢 CPU，预览只显示亮度灰度图；保存的视频仍然使用原始彩色 NV21。
 * 多次 [render] 在后台串行；[clear] 只递增代次，绝不会让主线程等待整帧颜色转换。
 */
class Nv21PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var mappedSourceWidth = 0
    private var mappedSourceHeight = 0
    private var sourceXByOutput = IntArray(0)
    private var sourceYByOutput = IntArray(0)
    private val renderLock = ReentrantLock()
    private val renderEpoch = AtomicLong(0L)

    /** 返回 false 表示 Surface 尚不可用；下一帧会自动重试。 */
    fun render(preview: PreviewFrame): Boolean {
        val requestedEpoch = renderEpoch.get()
        return renderLock.withLock {
            if (requestedEpoch != renderEpoch.get() || !isAvailable) return@withLock false
            val frame = preview.frame
            val requiredBytes = frame.width.toLong() * frame.height * 3L / 2L
            if (frame.width < 2 || frame.height < 2 ||
                frame.width % 2 != 0 || frame.height % 2 != 0 ||
                requiredBytes > frame.data.size.toLong()
            ) {
                return@withLock false
            }

            val scale = minOf(
                1.0,
                MAX_BITMAP_WIDTH.toDouble() / frame.width.toDouble(),
                MAX_BITMAP_HEIGHT.toDouble() / frame.height.toDouble()
            )
            val outputWidth = (frame.width * scale).roundToInt().coerceAtLeast(1)
            val outputHeight = (frame.height * scale).roundToInt().coerceAtLeast(1)
            ensureBuffers(frame.width, frame.height, outputWidth, outputHeight)
            convertNv21LumaToArgb(
                nv21 = frame.data,
                sourceWidth = frame.width,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )

            if (requestedEpoch != renderEpoch.get()) return@withLock false
            val outputBitmap = bitmap ?: return@withLock false
            outputBitmap.setPixels(
                pixels,
                0,
                outputWidth,
                0,
                0,
                outputWidth,
                outputHeight
            )
            if (requestedEpoch != renderEpoch.get() || !isAvailable) return@withLock false

            val canvas = runCatching { lockCanvas() }.getOrNull() ?: return@withLock false
            try {
                drawFitted(
                    canvas = canvas,
                    source = outputBitmap,
                    rotationDegrees = preview.rotationDegrees
                )
                true
            } finally {
                runCatching { unlockCanvasAndPost(canvas) }
            }
        }
    }

    /** 使正在转换的旧帧失效；黑色占位层负责遮住 Surface 中的旧内容。 */
    fun clear() {
        renderEpoch.incrementAndGet()
    }

    private fun ensureBuffers(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ) {
        if (bitmapWidth != outputWidth || bitmapHeight != outputHeight || bitmap == null) {
            bitmap?.recycle()
            bitmapWidth = outputWidth
            bitmapHeight = outputHeight
            pixels = IntArray(outputWidth * outputHeight)
            bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        }
        if (mappedSourceWidth != sourceWidth || mappedSourceHeight != sourceHeight ||
            sourceXByOutput.size != outputWidth || sourceYByOutput.size != outputHeight
        ) {
            mappedSourceWidth = sourceWidth
            mappedSourceHeight = sourceHeight
            sourceXByOutput = IntArray(outputWidth) { outputX ->
                outputX * sourceWidth / outputWidth
            }
            sourceYByOutput = IntArray(outputHeight) { outputY ->
                outputY * sourceHeight / outputHeight
            }
        }
    }

    /** 最近邻抽样足以用于构图预览；检测和保存仍使用原始彩色帧。 */
    private fun convertNv21LumaToArgb(
        nv21: ByteArray,
        sourceWidth: Int,
        outputWidth: Int,
        outputHeight: Int
    ) {
        var destinationIndex = 0
        for (outputY in 0 until outputHeight) {
            val sourceY = sourceYByOutput[outputY]
            val yRow = sourceY * sourceWidth
            for (outputX in 0 until outputWidth) {
                val sourceX = sourceXByOutput[outputX]
                val y = nv21[yRow + sourceX].toInt() and 0xFF
                pixels[destinationIndex++] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
        }
    }

    private fun drawFitted(canvas: Canvas, source: Bitmap, rotationDegrees: Int) {
        canvas.drawColor(Color.BLACK)
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val rotatedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) {
            source.height.toFloat()
        } else {
            source.width.toFloat()
        }
        val rotatedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) {
            source.width.toFloat()
        } else {
            source.height.toFloat()
        }
        val scale = minOf(
            canvas.width / rotatedWidth,
            canvas.height / rotatedHeight
        )

        val saveCount = canvas.save()
        canvas.translate(canvas.width / 2f, canvas.height / 2f)
        canvas.rotate(normalizedRotation.toFloat())
        val destination = RectF(
            -source.width * scale / 2f,
            -source.height * scale / 2f,
            source.width * scale / 2f,
            source.height * scale / 2f
        )
        canvas.drawBitmap(source, null, destination, paint)
        canvas.restoreToCount(saveCount)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_BITMAP_WIDTH = 480
        const val MAX_BITMAP_HEIGHT = 270
    }
}
