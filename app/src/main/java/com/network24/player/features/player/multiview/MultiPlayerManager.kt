package com.network24.player.features.player.multiview

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.network24.player.core.net.StreamDataSourceFactory

@OptIn(UnstableApi::class)
class MultiPlayerManager(
    private val context: Context,
    private val listener: Listener? = null
) {
    interface Listener {
        fun onLoading(slot: Int)
        fun onReady(slot: Int)
        fun onError(slot: Int, message: String)
    }

    private val players = arrayOfNulls<ExoPlayer>(4)
    private val urls = arrayOfNulls<String>(4)
    private val retryCounts = IntArray(4)
    private val maxAutoRetries = 3
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = arrayOfNulls<Runnable>(4)

    fun attach(slot: Int, playerView: PlayerView) {
        require(slot in 0..3)
        val player = players[slot] ?: createPlayer(slot).also { players[slot] = it }
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
    }

    private fun createPlayer(slot: Int): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1000, 2000)
            .build()

        return ExoPlayer.Builder(
            context.applicationContext,
            StreamDataSourceFactory.createSoftwareRenderersFactory(context)
        )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(StreamDataSourceFactory.createMediaSourceFactory())
            .build()
            .apply {
                playWhenReady = true
                volume = 0f

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> listener?.onLoading(slot)
                            Player.STATE_READY -> {
                                retryCounts[slot] = 0
                                listener?.onReady(slot)
                            }
                            Player.STATE_ENDED -> listener?.onError(slot, "Stream ended")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val url = urls[slot]
                        if (url.isNullOrBlank()) {
                            listener?.onError(slot, "Playback error")
                            return
                        }

                        val httpError = findHttpError(error)
                        if (httpError != null) {
                            if (httpError.first == 403 && retryCounts[slot] < maxAutoRetries) {
                                retryCounts[slot]++
                                scheduleRetry(slot, url, retryCounts[slot])
                            } else {
                                // Only the status code: the request path is
                                // /live/<user>/<pass>/..., which must never be
                                // shown on screen.
                                listener?.onError(
                                    slot,
                                    "Channel not available (HTTP ${httpError.first})"
                                )
                            }
                            return
                        }

                        if (retryCounts[slot] < maxAutoRetries) {
                            retryCounts[slot]++
                            listener?.onLoading(slot)
                            playerRetry(slot, url)
                        } else {
                            listener?.onError(
                                slot,
                                "${error.errorCodeName}: ${(error.cause?.message ?: error.message ?: "Playback error").take(160)}"
                            )
                        }
                    }
                })
            }
    }

    private fun scheduleRetry(slot: Int, url: String, attempt: Int) {
        retryRunnable[slot]?.let(mainHandler::removeCallbacks)

        // Give the server/session a short breathing window before reconnecting.
        val delayMs = 1500L * attempt
        listener?.onLoading(slot)

        val runnable = Runnable {
            if (urls[slot] == url && players[slot] != null) {
                playerRetry(slot, url)
            }
        }
        retryRunnable[slot] = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun playerRetry(slot: Int, url: String) {
        val player = players[slot] ?: return
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    private fun findHttpError(error: PlaybackException): Pair<Int, String>? {
        var current: Throwable? = error
        while (current != null) {
            if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return current.responseCode to (current.dataSpec.uri.host ?: "")
            }
            current = current.cause
        }
        return null
    }

    fun play(slot: Int, url: String) {
        require(slot in 0..3)
        retryRunnable[slot]?.let(mainHandler::removeCallbacks)
        retryRunnable[slot] = null

        val player = players[slot] ?: createPlayer(slot).also { players[slot] = it }

        if (urls[slot] == url && player.playbackState != Player.STATE_IDLE) {
            player.playWhenReady = true
            player.play()
            return
        }

        urls[slot] = url
        retryCounts[slot] = 0
        listener?.onLoading(slot)
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    fun setAudioFocus(slot: Int) {
        require(slot in 0..3)
        for (i in 0..3) {
            players[i]?.volume = if (i == slot) 1f else 0f
        }
    }

    fun clear(slot: Int) {
        require(slot in 0..3)
        retryRunnable[slot]?.let(mainHandler::removeCallbacks)
        retryRunnable[slot] = null
        players[slot]?.stop()
        players[slot]?.clearMediaItems()
        urls[slot] = null
        retryCounts[slot] = 0
    }

    fun getPlayer(slot: Int): ExoPlayer? = if (slot in 0..3) players[slot] else null

    /**
     * ExoPlayer keeps playing when its screen goes away, so without this the
     * 4 windows kept streaming (and the focused one kept playing sound) after
     * Home was pressed, using up the account's connections in the background.
     */
    fun pauseAll() {
        for (i in 0..3) players[i]?.pause()
    }

    fun resumeAll() {
        for (i in 0..3) {
            if (urls[i] != null) players[i]?.play()
        }
    }

    fun release() {
        for (i in 0..3) {
            retryRunnable[i]?.let(mainHandler::removeCallbacks)
            retryRunnable[i] = null
            players[i]?.release()
            players[i] = null
            urls[i] = null
            retryCounts[i] = 0
        }
    }
}
