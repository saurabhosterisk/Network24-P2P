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
import androidx.media3.ui.PlayerView

import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.Network24App
import com.network24.player.core.p2p.Network24P2pSession
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

    private var p2pSession: Network24P2pSession? = null

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





    // Keep live streams responsive when switching channels while retaining a
    // small buffer to absorb normal IPTV network variation.
    private val loadControl =

        DefaultLoadControl.Builder()

            .setBufferDurationsMs(
                5_000,
                20_000,
                1_000,
                2_000
            )

            .build()







    private fun ensureActivityLifecycleCallbacks(
        context: Context
    ) {


        if (
            lifecycleCallbacksRegistered
        ) return




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
                                        playbackState == Player.STATE_READY &&
                                        exoPlayer?.isPlaying == true
                                    ) {

                                        hasStartedPlaying = true
                                        playbackActuallyStarted = true

                                        // A successful start proves that the
                                        // active stream recovered. Do not keep
                                        // showing an earlier error/retry state.
                                        lastError = null
                                        streamErrorType = StreamErrorType.NONE
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

        p2pSession = (context.applicationContext as? Network24App)?.p2pSession
        p2pSession?.joinStream(streamId, streamUrl)

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

                MediaItem.fromUri(
                    streamUrl
                )
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
        val app = context.applicationContext as? Network24App
        val session = app?.p2pSession
        return if (session?.enabled == true) {
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                StreamDataSourceFactory.createHybridDataSourceFactory(session, session.mediaCache(), session.mediaRequestTimeoutMs())
            )
        } else StreamDataSourceFactory.createMediaSourceFactory()
    }









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

            MediaItem.fromUri(
                currentUrl!!
            )
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

        p2pSession?.joinStream(null)
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


                    stop()


                    clearMediaItems()



                    setMediaItem(
                        MediaItem.fromUri(
                            failedUrl
                        )
                    )


                    prepare()


                    play()
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

        p2pSession?.joinStream(null)
        p2pSession = null



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
