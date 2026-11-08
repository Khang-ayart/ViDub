package com.vidub.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

const val STT_RATE = 16_000

/** Giải mã luồng âm thanh YouTube (m4a/webm) về PCM 16 kHz mono (float). */
object AudioDecoder {

    /** Gọi [onChunk] cho từng đoạn âm thanh; trả về khi hết luồng. Chạy trên luồng nền. */
    suspend fun decode(url: String, onChunk: suspend (FloatArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(url, mapOf("User-Agent" to USER_AGENT))
            val trackIdx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("Không thấy luồng âm thanh")
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // Bộ giảm mẫu bằng trung bình cửa sổ
            var acc = 0.0
            var cnt = 0
            var phase = 0.0

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(o)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val pcm = ShortArray(sb.remaining())
                            sb.get(pcm)

                            val step = srcRate / STT_RATE.toDouble()
                            val frames = pcm.size / channels
                            val out = FloatArray((frames / step).toInt() + 2)
                            var outN = 0
                            for (f in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += pcm[f * channels + c]
                                acc += s / channels / 32768.0
                                cnt++
                                phase += 1.0
                                if (phase >= step) {
                                    if (outN < out.size) out[outN++] = (acc / cnt).toFloat()
                                    acc = 0.0
                                    cnt = 0
                                    phase -= step
                                }
                            }
                            if (outN > 0) onChunk(out.copyOf(outN))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}

/**
 * Nhận dạng giọng nói offline: Silero VAD cắt đoạn có tiếng nói, Zipformer (sherpa-onnx, int8) chuyển thành chữ.
 */
class SttEngine private constructor(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad
) : Closeable {

    companion object {
        /** [modelDir] chứa encoder*.onnx, decoder*.onnx, joiner*.onnx, tokens.txt. */
        fun create(modelDir: File, vadModel: File): SttEngine {
            fun find(prefix: String): String {
                val files = modelDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".onnx") }
                    ?.sortedByDescending { it.name.contains("int8") }
                return files?.firstOrNull()?.absolutePath
                    ?: throw IllegalStateException("Gói nhận dạng thiếu file $prefix*.onnx")
            }

            val tokens = File(modelDir, "tokens.txt")
            if (!tokens.exists()) throw IllegalStateException("Gói nhận dạng thiếu tokens.txt")

            val rec = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = STT_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = find("encoder"),
                            decoder = find("decoder"),
                            joiner = find("joiner")
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    ),
                    decodingMethod = "greedy_search"
                )
            )

            val vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModel.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 12.0f
                    ),
                    sampleRate = STT_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
            )
            return SttEngine(rec, vad)
        }
    }

    private fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, STT_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * Giải mã [audioUrl], cắt đoạn nói, nhận dạng từng đoạn.
     * [onProgress]: mốc thời gian (ms) âm thanh đã xử lý. [onSegment]: một câu đã nhận dạng (bắt đầu ms, kết thúc ms, chữ).
     */
    suspend fun run(
        audioUrl: String,
        onProgress: (Long) -> Unit,
        onSegment: suspend (Long, Long, String) -> Unit
    ) {
        var rest = FloatArray(0)
        var consumed = 0L

        suspend fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val text = transcribe(seg.samples)
                if (text.isNotBlank()) {
                    val startMs = seg.start * 1000L / STT_RATE
                    val endMs = (seg.start + seg.samples.size) * 1000L / STT_RATE
                    onSegment(startMs, endMs, text)
                }
            }
        }

        AudioDecoder.decode(audioUrl) { chunk ->
            val all = if (rest.isEmpty()) chunk else rest + chunk
            var p = 0
            while (all.size - p >= 512) {
                vad.acceptWaveform(all.copyOfRange(p, p + 512))
                p += 512
            }
            consumed += p
            rest = if (p < all.size) all.copyOfRange(p, all.size) else FloatArray(0)
            drain()
            onProgress(consumed * 1000L / STT_RATE)
        }
        vad.flush()
        drain()
        onProgress(Long.MAX_VALUE / 4)
    }

    override fun close() {
        try {
            vad.release()
        } catch (_: Throwable) {
        }
        try {
            recognizer.release()
        } catch (_: Throwable) {
        }
    }
}
