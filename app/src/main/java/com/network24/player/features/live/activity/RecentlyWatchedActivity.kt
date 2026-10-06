package com.network24.player.features.live.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.MasterChannelSearchResult
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.databinding.ActivityRecentlyWatchedBinding
import com.network24.player.features.live.adapter.MasterChannelSearchAdapter
import com.network24.player.features.parental.WebStateRepository
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentlyWatchedActivity : BaseActivity() {

    private lateinit var binding: ActivityRecentlyWatchedBinding
    private lateinit var historyRepository: LiveHistoryRepository
    private val recentResults = mutableListOf<MasterChannelSearchResult>()
    private lateinit var adapter: MasterChannelSearchAdapter
    private val database by lazy { DatabaseProvider.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentlyWatchedBinding.inflate(layoutInflater)
        setContentView(setupGlobalRightDrawer(binding.root, binding.btnMore))

        historyRepository = LiveHistoryRepository(this)
        adapter = MasterChannelSearchAdapter(
            onSelected = ::openChannel,
            onLongClicked = ::confirmRemove
        )
        binding.rvRecentChannels.layoutManager = LinearLayoutManager(this)
        binding.rvRecentChannels.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
    }

    private fun confirmClearAll() {
        showConfirmDialog(
            "Clear recently watched?",
            "Removes every channel from this list on all your devices and the web player.",
            "Clear all",
            onPositive = {
                lifecycleScope.launch {
                    val result = WebStateRepository(this@RecentlyWatchedActivity).clearRecent()
                    loadRecentlyWatched()
                    android.widget.Toast.makeText(
                        this@RecentlyWatchedActivity,
                        if (result.isSuccess) "Recently watched cleared" else "Cleared here; your other devices update when the server can be reached",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun confirmRemove(selected: MasterChannelSearchResult) {
        showConfirmDialog(
            "Remove from recently watched?",
            selected.channelName.orEmpty(),
            "Remove",
            onPositive = {
                lifecycleScope.launch {
                    WebStateRepository(this@RecentlyWatchedActivity).removeRecent(selected.streamId)
                    loadRecentlyWatched()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        loadRecentlyWatched()
    }

    private fun loadRecentlyWatched() {
        lifecycleScope.launch {
            binding.progressLoading.visibility = View.VISIBLE
            val results = withContext(Dispatchers.IO) {
                val recentChannels = historyRepository.getRecentlyWatched()
                val categoryNames = database.categoryDao()
                    .getByType(CategoryType.LIVE)
                    .associate { it.categoryId to it.name }
                val favoriteIds = database.favoritesDao()
                    .getByType(FavoriteItemType.LIVE_CHANNEL)
                    .map { it.itemId }
                    .toSet()

                recentChannels.mapNotNull { channel ->
                    val streamId = channel.stream_id ?: return@mapNotNull null
                    MasterChannelSearchResult(
                        streamId = streamId,
                        channelName = channel.name,
                        categoryId = channel.category_id,
                        categoryName = channel.category_id
                            ?.let { categoryNames[it] }
                            ?: "Uncategorized",
                        icon = channel.stream_icon,
                        streamType = channel.stream_type,
                        epgChannelId = channel.epg_channel_id,
                        tvArchive = channel.tv_archive,
                        tvArchiveDuration = channel.tv_archive_duration,
                        directSource = channel.direct_source,
                        num = channel.num,
                        added = channel.added,
                        customSid = channel.custom_sid,
                        isFavorite = favoriteIds.contains(streamId.toString())
                    )
                }
            }

            binding.progressLoading.visibility = View.GONE
            recentResults.clear()
            recentResults.addAll(results)
            adapter.submitResults(results)
            adapter.updateCurrentPrograms(loadCurrentPrograms(results))
            binding.btnClearAll.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            binding.txtEmpty.visibility = if (results.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun openChannel(selected: MasterChannelSearchResult) {
        val playbackChannels = recentResults.map { it.toLiveChannel() }
        val position = playbackChannels.indexOfFirst { it.stream_id == selected.streamId }
        if (position < 0) return

        PlayerState.channels.clear()
        PlayerState.channels.addAll(playbackChannels)
        PlayerState.currentPosition = position

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PLAY_SELECTED_CHANNEL, true)
        )
    }

    private suspend fun loadCurrentPrograms(
        results: List<MasterChannelSearchResult>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val epgChannelIds = results
            .mapNotNull { it.epgChannelId?.takeIf(String::isNotBlank) }
            .distinct()

        if (epgChannelIds.isEmpty()) return@withContext emptyMap()

        database.epgDao()
            .getNowByEpgChannelIdsChunked(epgChannelIds, System.currentTimeMillis())
            .asSequence()
            .filter { !it.title.isNullOrBlank() && !it.epgChannelId.isNullOrBlank() }
            .distinctBy { it.epgChannelId }
            .associate { it.epgChannelId!! to it.title!! }
    }
}
