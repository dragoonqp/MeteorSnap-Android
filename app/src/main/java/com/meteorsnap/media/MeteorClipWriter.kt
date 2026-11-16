package com.meteorsnap.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.nio.ByteBuffer
import java.util.Date
import java.util.Locale

/** 成功保存后的稳定结果；API 23-28 的 uri 为 file://，分享时必须再通过 FileProvider 转换。 */
data class SavedClip(
    val uri: Uri,
    val displayName: String,
    val absolutePath: String?,
    val durationUs: Long,
    val sampleCount: Int
)

/**
 * 把 HardwareH264Encoder 环形缓存中的一个 GOP 序列封装为 MP4。
 *
 * write 是阻塞磁盘 I/O，必须从 Dispatchers.IO/Worker 调用。传入样本可以包含关键帧之前的
 * P 帧、codec-config 和其他 generation；Writer 会只取 format 对应 generation，并丢弃首个
 * IDR 之前的数据。这样生成的 MP4 从可独立解码的位置开始。
 */
class MeteorClipWriter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 保存视频，文件名为“流星_yyyyMMdd_HHmmss.mp4”。
     *
     * @param format onOutputFormat 回调提供的、含 csd-0/csd-1 的 AVC MediaFormat
     * @param samples 触发前后窗口的编码样本；调用前应先完成“触发后 3 秒”的收集
     * @param wallClockMillis 仅用于文件名，不能用于视频 PTS
     * @param orientationHintDegrees MP4 旋转矩阵；手机 Camera2 使用，UVC 默认为 0
     */
    @Throws(IOException::class)
    fun write(
        format: android.media.MediaFormat,
        samples: List<HardwareH264Encoder.EncodedSample>,
        wallClockMillis: Long = System.currentTimeMillis(),
        orientationHintDegrees: Int = 0
    ): SavedClip {
        require(format.getString(android.media.MediaFormat.KEY_MIME) == HardwareH264Encoder.MIME_TYPE) {
            "MeteorClipWriter 只接受 AVC/H.264 输出格式"
        }
        require(format.getByteBuffer("csd-0") != null) { "输出格式缺少 H.264 csd-0" }
        require(samples.isNotEmpty()) { "没有可保存的编码样本" }
        require(orientationHintDegrees in setOf(0, 90, 180, 270)) {
            "MP4 旋转角度必须是 0/90/180/270，实际 $orientationHintDegrees"
        }

        val formatGeneration = if (format.containsKey(HardwareH264Encoder.FORMAT_KEY_GENERATION)) {
            format.getInteger(HardwareH264Encoder.FORMAT_KEY_GENERATION)
        } else {
            null
        }
        val fallbackGeneration = samples.map { it.generation }.distinct().singleOrNull()
            ?: requireNotNull(formatGeneration) {
                "样本包含多个 generation，但 MediaFormat 没有 generation 标记"
            }
        val expectedGeneration = formatGeneration ?: fallbackGeneration

        val ordered = samples.asSequence()
            .filter { it.generation == expectedGeneration }
            .filter { it.size > 0 && !it.isCodecConfig }
            .sortedBy { it.presentationTimeUs }
            .toList()
        require(ordered.isNotEmpty()) { "没有与输出格式 generation 匹配的媒体样本" }

        val firstKeyFrameIndex = ordered.indexOfFirst { it.isKeyFrame }
        require(firstKeyFrameIndex >= 0) {
            "缓存窗口内没有 IDR；请保留至少一个完整的 1 秒 GOP 或先 requestSyncFrame()"
        }
        val muxSamples = ordered.subList(firstKeyFrameIndex, ordered.size)
        val displayName = buildDisplayName(wallClockMillis)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStore(format, muxSamples, displayName, orientationHintDegrees)
        } else {
            writeToLegacyPictures(format, muxSamples, displayName, orientationHintDegrees)
        }
    }

    /** Android 10+：先写 IS_PENDING 条目，成功 stop MediaMuxer 后才向图库发布。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToMediaStore(
        format: android.media.MediaFormat,
        samples: List<HardwareH264Encoder.EncodedSample>,
        displayName: String,
        orientationHintDegrees: Int
    ): SavedClip {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE_MP4)
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore 无法创建视频条目")

        try {
            val stats = resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val muxer = MediaMuxer(
                    descriptor.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxSamples(muxer, format, samples, orientationHintDegrees)
            } ?: throw IOException("MediaStore 无法打开输出文件描述符")

            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
            if (published != 1) throw IOException("视频写入成功，但 MediaStore 发布失败")

            return SavedClip(
                uri = uri,
                displayName = displayName,
                absolutePath = null,
                durationUs = stats.durationUs,
                sampleCount = stats.sampleCount
            )
        } catch (error: Throwable) {
            // 未发布或半写入条目不应留在用户图库。
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Android 6-9：写公共 Pictures/MeteorSnap，并显式通知 MediaScanner。 */
    @Suppress("DEPRECATION")
    private fun writeToLegacyPictures(
        format: android.media.MediaFormat,
        samples: List<HardwareH264Encoder.EncodedSample>,
        displayName: String,
        orientationHintDegrees: Int
    ): SavedClip {
        if (appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Android 9 及以下保存公共 Pictures 需要 WRITE_EXTERNAL_STORAGE")
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IOException("共享存储当前不可写")
        }

        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_NAME
        )
        if (!album.exists() && !album.mkdirs()) {
            throw IOException("无法创建目录：${album.absolutePath}")
        }
        if (!album.isDirectory) throw IOException("输出位置不是目录：${album.absolutePath}")

        val outputFile = uniqueFile(album, displayName)
        try {
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val stats = muxSamples(muxer, format, samples, orientationHintDegrees)
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outputFile.absolutePath),
                arrayOf(MIME_TYPE_MP4),
                null
            )
            return SavedClip(
                uri = Uri.fromFile(outputFile),
                displayName = outputFile.name,
                absolutePath = outputFile.absolutePath,
                durationUs = stats.durationUs,
                sampleCount = stats.sampleCount
            )
        } catch (error: Throwable) {
            // 失败文件不是有效 MP4，删除比让扫描器收录损坏文件更安全。
            runCatching { if (outputFile.exists()) outputFile.delete() }
            throw error
        }
    }

    private fun muxSamples(
        muxer: MediaMuxer,
        format: android.media.MediaFormat,
        samples: List<HardwareH264Encoder.EncodedSample>,
        orientationHintDegrees: Int
    ): MuxStats {
        var started = false
        var stopped = false
        try {
            muxer.setOrientationHint(orientationHintDegrees)
            // generation 是 MeteorSnap 的内部键，不能传给设备厂商的 MediaMuxer 实现。
            val trackIndex = muxer.addTrack(
                copyAvcMediaFormat(format, includeGeneration = false)
            )
            muxer.start()
            started = true

            val basePtsUs = samples.first().presentationTimeUs
            var lastWrittenPtsUs = -1L
            var written = 0
            // MediaMuxer JNI 只接受 Direct ByteBuffer 或可访问底层 byte[] 的普通堆缓冲区。
            // ReadOnlyHeapByteBuffer.array() 不可用，会在部分 Android 版本精确抛出
            // “byteArray is null”。整个片段复用一个 Direct 缓冲，避免逐样本分配 native 内存。
            val sampleBuffer = ByteBuffer.allocateDirect(samples.maxOf { it.size })
            for (sample in samples) {
                // 输入已排序；对罕见的同 PTS 输出作 1us 修正以满足 MediaMuxer 单调要求。
                val rebasedPtsUs = maxOf(
                    sample.presentationTimeUs - basePtsUs,
                    lastWrittenPtsUs + 1L
                )
                sampleBuffer.clear()
                sample.copyPayloadTo(sampleBuffer)
                sampleBuffer.flip()
                val info = MediaCodec.BufferInfo().apply {
                    set(
                        0,
                        sample.size,
                        rebasedPtsUs,
                        sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                    )
                }
                muxer.writeSampleData(trackIndex, sampleBuffer, info)
                lastWrittenPtsUs = rebasedPtsUs
                written++
            }

            muxer.stop()
            stopped = true
            return MuxStats(
                durationUs = maxOf(0L, lastWrittenPtsUs),
                sampleCount = written
            )
        } finally {
            if (started && !stopped) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun buildDisplayName(wallClockMillis: Long): String {
        val timestamp = SimpleDateFormat(FILE_TIME_PATTERN, Locale.CHINA)
            .format(Date(wallClockMillis))
        return "流星_$timestamp.mp4"
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct

        val stem = requestedName.removeSuffix(".mp4")
        var suffix = 1
        while (true) {
            val candidate = File(directory, "${stem}_$suffix.mp4")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private data class MuxStats(val durationUs: Long, val sampleCount: Int)

    companion object {
        private const val ALBUM_NAME = "MeteorSnap"
        private const val MIME_TYPE_MP4 = "video/mp4"
        private const val FILE_TIME_PATTERN = "yyyyMMdd_HHmmss"
    }
}
