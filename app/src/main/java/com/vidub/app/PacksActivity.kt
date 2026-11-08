package com.vidub.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quản lý các gói tải thêm: nhận dạng giọng nói, giọng đọc Piper, gói dịch.
 * Mỗi gói tải / cập nhật / xóa độc lập; không gói nào bắt buộc.
 */
class PacksActivity : AppCompatActivity() {

    private class Row(val key: String, val title: String, val sub: String, val header: Boolean = false)

    private lateinit var store: PackStore
    private lateinit var adapter: ArrayAdapter<Row>
    private val rows = ArrayList<Row>()

    private var catalog: List<Pack> = emptyList()
    private var translated: Set<String> = emptySet()
    private val busy = HashMap<String, String>()   // khóa -> chữ tiến độ
    private val jobs = HashMap<String, Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PackStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Quản lý gói"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
        })
        root.addView(TextView(this).apply {
            text = "Chỉ tải những gói bạn cần. Chạm một gói để tải, cập nhật hoặc xóa."
            textSize = 13f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad / 2)
        })

        adapter = object : ArrayAdapter<Row>(this, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val r = rows[position]
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                t1.text = r.title
                t2.text = r.sub
                t2.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
                t1.setTypeface(null, if (r.header) Typeface.BOLD else Typeface.NORMAL)
                t1.textSize = if (r.header) 15f else 16f
                return v
            }

            override fun isEnabled(position: Int) = !rows[position].header
            override fun areAllItemsEnabled() = false
        }

        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        rebuild()
        lifecycleScope.launch {
            catalog = Catalog.load(this@PacksActivity)
            translated = try {
                LanguagePacks.downloaded()
            } catch (e: Exception) {
                emptySet()
            }
            rebuild()
        }
    }

    // ------------------------------------------------------------ danh sách

    private fun status(p: Pack): String {
        val size = if (p.sizeMb > 0) " · ~${p.sizeMb}MB" else ""
        return when {
            busy.containsKey(p.id) -> "⏳ ${busy[p.id]}"
            store.needsUpdate(p) -> "⟳ Có bản mới (chạm để cập nhật)"
            store.isInstalled(p.id) -> "✓ Đã tải"
            else -> "⬇ Chưa tải$size"
        }
    }

    private fun rebuild() {
        rows.clear()

        val stt = catalog.filter { it.type == "stt" }
        rows.add(Row("", "Nhận dạng giọng nói (tạo phụ đề khi video không có)", "", true))
        if (stt.isEmpty()) rows.add(Row("", "Đang tải danh sách…", ""))
        stt.forEach { rows.add(Row(it.id, "${LanguagePacks.name(it.lang)} — ${it.name}", status(it))) }

        val tts = catalog.filter { it.type == "tts" }
        rows.add(Row("", "Giọng đọc Piper (offline; mặc định dùng giọng hệ thống)", "", true))
        tts.forEach { rows.add(Row(it.id, it.name, status(it))) }

        rows.add(Row("", "Dịch sang tiếng Việt (ML Kit, ~30MB mỗi gói)", "", true))
        for (code in LanguagePacks.all()) {
            val key = "tr:$code"
            val default = if (code in LanguagePacks.DEFAULT) " · mặc định, tự tải khi cần" else ""
            val sub = when {
                busy.containsKey(key) -> "⏳ ${busy[key]}"
                code in translated -> "✓ Đã tải$default"
                else -> "⬇ Chưa tải$default"
            }
            rows.add(Row(key, LanguagePacks.name(code), sub))
        }
        adapter.notifyDataSetChanged()
    }

    private fun rebuildOnUi() = runOnUiThread { rebuild() }

    // ------------------------------------------------------------ thao tác

    private fun onRowClick(r: Row) {
        if (r.key.isEmpty()) return
        if (busy.containsKey(r.key)) {
            AlertDialog.Builder(this)
                .setMessage("Hủy tải gói này?")
                .setNegativeButton("Không", null)
                .setPositiveButton("Hủy tải") { _, _ -> jobs[r.key]?.cancel() }
                .show()
            return
        }
        if (r.key.startsWith("tr:")) {
            val code = r.key.removePrefix("tr:")
            if (code in translated) confirmDelete(LanguagePacks.name(code)) { deleteTranslation(code) }
            else downloadTranslation(code)
            return
        }

        val p = catalog.firstOrNull { it.id == r.key } ?: return
        when {
            store.needsUpdate(p) -> {
                AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setItems(arrayOf("Cập nhật", "Xóa")) { _, which ->
                        if (which == 0) downloadPack(p) else confirmDelete(p.name) { deletePack(p) }
                    }
                    .show()
            }
            store.isInstalled(p.id) -> confirmDelete(p.name) { deletePack(p) }
            else -> downloadPack(p)
        }
    }

    private fun confirmDelete(name: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage("Xóa gói \"$name\" khỏi máy?")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ -> action() }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun downloadPack(p: Pack) {
        busy[p.id] = "Đang tải…"
        rebuild()
        var lastMb = -1L
        jobs[p.id] = lifecycleScope.launch {
            try {
                store.install(p) { bytes ->
                    val mb = bytes / 1_048_576L
                    if (mb != lastMb) {
                        lastMb = mb
                        val total = if (p.sizeMb > 0) " / ~${p.sizeMb}" else ""
                        busy[p.id] = "Đang tải $mb$total MB…"
                        rebuildOnUi()
                    }
                }
                toast("Đã tải: ${p.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Tải lỗi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy.remove(p.id)
                jobs.remove(p.id)
                rebuild()
            }
        }
    }

    private fun deletePack(p: Pack) {
        store.delete(p.id)
        rebuild()
    }

    private fun downloadTranslation(code: String) {
        val key = "tr:$code"
        busy[key] = "Đang tải…"
        rebuild()
        jobs[key] = lifecycleScope.launch {
            try {
                LanguagePacks.download(code)
                translated = translated + code
                toast("Đã tải gói dịch ${LanguagePacks.name(code)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Tải lỗi (kiểm tra mạng): ${e.message}")
            } finally {
                busy.remove(key)
                jobs.remove(key)
                rebuild()
            }
        }
    }

    private fun deleteTranslation(code: String) {
        lifecycleScope.launch {
            try {
                LanguagePacks.delete(code)
                translated = translated - code
            } catch (e: Exception) {
                toast("Không xóa được: ${e.message}")
            }
            rebuild()
        }
    }
}
