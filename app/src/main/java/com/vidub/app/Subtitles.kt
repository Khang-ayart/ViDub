package com.vidub.app

import com.google.mlkit.nl.translate.TranslateLanguage
import org.schabi.newpipe.extractor.NewPipe

/** SPEECH = lời nói (dịch + đọc); SING = hát/rap; MUSIC = nhạc nền; SKIP = bỏ qua hoàn toàn. */
enum class Kind { SPEECH, SING, MUSIC, SKIP }

class Cue(val startMs: Long, val endMs: Long, val text: String, var kind: Kind = Kind.SPEECH) {
    /** Bản hiển thị tiếng Việt: bản dịch (SPEECH) hoặc nhãn [hát]/[nhạc]. */
    var vi: String? = Subtitles.marker(kind)
}

object Subtitles {

    fun marker(kind: Kind): String? = when (kind) {
        Kind.SING -> "[hát]"
        Kind.MUSIC -> "[nhạc]"
        else -> null
    }

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

    /** Thông tin chẩn đoán lần tải phụ đề gần nhất (hiện ra khi đọc phụ đề thất bại). */
    @Volatile
    var lastDebug: String = ""

    /** Chạy trên luồng nền (IO). [reaction] = true thì lọc chặt hơn lời bài hát lặp lại. */
    fun load(track: SubTrack, reaction: Boolean): List<Cue> {
        val dl = NewPipe.getDownloader()
        // Ưu tiên VTT; nếu không được thì dùng URL gốc (thường là TTML)
        val urls = listOf(track.url.replace(Regex("&fmt=[^&]*"), "") + "&fmt=vtt", track.url).distinct()
        var parsed: List<Cue> = emptyList()
        val dbg = StringBuilder()
        for (u in urls) {
            val fmt = Regex("fmt=([^&]*)").find(u)?.groupValues?.get(1) ?: "?"
            try {
                val body = dl.get(u).responseBody()
                parsed = parse(body, track.auto)
                dbg.append("[$fmt: ${body.length} ký tự, ${parsed.size} câu, đầu: ${body.take(50).replace('\n', ' ')}] ")
            } catch (e: Exception) {
                dbg.append("[$fmt lỗi: ${e.message}] ")
            }
            if (parsed.isNotEmpty()) break
        }
        lastDebug = dbg.toString()
        markRepeatedAsSinging(parsed, reaction)
        return merge(parsed)
    }

    // ------------------------------------------------------------ parsing

    private val TIME = Regex(
        """(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})"""
    )
    private val TAGS = Regex("<[^>]+>")
    private val NOTE = Regex("[♪♫♬🎵🎶]")
    private val BRACKET = Regex("""[\[(（【]([^\]）)】]*)[\]）)】]""")
    private val SING_RE = Regex(
        """(?i)\b(sing(ing|s)?|rap(ping|s)?|vocals?|lyrics?|chant(ing|s)?)\b|hát|노래|래핑|歌|唄|cantando|singt|поёт|поет"""
    )
    private val MUSIC_RE = Regex(
        """(?i)music|musik|musique|música|musica|instrumental|nhạc|음악|音楽|音乐|музык|bgm|melody"""
    )

    private fun ms(h: String, m: String, s: String, milli: String): Long =
        (if (h.isEmpty()) 0L else h.toLong()) * 3_600_000L +
            m.toLong() * 60_000L + s.toLong() * 1_000L + milli.toLong()

    private val NUM_ENTITY = Regex("&#(\\d+);")

    private fun unescape(text: String): String {
        var t = text
        repeat(2) {   // YouTube hay escape hai lần (&amp;#39;)
            t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ")
                .replace(NUM_ENTITY) { m ->
                    val cp = m.groupValues[1].toIntOrNull()
                    if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
                }
                .replace("&amp;", "&")
        }
        return t
    }

    private fun clean(line: String): String =
        unescape(line.replace(TAGS, "").replace(BRACKET, "").replace(NOTE, ""))   // [Music], [Âm nhạc], ♪...
            .replace(Regex("\\s+"), " ")
            .trim()

    private class RawCue(val start: Long, val end: Long, val lines: List<String>)

    /** Tự nhận định dạng: WebVTT hoặc TTML/srv3 (YouTube thường trả TTML). */
    fun parse(raw: String, auto: Boolean): List<Cue> {
        val entries = if (TIME.containsMatchIn(raw)) vttEntries(raw) else ttmlEntries(raw)
        return process(entries, auto)
    }

    private fun vttEntries(raw: String): List<RawCue> {
        val lines = raw.replace("\r", "").split("\n")
        val out = ArrayList<RawCue>()
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
            val rawLines = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                rawLines.add(lines[i].replace(TAGS, ""))
                i++
            }
            out.add(RawCue(start, end, rawLines))
        }
        return out
    }

    private val P_RE = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

    private fun ttmlTime(s: String): Long? {
        val v = s.trim()
        return try {
            when {
                v.endsWith("ms") -> v.dropLast(2).toDouble().toLong()
                v.endsWith("s") -> (v.dropLast(1).toDouble() * 1000).toLong()
                else -> {
                    val parts = v.split(':')
                    when (parts.size) {
                        3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000 + (parts[2].toDouble() * 1000).toLong()
                        2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
                        else -> (v.toDouble() * 1000).toLong()
                    }
                }
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun ttmlEntries(raw: String): List<RawCue> {
        val out = ArrayList<RawCue>()
        for (m in P_RE.findAll(raw)) {
            val attrs = m.groupValues[1]
            fun attr(n: String) = Regex("""\b$n="([^"]*)"""").find(attrs)?.groupValues?.get(1)
            // TTML: begin/end; srv3: t/d (mili giây)
            val start = attr("begin")?.let { ttmlTime(it) } ?: attr("t")?.toLongOrNull() ?: continue
            val end = attr("end")?.let { ttmlTime(it) }
                ?: attr("d")?.toLongOrNull()?.let { start + it }
                ?: (start + 2_000)
            val inner = m.groupValues[2].replace(Regex("<br\\s*/?>"), "\n")
            val lines = inner.split("\n").map { it.replace(TAGS, "") }
            out.add(RawCue(start, end, lines))
        }
        return out
    }

    private fun process(entries: List<RawCue>, auto: Boolean): List<Cue> {
        val out = ArrayList<Cue>()
        var prev: List<String> = emptyList()
        for (e in entries) {
            val rawLines = e.lines
            // Nhận diện hát/nhạc TRƯỚC khi xóa ký hiệu
            val hasNote = rawLines.any { NOTE.containsMatchIn(it) }
            var bracketSing = false
            var bracketMusic = false
            for (line in rawLines) {
                for (b in BRACKET.findAll(line)) {
                    val inner = b.groupValues[1]
                    if (SING_RE.containsMatchIn(inner)) bracketSing = true
                    else if (MUSIC_RE.containsMatchIn(inner)) bracketMusic = true
                }
            }

            val textLines = rawLines.map { clean(it) }.filter { it.isNotEmpty() }
            val kind = when {
                hasNote || bracketSing -> Kind.SING
                textLines.isEmpty() && bracketMusic -> Kind.MUSIC
                textLines.isEmpty() -> null           // [Applause], [Laughter]... -> bỏ
                else -> Kind.SPEECH
            }

            // Phụ đề tự động của YouTube lặp lại dòng cũ trong mỗi cue -> chỉ lấy dòng mới
            val fresh = if (auto) textLines.filter { it !in prev } else textLines
            if (textLines.isNotEmpty()) prev = textLines

            if (kind == Kind.SPEECH) {
                if (fresh.isNotEmpty()) out.add(Cue(e.start, e.end, fresh.joinToString(" ")))
            } else if (kind != null) {
                out.add(Cue(e.start, e.end, textLines.joinToString(" "), kind))
            }
        }
        return out
    }

    /**
     * Câu lặp lại ≥3 lần (điệp khúc) thì coi là lời hát.
     * Chỉ áp dụng khi video có dấu hiệu nhạc (≥3 cue hát/nhạc) hoặc ở chế độ reaction.
     */
    private fun markRepeatedAsSinging(cues: List<Cue>, aggressive: Boolean) {
        val musicy = cues.count { it.kind == Kind.SING || it.kind == Kind.MUSIC } >= 3
        if (!musicy && !aggressive) return

        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
        val counts = HashMap<String, Int>()
        for (c in cues) {
            if (c.kind != Kind.SPEECH) continue
            val n = norm(c.text)
            if (n.split(' ').size >= 3) counts[n] = (counts[n] ?: 0) + 1
        }
        for (c in cues) {
            if (c.kind == Kind.SPEECH && (counts[norm(c.text)] ?: 0) >= 3) {
                c.kind = Kind.SING
                c.vi = marker(Kind.SING)
            }
        }
    }

    // ------------------------------------------------------------ merging

    /** Gộp cue ngắn thành câu dài để dịch/đọc tự nhiên hơn; gộp các đoạn hát/nhạc liền nhau thành một nhãn. */
    fun merge(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        var cur: Cue? = null
        for (c in cues) {
            val cu = cur
            if (cu == null) {
                cur = Cue(c.startMs, c.endMs, c.text, c.kind)
                continue
            }

            if (cu.kind != Kind.SPEECH || c.kind != Kind.SPEECH) {
                if (cu.kind == c.kind && c.startMs - cu.endMs < 4_000) {
                    cur = Cue(cu.startMs, c.endMs, "", cu.kind)
                } else {
                    out.add(cu)
                    cur = Cue(c.startMs, c.endMs, c.text, c.kind)
                }
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
