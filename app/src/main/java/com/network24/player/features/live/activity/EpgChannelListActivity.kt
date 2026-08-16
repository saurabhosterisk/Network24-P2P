package com.network24.player.features.live.activity

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

import coil.load
import com.google.firebase.firestore.FirebaseFirestore

import com.network24.player.R
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

import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.state.PlayerState

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale





class EpgChannelListActivity : BaseActivity() {



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



    private val minuteWidthDp =
        9.0f



    private val rowHeightDp =
        70



    private val headerHeightDp =
        40



    private var timelineStart =
        0L



    private var timelineEnd =
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



    private var expectingFullscreenReturn =
        false





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





        PlayerManager.attach(
            this,
            binding.playerView
        )


        // Match the Channel List and Favorites behaviour: touching the active
        // preview opens the same full-screen PlayerActivity and its controls.
        binding.playerView.setOnClickListener {
            playingChannel?.let(::openFullscreen)
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









    private fun loadChannels() {


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



                nowHandler.postDelayed(
                    nowLineRunnable,
                    60_000L
                )



            } catch (e: Exception) {


                binding.txtEpgStatus.text =
                    e.message
                        ?: "Unable to load channels"



                hideLoadingMask()
            }
        }
    }


    private fun loadGuideData() {


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



                    val parts =
                        programView.tag
                            ?.toString()
                            ?.split("|")
                            ?: emptyList()



                    val start =
                        parts.getOrNull(1)
                            ?.toLongOrNull()
                            ?: 0L



                    val stop =
                        parts.getOrNull(2)
                            ?.toLongOrNull()
                            ?: start




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









    private fun nearestProgramInRow(
        rowIndex: Int,
        targetCenter: Long
    ): View? {


        val row =
            programFocusRows
                .getOrNull(rowIndex)
                ?: return null




        return row.minByOrNull { view ->



            val parts =
                view.tag
                    ?.toString()
                    ?.split("|")
                    ?: return@minByOrNull Long.MAX_VALUE



            val start =
                parts.getOrNull(1)
                    ?.toLongOrNull()
                    ?: return@minByOrNull Long.MAX_VALUE



            val stop =
                parts.getOrNull(2)
                    ?.toLongOrNull()
                    ?: start




            kotlin.math.abs(
                ((start + stop) / 2L)
                        - targetCenter
            )
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


    private fun openFullscreen(
        channel: LiveChannel
    ) {


        val position = channels.indexOfFirst {
            it.stream_id == channel.stream_id
        }


        if (position < 0) return


        // PlayerActivity uses PlayerState for previous/next channel controls
        // and MultiView. Populate it with this EPG category's channel list.
        PlayerState.channels.clear()
        PlayerState.channels.addAll(channels)
        PlayerState.currentPosition = position


        playingChannel =
            channel


        expectingFullscreenReturn =
            true


        PlayerManager.play(
            this,
            binding.playerView,
            buildStreamUrl(channel),
            channel.stream_id?.toString()
        )


        startActivity(
            Intent(
                this,
                PlayerActivity::class.java
            )
        )
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
                    .any { it.key == "LIVE_CHANNEL:$streamId" }
            }

            val channelName = channel.name ?: "this channel"
            val action = if (isFavorite) "remove" else "add"

            AlertDialog.Builder(this@EpgChannelListActivity)
                .setTitle("Favorites")
                .setMessage("Do you want to $action $channelName ${if (isFavorite) "from" else "to"} Favorites?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(if (isFavorite) "Remove" else "Add") { _, _ ->
                    toggleChannelFavorite(channel)
                }
                .show()
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
                    .any { it.key == "LIVE_CHANNEL:$streamId" }
            }

            try {
                if (isFavorite) {
                    favoritesRepository.removeFavorite(
                        prefs.getUsername(),
                        "LIVE_CHANNEL",
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
                        "LIVE_CHANNEL",
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


        val server =
            prefs.getServer()
                .trim()
                .trimEnd('/')



        return "$server/live/${prefs.getUsername()}/${prefs.getPassword()}/${channel.stream_id}.m3u8"
    }









    private fun channelBackground(
        channel: LiveChannel,
        focused: Boolean
    ): GradientDrawable {


        val selected =

            selectedChannel?.stream_id != null &&

                    selectedChannel?.stream_id ==
                    channel.stream_id




        return roundedBackground(
            focused || selected,
            selected
        )
    }









    private fun programBackground(
        channel: LiveChannel,
        isNow: Boolean,
        focused: Boolean
    ): GradientDrawable {


        val selected =

            selectedChannel?.stream_id != null &&

                    selectedChannel?.stream_id ==
                    channel.stream_id





        if (
            isNow
        ) {


            return GradientDrawable().apply {


                cornerRadius =
                    dp(4)
                        .toFloat()



                setColor(
                    Color.rgb(
                        255,
                        136,
                        0
                    )
                )



                setStroke(

                    dp(
                        if (
                            focused || selected
                        )
                            3
                        else
                            2
                    ),

                    if (
                        focused || selected
                    )

                        Color.WHITE

                    else

                        Color.rgb(
                            255,
                            193,
                            7
                        )
                )
            }
        }



        return roundedBackground(
            focused || selected,
            selected
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









    private fun dp(
        value: Int
    ): Int {


        return (
                value *
                        resources.displayMetrics.density
                )
            .toInt()
    }









    override fun onResume() {


        super.onResume()



        if (
            ::binding.isInitialized
        ) {


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


            if (expectingFullscreenReturn) {
                expectingFullscreenReturn = false


                PlayerState.currentChannel()?.let { channel ->
                    playingChannel = channel
                    updateTopInfo(channel)
                    pendingFocusChannelId = channel.stream_id
                }
            }


            if (playingChannel != null) {
                PlayerManager.resume()
            }
        }
    }









    override fun onPause() {
        binding.playerView.player
            ?.removeListener(playerListener)

        super.onPause()
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
