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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager

import com.google.android.material.internal.NavigationMenuView
import com.google.firebase.firestore.FirebaseFirestore

import com.network24.player.R
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.common.utils.EpgTimeFormatter
import com.network24.player.common.utils.LiveStreamUrlBuilder
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityChannelListBinding

import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.live.adapter.ChannelAdapter
import com.network24.player.features.live.history.LiveWatchHistory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback
import com.network24.player.features.login.activity.LoginActivity
import com.network24.player.features.settings.activity.SettingsActivity

import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.multiview.MultiViewActivity
import com.network24.player.features.player.state.PlayerState
import com.network24.player.features.player.ui.dialogs.StreamInfoDialog

import kotlinx.coroutines.launch



class ChannelListActivity : BaseActivity() {

    override fun onTvGuideUpdated() {
        if (::binding.isInitialized) {
            val selectedStreamId = channelList
                .getOrNull(previewPosition)
                ?.stream_id
            loadChannels(
                forceRefresh = false,
                preserveStreamId = selectedStreamId
            )
        }
    }


    private lateinit var binding: ActivityChannelListBinding

    private lateinit var repository: LiveRepository

    private lateinit var prefs: PreferenceManager

    private lateinit var favRepo: FavoritesRepository

    private lateinit var adapter: ChannelAdapter



    private val isTouchDevice by lazy {
        !packageManager.hasSystemFeature(
            PackageManager.FEATURE_LEANBACK
        )
    }



    private var previewPosition = -1

    // True while cardPlayer is expanded to fill the screen in place of the
    // channel list / EPG cards. Unlike the old PlayerActivity-based
    // fullscreen, this never swaps binding.playerView's surface - the same
    // TextureView keeps rendering the whole time, so there is no decoder
    // handoff for the freeze to happen on.
    private var isFullscreen = false

    private var fsSubtitleEnabled = false
    private var fsAspectRatioIndex = 0

    // How long STATE_BUFFERING can run uninterrupted before we tell the user
    // their connection looks slow, instead of leaving them staring at a
    // silent spinner with no idea whether the app is stuck or just waiting
    // on a genuinely poor connection.
    private val slowBufferingHandler = Handler(Looper.getMainLooper())
    private val slowBufferingRunnable = Runnable {
        binding.txtPlayerError.text =
            "Still buffering…\nYour connection looks slow."
        binding.txtPlayerError.visibility = View.VISIBLE
    }

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
    // was (its own MaterialCardView corner radius/margins are relaxed to
    // 0 for a true edge-to-edge look while fullscreen).
    private var cardPlayerCornerRadius = 0f
    private var cardPlayerElevation = 0f
    private var cardPlayerNormalConstraintSet: ConstraintSet? = null


    private val allChannels =
        mutableListOf<LiveChannel>()


    private val channelList =
        mutableListOf<LiveChannel>()


    private lateinit var categoryId: String

    // Unlike FavoriteChannelsActivity, this was never wired up here, so the
    // buffering spinner never appeared while a stream connected/reconnected
    // - a slow or dead channel just looked like a black-screen hang with no
    // feedback until PlayerManager's recovery-failed listener (up to ~60s
    // later) finally showed txtPlayerError.
    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.progressLoading.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                if (playbackState == Player.STATE_BUFFERING) {
                    slowBufferingHandler.removeCallbacks(slowBufferingRunnable)
                    slowBufferingHandler.postDelayed(slowBufferingRunnable, 15000L)
                } else {
                    slowBufferingHandler.removeCallbacks(slowBufferingRunnable)
                }

                if (playbackState == Player.STATE_READY) {
                    binding.txtPlayerError.visibility = View.GONE

                    // A fresh load (PlayerManager's idle-release grace period
                    // tears the player down after ~20s backgrounded - e.g.
                    // time spent in Settings toggling Secure Relay, or in
                    // MultiView) hands back a new ExoPlayer with new
                    // TrackGroup instances. The subtitle TrackSelectionOverride
                    // set on the old ones no longer matches anything, and
                    // nothing else re-selects a text track, so subtitles
                    // silently stopped rendering even though fsSubtitleEnabled
                    // (and the on-screen toggle) still say they're on.
                    fsToggleSubtitles(fsSubtitleEnabled)
                }
            }

            // The single source of truth for the fullscreen play/pause icon.
            // Every call site that starts playback (openFullscreen, a
            // channel switch) sets the icon from isPlaying() right after
            // calling PlayerManager.play() - but play() only starts
            // buffering, it doesn't start playing immediately, so that
            // snapshot always read "not playing" and nothing ever corrected
            // it once buffering actually finished. This fires on the real
            // transition, whenever it happens.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.fsBtnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }



    override fun onCreate(
        savedInstanceState: Bundle?
    ) {


        super.onCreate(savedInstanceState)



        binding =
            ActivityChannelListBinding.inflate(
                layoutInflater
            )


        setContentView(
            binding.root
        )



        registerDrawerBackHandler(
            binding.drawerLayout
        )

        // Registered after registerDrawerBackHandler so it is invoked
        // first (OnBackPressedDispatcher calls the most-recently-added
        // enabled callback). Exiting fullscreen takes priority over the
        // drawer-close-then-finish behavior above.
        onBackPressedDispatcher.addCallback(this) {
            if (isFullscreen) {
                exitFullscreen()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }



        val db =
            DatabaseProvider.get(this)



        favRepo =
            FavoritesRepository(
                db.favoritesDao(),
                FirebaseFirestore.getInstance()
            )



        prefs =
            PreferenceManager(this)

        fsSubtitleEnabled = prefs.areSubtitlesEnabled()



        repository =
            LiveRepository(this)



        categoryId =
            intent.getStringExtra(
                "category_id"
            ) ?: ""



        binding.txtCategoryName.text =
            intent.getStringExtra(
                "category_name"
            ) ?: "Live TV"




        binding.btnBack.setOnClickListener {

            finish()
        }




        binding.playerView.setShowSubtitleButton(
            false
        )


        binding.playerView.subtitleView
            ?.visibility = View.GONE




        setupDrawerAndMenu()

        setupFullscreenControls()






        /*
         * PlayerManager recovery failed callback
         * Retry is handled only by PlayerManager.
         */

        PlayerManager.setRecoveryFailedListener {

            runOnUiThread {

                binding.progressLoading.visibility =
                    View.GONE

                when (
                    PlayerManager.getStreamErrorType()
                ) {

                    PlayerManager.StreamErrorType.NETWORK -> {

                        binding.txtPlayerError.text =
                            "Network connection lost.\nReconnecting..."

                        binding.txtPlayerError.visibility =
                            View.VISIBLE

                    }


                    PlayerManager.StreamErrorType.SOURCE -> {

                        binding.txtPlayerError.text =
                            "Unable to play this stream right now. It may be temporarily unavailable."

                        binding.txtPlayerError.visibility =
                            View.VISIBLE

                    }


                    else -> {

                        binding.txtPlayerError.text =
                            "Unable to play this stream right now."

                        binding.txtPlayerError.visibility =
                            View.VISIBLE

                    }
                }
            }
        }


        PlayerManager.setRecoveryStatusListener { attempt ->

            runOnUiThread {

                binding.txtPlayerError.text =
                    "Network connection lost.\nReconnecting...\nAttempt $attempt/5"

                binding.txtPlayerError.visibility =
                    View.VISIBLE

            }
        }

        PlayerManager.setRecoveryRecoveredListener {
            runOnUiThread {
                binding.txtPlayerError.visibility =
                    View.GONE

            }
        }




        // playerView's click listener (touch-open-fullscreen, plus
        // toggling the fullscreen control overlay once inside it) is set
        // up in setupFullscreenControls(), called below.



        setupRecycler()



        setupSearch()



        lifecycleScope.launch {


            db.favoritesDao()
                .observeByType(
                    FavoriteItemType.LIVE_CHANNEL
                )
                .collect { favs ->


                    val favIds =
                        favs.map {
                            it.itemId
                        }.toSet()



                    adapter.updateFavorites(
                        favIds
                    )
                }
        }





        ensureInitialSyncThenLoad()
    }








    private fun ensureInitialSyncThenLoad() {

        // repository.getChannels() silently does its own network sync when
        // this category has no channels locally yet (first-ever visit)
        // before returning - without a loader up front, that sync ran with
        // the channel list just sitting there empty, no feedback at all.
        showLoader("Loading channels…")

        lifecycleScope.launch {


            try {


                val channels =
                    repository.getChannels(
                        server = prefs.getServer(),
                        username = prefs.getUsername(),
                        password = prefs.getPassword(),
                        categoryId = categoryId,
                        forceRefresh = false
                    )



                if (channels.isNotEmpty()) {

                    hideLoader()

                    applyChannelsToUi(
                        channels
                    )


                } else {

                    // Same loader, reused - forceRefreshData() shows its own
                    // message on the same singleton dialog.
                    forceRefreshData(
                        isInitialSync = true
                    )
                }



            } catch (e: Exception) {

                hideLoader()

                Toast.makeText(
                    this@ChannelListActivity,
                    e.message
                        ?: "Initial load failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }








    private fun loadChannels(
        forceRefresh: Boolean = false,
        preserveStreamId: Int? = null
    ) {


        binding.edtSearch.text?.clear()

        binding.edtSearch.clearFocus()



        lifecycleScope.launch {


            try {


                val channels =
                    repository.getChannels(
                        server = prefs.getServer(),
                        username = prefs.getUsername(),
                        password = prefs.getPassword(),
                        categoryId = categoryId,
                        forceRefresh = forceRefresh
                    )



                applyChannelsToUi(
                    channels,
                    preserveStreamId
                )



            } catch (e: Exception) {


                Toast.makeText(
                    this@ChannelListActivity,
                    e.message
                        ?: "Failed loading channels",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }








    private fun applyChannelsToUi(
        channels: List<LiveChannel>,
        preserveStreamId: Int? = null
    ) {


        allChannels.clear()

        allChannels.addAll(
            channels
        )



        channelList.clear()

        channelList.addAll(
            channels
        )



        adapter.updateData(
            channelList
        )



        if (channelList.isEmpty()) return




        val preservedPosition = preserveStreamId
            ?.let { streamId ->
                channelList.indexOfFirst { it.stream_id == streamId }
            }
            ?.takeIf { it >= 0 }

        val targetPos = preservedPosition
            ?: if (PlayerState.currentPosition in channelList.indices) {
                PlayerState.currentPosition
            } else {
                0
            }

        val shouldRestartPlayback = preserveStreamId == null ||
            channelList[targetPos].stream_id != preserveStreamId




        previewPosition =
            targetPos



        adapter.setPlaying(
            targetPos
        )



        if (shouldRestartPlayback) {
            showPreview(channelList[targetPos])
        } else {
            binding.txtPlayerError.visibility = View.GONE
            binding.txtOverlayChannel.text = channelList[targetPos].name ?: ""
        }



        loadProgramGuide(
            channelList[targetPos]
        )



        if (!isTouchDevice) {


            binding.rvChannels.post {


                binding.rvChannels
                    .findViewHolderForAdapterPosition(
                        targetPos
                    )
                    ?.itemView
                    ?.requestFocus()
            }
        }
    }








    private var isRefreshing = false








    private fun forceRefreshData(
        isInitialSync: Boolean = false
    ) {


        if (isRefreshing) return



        isRefreshing = true



        val msg =

            if (isInitialSync)

                "Downloading Channels for the first time…"

            else

                "Refreshing channels & categories…"





        runCallbackSyncWithLoader(

            loadingMessage = msg,

            successMessage = "Channels Refreshed Successfully!"

        ) { onSuccess, onError ->




            repository.syncAllData(

                server = prefs.getServer(),

                username = prefs.getUsername(),

                password = prefs.getPassword(),


                callback = object : SyncCallback {



                    override fun onSuccess() {


                        isRefreshing = false


                        prefs.setLastSyncTime(
                            System.currentTimeMillis()
                        )


                        onSuccess()


                        loadChannels(
                            forceRefresh = true
                        )
                    }




                    override fun onError(
                        message: String
                    ) {


                        isRefreshing = false


                        onError(
                            "Failed to refresh: $message"
                        )
                    }

                    override fun onProgress(percent: Int) {
                        showLoader("$msg $percent%")
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


            when (itemId) {



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
                    startActivity(Intent(this, RecentlyWatchedActivity::class.java))
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
                            this,
                            MasterChannelSearchActivity::class.java
                        )
                    )



                    true
                }




                R.id.action_settings -> {


                    startActivity(
                        Intent(
                            this,
                            SettingsActivity::class.java
                        )
                    )


                    true
                }





                R.id.action_logout -> {


                    lifecycleScope.launch {


                        try {


                            DatabaseProvider
                                .get(this@ChannelListActivity)
                                .favoritesDao()
                                .clearAll()


                        } catch (_: Exception) {

                        }



                        prefs.clear()



                        startActivity(
                            Intent(
                                this@ChannelListActivity,
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




        binding.drawerLayout
            .addDrawerListener(
                object : DrawerLayout.SimpleDrawerListener() {


                    override fun onDrawerOpened(
                        drawerView: View
                    ) {


                        if (
                            drawerView.id ==
                            binding.rightNav.id
                        ) {


                            binding.rightNav.post {


                                val menuView =
                                    binding.rightNav
                                        .getChildAt(0)
                                            as? NavigationMenuView



                                if (menuView != null) {


                                    for (
                                    i in 0 until menuView.childCount
                                    ) {


                                        val child =
                                            menuView.getChildAt(i)



                                        if (
                                            child.isFocusable
                                        ) {


                                            child.requestFocus()

                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
    }









    private fun setupRecycler() {


        binding.rvChannels.layoutManager =
            LinearLayoutManager(this)



        adapter =
            ChannelAdapter(

                channels = mutableListOf(),

                favoriteIds = emptySet(),



                onFocused = { _, _ -> },



                onClicked = { channel, position ->



                    if (
                        previewPosition == position
                    ) {


                        openFullscreen(
                            channel,
                            position
                        )


                    } else {



                        previewPosition =
                            position



                        adapter.setPlaying(
                            position
                        )



                        showPreview(
                            channel
                        )



                        loadProgramGuide(
                            channel
                        )
                    }
                },



                onLongClicked = { channel, _ ->


                    confirmToggleFavorite(
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









    private fun setupSearch() {


        binding.edtSearch
            .addTextChangedListener(

                object : TextWatcher {


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


                        filterChannels(
                            s.toString()
                        )
                    }



                    override fun afterTextChanged(
                        s: Editable?
                    ) = Unit
                }
            )
    }









    private fun filterChannels(
        keyword: String
    ) {


        val filtered =
            allChannels.filter { channel ->


                channel.name
                    ?.contains(
                        keyword,
                        ignoreCase = true
                    )
                    ?: false
            }




        channelList.clear()


        channelList.addAll(
            filtered
        )



        adapter.updateData(
            channelList
        )



        if (
            previewPosition != -1 &&
            allChannels.isNotEmpty() &&
            channelList.isNotEmpty()
        ) {



            val currentlyPlayingChannel =
                allChannels[previewPosition]



            adapter.setPlaying(
                channelList.indexOf(
                    currentlyPlayingChannel
                )
            )



        } else {


            adapter.setPlaying(-1)
        }
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
            channel.name ?: ""



        binding.txtOverlayProgram.text =
            "Loading TV Guide..."



        binding.txtNowTitle.text =
            "Loading TV Guide..."



        binding.txtNowTime.text =
            ""



        binding.txtNextTitle.text =
            ""



        binding.txtNextTime.text =
            ""
    }









    // Fullscreen used to be a separate PlayerActivity, reached via
    // startActivity() + PlayerManager.moveTo() handing the player's video
    // surface from this screen's inline PlayerView over to a brand new one.
    // That handoff is a real decoder-level operation (MediaCodec output
    // surface retarget), and on this hardware it visibly freezes the last
    // frame for a moment - no transition-animation trick can hide a stall
    // that happens below the UI layer. Expanding cardPlayer in place
    // instead means binding.playerView is never swapped, so there is no
    // handoff left to freeze on.
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
        // completely behind an now full-screen, seemingly "stuck" video.
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
        // showPreview() only resets the split-view text fields to a
        // "Loading..." placeholder and starts playback - it does not fetch
        // EPG data itself. Every other caller (the split-view row click
        // handler) calls this separately right after showPreview(); this
        // one was missing it entirely, so the fullscreen EPG (and the
        // channel title, had it not been set directly below) never
        // actually updated on channel switch - it just kept showing
        // whatever the previous channel's loadProgramGuide() call had last
        // written.
        loadProgramGuide(channel)

        binding.fsTxtChannelTitle.text = run {
            val streamId = channel.stream_id?.let { "$it - " } ?: ""
            "$streamId${channel.name ?: "Unknown Channel"}"
        }
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


    private fun fsCycleAspectRatio() {

        fsAspectRatioIndex = (fsAspectRatioIndex + 1) % 4

        val msg = when (fsAspectRatioIndex) {

            0 -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                "Aspect Ratio: Fit"
            }

            1 -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                "Aspect Ratio: Fill"
            }

            2 -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "Aspect Ratio: Zoom"
            }

            else -> {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                "Aspect Ratio: Fixed Width"
            }
        }

        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        showFsUiWithTimeout()
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

        binding.fsBtnAspect.setOnClickListener {
            fsCycleAspectRatio()
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
                ?: channel.stream_id?.toString()
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



                if (nowEpg != null) {


                    binding.txtNowTitle.text =
                        nowEpg.title
                            ?: "No Program Info"



                    binding.txtNowTime.text =
                        "${EpgTimeFormatter.format(nowEpg.startTimestamp)} - ${EpgTimeFormatter.format(nowEpg.stopTimestamp)}"



                    binding.txtOverlayProgram.text =
                        nowEpg.title
                            ?: ""


                    binding.fsTxtNowTitle.text =
                        nowEpg.title ?: "No Program Info"

                    binding.fsTxtNowTime.text =
                        binding.txtNowTime.text

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



                } else {



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




                if (nextEpg != null) {


                    binding.txtNextTitle.text =
                        nextEpg.title
                            ?: ""



                    binding.txtNextTime.text =
                        "${EpgTimeFormatter.format(nextEpg.startTimestamp)} - ${EpgTimeFormatter.format(nextEpg.stopTimestamp)}"


                    binding.fsTxtNextTitle.text = nextEpg.title ?: ""
                    binding.fsTxtNextTime.text = binding.txtNextTime.text



                } else {


                    binding.txtNextTitle.text =
                        ""



                    binding.txtNextTime.text =
                        ""

                    binding.fsTxtNextTitle.text = ""
                    binding.fsTxtNextTime.text = ""
                }




            } catch (_: Exception) {

                if (requestGeneration != epgRequestGeneration) return@launch

                binding.txtNowTitle.text =
                    "EPG unavailable"



                binding.txtNowTime.text =
                    ""



                binding.txtNextTitle.text =
                    ""



                binding.txtNextTime.text =
                    ""



                binding.txtOverlayProgram.text =
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

















    private fun toggleChannelFavorite(
        channel: LiveChannel
    ) {


        val streamId =
            channel.stream_id
                ?.toString()
                ?: return



        val userId =
            prefs.getUsername()



        lifecycleScope.launch {


            val key =
                "LIVE_CHANNEL:$streamId"



            val isFav =
                DatabaseProvider
                    .get(this@ChannelListActivity)
                    .favoritesDao()
                    .getAll()
                    .any {
                        it.key == key
                    }



            if (isFav) {


                favRepo.removeFavorite(
                    userId,
                    FavoriteItemType.LIVE_CHANNEL,
                    streamId
                )



                Toast.makeText(
                    this@ChannelListActivity,
                    "${channel.name} removed from Favorites",
                    Toast.LENGTH_SHORT
                ).show()



            } else {


                favRepo.addFavorite(
                    userId,
                    FavoriteItemType.LIVE_CHANNEL,
                    streamId
                )



                Toast.makeText(
                    this@ChannelListActivity,
                    "${channel.name} added to Favorites",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }









    private fun confirmToggleFavorite(
        channel: LiveChannel
    ) {


        val name =
            channel.name
                ?: "this channel"



        showConfirmDialog(
            title = "Favorites",
            message = "Do you want to add $name to Favorites?",
            positiveText = "Yes",
            negativeText = "No",
            onPositive = { toggleChannelFavorite(channel) }
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



        if (
            previewPosition >= 0 &&
            channelList.isNotEmpty()
        ) {



            val player =
                PlayerManager.getExoPlayerOrNull()



            if (player != null) {



                when (
                    player.playbackState
                ) {



                    Player.STATE_READY,
                    Player.STATE_BUFFERING -> {


                        player.play()
                    }




                    Player.STATE_IDLE,
                    Player.STATE_ENDED -> {


                        channelList
                            .getOrNull(previewPosition)
                            ?.let { currentChannel ->



                                PlayerManager.play(
                                    this,
                                    binding.playerView,
                                    buildStreamUrl(
                                        currentChannel
                                    ),
                                    currentChannel.stream_id?.toString()
                                )
                            }
                    }
                }
            }
        }

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
        slowBufferingHandler.removeCallbacks(slowBufferingRunnable)

        binding.playerView.player
            ?.removeListener(playerListener)
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
                    // The on-screen Next/Prev buttons call this after
                    // switching too - without it here the play/pause icon
                    // kept showing whatever the previous channel's state
                    // was (e.g. still "paused" after switching away from a
                    // paused channel, even though the new one autoplays).
                    showFsUiWithTimeout()
                    return true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    fsPlayPreviousChannel()
                    showFsUiWithTimeout()
                    return true
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {

            // Toggle first, then refresh the UI - showFsUiWithTimeout() sets
            // the play/pause icon from PlayerManager.isPlaying(), so calling
            // it before the toggle below read the state as it was BEFORE
            // this key press and always displayed the icon for the state
            // being left, not the state being entered.
            if (PlayerManager.isPlaying()) {
                PlayerManager.pause()
            } else {
                PlayerManager.resume()
            }

            showFsUiWithTimeout()

            return true
        }

        return super.onKeyDown(keyCode, event)
    }


    override fun onDestroy() {


        PlayerManager.setRecoveryFailedListener(
            null
        )

        PlayerManager.setRecoveryStatusListener(
            null
        )

        PlayerManager.setRecoveryRecoveredListener(
            null
        )



        super.onDestroy()
    }
}
