package com.meteorsnap.media

import android.graphics.Bitmap
import com.meteorsnap.camera.Nv21Frame
import kotlin.math.roundToInt

/** 一张为“流星照片模式”降采样后的独立帧。 */
data class PhotoTrailFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val argb: IntArray
) {
    fun copy(): PhotoTrailFrame = copy(argb = argb.copyOf())
}

/**
 * 把触发窗口内的多张缩小帧做“最大亮度叠加”。
 *
 * 对每个像素位置，取所有帧中亮度最高的那个颜色。这样暗背景保持基本稳定，而流星经过的
 * 几帧会被叠成一条亮线；这比单帧截图更接近肉眼想要的“流星划过天空照片”。
 */
object PhotoTrailComposer {
    const val MAX_OUTPUT_WIDTH = 640
    const val MAX_OUTPUT_HEIGHT = 360

    fun sampleNv21(frame: Nv21Frame): PhotoTrailFrame? {
        val requiredBytes = frame.width.toLong() * frame.height * 3L / 2L
        if (frame.width < 2 || frame.height < 2 ||
            frame.width % 2 != 0 || frame.height % 2 != 0 ||
            requiredBytes > frame.data.size.toLong()
        ) {
            return null
        }

        val scale = minOf(
            1.0,
            MAX_OUTPUT_WIDTH.toDouble() / frame.width.toDouble(),
            MAX_OUTPUT_HEIGHT.toDouble() / frame.height.toDouble()
        )
        val outputWidth = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (frame.height * scale).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(outputWidth * outputHeight)

        val frameSize = frame.width * frame.height
        var destinationIndex = 0
        for (outputY in 0 until outputHeight) {
            val sourceY = outputY * frame.height / outputHeight
            val yRow = sourceY * frame.width
            val vuRow = frameSize + (sourceY shr 1) * frame.width
            for (outputX in 0 until outputWidth) {
                val sourceX = outputX * frame.width / outputWidth
                val y = (frame.data[yRow + sourceX].toInt() and 0xFF) - 16
                val vuIndex = vuRow + (sourceX and -2)
                val v = (frame.data[vuIndex].toInt() and 0xFF) - 128
                val u = (frame.data[vuIndex + 1].toInt() and 0xFF) - 128
                val luminance = y.coerceAtLeast(0)

                val red = clampToByte((298 * luminance + 409 * v + 128) shr 8)
                val green = clampToByte((298 * luminance - 100 * u - 208 * v + 128) shr 8)
                val blue = clampToByte((298 * luminance + 516 * u + 128) shr 8)
                pixels[destinationIndex++] =
                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        return PhotoTrailFrame(
            timestampNs = frame.timestampNs,
            width = outputWidth,
            height = outputHeight,
            argb = pixels
        )
    }

    fun composeMaxBrightness(frames: List<PhotoTrailFrame>): Bitmap {
        require(frames.isNotEmpty()) { "没有可用于生成流星叠加照片的帧" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "流星叠加照片只能合成相同尺寸的帧"
        }

        val output = frames.first().argb.copyOf()
        val maxLuma = IntArray(output.size) { luminance(output[it]) }
        for (frameIndex in 1 until frames.size) {
            val pixels = frames[frameIndex].argb
            for (index in pixels.indices) {
                val y = luminance(pixels[index])
                if (y > maxLuma[index]) {
                    maxLuma[index] = y
                    output[index] = pixels[index]
                }
            }
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(argb: Int): Int {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (red * 77 + green * 150 + blue * 29) shr 8
    }

    private fun clampToByte(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }
}
