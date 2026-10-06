package com.network24.player.core.database.repository

import android.content.Context
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.HistoryEntity
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.parental.WebStateRepository

class LiveHistoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = DatabaseProvider.get(appContext)
    private val prefs = PreferenceManager(appContext)

    suspend fun record(channel: LiveChannel) {
        val streamId = channel.stream_id ?: return
        val itemType = liveItemType() ?: return

        db.historyDao().upsert(
            HistoryEntity(
                key = "$itemType:$streamId",
                itemType = itemType,
                itemId = streamId.toString(),
                updatedAtMs = System.currentTimeMillis()
            )
        )
        db.historyDao().trimToRecent(itemType, MAX_RECENT_CHANNELS)
        // the account's list on Main (shared with play.web24.live and the customer's other devices)
        WebStateRepository(appContext).addRecent(streamId)
    }

    /** This device's recently watched stream ids, newest first. */
    suspend fun localRecentIds(): List<Int> {
        val itemType = liveItemType() ?: return emptyList()
        return db.historyDao().getRecentByType(itemType, MAX_RECENT_CHANNELS).mapNotNull { it.itemId.toIntOrNull() }
    }

    /**
     * Puts the account's list (newest first, from Main - it includes what was watched on the web player and other
     * devices) on top, in that order. Channels only watched on this device keep their place below.
     */
    suspend fun mergeServerOrder(serverIds: List<Int>) {
        val itemType = liveItemType() ?: return
        val now = System.currentTimeMillis()
        serverIds.take(MAX_RECENT_CHANNELS).forEachIndexed { index, streamId ->
            db.historyDao().upsert(
                HistoryEntity(
                    key = "$itemType:$streamId",
                    itemType = itemType,
                    itemId = streamId.toString(),
                    updatedAtMs = now - index * 1000L
                )
            )
        }
        db.historyDao().trimToRecent(itemType, MAX_RECENT_CHANNELS)
    }

    /** Makes this device's list exactly the account's list (newest first). */
    suspend fun replaceWith(serverIds: List<Int>) {
        val itemType = liveItemType() ?: return
        db.historyDao().deleteByType(itemType)
        mergeServerOrder(serverIds)
    }

    suspend fun clearLocal() {
        val itemType = liveItemType() ?: return
        db.historyDao().deleteByType(itemType)
    }

    suspend fun removeLocal(streamId: Int) {
        val itemType = liveItemType() ?: return
        db.historyDao().deleteByKey("$itemType:$streamId")
    }

    suspend fun getRecentlyWatched(): List<LiveChannel> {
        val itemType = liveItemType() ?: return emptyList()
        val history = db.historyDao().getRecentByType(
            itemType = itemType,
            limit = MAX_RECENT_CHANNELS
        )
        val streamIds = history.mapNotNull { it.itemId.toIntOrNull() }
        if (streamIds.isEmpty()) return emptyList()

        val channelsById = db.channelDao()
            .getByStreamIds(streamIds)
            .associateBy { it.streamId }

        // channels of categories under the parental lock are left out until the PIN is entered
        val locked = ParentalLock.activeLockedIds(appContext)
        return streamIds.mapNotNull { streamId ->
            channelsById[streamId]?.takeIf { it.categoryId == null || it.categoryId !in locked }?.toLiveChannel()
        }
    }

    private fun liveItemType(): String? {
        val username = prefs.getUsername().trim()
        return username.takeIf { it.isNotEmpty() }?.let { "LIVE:$it" }
    }

    private companion object {
        const val MAX_RECENT_CHANNELS = 50
    }
}
