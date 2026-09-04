package com.network24.player.features.player.activity

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast

import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout

import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityPlayerBinding
import com.network24.player.features.live.history.LiveWatchHistory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.multiview.MultiViewActivity
import com.network24.player.features.player.state.PlayerState
import com.network24.player.features.player.ui.dialogs.StreamInfoDialog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat
import java.util.Locale
import androidx.media3.common.TrackSelectionOverride

@Suppress("UnsafeOptInUsageError")
class PlayerActivity : BaseActivity() {

    override fun onTvGuideUpdated() {
        if (!::binding.isInitialized || !::repository.isInitialized) return

        val current = PlayerState.currentChannel() ?: return
        val streamId = current.stream_id ?: return

        lifecycleScope.launch {
            val refreshedChannel = withContext(Dispatchers.IO) {
                repository.getChannelByStreamId(streamId)
            } ?: current

            val currentPosition = PlayerState.currentPosition
            if (
                currentPosition in PlayerState.channels.indices &&
                PlayerState.channels[currentPosition].stream_id == streamId
            ) {
                PlayerState.channels[currentPosition] = refreshedChannel
            }

            val epgId = refreshedChannel.epg_channel_id
                ?: refreshedChannel.stream_id?.toString()
                ?: return@launch

            loadEpg(epgId)
        }
    }

    companion object {
        const val EXTRA_PLAY_SELECTED_CHANNEL = "play_selected_channel"
    }


    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var repository: LiveRepository


    private var errorActive = false
    private var hasEverPlayed = false


    private var isSubtitleEnabled = false
    private var currentAspectRatioIndex = 0


    private val hideHandler =
        Handler(Looper.getMainLooper())


    private val hideRunnable = Runnable {

        val d = 300L


        binding.topTint
            .animate()
            .alpha(0f)
            .setDuration(d)
            .withEndAction {

                binding.topTint.visibility =
                    View.GONE
            }
            .start()

        binding.btnBack
            .animate()
            .alpha(0f)
            .setDuration(d)
            .withEndAction {
                binding.btnBack.visibility = View.GONE
            }
            .start()

        binding.btnMore
            .animate()
            .alpha(0f)
            .setDuration(d)
            .withEndAction {
                binding.btnMore.visibility = View.GONE
            }
            .start()



        binding.txtChannelTitle
            .animate()
            .alpha(0f)
            .setDuration(d)
            .withEndAction {

                binding.txtChannelTitle.visibility =
                    View.GONE
            }
            .start()



        binding.bottomOverlay
            .animate()
            .alpha(0f)
            .translationY(50f)
            .setDuration(d)
            .withEndAction {

                binding.bottomOverlay.visibility =
                    View.GONE
            }
            .start()
    }



    private val playerListener =
        object : Player.Listener {

            override fun onTracksChanged(tracks: Tracks) {
                applySubtitlePreference()
            }


            override fun onPlaybackStateChanged(
                playbackState: Int
            ) {


                if (playbackState == Player.STATE_BUFFERING) {


                    binding.progressBar.visibility =
                        View.VISIBLE


                    if (!hasEverPlayed && !errorActive) {

                        binding.txtPlayerError.visibility =
                            View.GONE
                    }


                    return
                }



                binding.progressBar.visibility =
                    View.GONE



                if (playbackState == Player.STATE_READY) {


                    hasEverPlayed = true


                    errorActive = false


                    binding.txtPlayerError.visibility =
                        View.GONE



                    applySubtitlePreference()
                }
            }




            override fun onIsPlayingChanged(
                isPlaying: Boolean
            ) {

                binding.btnPlayPause.setImageResource(
                    if (isPlaying)
                        R.drawable.ic_pause
                    else
                        R.drawable.ic_play
                )
            }




            override fun onPlayerError(
                error: PlaybackException
            ) {


                super.onPlayerError(error)


                errorActive = true


                binding.progressBar.visibility =
                    View.VISIBLE


                val attempt =
                    PlayerManager.getRecoveryAttempt()

                binding.txtPlayerError.text =
                    if (attempt > 0) {
                        "Reconnecting...\nAttempt $attempt/5"
                    } else {
                        "Reconnecting..."
                    }


                binding.txtPlayerError.visibility =
                    View.VISIBLE



                if (hasEverPlayed) {

                    Toast.makeText(
                        this@PlayerActivity,
                        "Reconnecting...",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {

                    Toast.makeText(
                        this@PlayerActivity,
                        "Starting stream...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)


        binding =
            ActivityPlayerBinding.inflate(layoutInflater)


        setContentView(setupGlobalRightDrawer(binding.root, binding.btnMore))


        prefs =
            PreferenceManager(this)

        isSubtitleEnabled = prefs.areSubtitlesEnabled()


        repository =
            LiveRepository(this)



        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )


        WindowInsetsControllerCompat(
            window,
            binding.root
        ).let { controller ->

            controller.hide(
                WindowInsetsCompat.Type.systemBars()
            )

            controller.systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }



        binding.progressBar.visibility =
            View.GONE




        binding.playerView.setShowSubtitleButton(
            false
        )


        binding.playerView.subtitleView
            ?.setApplyEmbeddedStyles(false)



        updateChannelUI(
            PlayerState.currentChannel()
        )

        PlayerManager.setRecoveryFailedListener {

            runOnUiThread {

                showPermanentPlaybackError()

            }

        }


        PlayerManager.setRecoveryStatusListener { attempt ->

            runOnUiThread {

                binding.txtPlayerError.text =
                    "Reconnecting...\nAttempt $attempt/5"

                binding.txtPlayerError.visibility =
                    View.VISIBLE

            }
        }

        showUiWithTimeout()


        setupClickListeners()



        PlayerManager.moveTo(
            this,
            binding.playerView
        )

        if (intent.getBooleanExtra(EXTRA_PLAY_SELECTED_CHANNEL, false)) {
            PlayerState.currentChannel()?.let(::switchToChannel)
        }


        onBackPressedDispatcher.addCallback(this) {

            finish()
        }
    }



    private fun buildStreamUrl(
        channel: LiveChannel
    ): String {


        val server =
            prefs.getServer()
                .trim()
                .trimEnd('/')


        return "$server/live/${prefs.getUsername().trim()}/${prefs.getPassword().trim()}/${channel.stream_id}.m3u8"
    }



    private fun setupClickListeners() {

        binding.btnBack.setOnClickListener {
            finish()
        }


        binding.root.setOnClickListener {

            toggleUi()
        }


        binding.playerView.setOnClickListener {

            toggleUi()
        }



        binding.btnPlayPause.setOnClickListener {


            if (PlayerManager.isPlaying()) {

                PlayerManager.pause()

            } else {

                PlayerManager.resume()
            }


            showUiWithTimeout()
        }



        binding.btnNext.setOnClickListener {

            playNextChannel()

            showUiWithTimeout()
        }



        binding.btnPrev.setOnClickListener {

            playPreviousChannel()

            showUiWithTimeout()
        }



        binding.btnInfo.setOnClickListener {


            val id =
                PlayerState.currentChannel()
                    ?.stream_id



            if (id == null) {


                Toast.makeText(
                    this,
                    "Channel not available",
                    Toast.LENGTH_SHORT
                ).show()


            } else {


                StreamInfoDialog
                    .newInstance()
                    .show(
                        supportFragmentManager,
                        "StreamInfoDialog"
                    )
            }


            showUiWithTimeout()
        }



        binding.btnAspect.setOnClickListener {


            currentAspectRatioIndex =
                (currentAspectRatioIndex + 1) % 4



            val msg =
                when (currentAspectRatioIndex) {


                    0 -> {

                        binding.playerView.resizeMode =
                            AspectRatioFrameLayout
                                .RESIZE_MODE_FIT


                        "Aspect Ratio: Fit"
                    }


                    1 -> {

                        binding.playerView.resizeMode =
                            AspectRatioFrameLayout
                                .RESIZE_MODE_FILL


                        "Aspect Ratio: Fill"
                    }


                    2 -> {

                        binding.playerView.resizeMode =
                            AspectRatioFrameLayout
                                .RESIZE_MODE_ZOOM


                        "Aspect Ratio: Zoom"
                    }


                    else -> {

                        binding.playerView.resizeMode =
                            AspectRatioFrameLayout
                                .RESIZE_MODE_FIXED_WIDTH


                        "Aspect Ratio: Fixed Width"
                    }
                }



            Toast.makeText(
                this,
                msg,
                Toast.LENGTH_SHORT
            ).show()


            showUiWithTimeout()
        }

        binding.btnGrid.setOnClickListener {


            if (PlayerState.channels.size < 2) {


                Toast.makeText(
                    this,
                    "Multi-view needs at least 2 channels",
                    Toast.LENGTH_SHORT
                ).show()


            } else {


                startActivity(
                    Intent(
                        this,
                        MultiViewActivity::class.java
                    )
                )


                showUiWithTimeout()
            }
        }



        binding.btnSubtitle.setOnClickListener {


            isSubtitleEnabled =
                !isSubtitleEnabled

            prefs.setSubtitlesEnabled(isSubtitleEnabled)



            toggleSubtitles(
                isSubtitleEnabled
            )



            Toast.makeText(
                this,
                if (isSubtitleEnabled)
                    "Subtitles Enabled"
                else
                    "Subtitles Disabled",
                Toast.LENGTH_SHORT
            ).show()



            showUiWithTimeout()
        }




    }





    private fun toggleSubtitles(
        enable: Boolean
    ) {

        val player =
            binding.playerView.player
                ?: return


        val builder =
            player.trackSelectionParameters
                .buildUpon()


        if (enable) {

            player.currentTracks.groups.forEach { group ->

                if (group.type == C.TRACK_TYPE_TEXT) {

                    for (i in 0 until group.length) {

                        if (group.isTrackSupported(i)) {

                            builder
                                .setTrackTypeDisabled(
                                    C.TRACK_TYPE_TEXT,
                                    false
                                )
                                .setOverrideForType(
                                    TrackSelectionOverride(
                                        group.mediaTrackGroup,
                                        i
                                    )
                                )

                            break
                        }
                    }
                }
            }



        } else {

            builder
                .clearOverridesOfType(
                    C.TRACK_TYPE_TEXT
                )
                .setTrackTypeDisabled(
                    C.TRACK_TYPE_TEXT,
                    true
                )
        }


        player.trackSelectionParameters =
            builder.build()


        binding.btnSubtitle.setColorFilter(

            if (enable)

                Color.parseColor("#FFC107")

            else

                Color.WHITE
        )
    }






    override fun onResume() {


        super.onResume()



        PlayerManager.attach(
            this,
            binding.playerView
        )

        binding.playerView.player
            ?.addListener(playerListener)

        PlayerManager.resume()


        when {


            binding.playerView.player?.playbackState ==
                    Player.STATE_READY -> {



                hasEverPlayed = true



                binding.progressBar.visibility =
                    View.GONE



                binding.txtPlayerError.visibility =
                    View.GONE



            }





            binding.playerView.player?.playbackState ==
                    Player.STATE_BUFFERING -> {



                binding.progressBar.visibility =
                    View.VISIBLE
            }





            binding.playerView.player?.playerError != null -> {



                binding.progressBar.visibility =
                    View.GONE



                binding.txtPlayerError.visibility =
                    View.VISIBLE



            }
        }




        applySubtitlePreference()



        showUiWithTimeout()



        binding.root.postDelayed(
            {

                binding.btnPlayPause.requestFocus()

            },
            350
        )
    }

    override fun onPause() {

        super.onPause()


        binding.playerView.player
            ?.removeListener(playerListener)



        hideHandler.removeCallbacks(
            hideRunnable
        )

    }





    override fun onDestroy() {

        PlayerManager.setRecoveryFailedListener(null)

        PlayerManager.setRecoveryStatusListener(null)

        PlayerManager.detach(
            binding.playerView
        )

        super.onDestroy()
    }






    private fun showPermanentPlaybackError() {


        errorActive = true



        binding.progressBar.visibility =
            View.GONE



        binding.txtPlayerError.text =
            "Unable to play this stream right now. It may be temporarily unavailable or your connection may be unstable."



        binding.txtPlayerError.visibility =
            View.VISIBLE






        showUiWithTimeout()
    }






    private fun showUiWithTimeout() {


        val d = 300L



        if (binding.bottomOverlay.visibility != View.VISIBLE) {



            binding.topTint.alpha = 0f


            binding.topTint.visibility =
                View.VISIBLE



            binding.topTint.animate()
                .alpha(1f)
                .setDuration(d)
                .start()

            binding.btnBack.alpha = 0f
            binding.btnBack.visibility = View.VISIBLE
            binding.btnBack.animate()
                .alpha(1f)
                .setDuration(d)
                .start()

            binding.btnMore.alpha = 0f
            binding.btnMore.visibility = View.VISIBLE
            binding.btnMore.animate()
                .alpha(1f)
                .setDuration(d)
                .start()





            binding.txtChannelTitle.alpha = 0f


            binding.txtChannelTitle.visibility =
                View.VISIBLE



            binding.txtChannelTitle.animate()
                .alpha(1f)
                .setDuration(d)
                .start()





            binding.bottomOverlay.alpha = 0f


            binding.bottomOverlay.translationY =
                50f



            binding.bottomOverlay.visibility =
                View.VISIBLE




            binding.bottomOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(d)
                .withEndAction {



                    binding.btnPlayPause.post {
                        binding.btnPlayPause.requestFocus()
                    }
                }
                .start()
        }




        hideHandler.removeCallbacks(
            hideRunnable
        )



        hideHandler.postDelayed(
            hideRunnable,
            5000
        )
    }






    private fun toggleUi() {


        if (binding.bottomOverlay.visibility == View.VISIBLE) {



            hideHandler.removeCallbacks(
                hideRunnable
            )



            hideRunnable.run()



        } else {



            showUiWithTimeout()
        }
    }

    private fun switchToChannel(
        channel: LiveChannel
    ) {

        LiveWatchHistory.record(applicationContext, channel)




        errorActive = false

        hasEverPlayed = false



        binding.progressBar.visibility =
            View.VISIBLE



        binding.txtPlayerError.visibility =
            View.GONE







        updateChannelUI(
            channel
        )



        PlayerManager.play(
            this,
            binding.playerView,
            buildStreamUrl(channel),
            channel.stream_id?.toString()
        )






        if (binding.playerView.player?.playbackState ==
            Player.STATE_READY) {



            binding.progressBar.visibility =
                View.GONE



            hasEverPlayed = true



        } else {


            binding.progressBar.visibility =
                View.VISIBLE
        }



        applySubtitlePreference()
    }

    private fun applySubtitlePreference() {
        toggleSubtitles(isSubtitleEnabled)
    }







    private fun playNextChannel() {


        PlayerState.next()
            ?.let { channel ->


                switchToChannel(
                    channel
                )
            }
    }







    private fun playPreviousChannel() {


        PlayerState.previous()
            ?.let { channel ->


                switchToChannel(
                    channel
                )
            }
    }








    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {



        if (binding.bottomOverlay.visibility != View.VISIBLE) {



            when (keyCode) {


                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {



                    showUiWithTimeout()


                    return true
                }
            }



        } else {



            hideHandler.removeCallbacks(
                hideRunnable
            )



            hideHandler.postDelayed(
                hideRunnable,
                5000
            )



            when (keyCode) {



                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_DPAD_UP -> {


                    playNextChannel()


                    return true
                }



                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN -> {


                    playPreviousChannel()


                    return true
                }
            }
        }




        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {


            showUiWithTimeout()



            if (PlayerManager.isPlaying()) {


                PlayerManager.pause()


            } else {


                PlayerManager.resume()
            }



            return true
        }



        return super.onKeyDown(
            keyCode,
            event
        )
    }









    private fun updateChannelUI(
        channel: LiveChannel?
    ) {


        if (channel == null)
            return



        errorActive = false

        hasEverPlayed = false




        binding.txtPlayerError.visibility =
            View.GONE







        val streamId =
            channel.stream_id?.let {

                "$it - "

            } ?: ""




        binding.txtChannelTitle.text =
            "$streamId${channel.name ?: "Unknown Channel"}"




        val epgId =
            channel.epg_channel_id
                ?: channel.stream_id?.toString()
                ?: ""



        if (epgId.isNotEmpty()) {


            loadEpg(
                epgId
            )
        }
    }

    private fun loadEpg(
        epgId: String
    ) {


        lifecycleScope.launch(
            Dispatchers.IO
        ) {


            try {


                val (now, next) =
                    repository.getNowNextEpg(
                        epgId
                    )



                withContext(
                    Dispatchers.Main
                ) {


                    updateEpg(
                        now,
                        next
                    )
                }



            } catch (_: Exception) {



                withContext(
                    Dispatchers.Main
                ) {


                    binding.txtNowTitle.text =
                        "No EPG Data"


                    binding.txtNextTitle.text =
                        ""


                    binding.txtNowTime.text =
                        ""


                    binding.txtNextTime.text =
                        ""



                    binding.epgProgress.layoutParams =
                        binding.epgProgress.layoutParams.apply {

                            width = 0
                        }
                }
            }
        }
    }







    private fun updateEpg(
        now: com.network24.player.core.database.entity.EpgEntity?,
        next: com.network24.player.core.database.entity.EpgEntity?
    ) {



        if (now != null) {



            binding.txtNowTitle.text =
                now.title ?: "No Program Info"



            binding.txtNowTime.text =
                "${formatTime(now.startTimestamp)} - ${formatTime(now.stopTimestamp)}"




            val progress =
                calculateEpgProgress(
                    now.startTimestamp,
                    now.stopTimestamp
                )



            binding.epgTrack.post {



                binding.epgProgress.layoutParams =
                    binding.epgProgress.layoutParams.apply {


                        width =
                            (binding.epgTrack.width * progress)
                                .toInt()
                    }
            }



        } else {



            binding.txtNowTitle.text =
                "No Program Info"


            binding.txtNowTime.text =
                ""


            binding.epgProgress.layoutParams =
                binding.epgProgress.layoutParams.apply {

                    width = 0
                }
        }




        if (next != null) {



            binding.txtNextTitle.text =
                next.title ?: ""



            binding.txtNextTime.text =
                "${formatTime(next.startTimestamp)} - ${formatTime(next.stopTimestamp)}"



        } else {



            binding.txtNextTitle.text =
                ""


            binding.txtNextTime.text =
                ""
        }
    }







    private fun formatTime(
        timeMs: Long?
    ): String {


        return if (
            timeMs == null ||
            timeMs == 0L
        ) {


            ""


        } else {


            try {


                SimpleDateFormat(
                    "hh:mm a",
                    Locale.getDefault()
                ).format(
                    java.util.Date(timeMs)
                )


            } catch (_: Exception) {


                ""
            }
        }
    }







    private fun calculateEpgProgress(
        startMs: Long?,
        stopMs: Long?
    ): Float {



        if (
            startMs == null ||
            stopMs == null ||
            stopMs <= startMs
        ) {


            return 0f
        }



        return (
                (System.currentTimeMillis() - startMs)
                    .toFloat()
                        /
                        (stopMs - startMs)
                            .toFloat()
                )
            .coerceIn(
                0f,
                1f
            )
    }







    private fun showPlayerError(
        message: String
    ) {


        binding.txtPlayerError.text =
            message


        binding.txtPlayerError.visibility =
            View.VISIBLE



        showUiWithTimeout()
    }
}
