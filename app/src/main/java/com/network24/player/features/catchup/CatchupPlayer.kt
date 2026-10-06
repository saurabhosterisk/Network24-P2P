package com.network24.player.features.catchup

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.Fmt
import com.network24.player.features.player.manager.PlayerManager
import org.json.JSONArray

/**
 * Catch-up player in the app's look. While a recording loads: a preloader with the show's name. Controls: a slider
 * over the whole show (move it to any point), back / forward, play-pause and "i" with the show's description and
 * "Start over"; the focused control says what it does. It goes on where the viewer left the show last time, and
 * when a show ends the channel's next recorded show follows.
 *
 * The panel's recording link starts at a whole minute. When the player can seek inside a recording it moves
 * exactly; when it cannot, a jump asks for the recording again from that minute (then the jumps are one minute).
 */
@OptIn(UnstableApi::class)
class CatchupPlayerActivity : AppCompatActivity() {
    private val d by lazy { resources.displayMetrics.density * minOf(1f, resources.displayMetrics.let { it.widthPixels / it.density / 960f }) }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val handler = Handler(Looper.getMainLooper())

    private val bg = Color.parseColor("#08090C")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")

    private class Item(val title: String, val desc: String, val start: Long, val end: Long) { val length get() = end - start }

    private lateinit var shows: List<Item>
    private var idx = 0
    private val show get() = shows[idx]
    private var streamId = 0
    private var channel = ""
    private var logo = ""

    private var player: ExoPlayer? = null
    /** Where in the show the current stream began (a whole minute), ms from the show's start. */
    private var base = 0L
    /** A position to move to as soon as the stream can seek (resume, slider, jumps). */
    private var pendingSeek = -1L
    private var seekable = false
    private var dragging = false

    private lateinit var view: PlayerView
    private lateinit var loader: LinearLayout
    private lateinit var loaderText: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var slider: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var btnBack: FrameLayout
    private lateinit var btnPlay: ImageView
    private lateinit var btnFwd: FrameLayout
    private lateinit var btnInfo: TextView
    private lateinit var hint: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoStart: TextView
    private lateinit var upNext: TextView
    private val stepLabels = mutableListOf<TextView>()

    private val hideControls = Runnable { setControls(false) }
    private val tick = object : Runnable {
        override fun run() { refreshTime(); savePosition(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars()); it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // the live player must not keep a second connection open while a recording plays
        runCatching { PlayerManager.stop() }
        streamId = intent.getIntExtra(EXTRA_STREAM, 0)
        channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        logo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        shows = runCatching {
            val a = JSONArray(intent.getStringExtra(EXTRA_SHOWS))
            (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.optString("title"), it.optString("desc"), it.optLong("start"), it.optLong("end")) }
        }.getOrDefault(emptyList()).filter { it.end > it.start }
        if (shows.isEmpty() || streamId == 0) { finish(); return }
        build()
        onBackPressedDispatcher.addCallback(this) {
            when {
                info.visibility == View.VISIBLE -> closeInfo()
                else -> finish()
            }
        }
        startShow(0, resume = true)
    }

    // ------------------------------------------------------------------------------------------------ look
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(this@CatchupPlayerActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius) }

    /** Focus: a white ring and slightly larger; the line under the buttons says what the focused one does. */
    private fun control(v: View, label: () -> String, radius: Float, ringColor: Int = Color.WHITE, onClick: () -> Unit) {
        v.isFocusable = true; v.isClickable = true
        val ring = GradientDrawable().apply { cornerRadius = dpf(radius); setStroke(dp(3), ringColor); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(120).start()
            view.foreground = if (has) ring else null
            if (has) hint.text = label()
        }
        v.setOnClickListener { onClick(); showControls() }
    }

    private fun stepButton(icon: Int, forward: Boolean) = FrameLayout(this).apply {
        background = shape(0x26FFFFFF, 28f)
        addView(ImageView(this@CatchupPlayerActivity).apply { setImageResource(icon); setColorFilter(textMain) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        val n = text("10", 10f, textMain, 800).apply { gravity = Gravity.CENTER }
        stepLabels += n
        addView(n, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(3) })
        control(this, { (if (forward) "Forward " else "Back ") + stepName() }, 28f) { jump(if (forward) step() else -step()) }
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        // preloader: spinner, the show's name and channel
        loader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xCC08090C.toInt()) }
        loader.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(logo.takeIf { it.isNotBlank() }) }, LinearLayout.LayoutParams(dp(110), dp(52)).apply { bottomMargin = dp(18) })
        loader.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(accent) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        loaderText = text("", 18f, textMain, 700).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(18), dp(24), 0) }
        loader.addView(loaderText, LinearLayout.LayoutParams(-1, -2))
        loader.addView(text("Loading the recording…", 13f, textSub, 500).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        root.addView(loader, FrameLayout.LayoutParams(-1, -1))

        // top: channel logo, show name, channel and when it aired
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(48), dp(28), dp(48), dp(48))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }
        topBar.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(4), dp(6), dp(4)); background = shape(0x99000000.toInt(), 8f); load(logo.takeIf { it.isNotBlank() })
        }, LinearLayout.LayoutParams(dp(72), dp(42)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        titleView = text("", 22f, textMain, 800); names.addView(titleView)
        subView = text("", 13f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) }; names.addView(subView)
        topBar.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom: slider with times, then the buttons and what the focused one does
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(56), dp(48), dp(28)); clipChildren = false; clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0x99000000.toInt(), 0x00000000))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsed = text("0:00", 14f, textMain, 700).apply { fontFeatureSettings = "tnum" }
        total = text("0:00", 14f, textSub, 700).apply { fontFeatureSettings = "tnum" }
        slider = SeekBar(this).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(accent); progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
            thumbTintList = ColorStateList.valueOf(Color.WHITE); splitTrack = false; keyProgressIncrement = 1
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    dragging = true
                    elapsed.text = clock(show.length * p / 1000)
                    // the remote moves the slider in steps; the jump happens when it rests for a moment
                    handler.removeCallbacks(commitSlider); handler.postDelayed(commitSlider, 900)
                    showControls()
                }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) { handler.removeCallbacks(commitSlider); commitSlider.run() }
            })
        }
        control(slider, { "Move to any point of the show · LEFT / RIGHT, then wait" }, 12f) {}
        slider.setOnClickListener(null)
        bar.addView(elapsed, LinearLayout.LayoutParams(dp(78), -2))
        bar.addView(slider, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(total, LinearLayout.LayoutParams(dp(78), -2).apply { marginStart = dp(4) })
        total.gravity = Gravity.END
        controls.addView(bar)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        btnBack = stepButton(R.drawable.ic_cu_back, false)
        btnPlay = ImageView(this).apply { setImageResource(R.drawable.ic_pause); setColorFilter(bg); setPadding(dp(16), dp(16), dp(16), dp(16)); background = shape(Color.WHITE, 34f) }
        // the white button gets the brand-violet ring (a white ring does not show on it)
        control(btnPlay, { if (player?.isPlaying == true) "Pause" else "Play" }, 34f, accent) { togglePlay() }
        btnFwd = stepButton(R.drawable.ic_cu_forward, true)
        btnInfo = text("i", 22f, textMain, 800).apply { gravity = Gravity.CENTER; background = shape(0x26FFFFFF, 28f) }
        control(btnInfo, { "About this show" }, 28f) { openInfo() }
        buttons.addView(btnBack, LinearLayout.LayoutParams(dp(56), dp(56)))
        buttons.addView(btnPlay, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginStart = dp(28) })
        buttons.addView(btnFwd, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(28) })
        buttons.addView(btnInfo, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(56) })
        controls.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        hint = text("", 13f, textSub, 600).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        controls.addView(hint, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        listOf<View>(slider, btnBack, btnPlay, btnFwd, btnInfo).forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        listOf<View>(btnBack, btnPlay, btnFwd, btnInfo).forEach { it.nextFocusUpId = slider.id }
        slider.nextFocusDownId = btnPlay.id
        btnBack.nextFocusLeftId = btnBack.id; btnInfo.nextFocusRightId = btnInfo.id

        // "i": the show's name, when it aired, how long, what it is about; Start over
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(32), dp(32), dp(28)); background = shape(0xF214161B.toInt(), 18f); visibility = View.GONE
        }
        info.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; load(logo.takeIf { it.isNotBlank() }) }, LinearLayout.LayoutParams(dp(96), dp(44)))
        infoTitle = text("", 24f, textMain, 800, lines = 2).apply { setPadding(0, dp(16), 0, 0) }; info.addView(infoTitle)
        infoMeta = text("", 13f, textSub, 600).apply { setPadding(0, dp(8), 0, 0) }; info.addView(infoMeta)
        infoDesc = text("", 15f, Color.parseColor("#D9F2F3F5"), 500, lines = 30).apply { setLineSpacing(0f, 1.25f) }
        info.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(infoDesc) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        val infoButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0) }
        infoStart = text("Start over", 15f, bg, 700).apply { gravity = Gravity.CENTER; setPadding(dp(22), dp(12), dp(22), dp(12)); background = shape(Color.WHITE, 10f) }
        val infoClose = text("Close", 15f, textMain, 700).apply { gravity = Gravity.CENTER; setPadding(dp(22), dp(12), dp(22), dp(12)); background = shape(0x26FFFFFF, 10f) }
        infoButtons.addView(infoStart); infoButtons.addView(infoClose, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        info.addView(infoButtons)
        root.addView(info, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.42f).toInt(), -1, Gravity.END).apply { setMargins(0, dp(24), dp(24), dp(24)) })
        listOf(infoStart to { closeInfo(); seekTo(0) }, infoClose to { closeInfo() }).forEach { (v, act) ->
            val ring = GradientDrawable().apply { cornerRadius = dpf(10f); setStroke(dp(3), if (v === infoStart) accent else Color.WHITE); setColor(Color.TRANSPARENT) }
            v.isFocusable = true; v.isClickable = true
            v.setOnFocusChangeListener { x, has -> x.foreground = if (has) ring else null }
            v.setOnClickListener { act() }
        }

        upNext = text("", 15f, textMain, 700).apply { setPadding(dp(20), dp(14), dp(20), dp(14)); background = shape(0xE614161B.toInt(), 12f); visibility = View.GONE }
        root.addView(upNext, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(48), dp(190)) })
        setContentView(root)
    }

    // ------------------------------------------------------------------------------------------------ playback
    private fun startShow(i: Int, resume: Boolean) {
        idx = i
        titleView.text = show.title
        subView.text = "$channel  ·  Aired ${Fmt.day(show.start)} ${Fmt.clock(show.start)}  ·  ${clock(show.length)}"
        loaderText.text = show.title
        total.text = clock(show.length)
        // one press on the slider = about 30 seconds of the show
        slider.keyProgressIncrement = (30_000L * 1000 / show.length).toInt().coerceIn(1, 100)
        upNext.visibility = View.GONE
        // where the viewer left this show last time (not in its first or last minute)
        val saved = if (resume) prefs().getLong(key(), 0L).takeIf { it in 60_000..(show.length - 90_000) } ?: 0L else 0L
        play(saved)
        setControls(true)
        btnPlay.requestFocus()
        if (saved > 0) hint.text = "Resumed at ${clock(saved)} · About this show > Start over to begin again"
    }

    /** Starts the recording at [from] ms into the show: the stream begins at that whole minute, then moves on to it. */
    private fun play(from: Long) {
        base = (from / 60_000) * 60_000
        pendingSeek = if (from - base > 1000) from else -1L
        seekable = false
        updateStepLabels()
        val p = player ?: ExoPlayer.Builder(this, StreamDataSourceFactory.createRenderersFactory(this))
            .setMediaSourceFactory(StreamDataSourceFactory.createMediaSourceFactory()).build().also { pl ->
                player = pl; view.player = pl
                pl.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        loader.visibility = if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) View.VISIBLE else View.GONE
                        if (state == Player.STATE_READY) {
                            seekable = pl.isCurrentMediaItemSeekable
                            updateStepLabels()
                            if (pendingSeek >= 0 && seekable) { pl.seekTo((pendingSeek - base).coerceAtLeast(0)); pendingSeek = -1 }
                        }
                        if (state == Player.STATE_ENDED) ended()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                        if (btnPlay.isFocused) hint.text = if (isPlaying) "Pause" else "Play"
                        if (isPlaying) scheduleHide() else showControls(keep = true)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        loader.visibility = View.GONE
                        hint.text = "This recording could not be played right now. Please try another show or try again later."
                        showControls(keep = true)
                    }
                })
            }
        loader.visibility = View.VISIBLE
        val url = Web24Api(this).catchupUrl(streamId, Web24Api.Programme(show.title, show.desc, show.start + base, show.end, true))
        p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); p.playWhenReady = true
        handler.removeCallbacks(tick); handler.post(tick)
    }

    /** Position in the show, ms. */
    private fun position(): Long = (base + (player?.currentPosition ?: 0L)).coerceIn(0, show.length)

    private fun seekTo(target: Long) {
        val t = target.coerceIn(0, show.length - 1000)
        val p = player ?: return
        val inStream = t - base
        if (seekable && inStream >= 0) p.seekTo(inStream) else play(t)
        refreshTime()
    }

    /** Back / forward: 10 s when the recording can seek, otherwise a minute (the link starts at whole minutes). */
    private fun step() = if (seekable) 10_000L else 60_000L
    private fun stepName() = if (seekable) "10 seconds" else "1 minute"
    private fun updateStepLabels() = stepLabels.forEach { it.text = if (seekable) "10" else "60" }

    private fun jump(by: Long) = seekTo(position() + by)

    private val commitSlider = Runnable {
        dragging = false
        seekTo(show.length * slider.progress / 1000)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) seekTo(0); p.play() }
    }

    private fun refreshTime() {
        if (!::slider.isInitialized || dragging) return
        val pos = position()
        elapsed.text = clock(pos)
        slider.progress = if (show.length > 0) (pos * 1000 / show.length).toInt() else 0
    }

    /** End of a show: it is done (no resume point); the channel's next recorded show follows after a few seconds. */
    private fun ended() {
        prefs().edit().remove(key()).apply()
        if (idx + 1 >= shows.size) { hint.text = "That was the last recorded show of this channel for now."; showControls(keep = true); return }
        val next = shows[idx + 1]
        var left = 5
        upNext.visibility = View.VISIBLE
        val count = object : Runnable {
            override fun run() {
                if (upNext.visibility != View.VISIBLE) return
                if (left == 0) { startShow(idx + 1, resume = false); return }
                upNext.text = "Up next  ·  ${next.title}  ·  in $left s   (BACK to stay)"
                left--; handler.postDelayed(this, 1000)
            }
        }
        handler.post(count)
    }

    // ------------------------------------------------------------------------------------------------ resume point
    private fun prefs() = getSharedPreferences("n24_catchup_pos", MODE_PRIVATE)
    private fun key() = "$streamId:${show.start}"
    private var savedAt = 0L

    private fun savePosition() {
        val now = System.currentTimeMillis()
        if (now - savedAt < 10_000 || player?.isPlaying != true) return
        savedAt = now
        val pos = position()
        val e = prefs().edit()
        if (pos > show.length - 120_000) e.remove(key()) else e.putLong(key(), pos)
        // keep the list small: points older than a week are dropped
        prefs().all.keys.filter { k -> k.substringAfter(':').toLongOrNull()?.let { now - it > 7 * 86_400_000L } == true }.forEach { e.remove(it) }
        e.apply()
    }

    // ------------------------------------------------------------------------------------------------ controls / info
    private fun setControls(show: Boolean) {
        handler.removeCallbacks(hideControls)
        val v = if (show) View.VISIBLE else View.GONE
        controls.visibility = v; topBar.visibility = v
        if (show) scheduleHide()
    }

    private fun showControls(keep: Boolean = false) {
        if (controls.visibility != View.VISIBLE) { setControls(true); btnPlay.requestFocus() }
        if (keep) handler.removeCallbacks(hideControls) else scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true && info.visibility != View.VISIBLE) handler.postDelayed(hideControls, 5000)
    }

    private fun openInfo() {
        infoTitle.text = show.title
        infoMeta.text = "$channel  ·  ${Fmt.day(show.start)} ${Fmt.clock(show.start)} – ${Fmt.clock(show.end)}  ·  ${clock(show.length)}"
        infoDesc.text = show.desc.ifBlank { "No description for this show." }
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        infoStart.requestFocus()
    }

    private fun closeInfo() {
        info.visibility = View.GONE
        btnInfo.requestFocus(); scheduleHide()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || info.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { jump(-step()); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jump(step()); showControls(); return true }
            KeyEvent.KEYCODE_INFO -> { openInfo(); return true }
            KeyEvent.KEYCODE_BACK -> if (upNext.visibility == View.VISIBLE) { upNext.visibility = View.GONE; showControls(keep = true); return true }
        }
        // controls hidden: LEFT / RIGHT jump straight away, any other key just brings the controls back
        if (controls.visibility != View.VISIBLE && event.keyCode != KeyEvent.KEYCODE_BACK) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> jump(-step())
                KeyEvent.KEYCODE_DPAD_RIGHT -> jump(step())
            }
            showControls(); return true
        }
        if (controls.visibility == View.VISIBLE) scheduleHide()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && controls.visibility != View.VISIBLE && info.visibility != View.VISIBLE) { showControls(); return true }
        return super.dispatchTouchEvent(ev)
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onStop() { super.onStop(); savedAt = 0; savePosition(); player?.pause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); player?.release(); player = null; super.onDestroy() }

    companion object {
        const val EXTRA_STREAM = "stream"; const val EXTRA_CHANNEL = "channel"; const val EXTRA_LOGO = "logo"; const val EXTRA_SHOWS = "shows"
    }
}
