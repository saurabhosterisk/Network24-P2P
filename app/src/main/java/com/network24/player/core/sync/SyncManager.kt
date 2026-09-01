package com.network24.player.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.network24.player.core.api.ApiClient
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.SyncMetaEntity
import com.network24.player.core.database.mapper.toCategoryEntity
import com.network24.player.core.database.mapper.toChannelEntity
import com.network24.player.core.database.mapper.toEpgEntity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.cache.memory.MemoryCache
import com.network24.player.core.network.DownloadProgressListener
import com.network24.player.core.network.ProgressResponseBody
import com.network24.player.common.models.LoginCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import android.util.Xml
import com.network24.player.core.database.entity.EpgEntity
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


class SyncManager(private val context: Context) {

    private companion object {
        private const val TAG = "Network24Sync"
        private const val EPG_INSERT_BATCH_SIZE = 2_000
        private const val FULL_EPG_FRESH_MS = 6L * 60L * 60L * 1000L

        private val fullEpgSyncScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
        private val fullEpgSyncMutex = Mutex()
        private var activeFullEpgSync: Deferred<SyncResult>? = null

        // Dashboard and every Live/Favorites/Category screen each call
        // syncAllData() independently on their own first load. Without this,
        // two concurrent full-catalogue downloads compete for the same slow
        // connection (seen on Fire TV Stick) until one read stalls past the
        // OkHttp timeout and the socket gets torn down mid-parse for both.
        private val categoriesSyncScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
        private val categoriesSyncMutex = Mutex()
        private var activeCategoriesSync: Deferred<SyncResult>? = null

        private val channelsSyncScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
        private val channelsSyncMutex = Mutex()
        private var activeChannelsSync: Deferred<SyncResult>? = null

        // Do not use ThreadLocal.withInitial here: that Java 8 API was added
        // to Android only in API 26, while Fire TV Stick 4K (1st Gen) runs
        // API 25. The explicit initialValue implementation is API 1 safe.
        private val xmltvOffsetDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                    isLenient = false
                }
        }
        private val xmltvUtcDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }

    private val db = DatabaseProvider.get(context)

    private fun baseUrl(server: String): String = server.trim().trimEnd('/') + "/"

    private fun resolveCredentials(provided: LoginCredentials?): LoginCredentials? {
        return provided ?: PreferenceManager(context).getLoginCredentials()
    }

    private fun serverForLog(server: String): String {
        return server.trim().trimEnd('/').replace(Regex("(?i)(https?://)[^/]+"), "$1<configured-host>")
    }

    suspend fun syncLiveCategories(
        force: Boolean = false,
        credentials: LoginCredentials? = null
    ): SyncResult {
        val runningSync = categoriesSyncMutex.withLock {
            activeCategoriesSync?.takeIf { it.isActive } ?: run {
                val newSync = categoriesSyncScope.async {
                    syncLiveCategoriesInternal(force, credentials)
                }
                activeCategoriesSync = newSync
                newSync.invokeOnCompletion {
                    categoriesSyncScope.launch {
                        categoriesSyncMutex.withLock {
                            if (activeCategoriesSync === newSync) {
                                activeCategoriesSync = null
                            }
                        }
                    }
                }
                newSync
            }
        }

        return runningSync.await()
    }

    private suspend fun syncLiveCategoriesInternal(
        force: Boolean,
        credentials: LoginCredentials?
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val creds = resolveCredentials(credentials)
                ?: return@withContext SyncResult.Error("Missing login credentials")

            val service = ApiClient.get(baseUrl(creds.server))
            // If your ApiClient uses create(...) instead of get(...), use:
            // val service = ApiClient.create(baseUrl(creds.server))

            if (!force) {
                // future: staleness policy using sync_meta
                db.syncMetaDao().get(SyncKeys.LIVE_CATEGORIES)
            }

            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "categories_start server=${serverForLog(creds.server)} user=${creds.username}")
            val response = service.getLiveCategories(creds.username, creds.password)
            if (!response.isSuccessful) {
                Log.e(TAG, "categories_http status=${response.code()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                return@withContext SyncResult.Error("Categories sync failed: HTTP ${response.code()}")
            }

            val categories = response.body().orEmpty()
            Log.i(TAG, "categories_success count=${categories.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")

            val entities = categories.mapIndexed { index, api ->
                api.toCategoryEntity(position = index)
            }

            db.withTransaction {
                db.categoryDao().clearByType(CategoryType.LIVE)
                db.categoryDao().upsertAll(entities)

                db.syncMetaDao().upsert(
                    SyncMetaEntity(
                        key = SyncKeys.LIVE_CATEGORIES,
                        lastSyncEpochMs = System.currentTimeMillis()
                    )
                )
            }

            SyncResult.Success
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "categories_exception ${t.message}", t)
            SyncResult.Error("Categories sync exception: ${t.message}", t)
        }
    }

    suspend fun syncLiveChannelsAll(
        force: Boolean = false,
        credentials: LoginCredentials? = null,
        onProgress: (Int) -> Unit = {}
    ): SyncResult {
        val runningSync = channelsSyncMutex.withLock {
            activeChannelsSync?.takeIf { it.isActive } ?: run {
                val newSync = channelsSyncScope.async {
                    syncLiveChannelsAllInternal(force, credentials, onProgress)
                }
                activeChannelsSync = newSync
                newSync.invokeOnCompletion {
                    channelsSyncScope.launch {
                        channelsSyncMutex.withLock {
                            if (activeChannelsSync === newSync) {
                                activeChannelsSync = null
                            }
                        }
                    }
                }
                newSync
            }
        }

        return runningSync.await()
    }

    private suspend fun syncLiveChannelsAllInternal(
        force: Boolean,
        credentials: LoginCredentials?,
        onProgress: (Int) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val creds = resolveCredentials(credentials)
                ?: return@withContext SyncResult.Error("Missing login credentials")

            val service = ApiClient.get(baseUrl(creds.server))
            // If your ApiClient uses create(...) instead of get(...), use:
            // val service = ApiClient.create(baseUrl(creds.server))

            if (!force) {
                db.syncMetaDao().get(SyncKeys.LIVE_CHANNELS_ALL)
            }

            // Xtream: categoryId="" commonly returns all channels
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "channels_start server=${serverForLog(creds.server)} user=${creds.username}")
            val response = service.getLiveStreams(
                username = creds.username,
                password = creds.password,
                categoryId = "",
                progress = DownloadProgressListener { percent -> onProgress(percent) }
            )

            if (!response.isSuccessful) {
                Log.e(TAG, "channels_http status=${response.code()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                return@withContext SyncResult.Error("Channels sync failed: HTTP ${response.code()}")
            }

            val channels = response.body().orEmpty()
            Log.i(TAG, "channels_success count=${channels.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            val entities = channels
                .filter { (it.stream_id ?: 0) != 0 }
                .map { it.toChannelEntity() }

            db.withTransaction {
                db.channelDao().clearAll()
                db.channelDao().upsertAll(entities)

                db.syncMetaDao().upsert(
                    SyncMetaEntity(
                        key = SyncKeys.LIVE_CHANNELS_ALL,
                        lastSyncEpochMs = System.currentTimeMillis()
                    )
                )
            }

            // The channel response may contain newly assigned epg_channel_id
            // values. Do not let an old in-memory channel list hide them.
            MemoryCache.clearAll()

            SyncResult.Success
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "channels_exception ${t.message}", t)
            SyncResult.Error("Channels sync exception: ${t.message}", t)
        }
    }

    suspend fun syncShortEpg(streamId: Int, force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        try {
            val creds = PreferenceManager(context).getLoginCredentials()
                ?: return@withContext SyncResult.Error("Missing login credentials")

            val service = ApiClient.get(baseUrl(creds.server))
            // If your ApiClient uses create(...) instead of get(...), use:
            // val service = ApiClient.create(baseUrl(creds.server))

            if (!force) {
                db.syncMetaDao().get(SyncKeys.epgKey(streamId))
            }

            val response = service.getShortEPG(
                username = creds.username,
                password = creds.password,
                streamId = streamId
            )

            if (!response.isSuccessful) {
                return@withContext SyncResult.Error("EPG sync failed: HTTP ${response.code()}")
            }

            val listings = response.body()?.epg_listings.orEmpty()
            val entities = listings.map { it.toEpgEntity(streamId) }

            db.withTransaction {
                db.epgDao().replaceForStream(streamId, entities)

                db.syncMetaDao().upsert(
                    SyncMetaEntity(
                        key = SyncKeys.epgKey(streamId),
                        lastSyncEpochMs = System.currentTimeMillis()
                    )
                )
            }

            SyncResult.Success
        } catch (t: Throwable) {
            SyncResult.Error("EPG sync exception: ${t.message}", t)
        }
    }

    /**
     * Full XMLTV downloads are large. All callers share one active refresh so
     * opening Live With EPG while the dashboard is syncing cannot download and
     * parse the same guide twice.
     */
    suspend fun syncFullEpg(force: Boolean = false, onProgress: (Int) -> Unit = {}): SyncResult {
        if (!force && isFullEpgFresh()) {
            return SyncResult.Success
        }

        val runningSync = fullEpgSyncMutex.withLock {
            activeFullEpgSync?.takeIf { it.isActive } ?: run {
                val newSync = fullEpgSyncScope.async {
                    syncFullEpgInternal(onProgress)
                }
                activeFullEpgSync = newSync
                newSync.invokeOnCompletion {
                    fullEpgSyncScope.launch {
                        fullEpgSyncMutex.withLock {
                            if (activeFullEpgSync === newSync) {
                                activeFullEpgSync = null
                            }
                        }
                    }
                }
                newSync
            }
        }

        return runningSync.await()
    }

    private suspend fun isFullEpgFresh(): Boolean {
        val lastSync = db.syncMetaDao()
            .get(SyncKeys.FULL_EPG)
            ?.lastSyncEpochMs
            ?: return false

        return System.currentTimeMillis() - lastSync < FULL_EPG_FRESH_MS
    }

    private suspend fun syncFullEpgInternal(onProgress: (Int) -> Unit): SyncResult = withContext(Dispatchers.IO) {
        try {
            val creds = PreferenceManager(context).getLoginCredentials()
                ?: return@withContext SyncResult.Error("Missing login credentials")
            val server = creds.server.trim().trimEnd('/') + "/"
            val service = ApiClient.get(server)
            // optional staleness check via sync_meta later (skip for now)

            val response = service.getXmlTv(creds.username, creds.password)
            if (!response.isSuccessful) {
                return@withContext SyncResult.Error("XMLTV download failed: HTTP ${response.code()}")
            }

            val body: ResponseBody = response.body() ?: return@withContext SyncResult.Error("XMLTV empty body")
            val advertisedContentLength = response.headers()
                .get(ProgressResponseBody.UNCOMPRESSED_LENGTH_HEADER)
                ?.toLongOrNull()
            val trackedBody = ProgressResponseBody(
                body,
                DownloadProgressListener { percent -> onProgress(percent) },
                advertisedContentLength
            )

            // Insert in sizeable batches. Streaming avoids holding the XMLTV
            // document in memory while reused formatters keep parsing cheap.
            db.epgDao().deleteAll()
            trackedBody.byteStream().use { input ->
                parseAndInsertXmlTv(input)
            }
            db.syncMetaDao().upsert(SyncMetaEntity(SyncKeys.FULL_EPG, System.currentTimeMillis()))

            // --> CLEAR THE IN-MEMORY EPG CACHE SO UI RE-FETCHES <--
            MemoryCache.clearAll()

            SyncResult.Success
        } catch (t: Throwable) {
            SyncResult.Error("Full EPG sync failed: ${t.message}", t)
        }
    }


    // -------------------------
// Internal parser + batch insert
// -------------------------
    private suspend fun parseAndInsertXmlTv(input: InputStream) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val batch = ArrayList<EpgEntity>(EPG_INSERT_BATCH_SIZE)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val epgChannelId = parser.getAttributeValue(null, "channel") ?: ""
                val startAttr = parser.getAttributeValue(null, "start")
                val stopAttr = parser.getAttributeValue(null, "stop")

                val startMs = parseXmlTvTimeToMs(startAttr)
                val stopMs = parseXmlTvTimeToMs(stopAttr)

                var title: String? = null
                var desc: String? = null

                // read inside <programme>...</programme>
                while (true) {
                    event = parser.next()
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "title" -> title = readText(parser)
                            "desc" -> desc = readText(parser)
                        }
                    } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                        break
                    } else if (event == XmlPullParser.END_DOCUMENT) {
                        break
                    }
                }

                // Build stable-ish id: channel + start + stop
                val id = "${epgChannelId}_${startMs}_${stopMs}"

                // Store using your existing old keys
                val entity = EpgEntity(
                    id = id,
                    streamId = 0, // unknown in XMLTV (we’ll query by epgChannelId)
                    epgChannelId = epgChannelId,
                    title = title,
                    description = desc,
                    start = startAttr,
                    end = stopAttr,
                    startTimestamp = if (startMs > 0) startMs else null,
                    stopTimestamp = if (stopMs > 0) stopMs else null
                )

                // skip bad rows
                if (epgChannelId.isNotBlank() && startMs > 0 && stopMs > 0) {
                    batch.add(entity)
                }

                if (batch.size >= EPG_INSERT_BATCH_SIZE) {
                    db.epgDao().insertAll(batch)
                    batch.clear()
                }
            }
            event = parser.next()
        }

        if (batch.isNotEmpty()) {
            db.epgDao().insertAll(batch)
        }
    }

    private fun readText(parser: XmlPullParser): String {
        // parser is at START_TAG; next() to TEXT then END_TAG
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result
    }

    /**
     * XMLTV time example:\n
     * 20250101153000 +0530\n
     * We convert it to epoch millis.\n
     * If parse fails returns 0.\n
     */
    private fun parseXmlTvTimeToMs(value: String?): Long {
        if (value.isNullOrBlank()) return 0L

        return try {
            // XMLTV normally provides an explicit offset, e.g.
            // "20260809153000 +0530". Parse it so EPG is not shifted
            // by the device/provider timezone.
            val normalized = value.trim()
            val formatter: SimpleDateFormat = requireNotNull(if (normalized.length >= 19) {
                xmltvOffsetDateFormatter.get()
            } else {
                xmltvUtcDateFormatter.get()
            })
            formatter.parse(normalized)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
