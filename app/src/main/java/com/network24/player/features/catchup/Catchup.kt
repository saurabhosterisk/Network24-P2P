package com.network24.player.features.catchup

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.features.discover.FeatureListActivity
import com.network24.player.features.discover.Fmt
import com.network24.player.features.discover.Row
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.reminders.Reminders
import kotlinx.coroutines.launch

/**
 * Catch-up TV: channels that keep recordings, then that channel's past shows by day. Select a past show to watch it
 * from the start (with pause, rewind and fast-forward); a future show sets a reminder; the show on now plays live.
 */
class CatchupActivity : FeatureListActivity() {
    private var channels: List<ChannelEntity> = emptyList()
    private var cur: ChannelEntity? = null
    private var guide: List<Web24Api.Programme> = emptyList()
    private var day = 0L
    private val backToChannels = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = showChannels() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backToChannels)
        b.btnBack.setOnClickListener { if (cur != null) showChannels() else finish() }
        loading(true)
        lifecycleScope.launch {
            channels = db.channelDao().getAll().filter { (it.tvArchive ?: 0) == 1 }.sortedBy { it.name }
            loading(false)
            val want = intent.getIntExtra(EXTRA_STREAM, 0)
            channels.firstOrNull { it.streamId == want }?.let { open(it) } ?: showChannels()
        }
    }

    private fun showChannels() {
        cur = null; backToChannels.isEnabled = false
        title("Catch-up TV", "Watch shows you missed - ${channels.size} channels keep recordings for a few days")
        chips(emptyList(), "") {}
        show(channels.map { ch ->
            Row("c${ch.streamId}", ch.name.orEmpty(), "Recordings of the last ${ch.tvArchiveDuration ?: 0} days", icon = ch.icon, badge = "Open") { open(ch) }
        }, "No channel with catch-up on your plan yet.")
    }

    private fun open(ch: ChannelEntity) {
        cur = ch; backToChannels.isEnabled = true
        title(ch.name.orEmpty(), "Select a past show to watch it from the start")
        loading(true); show(emptyList(), "")
        lifecycleScope.launch {
            guide = Web24Api(this@CatchupActivity).fullGuide(ch.streamId)
            loading(false)
            val now = System.currentTimeMillis()
            val days = guide.map { Fmt.startOfDay(it.start) }.distinct().sorted()
            day = Fmt.startOfDay(now).takeIf { it in days } ?: days.lastOrNull() ?: 0L
            chips(days.map { it.toString() to Fmt.day(it) }, day.toString()) { day = it.toLong(); render() }
            render()
        }
    }

    private fun render() {
        val ch = cur ?: return
        val now = System.currentTimeMillis()
        val keepMs = (ch.tvArchiveDuration ?: 0) * 86_400_000L
        val list = guide.filter { Fmt.startOfDay(it.start) == day }
        show(list.map { p ->
            val past = p.end <= now; val live = now in p.start until p.end
            val canPlay = past && (p.hasArchive || now - p.start < keepMs)
            val reminder = !past && !live && Reminders.has(this, ch.streamId, p.start)
            Row("${p.start}", p.title.ifBlank { "Untitled show" }, "${Fmt.clock(p.start)} – ${Fmt.clock(p.end)}", p.description.take(140), showIcon = false,
                lead = Fmt.clock(p.start),
                badge = when { live -> "LIVE"; canPlay -> "▶ Watch"; past -> "Not recorded"; reminder -> "Reminder set"; else -> "Remind me" },
                badgeColor = when { live -> Color.parseColor("#FF5252"); canPlay -> Color.parseColor("#4CAF50"); reminder -> Color.parseColor("#FFC107"); else -> 0 },
                progress = if (live) ((now - p.start) * 1000 / (p.end - p.start)).toInt() else -1) {
                when {
                    live -> play(listOf(ch), ch)
                    canPlay -> startActivity(Intent(this, CatchupPlayerActivity::class.java)
                        .putExtra(CatchupPlayerActivity.EXTRA_URL, Web24Api(this).catchupUrl(ch.streamId, p))
                        .putExtra(CatchupPlayerActivity.EXTRA_TITLE, p.title).putExtra(CatchupPlayerActivity.EXTRA_SUB, "${ch.name} · ${Fmt.day(p.start)} ${Fmt.clock(p.start)}"))
                    past -> toast("This show was not recorded.")
                    else -> { Reminders.toggle(this, ch.streamId, p.start, p.title, ch.name.orEmpty()); render() }
                }
            }
        }, "No TV guide for this day.")
        val nowIdx = list.indexOfFirst { now in it.start until it.end }.takeIf { it >= 0 } ?: list.indexOfLast { it.end <= now }
        if (nowIdx > 0) b.rvItems.scrollToPosition(nowIdx)
    }

    companion object { const val EXTRA_STREAM = "stream_id" }
}

/** Plays one recorded show with the standard controls (pause, seek, ±10 s). */
@OptIn(UnstableApi::class)
class CatchupPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // the live player must not keep a second connection open while a recording plays
        runCatching { PlayerManager.stop() }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply { useController = true; setShowNextButton(false); setShowPreviousButton(false); controllerShowTimeoutMs = 4000 }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val title = TextView(this).apply {
            text = (intent.getStringExtra(EXTRA_TITLE) ?: "") + "\n" + (intent.getStringExtra(EXTRA_SUB) ?: "")
            setTextColor(Color.WHITE); textSize = 18f; setShadowLayer(6f, 0f, 1f, Color.BLACK); setPadding(36, 28, 36, 0)
        }
        root.addView(title, FrameLayout.LayoutParams(-1, -2))
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> title.visibility = v })
        setContentView(root)
        val p = ExoPlayer.Builder(this, StreamDataSourceFactory.createRenderersFactory(this)).setMediaSourceFactory(StreamDataSourceFactory.createMediaSourceFactory())
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player = p
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                title.text = "This recording could not be played right now.\nPlease try another show or try again later."
                title.visibility = View.VISIBLE
            }
        })
        p.setMediaItem(MediaItem.fromUri(intent.getStringExtra(EXTRA_URL) ?: ""))
        p.prepare(); p.playWhenReady = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { player?.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player?.seekForward(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { view.showController(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }

    companion object { const val EXTRA_URL = "url"; const val EXTRA_TITLE = "title"; const val EXTRA_SUB = "sub" }
}
