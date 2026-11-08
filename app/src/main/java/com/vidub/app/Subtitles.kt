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

    /** Chạy trên luồng nền (IO). [reaction] = true thì lọc chặt hơn lời bài hát lặp lại. */
    fun load(track: SubTrack, reaction: Boolean): List<Cue> {
        val raw = NewPipe.getDownloader().get(track.url).responseBody()
        val cues = parseVtt(raw, track.auto)
        markRepeatedAsSinging(cues, reaction)
        return merge(cues)
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

    private fun clean(line: String): String =
        line.replace(TAGS, "")
            .replace(BRACKET, "")                 // [Music], [Âm nhạc], (applause)...
            .replace(NOTE, "")
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

            val rawLines = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                rawLines.add(lines[i].replace(TAGS, ""))
                i++
            }

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
                if (fresh.isNotEmpty()) out.add(Cue(start, end, fresh.joinToString(" ")))
            } else if (kind != null) {
                out.add(Cue(start, end, textLines.joinToString(" "), kind))
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
