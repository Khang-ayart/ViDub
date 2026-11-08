package com.vidub.app

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
