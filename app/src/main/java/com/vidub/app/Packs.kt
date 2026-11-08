package com.vidub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class PackFile(val url: String, val name: String)

/**
 * Một gói tải thêm. type: "stt" (nhận dạng giọng nói), "tts" (giọng đọc Piper), "vad" (phát hiện giọng nói).
 * Dùng [archive] (.tar.bz2) hoặc danh sách [files] (tải từng file, đặt tên theo [PackFile.name]).
 */
data class Pack(
    val id: String,
    val type: String,
    val lang: String,
    val name: String,
    val version: Int,
    val sizeMb: Int,
    val archive: String?,
    val files: List<PackFile>
)

/**
 * Danh mục gói. Lấy bản mới nhất từ GitHub của dự án (để thêm/sửa gói mà không cần build lại APK),
 * lưu cache; nếu không có mạng thì dùng cache hoặc bản đi kèm trong app.
 */
object Catalog {
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/Khang-ayart/ViDub/main/app/src/main/assets/catalog.json"

    private var memo: List<Pack>? = null
    private var memoAt = 0L

    suspend fun load(ctx: Context, force: Boolean = false): List<Pack> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        memo?.let { if (!force && now - memoAt < 10 * 60_000L) return@withContext it }

        val cache = File(ctx.filesDir, "catalog.json")
        var json: String? = null
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(REMOTE_URL).build()).execute().use { r ->
                if (r.isSuccessful) json = r.body?.string()
            }
            json?.let {
                parse(it)               // chỉ lưu cache nếu đọc được
                cache.writeText(it)
            }
        } catch (_: Exception) {
            json = null
        }
        if (json == null && cache.exists()) json = runCatching { cache.readText() }.getOrNull()
        if (json == null) json = ctx.assets.open("catalog.json").bufferedReader().use { it.readText() }

        val list = runCatching { parse(json!!) }.getOrElse {
            parse(ctx.assets.open("catalog.json").bufferedReader().use { r -> r.readText() })
        }
        memo = list
        memoAt = now
        list
    }

    private fun parse(text: String): List<Pack> {
        val arr = JSONObject(text).getJSONArray("packs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val files = o.optJSONArray("files")?.let { a ->
                (0 until a.length()).map { j ->
                    val f = a.getJSONObject(j)
                    PackFile(f.getString("url"), f.getString("name"))
                }
            } ?: emptyList()
            Pack(
                id = o.getString("id"),
                type = o.getString("type"),
                lang = o.optString("lang", ""),
                name = o.getString("name"),
                version = o.optInt("version", 1),
                sizeMb = o.optInt("sizeMb", 0),
                archive = o.optString("archive").takeIf { it.isNotEmpty() },
                files = files
            )
        }
    }
}

/** Quản lý thư mục gói: tải, cập nhật, xóa từng gói độc lập. */
class PackStore(ctx: Context) {
    private val root = File(ctx.filesDir, "packs").also { it.mkdirs() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun dir(id: String) = File(root, id)

    fun installedVersion(id: String): Int? =
        File(dir(id), ".version").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    fun isInstalled(id: String) = installedVersion(id) != null

    fun needsUpdate(p: Pack): Boolean = (installedVersion(p.id) ?: return false) < p.version

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /**
     * Tải gói vào thư mục tạm rồi mới thay thế bản cũ, nên tải lỗi/hủy giữa chừng không làm hỏng bản đang dùng.
     * [onProgress] nhận số byte đã tải (gọi từ luồng nền).
     */
    suspend fun install(pack: Pack, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(root, ".tmp-${pack.id}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            var done = 0L
            if (pack.archive != null) {
                done = downloadAndExtract(pack.archive, tmp, done, onProgress)
            } else {
                for (f in pack.files) {
                    done = downloadFile(f.url, File(tmp, f.name), done, onProgress)
                }
            }
            File(tmp, ".version").writeText(pack.version.toString())
            val target = dir(pack.id)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IllegalStateException("Không ghi được gói vào bộ nhớ")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private suspend fun downloadFile(url: String, out: File, start: Long, onProgress: (Long) -> Unit): Long {
        var done = start
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Tải lỗi (${r.code}): $url")
            val body = r.body ?: throw IllegalStateException("Phản hồi rỗng: $url")
            out.parentFile?.mkdirs()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
        }
        return done
    }

    /** Tải .tar.bz2 và giải nén thẳng từ luồng mạng, bỏ thư mục gốc đầu tiên trong file nén. */
    private suspend fun downloadAndExtract(url: String, outDir: File, start: Long, onProgress: (Long) -> Unit): Long {
        var done = start
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Tải lỗi (${r.code}): $url")
            val body = r.body ?: throw IllegalStateException("Phản hồi rỗng: $url")
            val counting = object : java.io.FilterInputStream(body.byteStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        done += n
                        onProgress(done)
                    }
                    return n
                }
            }
            val canonicalRoot = outDir.canonicalPath
            TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered())).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val e = tar.nextTarEntry ?: break
                    val rel = e.name.trimStart('.', '/').substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(outDir, rel)
                    if (!f.canonicalPath.startsWith(canonicalRoot + File.separator)) continue  // chặn đường dẫn lạ
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        }
        return done
    }
}
