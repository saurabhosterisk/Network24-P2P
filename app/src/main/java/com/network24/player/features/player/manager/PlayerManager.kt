package com.network24.player.features.player.manager


import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.player.state.PlayerState

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.lang.ref.WeakReference



@Suppress("UnsafeOptInUsageError")
object PlayerManager {



    private var exoPlayer: ExoPlayer? = null


    private var currentUrl: String? = null


    private var lastStreamUrl: String? = null


    private var currentPlayerView: PlayerView? = null

    private var playbackSessionId = 0

    private var streamGeneration = 0


    /**
     * A Media3 error from the previous item can arrive while a rapid channel
     * switch is replacing the media item. Keep enough request state to ignore
     * those stale callbacks instead of treating the newly selected channel as
     * down.
     */
    private var isReplacingMediaItem = false

    private var streamRequestedAtMs = 0L


    /**
     * WeakReference prevents Activity memory leak
     */
    private var ownerActivityRef:
            WeakReference<Activity>? = null





    private var lifecycleCallbacksRegistered =
        false

    private var processLifecycleObserverRegistered = false

    /**
     * IPTV accounts typically allow only 1-2 concurrent connections. Leaving
     * the player merely paused (never stopped) when nobody is showing it
     * keeps the server-side stream connection open, so a later playback
     * attempt can fail with "max connections reached" even though the user
     * believes they closed the channel. This job releases the connection
     * after a short grace period if no screen re-attaches the player, and
     * [processLifecycleObserver] releases it immediately when the whole app
     * leaves the foreground.
     */
    private var idleReleaseJob: Job? = null

    // Bumped on every attach() so a pending idle-release can tell whether a
    // new screen took over the player while it was waiting — currentPlayerView
    // alone isn't reliable here since several screens (ChannelListActivity,
    // EpgChannelListActivity) never call detach() on their way out, leaving
    // it pointing at a stale view instead of null.
    private var attachGeneration = 0

    private const val IDLE_RELEASE_GRACE_MS = 20_000L

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // Every activity that could own the player has stopped — the
            // app itself left the foreground (Home button, task switch, or
            // fully closed). Free the connection now instead of leaving it
            // paused-but-open.
            if (exoPlayer != null) {
                android.util.Log.d(
                    "N24_CONNECTION",
                    "App backgrounded; releasing player/connection"
                )
            }
            cancelIdleRelease()
            release()
        }
    }

    private fun ensureProcessLifecycleObserver() {
        if (processLifecycleObserverRegistered) return
        processLifecycleObserverRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        val scheduledGeneration = attachGeneration
        idleReleaseJob = playerScope.launch {
            delay(IDLE_RELEASE_GRACE_MS)
            // Nobody re-attached a player screen within the grace period —
            // release so the connection isn't held open behind a screen
            // (Dashboard, Settings, VOD, ...) that never shows it.
            if (attachGeneration == scheduledGeneration && exoPlayer != null) {
                android.util.Log.d(
                    "N24_CONNECTION",
                    "Idle grace period expired; releasing player/connection"
                )
                release()
            }
        }
    }

    private fun cancelIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
    }




    private var preservePlaybackThroughFullscreenReturn =
        false





    // Diagnostics

    private var rebufferCount = 0

    private var bufferingStartedAtMs = 0L

    private var totalBufferingMs = 0L

    private var hasStartedPlaying = false

    private var bufferingSessionActive = false

    private var playbackActuallyStarted = false

    private var lastError:
            PlaybackException? = null


    enum class StreamErrorType {
        NONE,
        NETWORK,
        SOURCE,
        UNKNOWN
    }

    private var streamErrorType =
        StreamErrorType.NONE




    private var lastPlaybackState =
        Player.STATE_IDLE






    // Recovery

    private var liveRecoveryJob:
            Job? = null


    private var liveRecoveryStartedAtMs =
        0L


    private var liveRecoveryAttempt =
        0

    private var recoveryStatusListener:
            ((Int) -> Unit)? = null

    private var recoveryRecoveredListener:
            (() -> Unit)? = null



    private val recoveryFailedListeners =
        mutableSetOf<() -> Unit>()





    private val playerScope =
        CoroutineScope(
            SupervisorJob()
                    +
                    Dispatchers.Main.immediate
        )





    private const val LIVE_RECOVERY_WINDOW_MS =
        30000L

    private const val FAST_LIVE_RECOVERY_WINDOW_MS =
        15000L

    private const val STREAM_SWITCH_SETTLE_MS =
        750L

    private const val LIVE_TARGET_OFFSET_MS =
        10000L





    // IPTV routes can have short loss/jitter bursts even when the WiFi link is
    // healthy. Keep enough media queued to absorb those bursts. Playback still
    // starts after two seconds; the larger minimum/max buffer is filled while
    // playing and does not force a 50-second startup delay.
    //
    // minBufferMs is kept below LIVE_TARGET_OFFSET_MS: on IPTV channels with a
    // short HLS DVR window, wanting more buffer than exists between the
    // playback position and the live edge makes BehindLiveWindowException
    // more likely instead of less.
    private val loadControl =

        DefaultLoadControl.Builder()

            .setBufferDurationsMs(
                8_000,
                30_000,
                2_000,
                3_000
            )

            .build()

    // ExoPlayer's default AdaptiveTrackSelection needs 10s of continuously
    // buffered media before it will step up to a higher-bitrate rendition
    // (media3's own default is DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS).
    // minBufferMs above is intentionally kept below that (to stay compatible
    // with LIVE_TARGET_OFFSET_MS), so on multi-bitrate channels that default
    // threshold was rarely reachable and playback got stuck on its initial
    // (often lowest) rendition — looking permanently soft/blurry even on a
    // good connection. Lower the threshold to fit inside the buffer budget
    // we actually maintain.
    private const val MIN_DURATION_FOR_QUALITY_INCREASE_MS = 4_000

    private fun createTrackSelector(context: Context): DefaultTrackSelector {
        val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
            MIN_DURATION_FOR_QUALITY_INCREASE_MS,
            AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
            AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
            AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
        )
        return DefaultTrackSelector(context, adaptiveTrackSelectionFactory)
    }







    private fun ensureActivityLifecycleCallbacks(
        context: Context
    ) {


        if (
            lifecycleCallbacksRegistered
        ) return

        ensureProcessLifecycleObserver()




        val application =
            context.applicationContext
                    as? Application
                ?: return





        application.registerActivityLifecycleCallbacks(

            object :
                Application.ActivityLifecycleCallbacks {



                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: android.os.Bundle?
                ) = Unit






                override fun onActivityStarted(
                    activity: Activity
                ) = Unit






                override fun onActivityResumed(
                    activity: Activity
                ) {


                    if (

                        activity.javaClass.name.endsWith(
                            "features.live.activity.EpgChannelListActivity"
                        )

                        &&

                        !currentUrl.isNullOrBlank()

                    ) {


                        runCatching {


                            attachFromEpgReflection(
                                activity
                            )

                        }
                    }
                }






                override fun onActivityPaused(
                    activity: Activity
                ) = Unit






                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: android.os.Bundle
                ) = Unit






                override fun onActivityStopped(
                    activity: Activity
                ) {


                    val owner =
                        ownerActivityRef
                            ?.get()



                    if (
                        owner === activity
                    ) {

                        exoPlayer?.pause()

                        scheduleIdleRelease()

                        ownerActivityRef =
                            null
                    }
                }






                override fun onActivityDestroyed(
                    activity: Activity
                ) = Unit
            }
        )



        lifecycleCallbacksRegistered =
            true
    }

    private fun attachFromEpgReflection(
        activity: Activity
    ) {


        try {


            val bindingField =
                activity.javaClass
                    .getDeclaredField(
                        "binding"
                    )
                    .apply {
                        isAccessible = true
                    }



            val binding =
                bindingField.get(
                    activity
                )



            val playerField =
                binding.javaClass
                    .getDeclaredField(
                        "playerView"
                    )
                    .apply {
                        isAccessible = true
                    }




            val playerView =
                playerField.get(
                    binding
                )
                        as? PlayerView
                    ?: return




            attach(
                activity,
                playerView
            )



        } catch (_: Exception) {


        }
    }









    fun getPlayer(
        context: Context
    ): ExoPlayer {


        if (
            exoPlayer == null
        ) {


            exoPlayer =

                ExoPlayer.Builder(

                    context.applicationContext,

                    StreamDataSourceFactory
                        .createRenderersFactory(
                            context
                        )

                )

                    .setMediaSourceFactory(mediaSourceFactory(context))

                    .setLoadControl(
                        loadControl
                    )

                    .setTrackSelector(
                        createTrackSelector(context)
                    )

                    .build()

                    .apply {



                        playWhenReady =
                            true





                        addListener(

                            object : Player.Listener {



                                override fun onPlaybackStateChanged(
                                    playbackState: Int
                                ) {

                                    val wasBuffering =
                                        lastPlaybackState == Player.STATE_BUFFERING


                                    /*
                                       Count buffering only after
                                       playback has started once
                                    */
                                    if (
                                        playbackState == Player.STATE_BUFFERING &&
                                        !wasBuffering &&
                                        playbackActuallyStarted
                                    ) {

                                        rebufferCount++

                                        bufferingStartedAtMs =
                                            System.currentTimeMillis()

                                        bufferingSessionActive = true
                                    }


                                    /*
                                       Buffering finished
                                    */
                                    if (
                                        wasBuffering &&
                                        playbackState != Player.STATE_BUFFERING &&
                                        bufferingSessionActive
                                    ) {


                                        val duration =
                                            System.currentTimeMillis()
                                        -
                                        bufferingStartedAtMs


                                        if (
                                            duration > 0L &&
                                            duration <= 300000L
                                        ) {

                                            totalBufferingMs += duration
                                        }


                                        bufferingStartedAtMs = 0L

                                        bufferingSessionActive = false
                                    }



                                    if (
                                        playbackState == Player.STATE_READY
                                    ) {

                                        val wasRecovering =
                                            liveRecoveryStartedAtMs != 0L ||
                                                    liveRecoveryAttempt > 0 ||
                                                    liveRecoveryJob?.isActive == true

                                        hasStartedPlaying = true
                                        playbackActuallyStarted = true

                                        // A successful start proves that the
                                        // active stream recovered. Do not keep
                                        // showing an earlier error/retry state.
                                        lastError = null
                                        streamErrorType = StreamErrorType.NONE

                                        if (wasRecovering) {
                                            android.util.Log.d(
                                                "N24_RECOVERY",
                                                "Playback recovered; clearing retry state"
                                            )
                                            recoveryRecoveredListener?.invoke()
                                        }

                                        cancelLiveRecovery()
                                    }


                                    lastPlaybackState =
                                        playbackState
                                }






                                override fun onPlayerError(
                                    error: PlaybackException
                                ) {
                                    handlePlayerError(error)
                                }
                            }
                        )
                    }


        }



        return exoPlayer!!
    }



    fun attach(
        context: Context,
        playerView: PlayerView
    ) {


        attachGeneration++

        cancelIdleRelease()

        ensureActivityLifecycleCallbacks(
            context
        )



        if (
            context is Activity
        ) {


            ownerActivityRef =
                WeakReference(
                    context
                )
        }





        val player =
            getPlayer(
                context
            )



        if (
            currentPlayerView !== playerView
        ) {


            currentPlayerView?.player =
                null



            playerView.player =
                player



            currentPlayerView =
                playerView
        }
    }









    fun detach(
        playerView: PlayerView
    ) {


        if (
            currentPlayerView === playerView
        ) {


            playerView.player =
                null



            currentPlayerView =
                null
        }
    }









    fun moveTo(
        context: Context,
        playerView: PlayerView
    ) {


        attach(
            context,
            playerView
        )
    }









    fun play(
        context: Context,
        playerView: PlayerView,
        streamUrl: String,
        streamId: String? = null
    ) {

        playbackSessionId++


        cancelLiveRecovery()





        val player =
            getPlayer(
                context
            )



        attach(
            context,
            playerView
        )





        if (
            currentUrl != streamUrl
        ) {

            streamGeneration++

            streamRequestedAtMs =
                SystemClock.elapsedRealtime()

            resetDiagnostics()



            isReplacingMediaItem = true

            try {
                player.stop()



            player.clearMediaItems()



            currentUrl =
                streamUrl



            lastStreamUrl =
                streamUrl





            player.setMediaItem(

                liveMediaItem(streamUrl)
            )



            player.prepare()



            player.play()
            } finally {
                isReplacingMediaItem = false
            }
        }
        else {


            player.play()
        }
    }

    private fun mediaSourceFactory(context: Context): androidx.media3.exoplayer.source.DefaultMediaSourceFactory {
        return StreamDataSourceFactory.createMediaSourceFactory()
    }

    private fun liveMediaItem(streamUrl: String): MediaItem =
        MediaItem.Builder()
            .setUri(streamUrl)
            // Stay a little behind the live edge so a delayed segment does not
            // immediately push the player behind the HLS live window.
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .build()
            )
            .build()









    fun retryCurrent() {

        playbackSessionId++
        cancelLiveRecovery()



        val player =
            exoPlayer
                ?: return





        if (
            currentUrl.isNullOrBlank()
        ) return





        player.stop()



        player.clearMediaItems()



        player.setMediaItem(

            liveMediaItem(currentUrl!!)
        )



        player.prepare()



        player.play()
    }









    fun stop() {


        cancelLiveRecovery()



        exoPlayer?.stop()



        exoPlayer?.clearMediaItems()



        currentUrl =
            null
    }









    fun pause() {


        exoPlayer?.pause()
    }









    fun resume() {


        exoPlayer?.play()
    }









    fun isPlaying(): Boolean {


        return exoPlayer?.isPlaying
            ?: false
    }









    fun getExoPlayerOrNull():

            ExoPlayer? {


        return exoPlayer
    }



    private fun handlePlayerError(
        error: PlaybackException
    ) {

        if (isReplacingMediaItem) return

        val player = exoPlayer ?: return
        val errorSession = playbackSessionId
        val errorGeneration = streamGeneration
        val errorUrl = currentUrl ?: return

        playerScope.launch {
            // Let a rapid channel switch settle. Media3 can dispatch the old
            // item's error just after the next item is selected.
            val elapsedSinceSwitch =
                SystemClock.elapsedRealtime() - streamRequestedAtMs
            val settleDelay =
                (STREAM_SWITCH_SETTLE_MS - elapsedSinceSwitch)
                    .coerceAtLeast(0L)

            if (settleDelay > 0L) delay(settleDelay)

            // Accept only an error that still belongs to the exact active
            // request. A stale exception is cleared during the new prepare.
            if (
                playbackSessionId != errorSession ||
                streamGeneration != errorGeneration ||
                currentUrl != errorUrl ||
                exoPlayer !== player ||
                player.playerError !== error
            ) {
                return@launch
            }

            lastError = error
            streamErrorType = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    StreamErrorType.NETWORK

                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    StreamErrorType.SOURCE

                else -> StreamErrorType.UNKNOWN
            }

            scheduleLiveChannelRecoveryIfNeeded()
        }
    }


    private fun scheduleLiveChannelRecoveryIfNeeded() {

        val recoverySession = playbackSessionId

        val ownerActivity = ownerActivityRef?.get() ?: return
        val reconnectMode = PreferenceManager(
            ownerActivity.applicationContext
        ).getAutoReconnectMode()

        if (reconnectMode == PreferenceManager.AutoReconnectMode.OFF) {
            recoveryFailedListeners.forEach { listener ->
                listener.invoke()
            }
            cancelLiveRecovery()
            return
        }



        if (
            currentUrl.isNullOrBlank()
            ||
            exoPlayer == null
        ) return


        // Start the recovery window only after an error for the active stream
        // has been accepted. Previously this stayed at zero, which made the
        // first failure look as if its retry window had already expired.
        if (liveRecoveryStartedAtMs == 0L) {
            liveRecoveryStartedAtMs =
                System.currentTimeMillis()
        }


        val elapsed =
            System.currentTimeMillis() -
                    liveRecoveryStartedAtMs

        val recoveryWindowMs = when (reconnectMode) {
            PreferenceManager.AutoReconnectMode.STANDARD ->
                LIVE_RECOVERY_WINDOW_MS

            PreferenceManager.AutoReconnectMode.FAST ->
                FAST_LIVE_RECOVERY_WINDOW_MS

            PreferenceManager.AutoReconnectMode.OFF ->
                return
        }

        if (elapsed >= recoveryWindowMs) {

            if (
                recoverySession != playbackSessionId
            ) {
                return
            }


            recoveryFailedListeners.forEach { listener ->
                listener.invoke()
            }

            cancelLiveRecovery()

            return
        }




        if (
            liveRecoveryJob?.isActive == true
        ) return





        val delayTime = when (reconnectMode) {
            PreferenceManager.AutoReconnectMode.STANDARD ->
                when (liveRecoveryAttempt) {
                    0 -> 3000L
                    1 -> 5000L
                    else -> 7000L
                }

            PreferenceManager.AutoReconnectMode.FAST ->
                when (liveRecoveryAttempt) {
                    0 -> 1000L
                    1 -> 2000L
                    else -> 3000L
                }

            PreferenceManager.AutoReconnectMode.OFF ->
                return
        }





        liveRecoveryAttempt++


        recoveryStatusListener?.invoke(
            liveRecoveryAttempt
        )


        val failedUrl =
            currentUrl



        liveRecoveryJob =
            playerScope.launch {


                delay(delayTime)


                if (
                    recoverySession != playbackSessionId
                ) {
                    return@launch
                }


                if (
                    failedUrl == null
                ) {
                    return@launch
                }



                android.util.Log.d(
                    "N24_RECOVERY",
                    "Retry attempt $liveRecoveryAttempt"
                )



                exoPlayer?.apply {

                    if (lastError?.let(::canRecoverWithoutReload) == true) {
                        // Behind-live-window and transient network errors don't
                        // mean the stream itself is broken. Seeking to the live
                        // default position and re-preparing the same session
                        // recovers without the visible black-screen flash of a
                        // full stop/clear/reload.
                        android.util.Log.d(
                            "N24_RECOVERY",
                            "Recovering without full reload (soft retry)"
                        )
                        lastError = null
                        streamErrorType = StreamErrorType.NONE
                        seekToDefaultPosition()
                        prepare()
                        play()
                    } else {
                        stop()
                        clearMediaItems()
                        setMediaItem(liveMediaItem(failedUrl))
                        prepare()
                        play()
                    }
                }



                scheduleLiveChannelRecoveryIfNeeded()
            }
    }








    private fun cancelLiveRecovery() {


        liveRecoveryJob
            ?.cancel()



        liveRecoveryJob =
            null



        liveRecoveryStartedAtMs =
            0L



        liveRecoveryAttempt =
            0
    }









    private fun resetDiagnostics() {


        rebufferCount =
            0



        bufferingStartedAtMs =
            0L



        totalBufferingMs =
            0L



        lastError =
            null

        streamErrorType = StreamErrorType.NONE

        lastPlaybackState =
            Player.STATE_IDLE

        hasStartedPlaying = false

        bufferingSessionActive = false

        playbackActuallyStarted = false

    }









    fun release() {


        cancelIdleRelease()

        cancelLiveRecovery()



        if (
            !currentUrl.isNullOrBlank()
        ) {


            lastStreamUrl =
                currentUrl
        }






        currentPlayerView?.player =
            null



        currentPlayerView =
            null






        exoPlayer?.stop()



        exoPlayer?.clearMediaItems()



        exoPlayer?.release()



        exoPlayer =
            null






        currentUrl =
            null


        resetDiagnostics()



        ownerActivityRef =
            null
    }



    fun setRecoveryFailedListener(
        listener: (() -> Unit)?
    ) {
        recoveryFailedListeners.clear()

        if(listener != null) {
            recoveryFailedListeners.add(listener)
        }
    }

    fun setRecoveryStatusListener(
        listener: ((Int) -> Unit)?
    ) {
        recoveryStatusListener = listener
    }

    fun setRecoveryRecoveredListener(
        listener: (() -> Unit)?
    ) {
        recoveryRecoveredListener = listener
    }







    fun getCurrentUrl(): String? {


        return currentUrl
    }









    fun getCurrentUrlOrEmpty(): String {


        return currentUrl
            ?: ""
    }









    fun getRebufferCount(): Int {


        return rebufferCount
    }


    fun hasEverStartedPlayback(): Boolean {
        return playbackActuallyStarted
    }


    fun isPlaybackStarted(): Boolean {

        return playbackActuallyStarted
    }





    fun getTotalBufferingMs(): Long {

        return totalBufferingMs
            .coerceIn(
                0L,
                3600000L
            )
    }

    // IPTV routes drop and re-establish a connection frequently; that alone
    // doesn't mean the stream/session is broken. Treat it the same as
    // behind-live-window: recover in place instead of a full reload.
    private fun canRecoverWithoutReload(error: PlaybackException): Boolean =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true

            else -> false
        }

    fun getTotalBufferingMsIncludingActive(): Long {
        val activeBufferingMs = if (bufferingSessionActive && bufferingStartedAtMs > 0L) {
            (System.currentTimeMillis() - bufferingStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (totalBufferingMs + activeBufferingMs).coerceIn(0L, 3600000L)
    }









    fun getLastError(): PlaybackException? {


        return lastError
    }


    fun getStreamErrorType(): StreamErrorType {
        return streamErrorType
    }








    fun getLastErrorMessage(): String {


        return lastError
            ?.message
            ?: ""
    }









    fun preservePlaybackForFullscreenReturn() {


        preservePlaybackThroughFullscreenReturn =
            true
    }









    fun shouldPreservePlayback(): Boolean {


        return preservePlaybackThroughFullscreenReturn
    }









    fun clearFullscreenPreserveFlag() {


        preservePlaybackThroughFullscreenReturn =
            false
    }









    fun getLastStreamUrl(): String? {


        return lastStreamUrl
    }









    fun isRecoveryRunning(): Boolean {


        return liveRecoveryJob
            ?.isActive
            ?: false
    }


    fun getRecoveryAttempt(): Int {
        return liveRecoveryAttempt
    }








    private fun releasePlayerOnly() {


        currentPlayerView?.player =
            null



        currentPlayerView =
            null



        exoPlayer?.stop()



        exoPlayer?.clearMediaItems()



        exoPlayer?.release()



        exoPlayer =
            null
    }

}
