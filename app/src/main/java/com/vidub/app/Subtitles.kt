package com.vidub.app

import com.google.mlkit.nl.translate.TranslateLanguage
import org.schabi.newpipe.extractor.NewPipe

data class Cue(val startMs: Long, val endMs: Long, val text: String) {
    /** Bản dịch tiếng Việt (điền dần khi dịch xong). */
    var vi: String? = null
}

object Subtitles {

    /** Chọn track phụ đề: ưu tiên bản người làm, sau đó mới tới tự động. */
    fun pick(subs: List<SubTrack>, pref: String?): SubTrack? {
        if (subs.isEmpty()) return null

        fun find(order: List<String>): SubTrack? {
            for (auto in listOf(false, true)) {
                for (code in order) {
                    subs.firstOrNull { it.auto == auto && it.lang.lowercase().startsWith(code) }
                        ?.let { return it }
                }
            }
            return null
        }

        val defaultOrder = listOf("vi", "ko", "en")
        find(if (pref != null) listOf(pref) else defaultOrder)?.let { return it }
        find(defaultOrder)?.let { return it }
        // Ngôn ngữ khác: lấy track đầu tiên mà ML Kit dịch được
        return subs.firstOrNull {
            TranslateLanguage.fromLanguageTag(it.lang.substringBefore('-').lowercase()) != null
        }
    }

    /** Chạy trên luồng nền (IO). */
    fun load(track: SubTrack): List<Cue> {
        val raw = NewPipe.getDownloader().get(track.url).responseBody()
        return merge(parseVtt(raw, track.auto))
    }

    private val TIME = Regex(
        """(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})"""
    )

    private fun ms(h: String, m: String, s: String, milli: String): Long =
        (if (h.isEmpty()) 0L else h.toLong()) * 3_600_000L +
            m.toLong() * 60_000L + s.toLong() * 1_000L + milli.toLong()

    private fun clean(line: String): String =
        line.replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")   // [Music], [Âm nhạc]...
            .replace("♪", "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun parseVtt(raw: String, auto: Boolean): List<Cue> {
        val lines = raw.replace("\r", "").split("\n")
        val out = ArrayList<Cue>()
        var prev: List<String> = emptyList()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val textLines = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                val c = clean(lines[i])
                if (c.isNotEmpty()) textLines.add(c)
                i++
            }
            // Phụ đề tự động của YouTube lặp lại dòng cũ trong mỗi cue -> chỉ lấy dòng mới
            val fresh = if (auto) textLines.filter { it !in prev } else textLines
            if (textLines.isNotEmpty()) prev = textLines
            if (fresh.isNotEmpty()) out.add(Cue(start, end, fresh.joinToString(" ")))
        }
        return out
    }

    /** Gộp các cue ngắn thành câu dài hơn để dịch và đọc tự nhiên hơn. */
    fun merge(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        var cur: Cue? = null
        for (c in cues) {
            val cu = cur
            if (cu == null) {
                cur = Cue(c.startMs, c.endMs, c.text)
                continue
            }
            val ended = cu.text.last() in ".?!…。？！"
            val canMerge = !ended &&
                c.endMs - cu.startMs <= 7_000 &&
                cu.text.length + c.text.length < 140 &&
                c.startMs - cu.endMs < 800
            if (canMerge) {
                cur = Cue(cu.startMs, c.endMs, cu.text + " " + c.text)
            } else {
                out.add(cu)
                cur = Cue(c.startMs, c.endMs, c.text)
            }
        }
        cur?.let { out.add(it) }
        return out
    }
}
