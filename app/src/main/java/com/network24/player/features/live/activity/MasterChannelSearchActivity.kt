package com.network24.player.features.live.activity

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.MasterChannelSearchResult
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityMasterChannelSearchBinding
import com.network24.player.features.live.adapter.MasterChannelSearchAdapter
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MasterChannelSearchActivity : BaseActivity() {

    private lateinit var binding: ActivityMasterChannelSearchBinding
    private lateinit var repository: LiveRepository
    private lateinit var adapter: MasterChannelSearchAdapter
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var prefs: PreferenceManager
    private val database by lazy { DatabaseProvider.get(this) }

    private var searchJob: Job? = null
    private var searchResults: List<MasterChannelSearchResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMasterChannelSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = LiveRepository(this)
        favoritesRepository = FavoritesRepository(
            database.favoritesDao(),
            FirebaseFirestore.getInstance()
        )
        prefs = PreferenceManager(this)

        adapter = MasterChannelSearchAdapter(
            onSelected = ::playSearchResult,
            onLongClicked = ::confirmToggleFavorite
        )
        binding.rvMasterResults.layoutManager = LinearLayoutManager(this)
        binding.rvMasterResults.adapter = adapter
        binding.rvMasterResults.setHasFixedSize(true)

        binding.btnBack.setOnClickListener { finish() }
        binding.edtMasterSearch.addTextChangedListener { text ->
            scheduleSearch(text?.toString().orEmpty())
        }
        binding.edtMasterSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearch(binding.edtMasterSearch.text?.toString().orEmpty(), immediate = true)
                true
            } else {
                false
            }
        }

        binding.edtMasterSearch.requestFocus()
    }

    private fun scheduleSearch(rawQuery: String, immediate: Boolean = false) {
        val query = rawQuery.trim()
        searchJob?.cancel()

        if (query.isBlank()) {
            searchResults = emptyList()
            adapter.submitResults(emptyList())
            adapter.updateCurrentPrograms(emptyMap())
            binding.progressSearch.visibility = View.GONE
            binding.txtSearchStatus.text = "Search every live channel without choosing a category."
            return
        }

        searchJob = lifecycleScope.launch {
            if (!immediate) delay(250)
            searchChannels(query)
        }
    }

    private suspend fun searchChannels(query: String) {
        binding.progressSearch.visibility = View.VISIBLE
        binding.txtSearchStatus.text = "Searching all live channels..."

        val result = withContext(Dispatchers.IO) {
            runCatching { repository.searchAllLiveChannels(query) }
        }

        if (binding.edtMasterSearch.text?.toString()?.trim() != query) return

        binding.progressSearch.visibility = View.GONE
        result.onFailure { error ->
            searchResults = emptyList()
            adapter.submitResults(emptyList())
            binding.txtSearchStatus.text = error.message ?: "Unable to search channels."
            return
        }

        searchResults = result.getOrDefault(emptyList())
        adapter.submitResults(searchResults)
        adapter.updateCurrentPrograms(emptyMap())
        adapter.updateCurrentPrograms(loadCurrentPrograms(searchResults))
        binding.txtSearchStatus.text = when (searchResults.size) {
            0 -> "No live channels match '$query'."
            1 -> "1 live channel found for '$query'. Select it to play."
            else -> "${searchResults.size} live channels found for '$query'. Select one to play."
        }
    }

    private fun playSearchResult(selected: MasterChannelSearchResult) {
        val playbackChannels = searchResults
            .map { it.toLiveChannel() }
            .distinctBy { it.stream_id }
        val position = playbackChannels.indexOfFirst {
            it.stream_id == selected.streamId
        }
        if (position < 0) return

        PlayerState.channels.clear()
        PlayerState.channels.addAll(playbackChannels)
        PlayerState.currentPosition = position

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PLAY_SELECTED_CHANNEL, true)
        )
    }

    private fun confirmToggleFavorite(result: MasterChannelSearchResult) {
        val streamId = result.streamId.toString()
        val channelName = result.channelName ?: "this channel"

        lifecycleScope.launch {
            val isFavorite = withContext(Dispatchers.IO) {
                DatabaseProvider
                    .get(this@MasterChannelSearchActivity)
                    .favoritesDao()
                    .getAll()
                    .any { it.key == "${FavoriteItemType.LIVE_CHANNEL}:$streamId" }
            }

            val action = if (isFavorite) "remove" else "add"
            AlertDialog.Builder(this@MasterChannelSearchActivity)
                .setTitle("Favorites")
                .setMessage(
                    "Do you want to $action $channelName " +
                        "${if (isFavorite) "from" else "to"} Favorites?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(if (isFavorite) "Remove" else "Add") { _, _ ->
                    updateFavorite(result, isFavorite)
                }
                .show()
        }
    }

    private suspend fun loadCurrentPrograms(
        results: List<MasterChannelSearchResult>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val epgChannelIds = results
            .mapNotNull { it.epgChannelId?.takeIf(String::isNotBlank) }
            .distinct()

        if (epgChannelIds.isEmpty()) return@withContext emptyMap()

        database.epgDao()
            .getNowByEpgChannelIds(epgChannelIds, System.currentTimeMillis())
            .asSequence()
            .filter { !it.title.isNullOrBlank() && !it.epgChannelId.isNullOrBlank() }
            .distinctBy { it.epgChannelId }
            .associate { it.epgChannelId!! to it.title!! }
    }

    private fun updateFavorite(
        result: MasterChannelSearchResult,
        isFavorite: Boolean
    ) {
        val streamId = result.streamId.toString()
        val userId = prefs.getUsername()

        lifecycleScope.launch {
            try {
                if (isFavorite) {
                    favoritesRepository.removeFavorite(
                        userId,
                        FavoriteItemType.LIVE_CHANNEL,
                        streamId
                    )
                } else {
                    favoritesRepository.addFavorite(
                        userId,
                        FavoriteItemType.LIVE_CHANNEL,
                        streamId
                    )
                }

                val updatedResults = searchResults.map { item ->
                    if (item.streamId == result.streamId) {
                        item.copy(isFavorite = !isFavorite)
                    } else {
                        item
                    }
                }
                searchResults = updatedResults
                adapter.submitResults(updatedResults)

                Toast.makeText(
                    this@MasterChannelSearchActivity,
                    "${result.channelName ?: "Channel"} " +
                        if (isFavorite) "removed from Favorites" else "added to Favorites",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    this@MasterChannelSearchActivity,
                    "Could not update Favorites",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }
}
