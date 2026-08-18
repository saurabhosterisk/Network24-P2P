package com.network24.player.features.live.repository

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.network24.player.core.cache.memory.CacheKeys as MemKeys
import com.network24.player.core.cache.memory.CacheTtl
import com.network24.player.core.cache.memory.MemoryCache
import com.network24.player.common.models.LoginCredentials
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.MasterChannelSearchResult
import com.network24.player.core.database.mapper.toLiveCategory
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.core.database.mapper.toEpgListing
import com.network24.player.core.sync.SyncManager
import com.network24.player.core.sync.SyncResult
import com.network24.player.features.live.models.LiveCategory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.live.models.ShortEPGResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LiveRepository(private val context: Context) {

    private val db = DatabaseProvider.get(context)
    private val sync = SyncManager(context)

    fun syncAllData(
        server: String,
        username: String,
        password: String,
        callback: SyncCallback
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val credentials = LoginCredentials(server.trim(), username.trim(), password)
                val r1 = sync.syncLiveCategories(force = true, credentials = credentials)
                if (r1 is SyncResult.Error) throw Exception(r1.message)

                val r2 = sync.syncLiveChannelsAll(force = true, credentials = credentials)
                if (r2 is SyncResult.Error) throw Exception(r2.message)

                MemoryCache.clearAll()

                withContext(Dispatchers.Main) { callback.onSuccess() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.message ?: "Unknown Error Occurred") }
            }
        }
    }

    suspend fun getCategories(
        server: String,
        username: String,
        password: String,
        forceRefresh: Boolean = false
    ): List<LiveCategory> {
        if (!forceRefresh) {
            MemoryCache.get<List<LiveCategory>>(MemKeys.LIVE_CATEGORIES)?.let { return it }
        }

        val roomList = db.categoryDao().getByType(CategoryType.LIVE).map { it.toLiveCategory() }
        if (roomList.isNotEmpty() && !forceRefresh) {
            MemoryCache.put(MemKeys.LIVE_CATEGORIES, roomList, CacheTtl.CATEGORIES_MS)
            return roomList
        }

        val credentials = LoginCredentials(server.trim(), username.trim(), password)
        val syncResult = sync.syncLiveCategories(force = true, credentials = credentials)
        if (syncResult is SyncResult.Error) {
            if (roomList.isNotEmpty()) return roomList
            throw Exception(syncResult.message)
        }

        val fresh = db.categoryDao().getByType(CategoryType.LIVE).map { it.toLiveCategory() }
        MemoryCache.put(MemKeys.LIVE_CATEGORIES, fresh, CacheTtl.CATEGORIES_MS)
        return fresh
    }

    suspend fun getChannels(
        server: String,
        username: String,
        password: String,
        categoryId: String,
        forceRefresh: Boolean = false
    ): List<LiveChannel> {
        val safeCategoryId = if (categoryId.isBlank()) "all" else categoryId
        val memKey = MemKeys.liveChannels(safeCategoryId)

        if (!forceRefresh) {
            MemoryCache.get<List<LiveChannel>>(memKey)?.let { return it }
        }

        val roomList = if (safeCategoryId == "all") {
            db.channelDao().getAll().map { it.toLiveChannel() }
        } else {
            db.channelDao().getByCategory(safeCategoryId).map { it.toLiveChannel() }
        }

        if (roomList.isNotEmpty() && !forceRefresh) {
            MemoryCache.put(memKey, roomList, CacheTtl.CHANNELS_MS)
            return roomList
        }

        val credentials = LoginCredentials(server.trim(), username.trim(), password)
        val syncResult = sync.syncLiveChannelsAll(force = true, credentials = credentials)
        if (syncResult is SyncResult.Error) {
            if (roomList.isNotEmpty()) return roomList
            throw Exception(syncResult.message)
        }

        val fresh = if (safeCategoryId == "all") {
            db.channelDao().getAll().map { it.toLiveChannel() }
        } else {
            db.channelDao().getByCategory(safeCategoryId).map { it.toLiveChannel() }
        }

        MemoryCache.put(memKey, fresh, CacheTtl.CHANNELS_MS)
        return fresh
    }

    /**
     * Searches the persisted live-channel catalogue across every category.
     * Matching and ranking are performed by SQLite; only result rows are
     * returned to the UI.
     */
    suspend fun searchAllLiveChannels(rawQuery: String): List<MasterChannelSearchResult> {
        val tokens = rawQuery
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return emptyList()

        val hasLiveCategories = db.categoryDao()
            .getByType(CategoryType.LIVE)
            .isNotEmpty()
        if (!hasLiveCategories) {
            val categorySync = sync.syncLiveCategories(force = true)
            if (categorySync is SyncResult.Error) throw Exception(categorySync.message)
        }

        if (db.channelDao().countAll() == 0) {
            val channelSync = sync.syncLiveChannelsAll(force = true)
            if (channelSync is SyncResult.Error) throw Exception(channelSync.message)
        }

        return db.channelDao().searchAllLiveChannels(
            buildMasterSearchQuery(tokens)
        )
    }

    private fun buildMasterSearchQuery(tokens: List<String>): SimpleSQLiteQuery {
        val normalizedName = listOf(
            "' '", "'-'", "'_'", "'.'", "'/'", "'|'", "'&'",
            "'('", "')'", "CHAR(39)", "':'", "','"
        ).fold("LOWER(COALESCE(c.name, ''))") { expression, character ->
            "REPLACE($expression, $character, '')"
        }

        val compactQuery = tokens.joinToString(separator = "")
        val allWordsClause = tokens.joinToString(" AND ") {
            "$normalizedName LIKE ?"
        }
        val anyWordsClause = tokens.joinToString(" OR ") {
            "$normalizedName LIKE ?"
        }

        val sql = """
            SELECT
                c.streamId AS streamId,
                c.name AS channelName,
                c.categoryId AS categoryId,
                COALESCE(cat.name, 'Uncategorized') AS categoryName,
                c.icon AS icon,
                c.streamType AS streamType,
                c.epgChannelId AS epgChannelId,
                c.tvArchive AS tvArchive,
                c.tvArchiveDuration AS tvArchiveDuration,
                c.directSource AS directSource,
                c.num AS num,
                c.added AS added,
                c.customSid AS customSid,
                EXISTS(
                    SELECT 1 FROM favorites f
                    WHERE f.itemType = 'LIVE_CHANNEL'
                      AND f.itemId = CAST(c.streamId AS TEXT)
                ) AS isFavorite
            FROM channels c
            LEFT JOIN categories cat ON cat.categoryId = c.categoryId
                AND cat.type = 'LIVE'
            WHERE c.name IS NOT NULL
              AND TRIM(c.name) != ''
              AND ($anyWordsClause)
            ORDER BY
                CASE
                    WHEN $normalizedName = ? THEN 1
                    WHEN $normalizedName LIKE ? THEN 2
                    WHEN $allWordsClause THEN 3
                    ELSE 4
                END,
                LENGTH(c.name) ASC,
                c.name COLLATE NOCASE ASC
            LIMIT 100
        """.trimIndent()

        val args = mutableListOf<Any>()
        args.addAll(tokens.map { "%$it%" })
        args.add(compactQuery)
        args.add("$compactQuery%")
        args.addAll(tokens.map { "%$it%" })

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    suspend fun getShortEPG(
        server: String,
        username: String,
        password: String,
        streamId: Int
    ): ShortEPGResponse {
        val memKey = MemKeys.epg(streamId)

        MemoryCache.get<ShortEPGResponse>(memKey)?.let { return it }

        val roomListings = db.epgDao().getByStream(streamId)
        if (roomListings.isNotEmpty()) {
            val epg = ShortEPGResponse(epg_listings = roomListings.map { it.toEpgListing() })
            MemoryCache.put(memKey, epg, CacheTtl.EPG_MS)
            return epg
        }

        val syncResult = sync.syncShortEpg(streamId = streamId, force = true)
        if (syncResult is SyncResult.Error) throw Exception(syncResult.message)

        val freshListings = db.epgDao().getByStream(streamId)
        val fresh = ShortEPGResponse(epg_listings = freshListings.map { it.toEpgListing() })
        MemoryCache.put(memKey, fresh, CacheTtl.EPG_MS)
        return fresh
    }

    suspend fun getNowNextEpg(epgChannelId: String): Pair<com.network24.player.core.database.entity.EpgEntity?, com.network24.player.core.database.entity.EpgEntity?> {
        if (epgChannelId.isBlank()) return Pair(null, null)

        val memKey = "epg_now_next_$epgChannelId"

        MemoryCache.get<Pair<com.network24.player.core.database.entity.EpgEntity?, com.network24.player.core.database.entity.EpgEntity?>>(memKey)?.let {
            return it
        }

        val now = System.currentTimeMillis()
        val nowEntity = db.epgDao().getNowByEpgChannelId(epgChannelId, now)
        val nextEntity = db.epgDao().getNextByEpgChannelId(epgChannelId, now)
        val result = Pair(nowEntity, nextEntity)

        MemoryCache.put(memKey, result, CacheTtl.EPG_MS)
        return result
    }

    /**
     * Returns the complete EPG stored for one channel, limited to the next
     * [days] days. If local XMLTV data is missing, fetches the full guide once
     * and retries the local query. The provider remains the source of truth for
     * how many future days are actually available.
     */
    suspend fun getFullEpg(
        epgChannelId: String,
        days: Int = 3,
        forceRefresh: Boolean = false
    ): List<com.network24.player.core.database.entity.EpgEntity> {
        if (epgChannelId.isBlank()) return emptyList()

        val safeDays = days.coerceIn(1, 3)
        val now = System.currentTimeMillis()
        val end = now + safeDays * 24L * 60L * 60L * 1000L
        val memKey = "epg_full_${epgChannelId}_${safeDays}"

        if (!forceRefresh) {
            MemoryCache.get<List<com.network24.player.core.database.entity.EpgEntity>>(memKey)?.let { return it }
        }

        var listings = db.epgDao().getByEpgChannelId(epgChannelId)
            .filter { (it.stopTimestamp ?: 0L) > now && (it.startTimestamp ?: Long.MAX_VALUE) < end }

        if (listings.isEmpty()) {
            val syncResult = sync.syncFullEpg(force = true)
            if (syncResult is SyncResult.Error) throw Exception(syncResult.message)

            listings = db.epgDao().getByEpgChannelId(epgChannelId)
                .filter { (it.stopTimestamp ?: 0L) > now && (it.startTimestamp ?: Long.MAX_VALUE) < end }
        }

        MemoryCache.put(memKey, listings, CacheTtl.EPG_MS)
        return listings
    }

}
