package com.network24.player.features.live.activity


import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast

import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.recyclerview.widget.LinearLayoutManager

import com.google.firebase.firestore.FirebaseFirestore

import com.network24.player.R
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.common.utils.EpgTimeFormatter
import com.network24.player.common.utils.LiveStreamUrlBuilder
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.preferences.PreferenceManager

import com.network24.player.databinding.ActivityFavoriteChannelsBinding

import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.live.adapter.ChannelAdapter
import com.network24.player.features.live.history.LiveWatchHistory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback

import com.network24.player.features.login.activity.LoginActivity

import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.multiview.MultiViewActivity
import com.network24.player.features.player.state.PlayerState
import com.network24.player.features.player.ui.dialogs.StreamInfoDialog
import com.network24.player.features.vpn.util.FullscreenVpnToggle

import kotlinx.coroutines.launch



class FavoriteChannelsActivity : BaseActivity() {

    override fun onTvGuideUpdated() {
        if (::binding.isInitialized) loadAllChannelsToMemory(forceRefresh = false)
    }



    private lateinit var binding:
            ActivityFavoriteChannelsBinding



    private lateinit var repository:
            LiveRepository



    private lateinit var prefs:
            PreferenceManager



    private lateinit var favRepo:
            FavoritesRepository



    private lateinit var adapter:
            ChannelAdapter






    // True while cardPlayer is expanded to fill the screen in place of the
    // channel list / EPG cards. The same PlayerView keeps rendering the
    // whole time - no PlayerManager attach()/surface handoff happens on
    // toggling this, so there is no decoder handoff for a freeze to
    // happen on (see the equivalent fix in ChannelListActivity.kt).
    private var isFullscreen = false

    private var fsSubtitleEnabled = false
    private lateinit var vpnToggle: FullscreenVpnToggle

    private val fsHideHandler = Handler(Looper.getMainLooper())

    private val fsHideRunnable = Runnable {
        val d = 300L

        binding.fsTopTint.animate().alpha(0f).setDuration(d)
            .withEndAction { binding.fsTopTint.visibility = View.GONE }.start()

        binding.fsBtnBack.animate().alpha(0f).setDuration(d)
            .withEndAction { binding.fsBtnBack.visibility = View.GONE }.start()

        binding.fsTxtChannelTitle.animate().alpha(0f).setDuration(d)
            .withEndAction { binding.fsTxtChannelTitle.visibility = View.GONE }.start()

        binding.fsBottomOverlay.animate().alpha(0f).translationY(50f).setDuration(d)
            .withEndAction { binding.fsBottomOverlay.visibility = View.GONE }.start()
    }

    // Saved so exitFullscreen() can put cardPlayer back exactly where it
    // was (its own MaterialCardView corner radius/elevation/margins are
    // relaxed to 0 for a true edge-to-edge look while fullscreen).
    private var cardPlayerCornerRadius = 0f
    private var cardPlayerElevation = 0f
    private var cardPlayerNormalConstraintSet: ConstraintSet? = null





    private var previewPosition =
        -1






    private var isRefreshing =
        false






    private val allChannels =
        mutableListOf<LiveChannel>()



    private val channelList =
        mutableListOf<LiveChannel>()





    private var currentFavIds:
            Set<String> = emptySet()






    private val isTouchDevice by lazy {


        !packageManager.hasSystemFeature(

            PackageManager.FEATURE_LEANBACK

        )

    }









    private val playerListener =

        object : Player.Listener {



            override fun onPlaybackStateChanged(
                playbackState: Int
            ) {



                binding.progressLoading.visibility =

                    if (
                        playbackState ==
                        Player.STATE_BUFFERING
                    )

                        View.VISIBLE

                    else

                        View.GONE






                if (

                    playbackState ==
                    Player.STATE_READY

                ) {



                    binding.txtPlayerError.visibility =
                        View.GONE

                    // A fresh load (PlayerManager's idle-release grace period
                    // tears the player down after ~20s backgrounded, e.g.
                    // time spent in Settings toggling Secure Relay or in
                    // MultiView) hands back a new ExoPlayer with new
                    // TrackGroup instances - the subtitle TrackSelectionOverride
                    // set on the old ones no longer matches anything, so
                    // subtitles silently stopped rendering even though
                    // fsSubtitleEnabled still says they're on.
                    fsToggleSubtitles(fsSubtitleEnabled)



                }

            }

        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {


        super.onCreate(
            savedInstanceState
        )



        binding =
            ActivityFavoriteChannelsBinding
                .inflate(layoutInflater)



        setContentView(
            binding.root
        )





        val db =
            DatabaseProvider.get(this)





        prefs =
            PreferenceManager(this)

        fsSubtitleEnabled = prefs.areSubtitlesEnabled()

        // No drawer-back-handler is registered on this screen (unlike
        // ChannelListActivity) - back normally just finishes the activity,
        // which this callback preserves via the disable/reinvoke/re-enable
        // fallthrough, only intercepting it to exit fullscreen first.
        onBackPressedDispatcher.addCallback(this) {
            if (isFullscreen) {
                exitFullscreen()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }



        repository =
            LiveRepository(this)





        favRepo =
            FavoritesRepository(

                db.favoritesDao(),

                FirebaseFirestore.getInstance()

            )






        binding.btnBack.setOnClickListener {

            finish()

        }





        binding.playerView
            .setShowSubtitleButton(false)



        binding.playerView
            .subtitleView
            ?.visibility =
            View.GONE






        setupDrawerAndMenu()










        /*
         * PlayerManager Recovery Handler
         * Same message as PlayerActivity
         */


        PlayerManager.setRecoveryFailedListener {


            runOnUiThread {



                binding.progressLoading.visibility =
                    View.GONE





                binding.txtPlayerError.text =

                    "Unable to play this stream right now. It may be temporarily unavailable or your connection may be unstable."





                binding.txtPlayerError.visibility =
                    View.VISIBLE








            }

        }









        vpnToggle = FullscreenVpnToggle(this, binding.fsBtnVpn, binding.fsBtnVpnRotate) { showFsUiWithTimeout() }
        vpnToggle.register()

        // playerView's click listener (touch-open-fullscreen, plus
        // toggling the fullscreen control overlay once inside it) and the
        // fullscreen control buttons are set up in setupFullscreenControls(),
        // called below.
        setupFullscreenControls()






        setupRecycler()


        setupSearch()







        lifecycleScope.launch {



            db.favoritesDao()

                .observeByType(

                    FavoriteItemType.LIVE_CHANNEL

                )

                .collect { favs ->





                    currentFavIds =

                        favs.map {


                            it.itemId


                        }

                            .toSet()






                    refreshFavoriteListFromDb(

                        currentFavIds

                    )

                }

        }







        loadAllChannelsToMemory(forceRefresh = false)

    }
















    private fun loadAllChannelsToMemory(
        forceRefresh: Boolean
    ) {

        // repository.getChannels() silently does its own network sync when
        // the local channel table is empty (first-ever visit to this
        // screen, or a fresh account) before returning - without a loader
        // up front, that sync ran with the favorites list just sitting
        // there empty, no feedback at all.
        showLoader("Loading channels…")

        lifecycleScope.launch {


            try {


                val channels =

                    repository.getChannels(

                        server =
                            prefs.getServer(),

                        username =
                            prefs.getUsername(),

                        password =
                            prefs.getPassword(),

                        categoryId =
                            "",

                        forceRefresh =
                            forceRefresh

                    )



                if (
                    channels.isEmpty() &&
                    !forceRefresh
                ) {
                    // Same loader, reused - forceRefreshData() shows its own
                    // message on the same singleton dialog.
                    forceRefreshData()
                    return@launch
                }

                hideLoader()

                allChannels.clear()



                allChannels.addAll(
                    channels
                )



                refreshFavoriteListFromDb(
                    currentFavIds
                )


            }
            catch(
                e: Exception
            ) {

                hideLoader()

                Toast.makeText(

                    this@FavoriteChannelsActivity,

                    e.message
                        ?: "Load failed",

                    Toast.LENGTH_LONG

                ).show()

            }

        }

    }









    private fun refreshFavoriteListFromDb(
        favIds: Set<String>
    ) {



        val favChannels =

            allChannels.filter {


                favIds.contains(

                    it.stream_id
                        ?.toString()
                        .orEmpty()

                )

            }





        channelList.clear()



        channelList.addAll(
            favChannels
        )



        adapter.updateData(
            channelList
        )



        adapter.updateFavorites(
            favIds
        )






        if (
            channelList.isEmpty()
        ) {



            previewPosition =
                -1



            binding.txtNowTitle.text =
                "No favorite channels"



            binding.txtPlayerError.visibility =
                View.GONE



            PlayerManager.pause()



            return

        }







        if (
            previewPosition !in channelList.indices
        ) {


            previewPosition =
                0

        }





        adapter.setPlaying(
            previewPosition
        )



        val targetChannel = channelList[previewPosition]

        // This whole function re-runs every time the favorites DB flow
        // re-emits - not just when the user actually adds/removes a
        // favorite, but for any rewrite of that table (e.g. a background
        // Firestore sync). showPreview() unconditionally calls
        // PlayerManager.play(), which - even for the exact same
        // already-loaded URL - still calls player.play() and forces
        // playWhenReady back to true. That silently undid a pause the user
        // had just made with the fullscreen play/pause button, since a
        // re-emission could land moments later with no actual channel
        // change. Only reload when the previewed channel genuinely changed.
        if (PlayerManager.getCurrentUrl() != buildStreamUrl(targetChannel)) {
            showPreview(
                targetChannel
            )
        } else {
            binding.txtOverlayChannel.text =
                targetChannel.name
                    ?: ""
        }

        loadProgramGuide(targetChannel)

    }









    private fun setupRecycler() {


        binding.rvChannels.layoutManager =

            LinearLayoutManager(this)






        adapter =

            ChannelAdapter(

                channels =
                    mutableListOf(),

                favoriteIds =
                    emptySet(),



                onFocused =
                    { _, _ -> },




                onClicked =
                    { channel, position ->




                        if (

                            previewPosition ==
                            position

                        ) {



                            openFullscreen(

                                channel,

                                position

                            )



                        }
                        else {



                            previewPosition =
                                position



                            adapter.setPlaying(
                                position
                            )



                            showPreview(
                                channel
                            )

                            // showPreview() only resets the preview text fields to a
                            // "Loading TV Guide..." placeholder and starts playback - it
                            // does not fetch EPG data itself. Without this call, NOW/NEXT
                            // just kept showing whatever the previously-previewed
                            // channel's loadProgramGuide() call had last written.
                            loadProgramGuide(
                                channel
                            )

                        }

                    },




                onLongClicked =
                    { channel, _ ->


                        confirmRemoveFavorite(
                            channel
                        )

                    }

            )






        binding.rvChannels.adapter =
            adapter






        PlayerManager.attach(

            this,

            binding.playerView

        )

    }









    private fun showPreview(
        channel: LiveChannel
    ) {

        LiveWatchHistory.record(applicationContext, channel)



        binding.txtPlayerError.visibility =
            View.GONE









        PlayerManager.play(

            this,

            binding.playerView,

            buildStreamUrl(channel),
            channel.stream_id?.toString()

        )






        binding.txtOverlayChannel.text =
            channel.name
                ?: ""



        binding.txtOverlayProgram.text =
            "Loading TV Guide..."



        binding.txtNowTitle.text =
            "Loading TV Guide..."

    }









    private fun openFullscreen(
        channel: LiveChannel,
        position: Int
    ) {


        if (isFullscreen) return

        isFullscreen = true






        PlayerState.channels.clear()



        PlayerState.channels.addAll(
            channelList
        )






        PlayerState.currentPosition =
            position






        PlayerManager.play(

            this,

            binding.playerView,

            buildStreamUrl(channel),
            channel.stream_id?.toString()

        )







        val cardPlayer = binding.cardPlayer

        cardPlayerCornerRadius = cardPlayer.radius
        cardPlayerElevation = cardPlayer.cardElevation
        cardPlayer.radius = 0f
        cardPlayer.setContentPadding(0, 0, 0, 0)
        // MaterialCardView's elevation is a real Z-translation, so a
        // non-zero value here would render cardPlayer above the fs*
        // overlay controls (added later in XML but with no elevation of
        // their own) even though it is declared first - hiding them
        // completely behind a now full-screen, seemingly "stuck" video.
        cardPlayer.cardElevation = 0f

        if (cardPlayerNormalConstraintSet == null) {
            cardPlayerNormalConstraintSet = ConstraintSet().apply {
                clone(binding.contentRoot)
            }
        }

        ConstraintSet().apply {
            clone(binding.contentRoot)
            clear(cardPlayer.id, ConstraintSet.START)
            clear(cardPlayer.id, ConstraintSet.END)
            clear(cardPlayer.id, ConstraintSet.TOP)
            clear(cardPlayer.id, ConstraintSet.BOTTOM)
            connect(cardPlayer.id, ConstraintSet.START, binding.contentRoot.id, ConstraintSet.START)
            connect(cardPlayer.id, ConstraintSet.END, binding.contentRoot.id, ConstraintSet.END)
            connect(cardPlayer.id, ConstraintSet.TOP, binding.contentRoot.id, ConstraintSet.TOP)
            connect(cardPlayer.id, ConstraintSet.BOTTOM, binding.contentRoot.id, ConstraintSet.BOTTOM)
            setMargin(cardPlayer.id, ConstraintSet.START, 0)
            setMargin(cardPlayer.id, ConstraintSet.END, 0)
            setMargin(cardPlayer.id, ConstraintSet.TOP, 0)
            setMargin(cardPlayer.id, ConstraintSet.BOTTOM, 0)
            applyTo(binding.contentRoot)
        }

        binding.headerCard.visibility = View.GONE
        binding.cardChannels.visibility = View.GONE
        binding.cardEpg.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE
        binding.layoutOverlay.visibility = View.GONE

        binding.fsTxtChannelTitle.text = run {
            val streamId = channel.stream_id?.let { "$it - " } ?: ""
            "$streamId${channel.name ?: "Unknown Channel"}"
        }

        // onCreate() forces this GONE for the split-view inline preview,
        // which is the right call there - but it's the same PlayerView
        // fullscreen now reuses, so that leftover GONE was also silently
        // suppressing subtitles here regardless of fsToggleSubtitles().
        binding.playerView.subtitleView?.visibility = View.VISIBLE
        fsToggleSubtitles(fsSubtitleEnabled)

        loadProgramGuide(channel)

        showFsUiWithTimeout()

        binding.fsBtnPlayPause.post {
            binding.fsBtnPlayPause.requestFocus()
        }
    }


    private fun exitFullscreen() {

        if (!isFullscreen) return

        isFullscreen = false

        fsHideHandler.removeCallbacks(fsHideRunnable)

        val cardPlayer = binding.cardPlayer

        cardPlayer.radius = cardPlayerCornerRadius
        cardPlayer.cardElevation = cardPlayerElevation
        // The original layout sets no app:contentPadding, so 0 is correct here.
        cardPlayer.setContentPadding(0, 0, 0, 0)

        cardPlayerNormalConstraintSet?.applyTo(binding.contentRoot)

        binding.headerCard.visibility = View.VISIBLE
        binding.cardChannels.visibility = View.VISIBLE
        binding.cardEpg.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
        binding.layoutOverlay.visibility = View.VISIBLE

        binding.fsTopTint.visibility = View.GONE
        binding.fsBtnBack.visibility = View.GONE
        binding.fsTxtChannelTitle.visibility = View.GONE
        binding.fsBottomOverlay.visibility = View.GONE

        binding.playerView.subtitleView?.visibility = View.GONE

        // previewPosition may have moved (fsPlayNextChannel/fsPlayPreviousChannel
        // while fullscreen) - scroll the list to it and focus that row so the
        // remote lands back where the user actually left off, not wherever
        // focus happened to be before entering fullscreen.
        if (previewPosition in channelList.indices) {

            // moveFocus = false: the scroll+focus below already does this
            // explicitly (and rvChannels is only now becoming visible again,
            // whereas setPlaying()'s own version was written for it always
            // being visible - only the "playing" highlight update is needed
            // from it here).
            adapter.setPlaying(previewPosition, moveFocus = false)

            val targetPosition = previewPosition

            binding.rvChannels.post {

                binding.rvChannels.scrollToPosition(targetPosition)

                binding.rvChannels.post {

                    binding.rvChannels
                        .findViewHolderForAdapterPosition(targetPosition)
                        ?.itemView
                        ?.requestFocus()
                }
            }
        }
    }


    private fun showFsUiWithTimeout() {

        binding.fsBtnPlayPause.setImageResource(
            if (PlayerManager.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )

        val d = 300L

        if (binding.fsBottomOverlay.visibility != View.VISIBLE) {

            binding.fsTopTint.alpha = 0f
            binding.fsTopTint.visibility = View.VISIBLE
            binding.fsTopTint.animate().alpha(1f).setDuration(d).start()

            binding.fsBtnBack.alpha = 0f
            binding.fsBtnBack.visibility = View.VISIBLE
            binding.fsBtnBack.animate().alpha(1f).setDuration(d).start()

            binding.fsTxtChannelTitle.alpha = 0f
            binding.fsTxtChannelTitle.visibility = View.VISIBLE
            binding.fsTxtChannelTitle.animate().alpha(1f).setDuration(d).start()

            binding.fsBottomOverlay.alpha = 0f
            binding.fsBottomOverlay.translationY = 50f
            binding.fsBottomOverlay.visibility = View.VISIBLE
            binding.fsBottomOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(d)
                .withEndAction {
                    ensureOverlayControlHasFocus()
                }
                .start()
        } else {

            // The branch above (which requests focus once the fade-in
            // animation ends) only runs when the overlay was hidden. Every
            // other caller here - every button's own click listener resets
            // the auto-hide timer through this same function while the
            // overlay is already visible - skipped it entirely. Real
            // Android focus also gets cleared off these buttons the moment
            // fsHideRunnable hides the overlay (a GONE view cannot hold
            // focus) and nothing ever reclaimed it, so it silently fell
            // back to the root layout - from then on the D-pad simply had
            // nothing focusable to send CENTER/ENTER to, even though a
            // button still looked focused on screen. Restore it here too,
            // without stealing focus from a button the user has already
            // navigated to.
            ensureOverlayControlHasFocus()
        }

        fsHideHandler.removeCallbacks(fsHideRunnable)
        fsHideHandler.postDelayed(fsHideRunnable, 5000)
    }

    // Only claims focus for the play/pause button when nothing in the
    // fullscreen control row already has it, so this never overrides
    // deliberate D-pad navigation to a different button.
    private fun ensureOverlayControlHasFocus() {
        if (binding.fsBottomOverlay.findFocus() == null) {
            binding.fsBtnPlayPause.post {
                binding.fsBtnPlayPause.requestFocus()
            }
        }
    }


    private fun toggleFsUi() {

        if (binding.fsBottomOverlay.visibility == View.VISIBLE) {
            fsHideHandler.removeCallbacks(fsHideRunnable)
            fsHideRunnable.run()
        } else {
            showFsUiWithTimeout()
        }
    }


    private fun fsSwitchToChannel(position: Int) {

        val channel = channelList.getOrNull(position) ?: return

        previewPosition = position
        // moveFocus = false: rvChannels is View.GONE while fullscreen, so
        // the adapter's usual "scroll to row and requestFocus() it" would
        // fail on a hidden view - and a failed requestFocus() still clears
        // focus from wherever it actually was (fsBtnNext/fsBtnPrev),
        // leaving it stranded. exitFullscreen() re-syncs the list's focus
        // separately once it's visible again.
        adapter.setPlaying(position, moveFocus = false)

        PlayerState.currentPosition = position

        showPreview(channel)

        binding.fsTxtChannelTitle.text = run {
            val streamId = channel.stream_id?.let { "$it - " } ?: ""
            "$streamId${channel.name ?: "Unknown Channel"}"
        }

        loadProgramGuide(channel)
    }


    private fun fsPlayNextChannel() {

        if (channelList.isEmpty()) return

        val next = (previewPosition + 1).let {
            if (it >= channelList.size) 0 else it
        }

        fsSwitchToChannel(next)
    }


    private fun fsPlayPreviousChannel() {

        if (channelList.isEmpty()) return

        val prev = (previewPosition - 1).let {
            if (it < 0) channelList.size - 1 else it
        }

        fsSwitchToChannel(prev)
    }


    private fun fsToggleSubtitles(enable: Boolean) {

        val player = binding.playerView.player ?: return

        val builder = player.trackSelectionParameters.buildUpon()

        if (enable) {

            player.currentTracks.groups.forEach { group ->

                if (group.type == C.TRACK_TYPE_TEXT) {

                    for (i in 0 until group.length) {

                        if (group.isTrackSupported(i)) {

                            builder
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, i)
                                )

                            break
                        }
                    }
                }
            }

        } else {

            builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }

        player.trackSelectionParameters = builder.build()

        binding.fsBtnSubtitle.setColorFilter(
            if (enable) Color.parseColor("#FFC107") else Color.WHITE
        )
    }


    private fun setupFullscreenControls() {

        binding.btnFullscreen.setOnClickListener {

            if (previewPosition >= 0) {
                channelList.getOrNull(previewPosition)?.let { channel ->
                    openFullscreen(channel, previewPosition)
                }
            }
        }

        binding.fsBtnBack.setOnClickListener {
            exitFullscreen()
        }

        binding.contentRoot.setOnClickListener {
            if (isFullscreen) toggleFsUi()
        }
        // setOnClickListener() makes a view focusable by default. contentRoot
        // spans the entire screen, so once it became focusable it acted as
        // the D-pad's fallback focus target every time the fullscreen
        // buttons lost focus (e.g. when the overlay auto-hides). From then
        // on DPAD_CENTER landed on contentRoot instead - its own click
        // handler only toggles the overlay's visibility, so the overlay
        // still visibly reacted to remote presses while play/pause,
        // subtitle, aspect, etc. silently never received a real click.
        // Keep it clickable (for touch-to-toggle) but out of D-pad focus
        // traversal entirely.
        binding.contentRoot.isFocusable = false

        binding.playerView.setOnClickListener {

            if (isFullscreen) {
                toggleFsUi()
            } else if (
                isTouchDevice &&
                previewPosition != -1 &&
                channelList.isNotEmpty()
            ) {
                channelList.getOrNull(previewPosition)?.let { channel ->
                    openFullscreen(channel, previewPosition)
                }
            }
        }

        binding.fsBtnPlayPause.setOnClickListener {

            if (PlayerManager.isPlaying()) {
                PlayerManager.pause()
                binding.fsBtnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                PlayerManager.resume()
                binding.fsBtnPlayPause.setImageResource(R.drawable.ic_pause)
            }

            showFsUiWithTimeout()
        }

        binding.fsBtnNext.setOnClickListener {
            fsPlayNextChannel()
            showFsUiWithTimeout()
        }

        binding.fsBtnPrev.setOnClickListener {
            fsPlayPreviousChannel()
            showFsUiWithTimeout()
        }

        binding.fsBtnInfo.setOnClickListener {

            val id = channelList.getOrNull(previewPosition)?.stream_id

            if (id == null) {
                Toast.makeText(this, "Channel not available", Toast.LENGTH_SHORT).show()
            } else {
                StreamInfoDialog.newInstance()
                    .show(supportFragmentManager, "StreamInfoDialog")
            }

            showFsUiWithTimeout()
        }

        binding.fsBtnSubtitle.setOnClickListener {

            fsSubtitleEnabled = !fsSubtitleEnabled
            prefs.setSubtitlesEnabled(fsSubtitleEnabled)

            fsToggleSubtitles(fsSubtitleEnabled)

            Toast.makeText(
                this,
                if (fsSubtitleEnabled) "Subtitles Enabled" else "Subtitles Disabled",
                Toast.LENGTH_SHORT
            ).show()

            showFsUiWithTimeout()
        }

        binding.fsBtnGrid.setOnClickListener {

            if (PlayerState.channels.size < 2) {
                Toast.makeText(
                    this,
                    "Multi-view needs at least 2 channels",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startActivity(Intent(this, MultiViewActivity::class.java))
                showFsUiWithTimeout()
            }
        }
    }



    private fun forceRefreshData() {


        if (isRefreshing)
            return



        isRefreshing = true

        runCallbackSyncWithLoader(
            loadingMessage = "Downloading channels for the first time…"
        ) { onLoaderSuccess, onLoaderError ->





        repository.syncAllData(

            server =
                prefs.getServer(),

            username =
                prefs.getUsername(),

            password =
                prefs.getPassword(),



            callback =
                object : SyncCallback {



                    override fun onSuccess() {


                        isRefreshing = false



                        prefs.setLastSyncTime(
                            System.currentTimeMillis()
                        )



                        onLoaderSuccess()

                        loadAllChannelsToMemory(
                            true
                        )

                    }





                    override fun onError(
                        message: String
                    ) {


                        isRefreshing = false



                        onLoaderError(message)

                    }

                }
        )

        }

    }









    private fun setupDrawerAndMenu() {


        binding.btnMore.setOnClickListener {


            openRightDrawer(
                binding.drawerLayout
            )

        }






        setupOptionalRightDrawerMenu(

            binding.drawerLayout,

            binding.rightNav

        ) { itemId ->




            when(itemId) {



                R.id.action_home -> {


                    startActivity(

                        Intent(

                            this,

                            DashboardActivity::class.java

                        ).putExtra(DashboardActivity.EXTRA_REFRESH_ACCOUNT, true)

                    )


                    finish()


                    true

                }

                R.id.action_recently_watched -> {

                    startActivity(
                        Intent(
                            this,
                            RecentlyWatchedActivity::class.java
                        )
                    )

                    true
                }





                R.id.action_refresh_all -> {


                    forceRefreshData()


                    true

                }





                R.id.action_refresh_guide -> {
                    refreshTvGuide()
                    true
                }





                R.id.action_master_search -> {



                    startActivity(
                        Intent(
                            this@FavoriteChannelsActivity,
                            MasterChannelSearchActivity::class.java
                        )
                    )



                    true

                }




                R.id.action_logout -> {



                    lifecycleScope.launch {



                        DatabaseProvider
                            .get(this@FavoriteChannelsActivity)
                            .favoritesDao()
                            .clearAll()



                        prefs.clear()



                        startActivity(

                            Intent(

                                this@FavoriteChannelsActivity,

                                LoginActivity::class.java

                            )

                        )


                        finishAffinity()

                    }



                    true

                }




                R.id.action_exit_app -> { confirmExitApp(); true }
                else -> false

            }

        }

    }









    private fun setupSearch() {


        binding.edtSearch
            .addTextChangedListener(

                object :
                    TextWatcher {



                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit





                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {



                        val keyword =
                            s.toString()



                        val filtered =

                            allChannels.filter {



                                it.name
                                    ?.contains(
                                        keyword,
                                        true
                                    )
                                    ?: false

                            }







                        val favorites =

                            filtered.filter {



                                currentFavIds.contains(

                                    it.stream_id
                                        ?.toString()
                                        .orEmpty()

                                )

                            }







                        channelList.clear()



                        channelList.addAll(
                            favorites
                        )



                        adapter.updateData(
                            channelList
                        )

                    }





                    override fun afterTextChanged(
                        s: Editable?
                    ) = Unit

                }

            )

    }









    private fun confirmRemoveFavorite(
        channel: LiveChannel
    ) {



        showConfirmDialog(
            title = "Remove Favorite",
            message = "Remove ${channel.name} from favorites?",
            positiveText = "Remove",
            onPositive = { removeFromFavorites(channel) }
        )
    }









    private fun removeFromFavorites(
        channel: LiveChannel
    ) {


        val streamId =

            channel.stream_id
                ?.toString()
                ?: return






        lifecycleScope.launch {



            favRepo.removeFavorite(

                prefs.getUsername(),

                FavoriteItemType.LIVE_CHANNEL,

                streamId

            )

        }

    }









    private fun buildStreamUrl(channel: LiveChannel): String {
        return LiveStreamUrlBuilder.build(prefs, channel.stream_id)
    }


    // Guards against a slower, older loadProgramGuide() request resolving
    // after a newer one (e.g. the user pressing Next/Previous rapidly while
    // channel-surfing) and overwriting the correct EPG with stale data from
    // a channel that isn't even selected anymore.
    private var epgRequestGeneration = 0

    private fun loadProgramGuide(
        channel: LiveChannel
    ) {


        val epgId =

            channel.epg_channel_id
                ?: channel.stream_id
                    ?.toString()
                ?: return

        val requestGeneration = ++epgRequestGeneration






        lifecycleScope.launch {


            try {



                val (nowEpg, nextEpg) =

                    repository.getNowNextEpg(
                        epgId
                    )

                // The suspend call above is where a newer request can
                // overtake and finish first - re-check staleness now,
                // right before touching any UI, not just at the start.
                if (requestGeneration != epgRequestGeneration) return@launch







                if (
                    nowEpg != null
                ) {



                    binding.txtNowTitle.text =

                        nowEpg.title
                            ?: "No Program Info"




                    binding.txtNowTime.text =

                        "${EpgTimeFormatter.format(nowEpg.startTimestamp)} - ${EpgTimeFormatter.format(nowEpg.stopTimestamp)}"




                    binding.txtOverlayProgram.text =

                        nowEpg.title
                            ?: ""

                    binding.fsTxtNowTitle.text = nowEpg.title ?: "No Program Info"
                    binding.fsTxtNowTime.text = binding.txtNowTime.text

                    val progress = calculateEpgProgress(
                        nowEpg.startTimestamp,
                        nowEpg.stopTimestamp
                    )

                    binding.fsEpgTrack.post {
                        binding.fsEpgProgress.layoutParams =
                            binding.fsEpgProgress.layoutParams.apply {
                                width = (binding.fsEpgTrack.width * progress).toInt()
                            }
                    }

                }
                else {



                    binding.txtNowTitle.text =
                        "No EPG"



                    binding.txtNowTime.text =
                        ""



                    binding.txtOverlayProgram.text =
                        ""

                    binding.fsTxtNowTitle.text = "No Program Info"
                    binding.fsTxtNowTime.text = ""
                    binding.fsEpgProgress.layoutParams =
                        binding.fsEpgProgress.layoutParams.apply { width = 0 }

                }








                if (
                    nextEpg != null
                ) {



                    binding.txtNextTitle.text =

                        nextEpg.title
                            ?: ""





                    binding.txtNextTime.text =

                        "${EpgTimeFormatter.format(nextEpg.startTimestamp)} - ${EpgTimeFormatter.format(nextEpg.stopTimestamp)}"

                    binding.fsTxtNextTitle.text = nextEpg.title ?: ""
                    binding.fsTxtNextTime.text = binding.txtNextTime.text

                }
                else {



                    binding.txtNextTitle.text =
                        ""



                    binding.txtNextTime.text =
                        ""

                    binding.fsTxtNextTitle.text = ""
                    binding.fsTxtNextTime.text = ""

                }





            }
            catch(
                e: Exception
            ) {

                if (requestGeneration != epgRequestGeneration) return@launch

                binding.txtNowTitle.text =
                    "EPG unavailable"



                binding.txtNowTime.text =
                    ""



                binding.txtNextTitle.text =
                    ""



                binding.txtNextTime.text =
                    ""

                binding.fsTxtNowTitle.text = "EPG unavailable"
                binding.fsTxtNowTime.text = ""
                binding.fsTxtNextTitle.text = ""
                binding.fsTxtNextTime.text = ""
                binding.fsEpgProgress.layoutParams =
                    binding.fsEpgProgress.layoutParams.apply { width = 0 }

            }

        }

    }


    private fun calculateEpgProgress(
        startMs: Long?,
        stopMs: Long?
    ): Float {

        if (startMs == null || stopMs == null || stopMs <= startMs) {
            return 0f
        }

        return (
            (System.currentTimeMillis() - startMs).toFloat() /
                (stopMs - startMs).toFloat()
            ).coerceIn(0f, 1f)
    }

















    override fun onResume() {


        super.onResume()

        vpnToggle.refresh()

        PlayerManager.attach(

            this,

            binding.playerView

        )





        PlayerManager.resume()





        binding.playerView.player
            ?.addListener(

                playerListener

            )

        // onPause() cancels the auto-hide timer but never restarted it here -
        // so leaving fullscreen for another activity (Settings, MultiView)
        // and coming back left the top/bottom overlay controls permanently
        // visible with nothing left to hide them again. showFsUiWithTimeout()
        // is safe to call unconditionally: it only fades the overlay in if
        // it isn't already visible, and always reschedules the hide timer.
        if (isFullscreen) {
            showFsUiWithTimeout()
        }

    }









    override fun onPause() {


        super.onPause()

        fsHideHandler.removeCallbacks(fsHideRunnable)



        binding.playerView.player
            ?.removeListener(

                playerListener

            )






        // Fullscreen no longer navigates away from this Activity (it's an
        // in-place UI expansion now), so onPause() only fires for genuine
        // backgrounding - always pause here.
        PlayerManager.pause()






        PlayerManager.detach(

            binding.playerView

        )

    }









    override fun onDestroy() {


        PlayerManager.detach(

            binding.playerView

        )





        if (
            isFinishing
        ) {


            PlayerManager.stop()

        }






        super.onDestroy()

    }


    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {

        if (!isFullscreen) {
            return super.onKeyDown(keyCode, event)
        }

        if (binding.fsBottomOverlay.visibility != View.VISIBLE) {

            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {

                    showFsUiWithTimeout()
                    return true
                }
            }

        } else {

            fsHideHandler.removeCallbacks(fsHideRunnable)
            fsHideHandler.postDelayed(fsHideRunnable, 5000)

            when (keyCode) {

                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_DPAD_UP -> {
                    fsPlayNextChannel()
                    return true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    fsPlayPreviousChannel()
                    return true
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {

            showFsUiWithTimeout()

            if (PlayerManager.isPlaying()) {
                PlayerManager.pause()
            } else {
                PlayerManager.resume()
            }

            return true
        }

        return super.onKeyDown(keyCode, event)
    }
}
