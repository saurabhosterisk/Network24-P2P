package com.network24.player.features.live.activity

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import com.google.firebase.firestore.FieldValue
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager

import com.google.android.material.internal.NavigationMenuView
import com.google.firebase.firestore.FirebaseFirestore

import com.network24.player.R
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

import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.state.PlayerState

import kotlinx.coroutines.launch

import java.text.SimpleDateFormat
import java.util.Locale



class ChannelListActivity : BaseActivity() {


    private lateinit var binding: ActivityChannelListBinding

    private lateinit var repository: LiveRepository

    private lateinit var prefs: PreferenceManager

    private lateinit var favRepo: FavoritesRepository

    private lateinit var adapter: ChannelAdapter



    private var isGoingToFullscreen = false

    private var loadingDialog: AlertDialog? = null


    private val isTouchDevice by lazy {
        !packageManager.hasSystemFeature(
            PackageManager.FEATURE_LEANBACK
        )
    }



    private var previewPosition = -1


    private val allChannels =
        mutableListOf<LiveChannel>()


    private val channelList =
        mutableListOf<LiveChannel>()


    private lateinit var categoryId: String





    private val playerListener =
        object : Player.Listener {


            override fun onPlaybackStateChanged(
                playbackState: Int
            ) {


                binding.progressLoading.visibility =
                    if (playbackState == Player.STATE_BUFFERING)

                        View.VISIBLE

                    else

                        View.GONE





                if (playbackState == Player.STATE_READY) {


                    binding.txtPlayerError.visibility =
                        View.GONE


                    binding.btnReportChannel.visibility =
                        View.GONE
                }
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



        val db =
            DatabaseProvider.get(this)



        favRepo =
            FavoritesRepository(
                db.favoritesDao(),
                FirebaseFirestore.getInstance()
            )



        prefs =
            PreferenceManager(this)



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



        setupReportButton()



        /*
         * PlayerManager recovery failed callback
         * Retry is handled only by PlayerManager.
         */

        PlayerManager.setRecoveryFailedListener {

            runOnUiThread {

                when (
                    PlayerManager.getStreamErrorType()
                ) {

                    PlayerManager.StreamErrorType.NETWORK -> {

                        binding.txtPlayerError.text =
                            "Network connection lost.\nReconnecting..."

                        binding.txtPlayerError.visibility =
                            View.VISIBLE

                        binding.btnReportChannel.visibility =
                            View.GONE
                    }


                    PlayerManager.StreamErrorType.SOURCE -> {

                        binding.txtPlayerError.text =
                            "Unable to play this stream right now. It may be temporarily unavailable."

                        binding.txtPlayerError.visibility =
                            View.VISIBLE

                        binding.btnReportChannel.visibility =
                            View.VISIBLE
                    }


                    else -> {

                        binding.txtPlayerError.text =
                            "Unable to play this stream right now."

                        binding.txtPlayerError.visibility =
                            View.VISIBLE

                        binding.btnReportChannel.visibility =
                            View.GONE
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

                binding.btnReportChannel.visibility =
                    View.GONE
            }
        }

        PlayerManager.setRecoveryRecoveredListener {
            runOnUiThread {
                binding.txtPlayerError.visibility =
                    View.GONE

                binding.btnReportChannel.visibility =
                    View.GONE
            }
        }




        binding.playerView.setOnClickListener {


            if (
                isTouchDevice &&
                previewPosition != -1 &&
                channelList.isNotEmpty()
            ) {


                val currentChannel =
                    channelList[previewPosition]


                openFullscreen(
                    currentChannel,
                    previewPosition
                )
            }
        }





        setupRecycler()



        setupSearch()



        lifecycleScope.launch {


            db.favoritesDao()
                .observeByType(
                    "LIVE_CHANNEL"
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






    private fun setupReportButton() {


        binding.btnReportChannel.visibility =
            View.GONE




        binding.btnReportChannel.setOnClickListener {


            if (
                previewPosition == -1 ||
                channelList.isEmpty()
            ) return@setOnClickListener




            val currentChannel =
                channelList[previewPosition]



            val channelName =
                currentChannel.name
                    ?: "Unknown Channel"



            val username =
                prefs.getUsername()




            val alertMessage =
                "🚨 System Alert: $username reported that the channel '$channelName' is currently down."




            val chatData =
                hashMapOf(

                    "senderId" to "system_bot",

                    "senderName" to "System",

                    "text" to alertMessage,

                    "ts" to FieldValue.serverTimestamp()
                )



            binding.btnReportChannel.visibility =
                View.GONE



            binding.txtPlayerError.text =
                "Sending report..."



            FirebaseFirestore
                .getInstance()
                .collection("rooms")
                .document("channel_down")
                .collection("messages")
                .add(chatData)



                .addOnSuccessListener {


                    binding.txtPlayerError.text =
                        "Channel reported. Our team will look into it."
                }



                .addOnFailureListener { exception ->


                    binding.btnReportChannel.visibility =
                        View.VISIBLE



                    binding.txtPlayerError.text =
                        "Failed to send report."



                    Toast.makeText(
                        this,
                        "Error: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }


    private fun ensureInitialSyncThenLoad() {


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


                    applyChannelsToUi(
                        channels
                    )


                } else {


                    forceRefreshData(
                        isInitialSync = true
                    )
                }



            } catch (e: Exception) {


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
        forceRefresh: Boolean = false
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
                    channels
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
        channels: List<LiveChannel>
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




        val targetPos =

            if (
                PlayerState.currentPosition
                in channelList.indices
            )

                PlayerState.currentPosition

            else

                0




        previewPosition =
            targetPos



        adapter.setPlaying(
            targetPos
        )



        showPreview(
            channelList[targetPos]
        )



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
                        )
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





                R.id.action_search_guide -> {



                    startActivity(
                        Intent(
                            this,
                            ProgramSearchActivity::class.java
                        )
                    )



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



        binding.btnReportChannel.visibility =
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









    private fun openFullscreen(
        channel: LiveChannel,
        position: Int
    ) {


        isGoingToFullscreen = true



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



        startActivity(
            Intent(
                this,
                PlayerActivity::class.java
            )
        )
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


    private fun loadProgramGuide(
        channel: LiveChannel
    ) {


        val epgId =
            channel.epg_channel_id
                ?: channel.stream_id?.toString()
                ?: return



        lifecycleScope.launch {


            try {


                val (nowEpg, nextEpg) =
                    repository.getNowNextEpg(
                        epgId
                    )



                if (nowEpg != null) {


                    binding.txtNowTitle.text =
                        nowEpg.title
                            ?: "No Program Info"



                    binding.txtNowTime.text =
                        "${formatTime(nowEpg.startTimestamp)} - ${formatTime(nowEpg.stopTimestamp)}"



                    binding.txtOverlayProgram.text =
                        nowEpg.title
                            ?: ""



                } else {



                    binding.txtNowTitle.text =
                        "No EPG"



                    binding.txtNowTime.text =
                        ""



                    binding.txtOverlayProgram.text =
                        ""
                }




                if (nextEpg != null) {


                    binding.txtNextTitle.text =
                        nextEpg.title
                            ?: ""



                    binding.txtNextTime.text =
                        "${formatTime(nextEpg.startTimestamp)} - ${formatTime(nextEpg.stopTimestamp)}"



                } else {


                    binding.txtNextTitle.text =
                        ""



                    binding.txtNextTime.text =
                        ""
                }




            } catch (_: Exception) {


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
            }
        }
    }









    private fun formatTime(
        timeMs: Long?
    ): String {


        if (
            timeMs == null ||
            timeMs == 0L
        ) return ""



        return try {


            SimpleDateFormat(
                "hh:mm a",
                Locale.getDefault()
            ).format(
                timeMs
            )



        } catch (_: Exception) {


            ""
        }
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
                    "LIVE_CHANNEL",
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
                    "LIVE_CHANNEL",
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



        AlertDialog.Builder(this)

            .setTitle(
                "Favorites"
            )

            .setMessage(
                "Do you want to add $name to Favorites?"
            )

            .setPositiveButton(
                "Yes"
            ) { _, _ ->


                toggleChannelFavorite(
                    channel
                )
            }

            .setNegativeButton(
                "No",
                null
            )

            .show()
    }









    private fun addFavorite(
        channel: LiveChannel
    ) {


        toggleChannelFavorite(
            channel
        )
    }









    override fun onResume() {


        super.onResume()



        if (isGoingToFullscreen) {


            isGoingToFullscreen = false



            val playingChannel =
                PlayerState.currentChannel()



            if (playingChannel != null) {



                val streamId =
                    playingChannel.stream_id
                        ?.toString()



                val listPosition =

                    if (streamId != null)

                        channelList.indexOfFirst {

                            it.stream_id
                                ?.toString() == streamId
                        }

                    else

                        -1




                if (listPosition >= 0) {



                    previewPosition =
                        listPosition



                    adapter.setPlaying(
                        listPosition
                    )



                    binding.txtOverlayChannel.text =
                        playingChannel.name
                            ?: ""



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



                    loadProgramGuide(
                        playingChannel
                    )



                    binding.rvChannels.post {


                        binding.rvChannels
                            .scrollToPosition(
                                listPosition
                            )



                        binding.rvChannels.post {


                            binding.rvChannels
                                .findViewHolderForAdapterPosition(
                                    listPosition
                                )
                                ?.itemView
                                ?.requestFocus()
                        }
                    }
                }
            }
        }




        if (
            previewPosition >= 0 &&
            channelList.isNotEmpty()
        ) {



            PlayerManager.attach(
                this,
                binding.playerView
            )



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
