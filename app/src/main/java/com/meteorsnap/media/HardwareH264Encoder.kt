package com.meteorsnap.media

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把手机 Camera2 或 UVC 采集器给出的 NV21 帧持续编码为 H.264/AVC。
 *
 * 本类使用 MediaCodec 的同步 ByteBuffer 模式，并把输入声明为
 * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible]。NV21 不能直接假定为
 * 编码器的内存布局；[MediaCodec.getInputImage] 返回的三个 Plane 可能带行填充，也可能让
 * U/V 通过 pixelStride=2 交错共享底层内存，所以必须逐 Plane 按 stride 写入。
 *
 * 线程约束：所有公开方法都串行化，但建议 Service 始终在同一个专用编码线程调用。
 * Listener 也在调用 [encodeNv21] 的线程同步执行，回调中不要做磁盘 I/O。
 */
class HardwareH264Encoder(
    private val callback: Callback,
    private val bitRate: Int = DEFAULT_BIT_RATE,
    private val iFrameIntervalSeconds: Int = DEFAULT_I_FRAME_INTERVAL_SECONDS,
    private val inputBufferTimeoutUs: Long = DEFAULT_INPUT_TIMEOUT_US
) : Closeable {

    interface Callback {
        /** 每次 start 后，编码器产生含 csd-0/csd-1 的输出格式时调用一次。 */
        fun onOutputFormat(format: MediaFormat, generation: Int)

        /** data 已脱离 MediaCodec 输出缓冲区，可安全放进跨线程环形缓冲。 */
        fun onEncodedSample(sample: EncodedSample)

        /** 编码异常通知；致命异常后调用方应 stop 并重新 start。 */
        fun onEncoderError(error: Throwable)
    }

    /**
     * 深不可变的编码样本。
     *
     * 构造和读取 data 时都复制数组，避免环形缓冲中的历史 GOP 被外部代码意外改写。
     * Writer 位于同一模块，可通过 [copyPayloadTo] 把内容复制进复用的 Direct ByteBuffer，
     * 从而既不暴露 payload，也兼容 MediaMuxer 的 JNI 缓冲区要求。
     */
    class EncodedSample(
        val timestampNs: Long,
        val presentationTimeUs: Long,
        val flags: Int,
        val generation: Int,
        data: ByteArray
    ) {
        private val payload: ByteArray = data.copyOf()

        val data: ByteArray
            get() = payload.copyOf()

        val size: Int
            get() = payload.size

        val isKeyFrame: Boolean
            get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || containsIdrSlice(payload)

        val isCodecConfig: Boolean
            get() = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

        /** 返回一个拥有独立 payload 的深拷贝，供 Service 做事件快照。 */
        fun copy(): EncodedSample = EncodedSample(
            timestampNs = timestampNs,
            presentationTimeUs = presentationTimeUs,
            flags = flags,
            generation = generation,
            data = payload
        )

        internal fun copyPayloadTo(destination: ByteBuffer) {
            require(destination.remaining() >= payload.size) {
                "目标 ByteBuffer 空间不足：需要 ${payload.size}，剩余 ${destination.remaining()}"
            }
            destination.put(payload)
        }
    }

    private val stateLock = Any()
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var frameRate = 0
    private var generation = 0
    private var lastInputPtsUs = Long.MIN_VALUE

    /** MediaCodec 只回传微秒 PTS；该表用于找回原始纳秒级单调时间戳。 */
    private val sourceTimestampByPtsUs = LinkedHashMap<Long, Long>()

    val isStarted: Boolean
        get() = synchronized(stateLock) { codec != null }

    val currentGeneration: Int
        get() = synchronized(stateLock) { generation }

    /**
     * 创建并启动一个硬件 AVC 编码会话，返回本会话 generation。
     * generation 每次成功 start 都不同，格式和样本不能跨 generation 混合封装。
     */
    fun start(width: Int, height: Int, fps: Int): Int = synchronized(stateLock) {
        check(codec == null) { "编码器已经启动" }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "YUV420 的宽高必须为正偶数"
        }
        require(fps > 0) { "fps 必须大于 0" }
        require(bitRate > 0) { "bitRate 必须大于 0" }
        require(iFrameIntervalSeconds > 0) { "I 帧间隔必须大于 0" }

        var createdCodec: MediaCodec? = null
        try {
            val codecInfo = findHardwareAvcEncoder(width, height, fps)
            val capabilities = codecInfo.getCapabilitiesForType(MIME_TYPE)
            val mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // 1 秒一个 IDR，触发前缓存最多只需向前回退约一秒即可找到可解码起点。
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)

                val encoderCapabilities = capabilities.encoderCapabilities
                if (encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    ) == true
                ) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                }

                // 禁止 B 帧可让输出 PTS 严格递增，便于事件环形缓冲直接写入 MediaMuxer。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }

            createdCodec = MediaCodec.createByCodecName(codecInfo.name)
            createdCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            createdCodec.start()
            // 部分厂商编码器不会立刻按 1 秒 GOP 输出首个 IDR；启动后主动请求一次，
            // 让预录环形缓冲尽快具备可独立解码的起点。
            runCatching {
                createdCodec.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }

            this.width = width
            this.height = height
            this.frameRate = fps
            this.generation = NEXT_GENERATION.getAndIncrement()
            this.lastInputPtsUs = Long.MIN_VALUE
            sourceTimestampByPtsUs.clear()
            codec = createdCodec
            createdCodec = null
            generation
        } catch (error: Throwable) {
            runCatching { createdCodec?.release() }
            notifyError(error)
            throw error
        }
    }

    /**
     * 编码一帧 NV21。timestampNs 必须来自 elapsedRealtimeNanos 一类单调时钟。
     *
     * @return false 表示编码器此刻没有空闲输入槽；实时管线应丢弃该帧，不能阻塞 UVC 回调。
     */
    fun encodeNv21(bytes: ByteArray, timestampNs: Long): Boolean = synchronized(stateLock) {
        val activeCodec = codec ?: throw IllegalStateException("编码器尚未启动")
        require(timestampNs >= 0L) { "timestampNs 不能为负数" }
        val expectedSize = frameSize(width, height)
        require(bytes.size >= expectedSize) {
            "NV21 数据不足：需要 $expectedSize 字节，实际 ${bytes.size} 字节"
        }

        try {
            // 先归还所有已完成输出，避免编码器因输出端背压而拿不到输入槽。
            drainOutputLocked(activeCodec)
            val inputIndex = activeCodec.dequeueInputBuffer(inputBufferTimeoutUs)
            if (inputIndex < 0) return@synchronized false

            var inputWasQueued = false
            try {
                val inputImage = activeCodec.getInputImage(inputIndex)
                    ?: throw IllegalStateException(
                        "${activeCodec.name} 未为 YUV420Flexible 返回可写 input Image"
                    )
                writeNv21ToYuv420Image(bytes, inputImage, width, height)

                // MediaCodec PTS 单位是微秒；对同一微秒内的时间戳作 +1 修正，保证严格递增。
                val convertedPtsUs = timestampNs / NANOS_PER_MICROSECOND
                val ptsUs = if (lastInputPtsUs == Long.MIN_VALUE) {
                    convertedPtsUs
                } else {
                    maxOf(convertedPtsUs, lastInputPtsUs + 1L)
                }
                activeCodec.queueInputBuffer(inputIndex, 0, expectedSize, ptsUs, 0)
                inputWasQueued = true
                lastInputPtsUs = ptsUs
                sourceTimestampByPtsUs[ptsUs] = timestampNs
                trimTimestampMapLocked()
            } finally {
                if (!inputWasQueued) {
                    // dequeue 后 input slot 归调用方所有。即使 Plane 写入失败，也尽力以空帧归还，
                    // 否则多次异常会耗尽所有输入槽；queue 本身失败时上层仍会重启 codec。
                    runCatching {
                        val fallbackPtsUs = maxOf(
                            timestampNs / NANOS_PER_MICROSECOND,
                            if (lastInputPtsUs == Long.MIN_VALUE) 0L else lastInputPtsUs + 1L
                        )
                        activeCodec.queueInputBuffer(inputIndex, 0, 0, fallbackPtsUs, 0)
                    }
                }
            }

            // 大多数编码器会滞后一至数帧；再次非阻塞 drain 可降低环形缓存延迟。
            drainOutputLocked(activeCodec)
            true
        } catch (error: Throwable) {
            notifyError(error)
            throw error
        }
    }

    /** 要求编码器尽快产生一个 IDR；MediaCodec 可能在随后几帧才响应。 */
    fun requestSyncFrame() = synchronized(stateLock) {
        val activeCodec = codec ?: return@synchronized
        try {
            activeCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (error: Throwable) {
            notifyError(error)
        }
    }

    /** 停止并释放本次编码会话；可重复调用。 */
    fun stop() = synchronized(stateLock) {
        val activeCodec = codec ?: return@synchronized
        codec = null
        try {
            drainOutputLocked(activeCodec)
        } catch (error: Throwable) {
            notifyError(error)
        } finally {
            runCatching { activeCodec.stop() }.onFailure(::notifyError)
            runCatching { activeCodec.release() }.onFailure(::notifyError)
            sourceTimestampByPtsUs.clear()
            lastInputPtsUs = Long.MIN_VALUE
        }
    }

    override fun close() = stop()

    private fun drainOutputLocked(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = activeCodec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val safeFormat = copyAvcMediaFormat(activeCodec.outputFormat).apply {
                        setInteger(FORMAT_KEY_GENERATION, generation)
                    }
                    runCallback { callback.onOutputFormat(safeFormat, generation) }
                }

                else -> if (outputIndex >= 0) {
                    try {
                        if (info.size > 0) {
                            val output = activeCodec.getOutputBuffer(outputIndex)
                                ?: throw IllegalStateException("编码器返回了空输出缓冲区")
                            val view = output.duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }
                            val copiedData = ByteArray(info.size)
                            view.get(copiedData)
                            val originalTimestampNs = sourceTimestampByPtsUs.remove(
                                info.presentationTimeUs
                            ) ?: info.presentationTimeUs * NANOS_PER_MICROSECOND
                            val sample = EncodedSample(
                                timestampNs = originalTimestampNs,
                                presentationTimeUs = info.presentationTimeUs,
                                flags = info.flags,
                                generation = generation,
                                data = copiedData
                            )
                            runCallback { callback.onEncodedSample(sample) }
                        }
                    } finally {
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun runCallback(block: () -> Unit) {
        try {
            block()
        } catch (callbackError: Throwable) {
            notifyError(callbackError)
        }
    }

    /** 厂商编码器若静默丢输入帧，也不能让长时监测中的 PTS 映射无限增长。 */
    private fun trimTimestampMapLocked() {
        val limit = maxOf(frameRate * 10, 300)
        while (sourceTimestampByPtsUs.size > limit) {
            val iterator = sourceTimestampByPtsUs.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun notifyError(error: Throwable) {
        // 错误回调自身不能反向破坏 MediaCodec 的资源回收路径。
        runCatching { callback.onEncoderError(error) }
    }

    companion object {
        const val MIME_TYPE: String = MediaFormat.MIMETYPE_VIDEO_AVC
        const val FORMAT_KEY_GENERATION: String = "meteorsnap-encoder-generation"
        const val DEFAULT_BIT_RATE: Int = 8_000_000
        const val DEFAULT_I_FRAME_INTERVAL_SECONDS: Int = 1
        const val DEFAULT_INPUT_TIMEOUT_US: Long = 33_000L

        private const val NANOS_PER_MICROSECOND = 1_000L
        private val NEXT_GENERATION = AtomicInteger(1)

        private fun frameSize(width: Int, height: Int): Int =
            Math.multiplyExact(Math.multiplyExact(width, height), 3) / 2

        /**
         * API 29+ 使用系统给出的硬件标志；API 23-28 没有该字段，只能排除 Android/Google
         * 软件 codec 的常见命名。找不到硬件实现时明确失败，不静默退化成高 CPU 软件编码。
         */
        private fun findHardwareAvcEncoder(
            width: Int,
            height: Int,
            fps: Int
        ): MediaCodecInfo {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(MIME_TYPE, true) } }
                .filter { info ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated
                    } else {
                        !looksLikeSoftwareCodec(info.name)
                    }
                }
                .filter { info ->
                    runCatching {
                        val caps = info.getCapabilitiesForType(MIME_TYPE)
                        caps.colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        ) && caps.videoCapabilities?.isSizeSupported(width, height) == true
                    }.getOrDefault(false)
                }
                .toList()

            return candidates.firstOrNull { info ->
                runCatching {
                    info.getCapabilitiesForType(MIME_TYPE)
                        .videoCapabilities
                        ?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
                }.getOrDefault(false)
            } ?: candidates.firstOrNull()
            ?: throw IllegalStateException(
                "设备没有支持 ${width}x$height YUV420Flexible 的硬件 AVC 编码器"
            )
        }

        private fun looksLikeSoftwareCodec(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("omx.google.") ||
                lower.startsWith("c2.android.") ||
                lower.contains("software") ||
                lower.contains("ffmpeg") ||
                lower.contains("sw.encoder")
        }

        /**
         * MediaCodec 正常会在 flags 标出关键帧，但部分设备/封装路径只给出普通 buffer。
         * 为避免“缓存里明明有 IDR 却被当成无关键帧”，这里额外识别 AVC/H.264 负载：
         * - Annex-B: 00 00 01 / 00 00 00 01 起始码
         * - AVCC: 4 字节 NAL length 前缀
         * NAL type 5 表示 IDR slice。
         */
        private fun containsIdrSlice(data: ByteArray): Boolean {
            if (data.isEmpty()) return false
            if (containsAnnexBIdr(data)) return true
            return containsLengthPrefixedIdr(data)
        }

        private fun containsAnnexBIdr(data: ByteArray): Boolean {
            var index = 0
            while (index + 4 < data.size) {
                val startCodeLength = when {
                    data[index] == 0.toByte() &&
                        data[index + 1] == 0.toByte() &&
                        data[index + 2] == 1.toByte() -> 3
                    data[index] == 0.toByte() &&
                        data[index + 1] == 0.toByte() &&
                        data[index + 2] == 0.toByte() &&
                        data[index + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (startCodeLength > 0) {
                    val nalHeaderIndex = index + startCodeLength
                    if (nalHeaderIndex < data.size && (data[nalHeaderIndex].toInt() and 0x1F) == 5) {
                        return true
                    }
                    index = nalHeaderIndex + 1
                } else {
                    index++
                }
            }
            return false
        }

        private fun containsLengthPrefixedIdr(data: ByteArray): Boolean {
            var index = 0
            var parsedNalCount = 0
            while (index + 5 <= data.size) {
                val nalLength = ((data[index].toInt() and 0xFF) shl 24) or
                    ((data[index + 1].toInt() and 0xFF) shl 16) or
                    ((data[index + 2].toInt() and 0xFF) shl 8) or
                    (data[index + 3].toInt() and 0xFF)
                if (nalLength <= 0 || index + 4 + nalLength > data.size) {
                    return false
                }
                val nalHeader = data[index + 4].toInt() and 0x1F
                if (nalHeader == 5) return true
                parsedNalCount++
                index += 4 + nalLength
            }
            return parsedNalCount > 0 && index == data.size
        }

        /** 把 NV21(Y + VU) 复制进语义顺序为 Y/U/V 的 YUV_420_888 planes。 */
        private fun writeNv21ToYuv420Image(
            nv21: ByteArray,
            image: Image,
            width: Int,
            height: Int
        ) {
            require(image.format == ImageFormat.YUV_420_888) {
                "期望 YUV_420_888，实际 format=${image.format}"
            }
            require(image.width == width && image.height == height) {
                "编码器 input Image 尺寸 ${image.width}x${image.height} 与配置不一致"
            }
            val planes = image.planes
            require(planes.size == 3) { "YUV420 输入必须包含 Y/U/V 三个 plane" }

            copyPlaneFromPacked(
                source = nv21,
                sourceOffset = 0,
                sourceRowStride = width,
                sourcePixelStride = 1,
                plane = planes[0],
                planeWidth = width,
                planeHeight = height
            )

            val chromaOffset = width * height
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            // NV21 色度排列为 VU；Image.planes 固定语义顺序为 Y、U、V。
            copyPlaneFromPacked(
                source = nv21,
                sourceOffset = chromaOffset + 1,
                sourceRowStride = width,
                sourcePixelStride = 2,
                plane = planes[1],
                planeWidth = chromaWidth,
                planeHeight = chromaHeight
            )
            copyPlaneFromPacked(
                source = nv21,
                sourceOffset = chromaOffset,
                sourceRowStride = width,
                sourcePixelStride = 2,
                plane = planes[2],
                planeWidth = chromaWidth,
                planeHeight = chromaHeight
            )
        }

        private fun copyPlaneFromPacked(
            source: ByteArray,
            sourceOffset: Int,
            sourceRowStride: Int,
            sourcePixelStride: Int,
            plane: Image.Plane,
            planeWidth: Int,
            planeHeight: Int
        ) {
            val target = plane.buffer.duplicate()
            val targetBase = target.position()
            val lastTargetIndex = targetBase +
                (planeHeight - 1) * plane.rowStride +
                (planeWidth - 1) * plane.pixelStride
            require(lastTargetIndex < target.limit()) {
                "编码器 plane 容量不足：limit=${target.limit()}, last=$lastTargetIndex"
            }

            // 绝大多数编码器的 Y plane 都是紧凑或只带 row padding。Y plane 占 1080p 帧的
            // 三分之二，走行级批量复制可显著减少 ByteBuffer.put(index, byte) 的 JNI/边界检查开销。
            if (sourcePixelStride == 1 && plane.pixelStride == 1) {
                for (row in 0 until planeHeight) {
                    val sourceRow = sourceOffset + row * sourceRowStride
                    val targetRow = targetBase + row * plane.rowStride
                    target.position(targetRow)
                    target.put(source, sourceRow, planeWidth)
                }
                return
            }

            for (row in 0 until planeHeight) {
                val sourceRow = sourceOffset + row * sourceRowStride
                val targetRow = targetBase + row * plane.rowStride
                for (column in 0 until planeWidth) {
                    target.put(
                        targetRow + column * plane.pixelStride,
                        source[sourceRow + column * sourcePixelStride]
                    )
                }
            }
        }
    }
}

/**
 * MediaFormat 是可变对象，而且 csd ByteBuffer 的 position 也可变；回调前做一次 AVC 所需字段的
 * 深复制，避免 Service 缓存的格式被 MediaCodec 或其他消费者改坏。
 */
internal fun copyAvcMediaFormat(
    source: MediaFormat,
    includeGeneration: Boolean = true
): MediaFormat {
    val mime = source.getString(MediaFormat.KEY_MIME) ?: HardwareH264Encoder.MIME_TYPE
    val width = source.getInteger(MediaFormat.KEY_WIDTH)
    val height = source.getInteger(MediaFormat.KEY_HEIGHT)
    val copy = MediaFormat.createVideoFormat(mime, width, height)

    val integerKeys = mutableListOf(
        MediaFormat.KEY_BIT_RATE,
        MediaFormat.KEY_FRAME_RATE,
        MediaFormat.KEY_COLOR_FORMAT,
        MediaFormat.KEY_I_FRAME_INTERVAL,
        MediaFormat.KEY_PROFILE,
        MediaFormat.KEY_LEVEL,
        MediaFormat.KEY_MAX_INPUT_SIZE,
        MediaFormat.KEY_STRIDE,
        MediaFormat.KEY_SLICE_HEIGHT
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        integerKeys += MediaFormat.KEY_COLOR_RANGE
        integerKeys += MediaFormat.KEY_COLOR_STANDARD
        integerKeys += MediaFormat.KEY_COLOR_TRANSFER
    }
    for (key in integerKeys) {
        if (source.containsKey(key)) {
            runCatching { copy.setInteger(key, source.getInteger(key)) }
        }
    }
    if (includeGeneration && source.containsKey(HardwareH264Encoder.FORMAT_KEY_GENERATION)) {
        copy.setInteger(
            HardwareH264Encoder.FORMAT_KEY_GENERATION,
            source.getInteger(HardwareH264Encoder.FORMAT_KEY_GENERATION)
        )
    }

    for (index in 0..2) {
        val key = "csd-$index"
        val sourceBuffer = source.getByteBuffer(key) ?: continue
        val view = sourceBuffer.duplicate()
        val data = ByteArray(view.remaining())
        view.get(data)
        copy.setByteBuffer(key, ByteBuffer.wrap(data))
    }
    return copy
}
