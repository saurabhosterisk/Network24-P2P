package com.network24.player.features.live.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

import coil.load
import com.google.firebase.firestore.FirebaseFirestore

import com.network24.player.R
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.common.utils.LiveStreamUrlBuilder
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.sync.SyncManager
import com.network24.player.databinding.ActivityEpgChannelListBinding

import com.network24.player.features.live.history.LiveWatchHistory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.live.repository.LiveRepository

import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.multiview.MultiViewActivity
import com.network24.player.features.player.state.PlayerState
import com.network24.player.features.player.ui.dialogs.StreamInfoDialog
import com.network24.player.features.vpn.util.FullscreenVpnToggle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale





class EpgChannelListActivity : BaseActivity() {

    override fun onTvGuideUpdated() {
        if (::binding.isInitialized) loadChannels()
    }

    /** Called by the EPG screen's drawer binder. */
    internal fun refreshGuideFromMenu() = refreshTvGuide()



    private lateinit var binding:
            ActivityEpgChannelListBinding



    private lateinit var repository:
            LiveRepository



    private lateinit var prefs:
            PreferenceManager


    private lateinit var favoritesRepository:
            FavoritesRepository



    private lateinit var categoryId:
            String



    private var categoryName =
        ""



    private val channels =
        mutableListOf<LiveChannel>()



    private val epgByChannel =
        mutableMapOf<String, List<EpgEntity>>()



    private var selectedChannel:
            LiveChannel? = null


    /**
     * The channel currently playing in the compact EPG preview. Focus may move
     * around the guide without changing playback, so this is intentionally
     * separate from [selectedChannel].
     */
    private var playingChannel:
            LiveChannel? = null



    private var channelWidthDp =
        220



    internal val minuteWidthDp =
        9.0f



    private val rowHeightDp =
        70



    private val headerHeightDp =
        40



    internal var timelineStart =
        0L



    internal var timelineEnd =
        0L



    private var syncingVertical =
        false



    private var syncingHorizontal =
        false



    private var lastHorizontalX =
        0



    private lateinit var loadingMask:
            FrameLayout



    private val channelFocusViews =
        mutableListOf<View>()



    private val programFocusRows =
        mutableListOf<MutableList<View>>()



    private var pendingFocusChannelId:
            Int? = null



    private var pendingFocusProgramKey:
            String? = null



    // (expectingFullscreenReturn removed: openFullscreen no longer leaves
    // this activity, so there is no "returned from PlayerActivity" case
    // to detect in onResume anymore.)

    // True while topCard is expanded to fill the screen in place of the
    // channel/EPG grid. The same binding.playerView keeps rendering the
    // whole time - no surface handoff, so no decoder-level freeze on toggle.
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

    // Saved so exitFullscreen() can put topCard back exactly where it was.
    private var topCardCornerRadius = 0f
    private var topCardElevation = 0f
    private var topCardStrokeWidth = 0
    private var contentRootNormalConstraintSet: ConstraintSet? = null
    private var topCardInnerNormalConstraintSet: ConstraintSet? = null





    private val nowHandler =
        Handler(
            Looper.getMainLooper()
        )





    private val nowLineRunnable =
        object : Runnable {


            override fun run() {


                if (
                    !isFinishing &&
                    channels.isNotEmpty()
                ) {


                    renderGrid(
                        preserveScroll = true
                    )
                }



                nowHandler.postDelayed(
                    this,
                    60_000L
                )
            }
        }

    /**
     * Full grid rebuilds are expensive on low-RAM devices, so this must only
     * ever have one pending tick scheduled at a time. removeCallbacks() first
     * is required: without it, calling this from both the initial-load path
     * and onResume would leave two independent tick chains running forever.
     */
    private fun scheduleNowLineTick() {
        nowHandler.removeCallbacks(nowLineRunnable)
        nowHandler.postDelayed(nowLineRunnable, 60_000L)
    }




    private val playerListener =
        object : Player.Listener {


            override fun onPlaybackStateChanged(
                playbackState: Int
            ) {


                binding.progressLoading.visibility =
                    if (playbackState == Player.STATE_BUFFERING) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                if (playbackState == Player.STATE_READY) {
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


            override fun onPlayerError(
                error: PlaybackException
            ) {
                binding.progressLoading.visibility =
                    View.GONE
            }
        }









    override fun onCreate(
        savedInstanceState: Bundle?
    ) {


        super.onCreate(
            savedInstanceState
        )



        binding =
            ActivityEpgChannelListBinding.inflate(
                layoutInflater
            )



        setContentView(
            binding.root
        )



        prefs =
            PreferenceManager(this)

        fsSubtitleEnabled = prefs.areSubtitlesEnabled()


        favoritesRepository =
            FavoritesRepository(
                DatabaseProvider.get(this).favoritesDao(),
                FirebaseFirestore.getInstance()
            )



        repository =
            LiveRepository(this)



        categoryId =
            intent.getStringExtra(
                "category_id"
            )
                ?.trim()
                .orEmpty()



        categoryName =
            intent.getStringExtra(
                "category_name"
            )
                ?.trim()
                .orEmpty()



        binding.txtCategoryName.text =
            categoryName.ifBlank {
                "LIVE WITH EPG"
            }




        binding.btnBack.setOnClickListener {

            finish()
        }

        // Exiting fullscreen takes priority over the default finish()
        // above - registered after so it is the callback the dispatcher
        // invokes first.
        onBackPressedDispatcher.addCallback(this) {
            if (isFullscreen) {
                exitFullscreen()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }





        PlayerManager.attach(
            this,
            binding.playerView
        )


        // Touching the active preview expands it in place to fill the
        // screen; touching it again while already fullscreen just toggles
        // the control overlay.
        binding.playerView.setOnClickListener {
            if (isFullscreen) {
                toggleFsUi()
            } else {
                playingChannel?.let(::openFullscreen)
            }
        }





        PlayerManager.setRecoveryFailedListener {


            runOnUiThread {


                binding.progressLoading.visibility =
                    View.GONE


                binding.txtEpgStatus.text =
                    "Unable to play this stream right now. It may be temporarily unavailable or your connection may be unstable."

            }
        }





        setupLoadingMask()


        setupChannelColumnWidth()


        setupStickyScrolling()

        vpnToggle = FullscreenVpnToggle(this, binding.fsBtnVpn, binding.fsBtnVpnRotate) { showFsUiWithTimeout() }
        vpnToggle.register()

        setupFullscreenControls()


        loadChannels()
    }


    private fun setupLoadingMask() {


        loadingMask =
            FrameLayout(this).apply {


                setBackgroundColor(
                    Color.argb(
                        205,
                        8,
                        6,
                        24
                    )
                )


                isClickable = true


                isFocusable = true


                elevation = 50f
            }




        val content =
            LinearLayout(this).apply {


                orientation =
                    LinearLayout.VERTICAL


                gravity =
                    Gravity.CENTER


                setPadding(
                    dp(28),
                    dp(24),
                    dp(28),
                    dp(24)
                )



                background =
                    GradientDrawable().apply {


                        cornerRadius =
                            dp(14).toFloat()


                        setColor(
                            Color.rgb(
                                28,
                                24,
                                58
                            )
                        )


                        setStroke(
                            dp(1),
                            Color.rgb(
                                76,
                                64,
                                125
                            )
                        )
                    }
            }





        val progress =
            ProgressBar(this).apply {


                isIndeterminate =
                    true


                indeterminateTintList =
                    android.content.res.ColorStateList.valueOf(
                        Color.rgb(
                            124,
                            77,
                            255
                        )
                    )
            }





        content.addView(
            progress,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )





        content.addView(
            TextView(this).apply {


                text =
                    "Loading Live With EPG"


                setTextColor(
                    Color.WHITE
                )


                textSize =
                    17f


                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )


                gravity =
                    Gravity.CENTER


                setPadding(
                    0,
                    dp(14),
                    0,
                    0
                )

            },
            LinearLayout.LayoutParams(
                dp(250),
                -2
            )
        )





        content.addView(
            TextView(this).apply {


                text =
                    "Loading channels and programme guide…"


                setTextColor(
                    Color.rgb(
                        190,
                        184,
                        215
                    )
                )


                textSize =
                    13f


                gravity =
                    Gravity.CENTER


                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )

            },
            LinearLayout.LayoutParams(
                dp(250),
                -2
            )
        )





        loadingMask.addView(
            content,
            FrameLayout.LayoutParams(
                dp(310),
                -2,
                Gravity.CENTER
            )
        )




        (binding.root as ViewGroup)
            .addView(
                loadingMask,
                ViewGroup.LayoutParams(
                    -1,
                    -1
                )
            )



        loadingMask.bringToFront()
    }







    private fun hideLoadingMask() {


        if (
            ::loadingMask.isInitialized
        ) {


            loadingMask.visibility =
                View.GONE
        }
    }









    private fun setupChannelColumnWidth() {


        binding.epgArea.post {


            val density =
                resources.displayMetrics.density



            val playerWidthPx =
                (
                        binding.topCard.width
                                - dp(16)
                        )
                    .coerceAtLeast(0)




            val targetPx =
                (
                        playerWidthPx * 0.30f
                        )
                    .toInt()




            channelWidthDp =
                (
                        targetPx / density
                        )
                    .toInt()
                    .coerceAtLeast(220)




            binding.stickyDate.layoutParams =
                binding.stickyDate.layoutParams.apply {

                    width =
                        targetPx
                }




            binding.epgHeaderScroll.layoutParams =
                (
                        binding.epgHeaderScroll.layoutParams
                                as ViewGroup.MarginLayoutParams
                        )
                    .apply {


                        width =
                            (
                                    binding.epgArea.width
                                            - targetPx
                                    )
                                .coerceAtLeast(0)



                        marginStart =
                            targetPx
                    }




            binding.channelVerticalScroll.layoutParams =
                binding.channelVerticalScroll.layoutParams.apply {


                    width =
                        targetPx
                }





            binding.channelVerticalScroll
                .getChildAt(0)
                ?.layoutParams
                ?.width =
                targetPx





            binding.epgHorizontalScroll.layoutParams =
                (
                        binding.epgHorizontalScroll.layoutParams
                                as ViewGroup.MarginLayoutParams
                        )
                    .apply {


                        marginStart =
                            targetPx
                    }





            binding.epgArea.requestLayout()



            if (
                channels.isNotEmpty()
            ) {


                renderGrid(
                    true
                )
            }
        }
    }









    private fun setupStickyScrolling() {


        binding.epgHorizontalScroll
            .setOnScrollChangeListener {


                    _,
                    x,
                    _,
                    _,
                    _ ->



                if (
                    !syncingHorizontal
                ) {


                    syncingHorizontal =
                        true



                    binding.epgHeaderScroll
                        .scrollTo(
                            x,
                            0
                        )



                    syncingHorizontal =
                        false
                }



                if (
                    x != lastHorizontalX
                ) {


                    lastHorizontalX =
                        x



                    updateStickyDate(
                        x
                    )
                }
            }





        binding.epgHeaderScroll
            .setOnScrollChangeListener {


                    _,
                    x,
                    _,
                    _,
                    _ ->



                if (
                    !syncingHorizontal
                ) {


                    syncingHorizontal =
                        true



                    binding.epgHorizontalScroll
                        .scrollTo(
                            x,
                            0
                        )



                    syncingHorizontal =
                        false
                }



                if (
                    x != lastHorizontalX
                ) {


                    lastHorizontalX =
                        x



                    updateStickyDate(
                        x
                    )
                }
            }





        binding.epgVerticalScroll
            .setOnScrollChangeListener {


                    _,
                    _,
                    y,
                    _,
                    _ ->



                if (
                    !syncingVertical
                ) {


                    syncingVertical =
                        true



                    binding.channelVerticalScroll
                        .scrollTo(
                            0,
                            y
                        )



                    syncingVertical =
                        false
                }
            }





        binding.channelVerticalScroll
            .setOnScrollChangeListener {


                    _,
                    _,
                    y,
                    _,
                    _ ->



                if (
                    !syncingVertical
                ) {


                    syncingVertical =
                        true



                    binding.epgVerticalScroll
                        .scrollTo(
                            0,
                            y
                        )



                    syncingVertical =
                        false
                }
            }
    }









    internal fun loadChannels() {


        binding.txtEpgStatus.text =
            "Loading channels…"




        lifecycleScope.launch {


            try {



                if (
                    categoryId.isBlank()
                ) {


                    binding.txtEpgStatus.text =
                        "Invalid category"



                    hideLoadingMask()



                    return@launch
                }





                var result =
                    repository.getChannels(
                        prefs.getServer(),
                        prefs.getUsername(),
                        prefs.getPassword(),
                        categoryId,
                        false
                    )





                if (
                    result.isEmpty()
                ) {


                    result =
                        repository.getChannels(
                            prefs.getServer(),
                            prefs.getUsername(),
                            prefs.getPassword(),
                            categoryId,
                            true
                        )
                }





                channels.clear()



                channels.addAll(
                    result
                )





                if (
                    channels.isEmpty()
                ) {


                    binding.txtEpgStatus.text =
                        "No channels available in this category"



                    hideLoadingMask()



                    return@launch
                }




                loadGuideData()



                scheduleNowLineTick()



            } catch (e: Exception) {


                binding.txtEpgStatus.text =
                    e.message
                        ?: "Unable to load channels"



                hideLoadingMask()
            }
        }
    }


    internal fun loadGuideData() {


        binding.txtEpgStatus.text =
            "Loading 2-day guide…"




        lifecycleScope.launch(
            Dispatchers.IO
        ) {


            try {


                val ids =
                    channels
                        .mapNotNull {
                            it.epg_channel_id
                                ?.takeIf(String::isNotBlank)
                        }
                        .distinct()



                val now =
                    System.currentTimeMillis()



                val end =
                    startOfDay(2)



                val db =
                    DatabaseProvider
                        .get(
                            this@EpgChannelListActivity
                        )



                var listings =

                    if (
                        ids.isEmpty()
                    )

                        emptyList()

                    else

                        db.epgDao()
                            .getByEpgChannelIds(
                                ids,
                                now,
                                end
                            )





                if (
                    ids.isNotEmpty() &&
                    listings.isEmpty()
                ) {


                    val syncResult =
                        SyncManager(
                            this@EpgChannelListActivity
                        )
                            .syncFullEpg(
                                force = true
                            )



                    if (
                        syncResult !is
                                com.network24.player.core.sync.SyncResult.Error
                    ) {


                        listings =
                            db.epgDao()
                                .getByEpgChannelIds(
                                    ids,
                                    now,
                                    end
                                )
                    }
                }





                epgByChannel.clear()



                epgByChannel.putAll(
                    listings.groupBy {
                        it.epgChannelId.orEmpty()
                    }
                )





                withContext(
                    Dispatchers.Main
                ) {


                    renderGrid(
                        preserveScroll = false
                    )
                }




            } catch (e: Exception) {



                withContext(
                    Dispatchers.Main
                ) {


                    binding.txtEpgStatus.text =
                        e.message
                            ?: "EPG unavailable"



                    renderGrid(
                        preserveScroll = false
                    )
                }
            }
        }
    }









    private fun renderGrid(
        preserveScroll: Boolean
    ) {

        // The periodic now-line refresh (nowLineRunnable, every 60s) gets
        // here with preserveScroll=true and rebuilds every cell from
        // scratch to update live-program highlighting. removeAllViews()
        // below destroys whatever the user currently has focused, and
        // Android's default focus-recovery on a detached view does not
        // reliably land back on the equivalent new cell - it can jump
        // anywhere, which is what made up/down navigation feel like it
        // randomly broke while browsing (more likely the longer someone
        // lingers, e.g. scrolling toward the end of a long channel list).
        // Capturing the real focus target first lets the existing
        // restorePendingFocus() mechanism put it back exactly where it
        // was. Gated to preserveScroll only so the unrelated initial-load
        // path (which sets pendingFocusChannelId itself, e.g. after a
        // category switch) is untouched.
        if (preserveScroll) {
            captureCurrentGridFocus()
        }


        val savedX =

            if (preserveScroll)

                binding.epgHorizontalScroll.scrollX

            else

                0




        val savedY =

            if (preserveScroll)

                binding.epgVerticalScroll.scrollY

            else

                0





        timelineStart =
            floorToHalfHour(
                System.currentTimeMillis()
            )



        timelineEnd =
            startOfDay(
                2
            )



        if (
            timelineEnd <= timelineStart
        ) {


            timelineEnd =
                timelineStart +
                        2L *
                        24L *
                        60L *
                        60L *
                        1000L
        }





        binding.epgHeaderContainer
            .removeAllViews()



        binding.channelContainer
            .removeAllViews()



        binding.epgRowsContainer
            .removeAllViews()



        channelFocusViews.clear()



        programFocusRows.clear()





        renderTimelineHeader()





        channels.forEachIndexed {
                _,
                channel ->



            addStickyChannel(
                channel
            )



            addEpgRow(channel)
        }





        wireFocusNavigation()



        binding.txtEpgStatus.text =
            "${channels.size} channels • 2-day guide"





        if (
            selectedChannel == null &&
            channels.isNotEmpty()
        ) {


            updateTopInfo(
                channels.first()
            )



            if (
                !preserveScroll
            ) {


                pendingFocusChannelId =
                    channels.first()
                        .stream_id
            }
        }






        updateStickyDate(
            savedX
        )





        binding.epgArea.post {


            binding.epgHorizontalScroll
                .scrollTo(
                    savedX.coerceAtLeast(0),
                    0
                )



            binding.epgHeaderScroll
                .scrollTo(
                    savedX.coerceAtLeast(0),
                    0
                )



            binding.epgVerticalScroll
                .scrollTo(
                    0,
                    savedY.coerceAtLeast(0)
                )



            binding.channelVerticalScroll
                .scrollTo(
                    0,
                    savedY.coerceAtLeast(0)
                )



            updateStickyDate(
                binding.epgHorizontalScroll.scrollX
            )



            restorePendingFocus()



            hideLoadingMask()
        }
    }









    private fun renderTimelineHeader() {


        val totalMinutes =

            (
                    (timelineEnd - timelineStart)
                            /
                            60_000L
                    )
                .coerceAtLeast(
                    30L
                )




        val timeline =
            LinearLayout(this).apply {


                orientation =
                    LinearLayout.HORIZONTAL



                gravity =
                    Gravity.CENTER_VERTICAL



                setBackgroundColor(
                    Color.rgb(
                        33,
                        30,
                        58
                    )
                )
            }





        val time =
            Calendar.getInstance().apply {


                timeInMillis =
                    timelineStart
            }




        var elapsed =
            0L




        while (
            elapsed < totalMinutes
        ) {



            val label =
                TextView(this).apply {


                    text =
                        SimpleDateFormat(
                            "h:mm a",
                            Locale.getDefault()
                        )
                            .format(
                                time.time
                            )



                    gravity =
                        Gravity.CENTER



                    setTextColor(
                        Color.rgb(
                            213,
                            208,
                            255
                        )
                    )



                    textSize =
                        13f



                    setTypeface(
                        typeface,
                        android.graphics.Typeface.BOLD
                    )



                    setPadding(
                        dp(4),
                        0,
                        dp(4),
                        0
                    )



                    background =
                        null
                }





            timeline.addView(

                label,

                LinearLayout.LayoutParams(
                    (30L * minuteWidthDp)
                        .toInt(),

                    dp(headerHeightDp)
                )
            )




            time.add(
                Calendar.MINUTE,
                30
            )



            elapsed +=
                30L
        }





        binding.epgHeaderContainer
            .addView(

                timeline,

                LinearLayout.LayoutParams(
                    -2,
                    dp(headerHeightDp)
                )
            )
    }









    private fun addStickyChannel(
        channel: LiveChannel
    ) {


        val panel =
            createChannelPanel(
                channel
            )



        panel.id =
            View.generateViewId()



        panel.tag =
            channel.stream_id



        channelFocusViews.add(
            panel
        )



        binding.channelContainer.addView(

            panel,

            LinearLayout.LayoutParams(
                dp(channelWidthDp - 5),
                dp(rowHeightDp - 6)
            ).apply {


                topMargin =
                    dp(3)



                bottomMargin =
                    dp(3)



                marginEnd =
                    dp(5)
            }
        )
    }









    private fun addEpgRow(
        channel: LiveChannel
    ) {


        val timeline =
            LinearLayout(this).apply {


                orientation =
                    LinearLayout.HORIZONTAL



                gravity =
                    Gravity.CENTER_VERTICAL
            }




        val focusablePrograms =
            mutableListOf<View>()





        val programs =

            epgByChannel[
                channel.epg_channel_id
                    .orEmpty()
            ]
                .orEmpty()
                .filter {

                    val stop =
                        it.stopTimestamp ?: 0L

                    val start =
                        it.startTimestamp ?: Long.MAX_VALUE


                    stop > timelineStart &&
                            start < timelineEnd
                }






        if (
            programs.isEmpty()
        ) {


            addNoInformationBlock(
                timeline,
                timelineEnd - timelineStart
            )



        } else {



            var cursor =
                timelineStart



            programs.forEach { program ->


                val start =

                    (
                            program.startTimestamp
                                ?: cursor
                            )
                        .coerceIn(
                            timelineStart,
                            timelineEnd
                        )



                val stop =

                    (
                            program.stopTimestamp
                                ?: (
                                        start +
                                                30L *
                                                60L *
                                                1000L
                                        )
                            )
                        .coerceIn(
                            timelineStart,
                            timelineEnd
                        )



                if (
                    start > cursor
                ) {


                    addEmptyBlock(
                        timeline,
                        start - cursor
                    )



                    cursor =
                        start
                }




                if (
                    stop > start
                ) {


                    focusablePrograms.add(

                        addProgramBlock(
                            timeline,
                            channel,
                            program,
                            stop - start
                        )
                    )



                    cursor =
                        stop
                }
            }




            if (
                cursor < timelineEnd
            ) {


                addEmptyBlock(
                    timeline,
                    timelineEnd - cursor
                )
            }
        }





        programFocusRows.add(
            focusablePrograms
        )



        val rowFrame =
            FrameLayout(this)



        rowFrame.addView(

            timeline,

            FrameLayout.LayoutParams(
                -2,
                dp(rowHeightDp)
            )
        )



        if (
            isTodayTimeline()
        ) {


            addNowLine(
                rowFrame,
                timelineStart,
                rowHeightDp
            )
        }



        binding.epgRowsContainer.addView(

            rowFrame,

            LinearLayout.LayoutParams(
                -2,
                dp(rowHeightDp)
            )
        )
    }

    private fun createChannelPanel(
        channel: LiveChannel
    ): LinearLayout {


        return LinearLayout(this).apply {


            orientation =
                LinearLayout.HORIZONTAL



            gravity =
                Gravity.CENTER_VERTICAL



            isFocusable =
                true



            isClickable =
                true



            setPadding(
                dp(8),
                dp(4),
                dp(8),
                dp(4)
            )



            background =
                channelBackground(
                    channel,
                    false
                )



            setOnFocusChangeListener { view, hasFocus ->


                view.background =
                    channelBackground(
                        channel,
                        hasFocus
                    )



                if (
                    hasFocus
                ) {


                    updateTopInfo(
                        channel
                    )
                }
            }




            setOnClickListener {


                playChannel(
                    channel
                )
            }


            setOnLongClickListener {
                confirmToggleFavorite(channel)
                true
            }






            val logo =
                ImageView(
                    this@EpgChannelListActivity
                ).apply {


                    isFocusable =
                        false



                    isClickable =
                        false



                    scaleType =
                        ImageView.ScaleType.CENTER_INSIDE



                    load(
                        channel.stream_icon
                    ) {


                        placeholder(
                            R.drawable.app_logo
                        )



                        error(
                            R.drawable.app_logo
                        )
                    }
                }




            addView(

                logo,

                LinearLayout.LayoutParams(
                    dp(62),
                    dp(52)
                ).apply {


                    marginEnd =
                        dp(10)
                }
            )






            val name =
                TextView(
                    this@EpgChannelListActivity
                ).apply {


                    isFocusable =
                        false



                    isClickable =
                        false



                    text =
                        channel.name
                            ?: "Unknown CHANNEL"



                    setTextColor(
                        Color.WHITE
                    )



                    textSize =
                        15f


                    // The title fills the row, so parent gravity alone does
                    // not center it. Match the program cards on the right.
                    gravity =
                        Gravity.CENTER_VERTICAL


                    includeFontPadding =
                        false



                    maxLines =
                        2



                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                }




            addView(

                name,

                LinearLayout.LayoutParams(
                    0,
                    -1,
                    1f
                )
            )
        }
    }









    private fun addNoInformationBlock(
        parent: LinearLayout,
        durationMs: Long
    ) {


        val minutes =
            (
                    durationMs /
                            60_000L
                    )
                .coerceAtLeast(
                    5L
                )



        val cardGap =
            dp(6)



        val cardWidth =
            (
                    minutes *
                            minuteWidthDp
                    )
                .toInt()
                .minus(cardGap)
                .coerceAtLeast(
                    dp(120)
                )




        val card =
            TextView(this).apply {


                text =
                    "No Information"



                gravity =
                    Gravity.CENTER_VERTICAL



                setTextColor(
                    Color.WHITE
                )



                textSize =
                    15f



                setPadding(
                    dp(12),
                    dp(4),
                    dp(12),
                    dp(4)
                )



                maxLines =
                    1



                isSingleLine =
                    true



                ellipsize =
                    android.text.TextUtils.TruncateAt.END



                background =
                    roundedBackground(
                        false,
                        false
                    )
            }




        parent.addView(

            card,

            LinearLayout.LayoutParams(
                cardWidth,
                dp(rowHeightDp - 6)
            ).apply {


                marginEnd =
                    cardGap



                topMargin =
                    dp(3)



                bottomMargin =
                    dp(3)
            }
        )
    }









    private fun addEmptyBlock(
        parent: LinearLayout,
        durationMs: Long
    ) {


        val minutes =
            (
                    durationMs /
                            60_000L
                    )
                .coerceAtLeast(
                    5L
                )



        parent.addView(

            View(this),

            LinearLayout.LayoutParams(

                (
                        minutes *
                                minuteWidthDp
                        )
                    .toInt()
                    .coerceAtLeast(
                        dp(18)
                    ),

                dp(rowHeightDp - 6)

            ).apply {


                topMargin =
                    dp(3)



                bottomMargin =
                    dp(3)
            }
        )
    }









    private fun addProgramBlock(
        parent: LinearLayout,
        channel: LiveChannel,
        program: EpgEntity,
        durationMs: Long
    ): View {


        val now =
            System.currentTimeMillis()



        val start =
            program.startTimestamp
                ?: Long.MAX_VALUE



        val stop =
            program.stopTimestamp
                ?: Long.MIN_VALUE



        val isNow =
            start <= now &&
                    stop > now




        val title =
            program.title
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "No Program Info"





        val minutes =
            (
                    durationMs /
                            60_000L
                    )
                .coerceAtLeast(
                    5L
                )



        val cardGap =
            dp(6)



        val cardWidth =
            (
                    minutes *
                            minuteWidthDp
                    )
                .toInt()
                .minus(cardGap)
                .coerceAtLeast(
                    dp(55)
                )






        val card =
            FrameLayout(this).apply {


                id =
                    View.generateViewId()



                tag =
                    "${channel.stream_id}|${start}|${stop}"



                isFocusable =
                    true



                isClickable =
                    true




                background =
                    programBackground(
                        channel,
                        isNow,
                        false
                    )



                setOnFocusChangeListener { view, hasFocus ->



                    view.background =
                        programBackground(
                            channel,
                            isNow,
                            hasFocus
                        )



                    if (
                        hasFocus
                    ) {


                        updateTopInfo(
                            channel,
                            program
                        )
                    }
                }





                setOnClickListener {


                    playChannel(
                        channel,
                        program
                    )
                }


                setOnLongClickListener {
                    confirmToggleFavorite(channel)
                    true
                }
            }






        val text =
            TextView(this).apply {


                this.text =
                    title



                gravity =
                    Gravity.CENTER_VERTICAL



                setPadding(
                    dp(10),
                    dp(4),
                    dp(10),
                    dp(4)
                )



                setTextColor(
                    Color.WHITE
                )



                textSize =
                    14f


                // Keep the program text on the same vertical center line as
                // the sticky channel name for this row.
                includeFontPadding =
                    false



                maxLines =
                    2



                ellipsize =
                    android.text.TextUtils.TruncateAt.END



                isFocusable =
                    false



                isClickable =
                    false
            }






        card.addView(

            text,

            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )





        parent.addView(

            card,

            LinearLayout.LayoutParams(
                cardWidth,
                dp(rowHeightDp - 6)
            ).apply {


                marginEnd =
                    cardGap



                topMargin =
                    dp(3)



                bottomMargin =
                    dp(3)
            }
        )



        return card
    }


    private fun wireFocusNavigation() {


        if (
            channelFocusViews.isEmpty()
        ) return




        val firstChannel =
            channelFocusViews.firstOrNull()



        val firstProgram =
            programFocusRows
                .firstOrNull()
                ?.firstOrNull()





        firstChannel?.let { channel ->


            channel.nextFocusUpId =
                binding.btnBack.id



            binding.btnBack.nextFocusDownId =
                channel.id
        }





        firstProgram?.let { program ->


            program.nextFocusUpId =
                binding.btnMore.id



            binding.btnMore.nextFocusDownId =
                program.id
        }






        channelFocusViews
            .forEachIndexed { index, view ->



                if (
                    index > 0
                ) {


                    view.nextFocusUpId =
                        channelFocusViews[index - 1].id
                }



                if (
                    index <
                    channelFocusViews.lastIndex
                ) {


                    view.nextFocusDownId =
                        channelFocusViews[index + 1].id
                }




                val row =
                    programFocusRows
                        .getOrNull(index)
                        .orEmpty()





                if (
                    row.isNotEmpty()
                ) {



                    view.nextFocusRightId =
                        row.first().id





                    row.forEachIndexed { programIndex, programView ->



                        programView.nextFocusLeftId =
                            view.id



                        programView.nextFocusRightId =

                            if (
                                programIndex <
                                row.lastIndex
                            )

                                row[programIndex + 1].id

                            else

                                programView.id
                    }
                }
            }







        for (
        rowIndex in programFocusRows.indices
        ) {


            programFocusRows[rowIndex]
                .forEach { programView ->



                    val (start, stop) =
                        parseProgramTagWindow(programView.tag)
                            ?: (0L to 0L)




                    val center =
                        (start + stop) / 2L




                    val up =
                        nearestProgramInRow(
                            rowIndex - 1,
                            center
                        )



                    val down =
                        nearestProgramInRow(
                            rowIndex + 1,
                            center
                        )





                    programView.nextFocusUpId =

                        when {


                            up != null ->
                                up.id



                            rowIndex == 0 ->
                                binding.btnMore.id



                            else ->
                                channelFocusViews
                                    .getOrNull(rowIndex)
                                    ?.id
                                    ?: programView.id
                        }





                    programView.nextFocusDownId =

                        down?.id
                            ?: channelFocusViews
                                .getOrNull(rowIndex)
                                ?.id
                                    ?: programView.id
                }
        }







        binding.btnBack.isFocusable =
            true



        binding.btnMore.isFocusable =
            true




        binding.btnBack.nextFocusRightId =
            binding.btnMore.id



        binding.btnMore.nextFocusLeftId =
            binding.btnBack.id
    }









    /** Parses the "streamId|start|stop" tag set on program cell views. */
    private fun parseProgramTagWindow(tag: Any?): Pair<Long, Long>? {
        val parts = tag?.toString()?.split("|") ?: return null
        val start = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val stop = parts.getOrNull(2)?.toLongOrNull() ?: start
        return start to stop
    }

    private fun nearestProgramInRow(
        rowIndex: Int,
        targetCenter: Long
    ): View? {


        val row =
            programFocusRows
                .getOrNull(rowIndex)
                ?: return null




        return row.minByOrNull { view ->



            val (start, stop) =
                parseProgramTagWindow(view.tag)
                    ?: return@minByOrNull Long.MAX_VALUE




            kotlin.math.abs(
                ((start + stop) / 2L)
                        - targetCenter
            )
        }
    }









    // Finds whichever grid cell currently has keyboard focus (a program
    // card or a channel-logo card) and records it via the same
    // pendingFocusChannelId/pendingFocusProgramKey fields restorePendingFocus()
    // already knows how to re-apply after a rebuild. Must run before
    // renderGrid() tears down the current views.
    private fun captureCurrentGridFocus() {

        val focused =
            currentFocus
                ?: return

        for (row in programFocusRows) {

            val index =
                row.indexOf(focused)

            if (index >= 0) {

                val tag =
                    focused.tag as? String
                        ?: return

                val streamId =
                    tag.substringBefore('|')
                        .toIntOrNull()
                        ?: return

                pendingFocusChannelId =
                    streamId

                pendingFocusProgramKey =
                    tag

                return
            }
        }

        val channelIndex =
            channelFocusViews.indexOf(focused)

        if (channelIndex >= 0) {

            pendingFocusChannelId =
                channels.getOrNull(channelIndex)
                    ?.stream_id

            pendingFocusProgramKey =
                null
        }
    }


    private fun restorePendingFocus() {


        val streamId =
            pendingFocusChannelId
                ?: return




        val channelIndex =
            channels.indexOfFirst {


                it.stream_id ==
                        streamId
            }





        if (
            channelIndex < 0
        ) return





        binding.channelVerticalScroll.post {


            val channelView =
                channelFocusViews
                    .getOrNull(channelIndex)





            val programKey =
                pendingFocusProgramKey





            val target =

                if (
                    !programKey.isNullOrBlank()
                )

                    programFocusRows
                        .getOrNull(channelIndex)
                        ?.firstOrNull {

                            it.tag
                                ?.toString() == programKey
                        }

                else

                    null





            val focusTarget =
                target
                    ?: channelView





            if (
                focusTarget != null
            ) {



                focusTarget.post {


                    focusTarget.requestFocus()



                    pendingFocusChannelId =
                        null



                    pendingFocusProgramKey =
                        null
                }
            }
        }
    }









    private fun updateTopInfo(
        channel: LiveChannel,
        program: EpgEntity? = null
    ) {


        selectedChannel =
            channel



        binding.txtPlayerChannel.text =
            channel.name
                ?: "Unknown Channel"




        val current =

            program
                ?: epgByChannel[
                    channel.epg_channel_id
                        .orEmpty()
                ]
                    .orEmpty()
                    .firstOrNull {



                        val now =
                            System.currentTimeMillis()



                        (
                                it.startTimestamp
                                    ?: Long.MAX_VALUE
                                ) <= now &&

                                (
                                        it.stopTimestamp
                                            ?: Long.MIN_VALUE
                                        ) > now
                    }




        binding.txtChannelTitle.text =
            current?.title
                ?: "No current program"




        binding.txtDescription.text =
            current?.description
                .orEmpty()



        binding.txtEpgStatus.text =
            "EPG guide"
    }


    private fun playChannel(
        channel: LiveChannel,
        program: EpgEntity? = null
    ) {


        // First click starts/replaces the compact preview. A second click on
        // the channel already playing opens the shared full-screen player.
        if (playingChannel?.stream_id == channel.stream_id) {
            openFullscreen(channel)
            return
        }


        selectedChannel =
            channel

        LiveWatchHistory.record(applicationContext, channel)



        updateTopInfo(
            channel,
            program
        )



        binding.progressLoading.visibility =
            View.VISIBLE


        PlayerManager.play(

            this,

            binding.playerView,

            buildStreamUrl(
                channel
            ),
            channel.stream_id?.toString()
        )


        playingChannel =
            channel



        pendingFocusChannelId =
            channel.stream_id



        if (
            program != null
        ) {


            pendingFocusProgramKey =
                "${channel.stream_id}|${program.startTimestamp}|${program.stopTimestamp}"
        }
    }


    // Fullscreen used to hand off to a separate PlayerActivity via
    // startActivity() + PlayerManager re-attaching the player's video
    // surface to a brand new PlayerView. That handoff is a real decoder
    // level operation (MediaCodec output surface retarget) that visibly
    // froze the last frame for a moment on this hardware. Expanding
    // topCard/playerView in place instead means binding.playerView is
    // never swapped, so there is no handoff left to freeze on.
    private fun openFullscreen(
        channel: LiveChannel
    ) {

        if (isFullscreen) return

        val position = channels.indexOfFirst {
            it.stream_id == channel.stream_id
        }

        if (position < 0) return

        isFullscreen = true

        // Still populated for MultiView's "at least 2 channels" grid button.
        PlayerState.channels.clear()
        PlayerState.channels.addAll(channels)
        PlayerState.currentPosition = position

        playingChannel = channel

        PlayerManager.play(
            this,
            binding.playerView,
            buildStreamUrl(channel),
            channel.stream_id?.toString()
        )

        val topCard = binding.topCard

        topCardCornerRadius = topCard.radius
        topCardElevation = topCard.cardElevation
        topCardStrokeWidth = topCard.strokeWidth
        topCard.radius = 0f
        topCard.cardElevation = 0f
        topCard.strokeWidth = 0
        topCard.setContentPadding(0, 0, 0, 0)

        if (contentRootNormalConstraintSet == null) {
            contentRootNormalConstraintSet = ConstraintSet().apply {
                clone(binding.contentRoot)
            }
        }

        ConstraintSet().apply {
            clone(binding.contentRoot)
            clear(topCard.id, ConstraintSet.START)
            clear(topCard.id, ConstraintSet.END)
            clear(topCard.id, ConstraintSet.TOP)
            clear(topCard.id, ConstraintSet.BOTTOM)
            constrainPercentHeight(topCard.id, 1f)
            connect(topCard.id, ConstraintSet.START, binding.contentRoot.id, ConstraintSet.START)
            connect(topCard.id, ConstraintSet.END, binding.contentRoot.id, ConstraintSet.END)
            connect(topCard.id, ConstraintSet.TOP, binding.contentRoot.id, ConstraintSet.TOP)
            connect(topCard.id, ConstraintSet.BOTTOM, binding.contentRoot.id, ConstraintSet.BOTTOM)
            setMargin(topCard.id, ConstraintSet.START, 0)
            setMargin(topCard.id, ConstraintSet.END, 0)
            setMargin(topCard.id, ConstraintSet.TOP, 0)
            setMargin(topCard.id, ConstraintSet.BOTTOM, 0)
            applyTo(binding.contentRoot)
        }

        if (topCardInnerNormalConstraintSet == null) {
            topCardInnerNormalConstraintSet = ConstraintSet().apply {
                clone(binding.topCardInner)
            }
        }

        // playerView normally only takes 30% of topCard's width, with
        // infoPanel filling the rest - drop that percentage constraint so
        // it fills topCardInner entirely (infoPanel is hidden below anyway).
        ConstraintSet().apply {
            clone(binding.topCardInner)
            constrainPercentWidth(binding.playerView.id, 1f)
            clear(binding.playerView.id, ConstraintSet.END)
            connect(binding.playerView.id, ConstraintSet.END, binding.topCardInner.id, ConstraintSet.END)
            applyTo(binding.topCardInner)
        }

        binding.headerCard.visibility = View.GONE
        binding.epgArea.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE

        binding.fsTxtChannelTitle.text = run {
            val streamId = channel.stream_id?.let { "$it - " } ?: ""
            "$streamId${channel.name ?: "Unknown Channel"}"
        }

        binding.playerView.subtitleView?.visibility = View.VISIBLE
        fsToggleSubtitles(fsSubtitleEnabled)

        val epgId = channel.epg_channel_id ?: channel.stream_id?.toString()
        binding.fsTxtNowTitle.text =
            epgByChannel[epgId.orEmpty()]
                ?.firstOrNull {
                    val now = System.currentTimeMillis()
                    (it.startTimestamp ?: Long.MAX_VALUE) <= now &&
                        (it.stopTimestamp ?: 0L) >= now
                }
                ?.title
                ?: "No Program Info"

        showFsUiWithTimeout()

        binding.fsBtnPlayPause.post {
            binding.fsBtnPlayPause.requestFocus()
        }
    }


    private fun exitFullscreen() {

        if (!isFullscreen) return

        isFullscreen = false

        fsHideHandler.removeCallbacks(fsHideRunnable)

        val topCard = binding.topCard

        topCard.radius = topCardCornerRadius
        topCard.cardElevation = topCardElevation
        topCard.strokeWidth = topCardStrokeWidth
        topCard.setContentPadding(0, 0, 0, 0)

        contentRootNormalConstraintSet?.applyTo(binding.contentRoot)
        topCardInnerNormalConstraintSet?.applyTo(binding.topCardInner)

        binding.headerCard.visibility = View.VISIBLE
        binding.epgArea.visibility = View.VISIBLE
        binding.infoPanel.visibility = View.VISIBLE

        binding.fsTopTint.visibility = View.GONE
        binding.fsBtnBack.visibility = View.GONE
        binding.fsTxtChannelTitle.visibility = View.GONE
        binding.fsBottomOverlay.visibility = View.GONE

        binding.playerView.subtitleView?.visibility = View.GONE

        // fsPlayNextChannel/fsPlayPreviousChannel may have moved playingChannel
        // while fullscreen - re-focus the grid on whatever is actually
        // playing now that it is visible again, same as a normal channel
        // switch already does via pendingFocusChannelId.
        playingChannel?.stream_id?.let { streamId ->
            pendingFocusChannelId = streamId
            pendingFocusProgramKey = null
            restorePendingFocus()
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


    private fun fsSwitchToChannel(channel: LiveChannel) {

        LiveWatchHistory.record(applicationContext, channel)

        updateTopInfo(channel)

        playingChannel = channel
        pendingFocusChannelId = channel.stream_id

        PlayerManager.play(
            this,
            binding.playerView,
            buildStreamUrl(channel),
            channel.stream_id?.toString()
        )

        binding.fsTxtChannelTitle.text = run {
            val streamId = channel.stream_id?.let { "$it - " } ?: ""
            "$streamId${channel.name ?: "Unknown Channel"}"
        }

        val epgId = channel.epg_channel_id ?: channel.stream_id?.toString()
        binding.fsTxtNowTitle.text =
            epgByChannel[epgId.orEmpty()]
                ?.firstOrNull {
                    val now = System.currentTimeMillis()
                    (it.startTimestamp ?: Long.MAX_VALUE) <= now &&
                        (it.stopTimestamp ?: 0L) >= now
                }
                ?.title
                ?: "No Program Info"
    }


    private fun fsPlayNextChannel() {

        if (channels.isEmpty()) return

        val currentIndex = channels.indexOfFirst {
            it.stream_id == playingChannel?.stream_id
        }

        val next = (currentIndex + 1).let {
            if (it < 0 || it >= channels.size) 0 else it
        }

        channels.getOrNull(next)?.let(::fsSwitchToChannel)
    }


    private fun fsPlayPreviousChannel() {

        if (channels.isEmpty()) return

        val currentIndex = channels.indexOfFirst {
            it.stream_id == playingChannel?.stream_id
        }

        val prev = (currentIndex - 1).let {
            if (it < 0) channels.size - 1 else it
        }

        channels.getOrNull(prev)?.let(::fsSwitchToChannel)
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

        binding.fsBtnBack.setOnClickListener {
            exitFullscreen()
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

            if (playingChannel?.stream_id == null) {
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


    private fun confirmToggleFavorite(
        channel: LiveChannel
    ) {
        val streamId = channel.stream_id?.toString() ?: return

        lifecycleScope.launch {
            val isFavorite = withContext(Dispatchers.IO) {
                DatabaseProvider
                    .get(this@EpgChannelListActivity)
                    .favoritesDao()
                    .getAll()
                    .any { it.key == "${FavoriteItemType.LIVE_CHANNEL}:$streamId" }
            }

            val channelName = channel.name ?: "this channel"
            val action = if (isFavorite) "remove" else "add"

            showConfirmDialog(
                title = "Favorites",
                message = "Do you want to $action $channelName ${if (isFavorite) "from" else "to"} Favorites?",
                positiveText = if (isFavorite) "Remove" else "Add",
                onPositive = { toggleChannelFavorite(channel) }
            )
        }
    }


    private fun toggleChannelFavorite(
        channel: LiveChannel
    ) {
        val streamId = channel.stream_id?.toString() ?: return

        lifecycleScope.launch {
            val isFavorite = withContext(Dispatchers.IO) {
                DatabaseProvider
                    .get(this@EpgChannelListActivity)
                    .favoritesDao()
                    .getAll()
                    .any { it.key == "${FavoriteItemType.LIVE_CHANNEL}:$streamId" }
            }

            try {
                if (isFavorite) {
                    favoritesRepository.removeFavorite(
                        prefs.getUsername(),
                        FavoriteItemType.LIVE_CHANNEL,
                        streamId
                    )
                    Toast.makeText(
                        this@EpgChannelListActivity,
                        "${channel.name ?: "Channel"} removed from Favorites",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    favoritesRepository.addFavorite(
                        prefs.getUsername(),
                        FavoriteItemType.LIVE_CHANNEL,
                        streamId
                    )
                    Toast.makeText(
                        this@EpgChannelListActivity,
                        "${channel.name ?: "Channel"} added to Favorites",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                Toast.makeText(
                    this@EpgChannelListActivity,
                    "Could not update Favorites",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }









    private fun buildStreamUrl(
        channel: LiveChannel
    ): String {
        return LiveStreamUrlBuilder.build(prefs, channel.stream_id)
    }









    // "Currently playing channel" used to get a red border here that
    // stayed lit regardless of focus - removed per direct feedback that
    // it was confusing and unclear what it meant. The preview panel
    // above the grid already shows what's currently playing, so the
    // channel-logo cell now only ever reflects keyboard focus, same as
    // every other cell in the grid.
    private fun channelBackground(
        channel: LiveChannel,
        focused: Boolean
    ): GradientDrawable {

        return roundedBackground(
            focused,
            false
        )
    }









    private fun programBackground(
        channel: LiveChannel,
        isNow: Boolean,
        focused: Boolean
    ): GradientDrawable {


        // "This is the channel currently playing" (red) is shown on that
        // channel's own logo cell only - see channelBackground(). Program
        // cells used to inherit that same red across the channel's ENTIRE
        // row (every timeslot, hours in both directions), which looked
        // like a persistent warning/error banner rather than a "now
        // playing" cue. Program cells only ever respond to focus here.
        if (
            focused
        ) {

            return roundedBackground(
                true,
                false
            )
        }



        if (
            isNow
        ) {

            // A lightweight "airing now" cue - a dark amber tint and
            // border, not a full bright block. The previous solid
            // #FF8800 fill on every live cell made it impossible to
            // tell "this is live" apart from "this is focused" at a
            // glance, since focus was only a thin border on top of the
            // same loud color.
            return GradientDrawable().apply {


                cornerRadius =
                    dp(4)
                        .toFloat()



                setColor(
                    Color.rgb(
                        46,
                        36,
                        20
                    )
                )



                setStroke(
                    dp(2),
                    Color.rgb(
                        255,
                        193,
                        7
                    )
                )
            }
        }



        return roundedBackground(
            false,
            false
        )
    }









    private fun roundedBackground(
        active: Boolean,
        strong: Boolean
    ): GradientDrawable {


        val bg =

            if (
                strong
            )

                Color.rgb(
                    81,
                    45,
                    49
                )

            else if (
                active
            )

                Color.rgb(
                    58,
                    37,
                    40
                )

            else

                Color.rgb(
                    30,
                    30,
                    30
                )




        val stroke =

            if (
                strong
            )

                Color.rgb(
                    215,
                    25,
                    32
                )

            else if (
                active
            )

                Color.rgb(
                    255,
                    255,
                    255
                )

            else

                Color.rgb(
                    48,
                    48,
                    48
                )





        return GradientDrawable().apply {


            cornerRadius =
                dp(6)
                    .toFloat()



            setColor(
                bg
            )



            setStroke(
                dp(
                    if (
                        strong
                    )
                        3
                    else
                        2
                ),
                stroke
            )
        }
    }









    private fun startOfDay(
        offset: Int
    ): Long {


        return Calendar.getInstance().apply {


            set(
                Calendar.HOUR_OF_DAY,
                0
            )


            set(
                Calendar.MINUTE,
                0
            )


            set(
                Calendar.SECOND,
                0
            )


            set(
                Calendar.MILLISECOND,
                0
            )



            add(
                Calendar.DAY_OF_YEAR,
                offset
            )

        }.timeInMillis
    }









    private fun floorToHalfHour(
        timestamp: Long
    ): Long {


        val cal =
            Calendar.getInstance().apply {


                timeInMillis =
                    timestamp
            }



        cal.set(
            Calendar.SECOND,
            0
        )


        cal.set(
            Calendar.MILLISECOND,
            0
        )



        cal.set(

            Calendar.MINUTE,

            if (
                cal.get(
                    Calendar.MINUTE
                ) < 30
            )

                0

            else

                30
        )



        return cal.timeInMillis
    }









    private val density by lazy { resources.displayMetrics.density }

    internal fun dp(
        value: Int
    ): Int {


        return (
                value *
                        density
                )
            .toInt()
    }









    override fun onResume() {


        super.onResume()



        if (
            ::binding.isInitialized
        ) {

            vpnToggle.refresh()

            PlayerManager.attach(

                this,

                binding.playerView
            )


            binding.playerView.player?.let { player ->
                player.addListener(playerListener)
                binding.progressLoading.visibility =
                    if (player.playbackState == Player.STATE_BUFFERING) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }


            if (playingChannel != null) {
                PlayerManager.resume()
            }

            scheduleNowLineTick()

            // onPause() cancels the auto-hide timer but never restarted it
            // here - so leaving fullscreen for another activity (Settings,
            // MultiView) and coming back left the top/bottom overlay
            // controls permanently visible with nothing left to hide them
            // again. showFsUiWithTimeout() is safe to call unconditionally:
            // it only fades the overlay in if it isn't already visible, and
            // always reschedules the hide timer.
            if (isFullscreen) {
                showFsUiWithTimeout()
            }
        }
    }









    override fun onPause() {
        binding.playerView.player
            ?.removeListener(playerListener)

        nowHandler.removeCallbacks(nowLineRunnable)
        fsHideHandler.removeCallbacks(fsHideRunnable)

        super.onPause()
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


    override fun onDestroy() {


        PlayerManager.setRecoveryFailedListener(
            null
        )



        nowHandler.removeCallbacks(
            nowLineRunnable
        )



        super.onDestroy()
    }
}
