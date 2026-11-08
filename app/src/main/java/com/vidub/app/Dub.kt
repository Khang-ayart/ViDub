package com.vidub.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** Dịch offline bằng ML Kit: nguồn (ko/en/...) -> tiếng Việt. */
class TranslateManager {
    private var translator: Translator? = null

    suspend fun prepare(sourceLang: String) {
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
        val t = Translation.getClient(options)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        translator = t
    }

    suspend fun translate(text: String): String =
        translator?.translate(text)?.await() ?: text

    fun close() {
        translator?.close()
        translator = null
    }
}

/** Quản lý gói ngôn ngữ: liệt kê, tải, xóa. */
object LanguagePacks {
    /** Hai ngôn ngữ mặc định: tự tải khi cần. Ngôn ngữ khác phải tải trước. */
    val DEFAULT = setOf(TranslateLanguage.ENGLISH, TranslateLanguage.KOREAN)

    fun name(code: String): String {
        val n = Locale(code).getDisplayLanguage(Locale("vi"))
        return if (n.isBlank() || n == code) code else n.replaceFirstChar { it.uppercase() }
    }

    fun all(): List<String> =
        TranslateLanguage.getAllLanguages()
            .filter { it != TranslateLanguage.VIETNAMESE }
            .sortedBy { name(it) }

    suspend fun downloaded(): Set<String> =
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
            .map { it.language }
            .toSet()

    /** Tải gói [code] (kèm gói tiếng Việt/Anh nếu còn thiếu). */
    suspend fun download(code: String) {
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(code)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
        try {
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } finally {
            t.close()
        }
    }

    suspend fun delete(code: String) {
        RemoteModelManager.getInstance()
            .deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
            .await()
    }
}

/** Nhận diện ngôn ngữ của một câu (offline). Trả về "und" nếu không chắc. */
class LangId {
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.7f).build()
    )

    suspend fun detect(text: String): String = client.identifyLanguage(text).await()

    fun close() = client.close()
}

/** Đọc tiếng Việt bằng TTS có sẵn trong máy. */
class Dubber(
    context: Context,
    private val speakStarted: () -> Unit,
    private val speakEnded: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    @Volatile
    private var currentId = ""

    var ready = false
        private set
    var viSupported = false
        private set

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

    fun speak(text: String, rate: Float) {
        val engine = tts ?: return
        if (!ready || !viSupported) return
        val id = "u${System.nanoTime()}"
        currentId = id
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        currentId = ""
        tts?.stop()
    }

    fun shutdown() {
        currentId = ""
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
