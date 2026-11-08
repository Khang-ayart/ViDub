package com.vidub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Engine đọc tiếng Việt. Có hai loại: TTS của hệ thống (mặc định, dự phòng) và Piper (offline, tải thêm). */
interface SpeechEngine {
    val canSpeak: Boolean
    fun speak(text: String, rate: Float)
    fun stop()
    fun shutdown()
}

/** TTS có sẵn của Android. */
class SystemTts(
    context: Context,
    private val speakStarted: () -> Unit,
    private val speakEnded: () -> Unit
) : SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    @Volatile
    private var currentId = ""

    var ready = false
        private set
    var viSupported = false
        private set

    override val canSpeak: Boolean get() = ready && viSupported

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // Đợi constructor chạy xong rồi mới thiết lập
            main.post { setup(status) }
        }
    }

    private fun setup(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = engine.setLanguage(Locale("vi", "VN"))
        viSupported = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == currentId) main.post { speakStarted() }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == currentId) main.post { speakEnded() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == currentId) main.post { speakEnded() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == currentId) main.post { speakEnded() }
            }
        })
        ready = true
    }

    override fun speak(text: String, rate: Float) {
        val engine = tts ?: return
        if (!canSpeak) return
        val id = "u${System.nanoTime()}"
        currentId = id
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        currentId = ""
        tts?.stop()
    }

    override fun shutdown() {
        currentId = ""
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

/**
 * Piper (VITS) chạy offline bằng sherpa-onnx trên CPU.
 * Mỗi câu: tổng hợp trên luồng nền rồi phát bằng AudioTrack; câu mới tới thì hủy câu đang đọc.
 * Nếu pack có file *.int8.onnx thì dùng bản int8 (nhẹ và nhanh hơn).
 */
class PiperTts(
    packDir: File,
    private val speakStarted: () -> Unit,
    private val speakEnded: () -> Unit
) : SpeechEngine {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val tts: OfflineTts

    @Volatile
    private var gen = 0

    @Volatile
    private var track: AudioTrack? = null

    /** Gọi (trên luồng chính) khi tổng hợp/phát lỗi để app chuyển về giọng hệ thống. */
    var onError: ((Throwable) -> Unit)? = null

    override val canSpeak: Boolean get() = true

    init {
        val onnx = packDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".onnx") }
            .sortedByDescending { it.name.contains("int8") }   // ưu tiên bản int8 nếu có
            .firstOrNull() ?: throw IllegalStateException("Gói giọng đọc thiếu file .onnx")
        val tokens = packDir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: throw IllegalStateException("Gói giọng đọc thiếu tokens.txt")
        val data = packDir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
            ?: throw IllegalStateException("Gói giọng đọc thiếu espeak-ng-data")

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = data.absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        tts = OfflineTts(config = config)
    }

    override fun speak(text: String, rate: Float) {
        val id = ++gen
        haltTrack()
        worker.execute {
            if (id != gen) return@execute
            var t: AudioTrack? = null
            try {
                val audio = tts.generate(text = text, sid = 0, speed = rate)
                if (id != gen || audio.samples.isEmpty()) {
                    if (id == gen) main.post { speakEnded() }
                    return@execute
                }

                val sr = audio.sampleRate
                val bytes = audio.samples.size * 4
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minOf(bytes, sr * 4))   // đệm tối đa ~1 giây
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                main.post { if (id == gen) speakStarted() }
                t.play()

                val chunk = sr / 10   // ghi từng 0,1 giây để kịp hủy khi có câu mới
                var off = 0
                while (off < audio.samples.size && id == gen) {
                    val n = minOf(chunk, audio.samples.size - off)
                    val w = t.write(audio.samples, off, n, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) break
                    off += n
                }
                // Chờ phát nốt phần đệm
                if (id == gen && off >= audio.samples.size) {
                    val deadline = System.currentTimeMillis() + 2_000
                    while (id == gen && t.playbackHeadPosition < audio.samples.size &&
                        System.currentTimeMillis() < deadline
                    ) Thread.sleep(20)
                }
                if (id == gen) main.post { if (id == gen) speakEnded() }
            } catch (e: Throwable) {
                if (id == gen) main.post { onError?.invoke(e) }
            } finally {
                try {
                    t?.release()
                } catch (_: Exception) {
                }
                if (track === t) track = null
            }
        }
    }

    private fun haltTrack() {
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        gen++
        haltTrack()
    }

    override fun shutdown() {
        gen++
        haltTrack()
        worker.shutdownNow()
        try {
            tts.release()
        } catch (_: Throwable) {
        }
    }
}
