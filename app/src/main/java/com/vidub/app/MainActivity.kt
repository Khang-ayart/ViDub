package com.vidub.app

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
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
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var subView: TextView
    private lateinit var dubSwitch: SwitchCompat
    private lateinit var langSpinner: Spinner
    private lateinit var volSeek: SeekBar

    private var player: ExoPlayer? = null
    private lateinit var dubber: Dubber
    private val translator = TranslateManager()
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null

    private var cues: List<Cue> = emptyList()
    private var lastSpoken = -1
    private var shownIdx = -1
    private var speaking = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        playerView = findViewById(R.id.playerView)
        titleView = findViewById(R.id.titleView)
        statusView = findViewById(R.id.statusView)
        subView = findViewById(R.id.subView)
        dubSwitch = findViewById(R.id.dubSwitch)
        langSpinner = findViewById(R.id.langSpinner)
        volSeek = findViewById(R.id.volSeek)

        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Tự động", "English", "한국어")
        )
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

        dubber = Dubber(
            this,
            speakStarted = { speaking = true; applyVolume() },
            speakEnded = { speaking = false; applyVolume() }
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

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        player?.pause()
        stopDub()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        translator.close()
        dubber.shutdown()
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

                if (!prepareVideo(data)) {
                    setStatus("Không lấy được luồng video (video giới hạn tuổi, riêng tư hoặc YouTube đã đổi). Thử cập nhật NewPipeExtractor.")
                    return@launch
                }

                val pref = when (langSpinner.selectedItemPosition) {
                    1 -> "en"
                    2 -> "ko"
                    else -> null
                }
                val track = Subtitles.pick(data.subs, pref)
                if (track == null) {
                    setStatus("Video không có phụ đề nên chưa lồng tiếng được. Đang phát video bình thường.")
                    player?.playWhenReady = true
                    return@launch
                }

                setStatus("Đang tải phụ đề (${track.lang}${if (track.auto) ", tự động" else ""})…")
                val parsed = withContext(Dispatchers.IO) { Subtitles.load(track) }
                if (parsed.isEmpty()) {
                    setStatus("Không đọc được phụ đề của video này. Đang phát video bình thường.")
                    player?.playWhenReady = true
                    return@launch
                }
                cues = parsed

                val code = track.lang.substringBefore('-').lowercase()
                if (code == "vi") {
                    cues.forEach { it.vi = it.text }
                    setStatus("Có phụ đề tiếng Việt sẵn · lồng tiếng." + ttsNote())
                    player?.playWhenReady = true
                    return@launch
                }

                val source = TranslateLanguage.fromLanguageTag(code)
                if (source == null) {
                    setStatus("Chưa hỗ trợ dịch từ ngôn ngữ \"${track.lang}\". Đang phát video bình thường.")
                    player?.playWhenReady = true
                    return@launch
                }

                setStatus("Đang chuẩn bị gói dịch ${track.lang} → vi (lần đầu cần tải khoảng 30MB)…")
                translator.prepare(source)

                // Dịch lần lượt từ đầu; phát video khi đã dịch được vài câu đầu
                var started = false
                for ((i, cue) in cues.withIndex()) {
                    cue.vi = translator.translate(cue.text)
                    if (!started && (i >= START_AFTER || i == cues.lastIndex)) {
                        started = true
                        player?.playWhenReady = true
                    }
                    if (i % 10 == 0) setStatus("Đang dịch phụ đề ${i + 1}/${cues.size}…")
                }
                setStatus("Đã dịch xong · lồng tiếng ${track.lang} → vi." + ttsNote())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Lỗi: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun ttsNote(): String =
        if (dubber.ready && !dubber.viSupported)
            "\n⚠ Máy chưa có giọng tiếng Việt. Vào Cài đặt → Chuyển văn bản thành giọng nói → tải gói tiếng Việt."
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
        stopDub()
        cues = emptyList()
        lastSpoken = -1
        shownIdx = -1
        subView.text = ""
        titleView.text = ""
        player?.stop()
    }

    // ---------------------------------------------------------------- dubbing

    private fun stopDub() {
        dubber.stop()
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
        return if (ans >= 0 && pos <= cues[ans].endMs) ans else -1
    }

    private fun tick() {
        val p = player ?: return
        if (cues.isEmpty()) return
        val pos = p.currentPosition
        val idx = findCue(pos)

        if (idx != shownIdx) {
            shownIdx = idx
            subView.text = if (idx >= 0) {
                val c = cues[idx]
                (c.vi ?: "…") + "\n\n" + c.text
            } else ""
        }

        if (!dubSwitch.isChecked || !p.isPlaying || idx < 0 || idx == lastSpoken) return
        val cue = cues[idx]
        val vi = cue.vi ?: return
        lastSpoken = idx

        // Thời gian còn lại tới câu kế tiếp -> tăng tốc đọc nếu câu dịch dài
        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: (cue.endMs + 1_500)
        val availSec = ((nextStart - pos) / 1000f).coerceAtLeast(1f)
        val needSec = vi.length / CHARS_PER_SEC
        dubber.speak(vi, (needSec / availSec).coerceIn(1f, 2f))
    }

    companion object {
        private const val TICK_MS = 150L
        private const val START_AFTER = 14        // phát khi đã dịch xong ~15 câu đầu
        private const val CHARS_PER_SEC = 14f     // ước lượng tốc độ đọc tiếng Việt ở 1.0x
    }
}
