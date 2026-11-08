package com.vidub.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
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
