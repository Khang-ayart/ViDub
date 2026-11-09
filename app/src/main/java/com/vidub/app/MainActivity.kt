package com.vidub.app

import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var subView: TextView
    private lateinit var dubSwitch: SwitchCompat
    private lateinit var reactionSwitch: SwitchCompat
    private lateinit var langSpinner: Spinner
    private lateinit var ttsSpinner: Spinner
    private lateinit var volSeek: SeekBar

    private var player: ExoPlayer? = null
    private val translator = TranslateManager()
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var store: PackStore

    // Giọng đọc: hệ thống luôn có sẵn làm mặc định và dự phòng; Piper chỉ khi người dùng chọn
    private lateinit var system: SystemTts
    private var piper: PiperTts? = null
    private var currentTtsId = "system"
    private var ttsOptions: List<Pair<String, String>> = listOf(SYSTEM_TTS to SYSTEM_TTS_NAME)

    private var cues: List<Cue> = emptyList()
    private var lastSpoken = -1
    private var shownIdx = -1
    private var speaking = false

    // Trạng thái nhận dạng giọng nói (khi video không có phụ đề)
    private var sttGen = 0
    @Volatile private var sttActive = false
    @Volatile private var sttDone = false
    @Volatile private var processedMs = 0L
    private var sttStarted = false
    private var autoPaused = false

    /** (mã ngôn ngữ hoặc null = tự động, tên hiển thị) */
    private var langOptions: List<Pair<String?, String>> = defaultLangOptions()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("vidub", MODE_PRIVATE)
        store = PackStore(this)

        urlInput = findViewById(R.id.urlInput)
        playerView = findViewById(R.id.playerView)
        titleView = findViewById(R.id.titleView)
        statusView = findViewById(R.id.statusView)
        subView = findViewById(R.id.subView)
        dubSwitch = findViewById(R.id.dubSwitch)
        reactionSwitch = findViewById(R.id.reactionSwitch)
        langSpinner = findViewById(R.id.langSpinner)
        ttsSpinner = findViewById(R.id.ttsSpinner)
        volSeek = findViewById(R.id.volSeek)

        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            langOptions.map { it.second }
        )
        ttsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ttsOptions.map { it.second }
        )
        ttsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = ttsOptions.getOrNull(position)?.first ?: return
                if (chosen == currentTtsId) return
                prefs.edit().putString("tts", chosen).apply()
                applyTts(chosen)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        fitPlayer()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .also { p ->
                playerView.player = p
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isPlaying) stopDub()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        stopDub()
                        lastSpoken = -1
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        setStatus("Lỗi phát video: ${error.errorCodeName}")
                    }
                })
            }

        system = SystemTts(
            this,
            speakStarted = { onSpeakStarted() },
            speakEnded = { onSpeakEnded() }
        )

        dubSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) stopDub()
        }
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = applyVolume()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.pasteBtn).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrBlank()) urlInput.setText(text.trim())
        }
        findViewById<Button>(R.id.goBtn).setOnClickListener { submit() }
        findViewById<Button>(R.id.packsBtn).setOnClickListener {
            startActivity(Intent(this, PacksActivity::class.java))
        }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else false
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitPlayer()
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // Quay lại từ màn hình quản lý gói: cập nhật danh sách ngôn ngữ và giọng đọc
        refreshLangOptions()
        refreshTtsOptions()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        player?.pause()
        stopDub()
    }

    override fun onDestroy() {
        super.onDestroy()
        sttGen++
        loadJob?.cancel()
        translator.close()
        piper?.shutdown()
        piper = null
        system.shutdown()
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- UI helpers

    private fun fitPlayer() {
        val dm = resources.displayMetrics
        val h = minOf(dm.widthPixels * 9 / 16, (dm.heightPixels * 0.6).toInt())
        playerView.layoutParams = playerView.layoutParams.also { it.height = h }
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        urlInput.setText(text)
        load(text)
    }

    private fun submit() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
        load(urlInput.text.toString())
    }

    // ---------------------------------------------------------------- language / voice options

    /** Ô chọn ngôn ngữ: Tự động, English, 한국어 + các ngôn ngữ đã tải gói dịch hoặc gói nhận dạng. */
    private fun refreshLangOptions() {
        lifecycleScope.launch {
            val have = try {
                LanguagePacks.downloaded()
            } catch (e: Exception) {
                emptySet()
            }
            val stt = Catalog.load(this@MainActivity)
                .filter { it.type == "stt" && store.isInstalled(it.id) }
                .map { it.lang }
            val extra = (have + stt)
                .filter { it !in LanguagePacks.DEFAULT && it != TranslateLanguage.VIETNAMESE && it.isNotEmpty() }
                .toSortedSet()
                .sortedBy { LanguagePacks.name(it) }
                .map { it to LanguagePacks.name(it) }
            val prev = langOptions.getOrNull(langSpinner.selectedItemPosition)?.first
            val next = defaultLangOptions() + extra
            if (next == langOptions) return@launch
            langOptions = next
            langSpinner.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                langOptions.map { it.second }
            )
            langSpinner.setSelection(langOptions.indexOfFirst { it.first == prev }.coerceAtLeast(0))
        }
    }

    /** Ô chọn giọng đọc: giọng hệ thống + các gói Piper đã tải. */
    private fun refreshTtsOptions() {
        lifecycleScope.launch {
            val installed = Catalog.load(this@MainActivity)
                .filter { it.type == "tts" && store.isInstalled(it.id) }
                .map { it.id to it.name }
            ttsOptions = listOf(SYSTEM_TTS to SYSTEM_TTS_NAME) + installed
            ttsSpinner.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ttsOptions.map { it.second }
            )
            val saved = prefs.getString("tts", SYSTEM_TTS) ?: SYSTEM_TTS
            val want = if (ttsOptions.any { it.first == saved }) saved else SYSTEM_TTS
            ttsSpinner.setSelection(ttsOptions.indexOfFirst { it.first == want }.coerceAtLeast(0), false)
            applyTts(want)
        }
    }

    /** Chuyển engine đọc. Piper lỗi thì tự về giọng hệ thống. */
    private fun applyTts(id: String, force: Boolean = false) {
        if (!force && id == currentTtsId) return
        currentTtsId = id
        stopDub()
        piper?.shutdown()
        piper = null
        if (id == SYSTEM_TTS) return

        val gen = id
        lifecycleScope.launch {
            try {
                val engine = withContext(Dispatchers.Default) {
                    PiperTts(
                        store.dir(id),
                        speakStarted = { onSpeakStarted() },
                        speakEnded = { onSpeakEnded() }
                    )
                }
                if (currentTtsId != gen) {
                    engine.shutdown()
                    return@launch
                }
                engine.onError = { e -> piperFailed(e) }
                piper = engine
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                piperFailed(e)
            }
        }
    }

    private fun piperFailed(e: Throwable) {
        Toast.makeText(
            this,
            "Giọng Piper lỗi (${e.message ?: e.javaClass.simpleName}) — dùng giọng hệ thống",
            Toast.LENGTH_LONG
        ).show()
        piper?.shutdown()
        piper = null
        currentTtsId = SYSTEM_TTS
        prefs.edit().putString("tts", SYSTEM_TTS).apply()
        ttsSpinner.setSelection(0, false)
    }

    // ---------------------------------------------------------------- loading

    private fun extractUrl(text: String): String? {
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        return if (url.contains("youtu")) url else null
    }

    private fun load(rawText: String) {
        val url = extractUrl(rawText)
        if (url == null) {
            setStatus("Link không hợp lệ — hãy dán link YouTube.")
            return
        }
        loadJob?.cancel()
        resetState()

        loadJob = lifecycleScope.launch {
            try {
                setStatus("Đang lấy thông tin video…")
                val data = withContext(Dispatchers.IO) { YtFetcher.fetch(url) }
                titleView.text = data.title

                if (!reactionSwitch.isChecked && REACTION_TITLE.containsMatchIn(data.title)) {
                    reactionSwitch.isChecked = true
                    Toast.makeText(this@MainActivity, "Tự bật chế độ reaction", Toast.LENGTH_SHORT).show()
                }

                if (!prepareVideo(data)) {
                    setStatus("Không lấy được luồng video (video giới hạn tuổi, riêng tư hoặc YouTube đã đổi). Thử cập nhật NewPipeExtractor.")
                    return@launch
                }

                val pref = langOptions.getOrNull(langSpinner.selectedItemPosition)?.first
                val reaction = reactionSwitch.isChecked
                val track = Subtitles.pick(data.subs, pref)
                if (track == null) {
                    if (data.subs.isNotEmpty()) {
                        val langs = data.subs.joinToString(", ") { it.lang + if (it.auto) " (tự động)" else "" }
                        playPlain("Video có phụ đề ($langs) nhưng app chưa hỗ trợ dịch các ngôn ngữ này.")
                    } else {
                        runAutoSubs(data, pref, reaction)
                    }
                } else {
                    runSubtitles(track, reaction)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Lỗi: ${e.message ?: e.javaClass.simpleName}")
                if (sttActive) sttDone = true
            }
        }
    }

    /** Video có phụ đề: dịch (nếu cần) rồi lồng tiếng. */
    private suspend fun runSubtitles(track: SubTrack, reaction: Boolean) {
        val code = track.lang.substringBefore('-').lowercase()
        if (code != "vi" && !checkTranslatable(code, track.lang)) return

        setStatus("Đang tải phụ đề (${track.lang}${if (track.auto) ", tự động" else ""})…")
        val parsed = withContext(Dispatchers.IO) { Subtitles.load(track, reaction) }
        if (parsed.isEmpty()) {
            playPlain("Không đọc được phụ đề của video này (${Subtitles.lastDebug}).")
            return
        }

        if (reaction) {
            setStatus("Đang lọc lời người nói…")
            filterReaction(parsed, code)
        }
        cues = parsed
        val skipped = parsed.count { it.kind == Kind.SKIP }
        val skipNote = if (skipped > 0) " Đã bỏ $skipped câu không phải lời người nói." else ""

        if (code == "vi") {
            cues.forEach { if (it.kind == Kind.SPEECH) it.vi = it.text }
            setStatus("Có phụ đề tiếng Việt sẵn · lồng tiếng.$skipNote" + ttsNote())
            player?.playWhenReady = true
            return
        }

        setStatus("Đang chuẩn bị gói dịch ${track.lang} → vi (lần đầu có thể cần tải)…")
        translator.prepare(code)

        // Dịch lần lượt từ đầu; phát video khi đã dịch được vài câu đầu.
        // Chỉ dịch lời nói; hát/nhạc chỉ hiện nhãn [hát]/[nhạc].
        var started = false
        for ((i, cue) in cues.withIndex()) {
            if (cue.kind == Kind.SPEECH) cue.vi = translator.translate(cue.text)
            if (!started && (i >= START_AFTER || i == cues.lastIndex)) {
                started = true
                player?.playWhenReady = true
            }
            if (i % 10 == 0) setStatus("Đang dịch phụ đề ${i + 1}/${cues.size}…")
        }
        setStatus("Đã dịch xong · lồng tiếng ${track.lang} → vi.$skipNote" + ttsNote())
    }

    /**
     * Gói dịch: Anh/Hàn tự tải khi cần; ngôn ngữ khác phải tải trước trong "Quản lý gói".
     * Trả về false (và phát video thường) nếu chưa dùng được.
     */
    private suspend fun checkTranslatable(code: String, label: String): Boolean {
        if (TranslateLanguage.fromLanguageTag(code) == null) {
            playPlain("Chưa hỗ trợ dịch từ ngôn ngữ \"$label\".")
            return false
        }
        if (code !in LanguagePacks.DEFAULT) {
            val have = try {
                LanguagePacks.downloaded()
            } catch (e: Exception) {
                emptySet()
            }
            if (code !in have) {
                playPlain("Cần tải gói dịch ${LanguagePacks.name(code)} (nút \"Quản lý gói\") rồi bấm Xem lại.")
                return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- nhận dạng giọng nói (không có phụ đề)

    /** Đoán ngôn ngữ nói theo ký tự trong tiêu đề; không đoán được thì coi là tiếng Anh. */
    private fun guessLang(title: String): String = when {
        title.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' } -> "ko"
        title.any { it in '぀'..'ヿ' } -> "ja"
        title.any { it in '฀'..'๿' } -> "th"
        title.any { it in 'Ѐ'..'ӿ' } -> "ru"
        title.any { it in '一'..'鿿' } -> "zh"
        else -> "en"
    }

    private suspend fun runAutoSubs(data: VideoData, pref: String?, reaction: Boolean) {
        val audioUrl = data.audioOnlyUrl
        if (audioUrl == null) {
            playPlain("Video không có phụ đề và không lấy được luồng âm thanh để nhận dạng.")
            return
        }
        val lang = pref ?: guessLang(data.title)
        val langName = LanguagePacks.name(lang)

        val catalog = Catalog.load(this)
        val sttPack = catalog.firstOrNull { it.type == "stt" && it.lang == lang }
        val vadPack = catalog.firstOrNull { it.type == "vad" }
        if (sttPack == null || vadPack == null) {
            playPlain("Video không có phụ đề. Chưa có gói nhận dạng giọng nói cho \"$langName\".")
            return
        }
        // Tiếng Anh tự tải khi cần; ngôn ngữ khác hỏi trước khi tải
        if (!store.isInstalled(sttPack.id) && lang != "en") {
            val size = if (sttPack.sizeMb > 0) " (~${sttPack.sizeMb}MB)" else ""
            val ok = askYesNo(
                "Video này không có phụ đề.\n\nTải gói nhận dạng giọng nói $langName$size để tạo phụ đề tự động?\n\n" +
                    "(Ngôn ngữ được đoán theo tiêu đề. Nếu sai, chọn lại ở ô ngôn ngữ rồi bấm Xem.)",
                "Tải và dùng",
                "Xem không lồng tiếng"
            )
            if (!ok) {
                playPlain("Video không có phụ đề.")
                return
            }
        }
        setStatus("Video không có phụ đề (NewPipe trả về ${data.subs.size} phụ đề) — dùng nhận dạng giọng nói, đoán là $langName.")
        if (lang != "vi" && !checkTranslatable(lang, langName)) return

        val gen = ++sttGen
        for (p in listOf(vadPack, sttPack)) {
            if (store.isInstalled(p.id)) continue
            setStatus("Đang tải ${p.name}…")
            var lastMb = -1L
            store.install(p) { bytes ->
                val mb = bytes / 1_048_576L
                if (mb != lastMb) {
                    lastMb = mb
                    runOnUiThread { if (gen == sttGen) setStatus("Đang tải ${p.name}: $mb MB…") }
                }
            }
        }

        setStatus("Đang nạp mô hình nhận dạng giọng nói…")
        val engine = withContext(Dispatchers.Default) {
            SttEngine.create(store.dir(sttPack.id), File(store.dir(vadPack.id), "silero_vad.onnx"))
        }
        val langId = if (reaction) LangId() else null

        val live = CopyOnWriteArrayList<Cue>()
        cues = live
        processedMs = 0
        sttStarted = false
        autoPaused = false
        sttDone = false
        sttActive = true
        if (lang != "vi") translator.prepare(lang)
        setStatus("Video không có phụ đề — đang nhận dạng giọng nói ($langName). Video sẽ phát sau vài giây." + ttsNote())

        var lastShown = -1L
        try {
            // Giải mã + nhận dạng nặng CPU nên chạy ngoài luồng chính
            withContext(Dispatchers.Default) {
              engine.run(
                audioUrl,
                onProgress = { ms ->
                    processedMs = ms
                    val bucket = ms / 5_000
                    if (bucket != lastShown && ms < Long.MAX_VALUE / 8) {
                        lastShown = bucket
                        handler.post {
                            if (gen == sttGen && sttActive && !sttDone) {
                                setStatus("Đang nhận dạng giọng nói ($langName): ${fmtTime(ms)}" +
                                    if (autoPaused) " · tạm dừng chờ nhận dạng" else "")
                            }
                        }
                    }
                },
                onSegment = { startMs, endMs, raw ->
                    val text = normalizeStt(raw, lang)
                    if (text.isNotBlank()) {
                        val cue = Cue(startMs, maxOf(endMs, startMs + 800), text)
                        if (langId != null && text.length >= 12) {
                            val d = langId.detect(text).substringBefore('-').lowercase()
                            if (d != "und" && d != lang) cue.kind = Kind.SKIP
                        }
                        if (cue.kind == Kind.SPEECH) {
                            cue.vi = if (lang == "vi") text else translator.translate(text)
                        }
                        live.add(cue)
                    }
                }
              )
            }
            if (gen == sttGen) setStatus("Đã nhận dạng và dịch xong · lồng tiếng $langName → vi." + ttsNote())
        } finally {
            engine.close()
            langId?.close()
            if (gen == sttGen) sttDone = true
        }
    }

    private suspend fun askYesNo(message: String, yes: String, no: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(yes) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(no) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun normalizeStt(raw: String, lang: String): String {
        var t = raw.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        // Mô hình tiếng Anh xuất toàn chữ hoa -> đưa về chữ thường cho dịch tốt hơn
        if (lang == "en" && t.any { it.isLetter() } && t == t.uppercase()) {
            t = t.lowercase().replaceFirstChar { it.uppercase() }
        }
        return t
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- common

    private fun playPlain(reason: String) {
        setStatus("$reason Đang phát video bình thường.")
        player?.playWhenReady = true
    }

    /** Chế độ reaction: bỏ các câu có ngôn ngữ khác ngôn ngữ người nói (thường là lời bài hát/clip gốc). */
    private suspend fun filterReaction(list: List<Cue>, expected: String) {
        val langId = LangId()
        try {
            for (c in list) {
                if (c.kind != Kind.SPEECH || c.text.length < 12) continue
                val lang = langId.detect(c.text).substringBefore('-').lowercase()
                if (lang != "und" && lang != expected) c.kind = Kind.SKIP
            }
        } finally {
            langId.close()
        }
    }

    private fun ttsNote(): String =
        if (currentTtsId == SYSTEM_TTS && system.ready && !system.viSupported)
            "\n⚠ Máy chưa có giọng tiếng Việt. Vào Cài đặt → Chuyển văn bản thành giọng nói → tải gói tiếng Việt, hoặc tải giọng Piper trong \"Quản lý gói\"."
        else ""

    private fun prepareVideo(data: VideoData): Boolean {
        val p = player ?: return false
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val progressive = ProgressiveMediaSource.Factory(http)

        val source: MediaSource = when {
            data.videoUrl != null && data.audioUrl != null -> MergingMediaSource(
                progressive.createMediaSource(MediaItem.fromUri(data.videoUrl)),
                progressive.createMediaSource(MediaItem.fromUri(data.audioUrl))
            )
            data.videoUrl != null ->
                progressive.createMediaSource(MediaItem.fromUri(data.videoUrl))
            data.hlsUrl != null ->
                HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(data.hlsUrl))
            else -> return false
        }
        p.setMediaSource(source)
        p.playWhenReady = false
        p.prepare()
        return true
    }

    private fun resetState() {
        sttGen++
        sttActive = false
        sttDone = false
        sttStarted = false
        autoPaused = false
        processedMs = 0
        stopDub()
        cues = emptyList()
        lastSpoken = -1
        shownIdx = -1
        subView.text = ""
        titleView.text = ""
        player?.stop()
    }

    // ---------------------------------------------------------------- dubbing

    private fun onSpeakStarted() {
        speaking = true
        applyVolume()
    }

    private fun onSpeakEnded() {
        speaking = false
        applyVolume()
    }

    private fun speakVi(text: String, rate: Float) {
        val e = piper
        if (e != null) e.speak(text, rate) else system.speak(text, rate)
    }

    private fun stopDub() {
        system.stop()
        piper?.stop()
        speaking = false
        applyVolume()
    }

    /** Hạ âm lượng tiếng gốc khi giọng Việt đang đọc. */
    private fun applyVolume() {
        player?.volume =
            if (dubSwitch.isChecked && speaking) volSeek.progress / 100f else 1f
    }

    private fun findCue(pos: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= pos) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return if (ans >= 0 && pos <= cues[ans].endMs && cues[ans].kind != Kind.SKIP) ans else -1
    }

    /** Khi đang nhận dạng giọng nói: bắt đầu phát khi đã có đủ đoạn đầu, tạm dừng nếu video đuổi kịp phần đã nhận dạng. */
    private fun gateByStt(p: ExoPlayer) {
        if (!sttActive) return
        val pos = p.currentPosition
        if (!sttStarted) {
            if (processedMs >= START_MS || sttDone) {
                sttStarted = true
                p.playWhenReady = true
            }
            return
        }
        if (sttDone) {
            if (autoPaused) {
                autoPaused = false
                p.playWhenReady = true
            }
            return
        }
        if (autoPaused && p.playWhenReady) autoPaused = false   // người dùng tự bấm phát
        if (!autoPaused && p.playWhenReady && pos > processedMs - 1_500) {
            autoPaused = true
            p.playWhenReady = false
        } else if (autoPaused && processedMs - pos > RESUME_AHEAD_MS) {
            autoPaused = false
            p.playWhenReady = true
        }
    }

    private fun tick() {
        val p = player ?: return
        gateByStt(p)
        if (cues.isEmpty()) return
        val pos = p.currentPosition
        val idx = findCue(pos)

        if (idx != shownIdx) {
            shownIdx = idx
            subView.text = if (idx >= 0) {
                val c = cues[idx]
                if (c.kind == Kind.SPEECH) (c.vi ?: "…") + "\n\n" + c.text else (c.vi ?: "")
            } else ""
        }

        if (!dubSwitch.isChecked || !p.isPlaying || idx < 0 || idx == lastSpoken) return
        val cue = cues[idx]
        if (cue.kind != Kind.SPEECH) return          // hát/nhạc: chỉ hiện nhãn, không đọc
        val vi = cue.vi ?: return
        lastSpoken = idx

        // Thời gian còn lại tới câu kế tiếp -> tăng tốc đọc nếu câu dịch dài
        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: (cue.endMs + 1_500)
        val availSec = ((nextStart - pos) / 1000f).coerceAtLeast(1f)
        val needSec = vi.length / CHARS_PER_SEC
        speakVi(vi, (needSec / availSec).coerceIn(1f, 2f))
    }

    companion object {
        private const val SYSTEM_TTS = "system"
        private const val SYSTEM_TTS_NAME = "Hệ thống (mặc định)"
        private const val TICK_MS = 150L
        private const val START_AFTER = 14        // phát khi đã dịch xong ~15 câu đầu
        private const val START_MS = 15_000L      // nhận dạng: phát khi đã xử lý ~15 giây đầu
        private const val RESUME_AHEAD_MS = 8_000L
        private const val CHARS_PER_SEC = 14f     // ước lượng tốc độ đọc tiếng Việt ở 1.0x
        private val REACTION_TITLE = Regex("""(?i)react|리액션|リアクション|phản ứng""")

        private fun defaultLangOptions(): List<Pair<String?, String>> =
            listOf(null to "Tự động", "en" to "English", "ko" to "한국어")
    }
}
