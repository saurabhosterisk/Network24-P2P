package com.network24.player.features.live.activity


import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast

import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
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
import com.network24.player.features.live.ChannelDownReporter
import com.network24.player.features.live.adapter.ChannelAdapter
import com.network24.player.features.live.history.LiveWatchHistory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback

import com.network24.player.features.login.activity.LoginActivity

import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.state.PlayerState

import kotlinx.coroutines.launch



class FavoriteChannelsActivity : BaseActivity() {



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






    private var isGoingToFullscreen =
        false





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



                    binding.btnReportChannel.visibility =
                        View.GONE


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



        setupReportButton()







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






                binding.btnReportChannel.visibility =
                    View.VISIBLE




                binding.btnReportChannel.post {


                    binding.btnReportChannel.requestFocus()

                }


            }

        }









        binding.playerView.setOnClickListener {


            if (

                isTouchDevice

                &&

                previewPosition != -1

                &&

                channelList.isNotEmpty()

            ) {



                openFullscreen(

                    channelList[previewPosition],

                    previewPosition

                )

            }

        }







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









    private fun setupReportButton() {
        binding.btnReportChannel.visibility = View.GONE

        binding.btnReportChannel.setOnClickListener {
            if (previewPosition == -1 || channelList.isEmpty()) return@setOnClickListener

            val channel = channelList[previewPosition]
            val username = prefs.getUsername()

            binding.btnReportChannel.visibility = View.GONE
            binding.txtPlayerError.text = "Sending report..."

            ChannelDownReporter.report(
                username = username,
                channelName = channel.name ?: "Unknown Channel",
                onSuccess = {
                    binding.txtPlayerError.text = "Channel reported. Our team will look into it."
                },
                onFailure = {
                    binding.btnReportChannel.visibility = View.VISIBLE
                    binding.txtPlayerError.text = "Failed to send report."
                }
            )
        }
    }







    private fun loadAllChannelsToMemory(
        forceRefresh: Boolean
    ) {


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
                    forceRefreshData()
                    return@launch
                }



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



        showPreview(

            channelList[previewPosition]

        )

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



        binding.btnReportChannel.visibility =
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


        isGoingToFullscreen =
            true






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



    private fun forceRefreshData() {


        if (isRefreshing)
            return



        isRefreshing = true





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



                        loadAllChannelsToMemory(
                            true
                        )

                    }





                    override fun onError(
                        message: String
                    ) {


                        isRefreshing = false



                        Toast.makeText(

                            this@FavoriteChannelsActivity,

                            message,

                            Toast.LENGTH_LONG

                        ).show()

                    }

                }
        )

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


                    if (
                        previewPosition in channelList.indices
                    ) {


                        loadProgramGuide(

                            channelList[previewPosition]

                        )

                    }


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



        AlertDialog.Builder(this)

            .setTitle(
                "Remove Favorite"
            )


            .setMessage(

                "Remove ${channel.name} from favorites?"

            )


            .setPositiveButton(
                "Remove"
            ) { _, _ ->


                removeFromFavorites(
                    channel
                )

            }


            .setNegativeButton(
                "Cancel",
                null
            )


            .show()

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


    private fun loadProgramGuide(
        channel: LiveChannel
    ) {


        val epgId =

            channel.epg_channel_id
                ?: channel.stream_id
                    ?.toString()
                ?: return






        lifecycleScope.launch {


            try {



                val (nowEpg, nextEpg) =

                    repository.getNowNextEpg(
                        epgId
                    )







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



                }
                else {



                    binding.txtNowTitle.text =
                        "No EPG"



                    binding.txtNowTime.text =
                        ""



                    binding.txtOverlayProgram.text =
                        ""

                }








                if (
                    nextEpg != null
                ) {



                    binding.txtNextTitle.text =

                        nextEpg.title
                            ?: ""





                    binding.txtNextTime.text =

                        "${EpgTimeFormatter.format(nextEpg.startTimestamp)} - ${EpgTimeFormatter.format(nextEpg.stopTimestamp)}"




                }
                else {



                    binding.txtNextTitle.text =
                        ""



                    binding.txtNextTime.text =
                        ""

                }





            }
            catch(
                e: Exception
            ) {



                binding.txtNowTitle.text =
                    "EPG unavailable"



                binding.txtNowTime.text =
                    ""



                binding.txtNextTitle.text =
                    ""



                binding.txtNextTime.text =
                    ""

            }

        }

    }

















    override fun onResume() {


        super.onResume()



        isGoingToFullscreen =
            false






        PlayerManager.attach(

            this,

            binding.playerView

        )





        PlayerManager.resume()





        binding.playerView.player
            ?.addListener(

                playerListener

            )

    }









    override fun onPause() {


        super.onPause()





        binding.playerView.player
            ?.removeListener(

                playerListener

            )






        if (
            !isGoingToFullscreen
        ) {


            PlayerManager.pause()

        }






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


}
