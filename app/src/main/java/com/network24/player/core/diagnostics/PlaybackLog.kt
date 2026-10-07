package com.network24.player.core.diagnostics

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import com.network24.player.core.net.SpeedMonitor
import com.network24.player.features.player.manager.PlayerManager

/**
 * What the player did lately, for the Stream health screen: one sample a second for the last two minutes
 * (stream speed, seconds buffered ahead, buffering or not) and the last events (start, buffering, errors,
 * recovery). Kept while the app runs, so opening the screen already shows what happened before it.
 */
object PlaybackLog {

    enum class Kind { START, BUFFER, BUFFER_END, ERROR, RECOVERED, CHANNEL, INFO }

    data class Event(val at: Long, val kind: Kind, val text: String)

    /** mbps = stream speed then, aheadMs = video buffered ahead, buffering = the picture was waiting. */
    data class Sample(val at: Long, val mbps: Float, val aheadMs: Long, val buffering: Boolean, val dropped: Int)

    private const val MAX_EVENTS = 40
    const val MAX_SAMPLES = 120

    private val events = ArrayDeque<Event>()
    private val samples = ArrayDeque<Sample>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    /** Startup time of the current channel (open -> first picture moving), 0 while unknown. */
    @Volatile var lastStartupMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 1000L)
        }
    }

    /** Called when the player is created; sampling is a few field reads a second. */
    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    @Synchronized
    fun add(kind: Kind, text: String) {
        events.addLast(Event(System.currentTimeMillis(), kind, text))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun events(): List<Event> = events.toList()

    @Synchronized
    fun samples(): List<Sample> = samples.toList()

    /** Buffering events in the last [windowMs]. */
    @Synchronized
    fun bufferingsSince(windowMs: Long): Int {
        val from = System.currentTimeMillis() - windowMs
        return events.count { it.kind == Kind.BUFFER && it.at >= from }
    }

    /** A new channel: the graph starts again. */
    @Synchronized
    fun newChannel(name: String) {
        samples.clear()
        lastStartupMs = 0L
        add(Kind.CHANNEL, name)
    }

    @Synchronized
    private fun sample() {
        val p = PlayerManager.getExoPlayerOrNull() ?: return
        if (p.playbackState == Player.STATE_IDLE && !p.playWhenReady) return
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L).takeIf { p.bufferedPosition != C.TIME_UNSET } ?: 0L
        val buffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady && PlayerManager.hasEverStartedPlayback()
        val dropped = runCatching { p.videoDecoderCounters?.droppedBufferCount ?: 0 }.getOrDefault(0)
        samples.addLast(Sample(System.currentTimeMillis(), SpeedMonitor.getMbps().toFloat(), ahead, buffering, dropped))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }
}
