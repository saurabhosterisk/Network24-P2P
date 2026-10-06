package com.network24.player.features.live.history

import android.content.Context
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.player.manager.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records live channels without tying watch history to an Activity lifecycle. A channel only counts as watched
 * once it has really played for [MIN_WATCH_MS] - flicking through previews no longer fills Recently Watched.
 */
object LiveWatchHistory {

    private const val MIN_WATCH_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null

    fun record(context: Context, channel: LiveChannel) {
        val streamId = channel.stream_id ?: return
        val appContext = context.applicationContext
        pending?.cancel()
        pending = scope.launch {
            delay(MIN_WATCH_MS)
            if (PlayerManager.currentStreamId != streamId || !PlayerManager.isPlaying()) return@launch
            withContext(Dispatchers.IO) { LiveHistoryRepository(appContext).record(channel) }
        }
    }
}
