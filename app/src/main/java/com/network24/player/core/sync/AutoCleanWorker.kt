package com.network24.player.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.network24.player.core.cache.memory.MemoryCache
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Settings > Auto clean: the "Fresh start" clean-up on a schedule, the way Auto update keeps channels fresh.
 * Fire TV sticks have little storage and the app's picture cache grows with every poster and channel logo shown;
 * customers used to be told "clear the cache" over the phone. Login, favourites, reminders, parental lock and
 * settings are never touched. A scheduled clean does exactly what "Clean now" does.
 */
class AutoCleanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && PreferenceManager(applicationContext).getAutoCleanDays() <= 0) return Result.success()
        clean(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "n24_auto_clean"
        private const val KEY_MANUAL = "manual"
        /** Unique name of the "Clean now" job, observed by the Auto clean screen. */
        const val MANUAL_WORK = "n24_clean_now"

        /**
         * Clears recently watched, recent searches, the picture cache (memory + disk), the app's cache folder and the
         * in-memory catalogue cache. Returns the bytes freed from the cache folder.
         */
        @OptIn(coil.annotation.ExperimentalCoilApi::class)
        suspend fun clean(context: Context): Long {
            val app = context.applicationContext
            val prefs = PreferenceManager(app)
            MemoryCache.clearAll()
            prefs.setLastSyncTime(0L)
            runCatching { LiveHistoryRepository(app).clearLocal() }
            app.getSharedPreferences("n24_search", Context.MODE_PRIVATE).edit().clear().apply()
            val freed = withContext(Dispatchers.IO) {
                val loader = coil.Coil.imageLoader(app)
                loader.memoryCache?.clear()
                runCatching { loader.diskCache?.clear() }
                var bytes = 0L
                app.cacheDir.listFiles()?.forEach { f ->
                    bytes += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    runCatching { f.deleteRecursively() }
                }
                bytes
            }
            prefs.setLastCleanMs(System.currentTimeMillis())
            prefs.setLastCleanFreedBytes(freed)
            return freed
        }

        /** Keeps the periodic job in line with the setting (see [AutoRefreshWorker.schedule]). */
        fun schedule(context: Context, intervalChanged: Boolean = false) {
            val workManager = WorkManager.getInstance(context)
            val days = PreferenceManager(context).getAutoCleanDays()
            if (days <= 0) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoCleanWorker>(days.toLong(), TimeUnit.DAYS)
                .setInitialDelay(days.toLong(), TimeUnit.DAYS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                if (intervalChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Settings > Auto clean > "Clean now": the same clean, right away, even when the schedule is off. */
        fun cleanNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoCleanWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_MANUAL to true))
                    .build()
            )
        }

        /** Fire OS may delay periodic jobs a lot: when the app opens with the last clean older than the interval, clean now. */
        fun cleanIfDue(context: Context) {
            val prefs = PreferenceManager(context)
            val days = prefs.getAutoCleanDays()
            if (days <= 0) return
            if (prefs.getLastCleanMs() == 0L) { prefs.setLastCleanMs(System.currentTimeMillis()); return }
            if (System.currentTimeMillis() - prefs.getLastCleanMs() < TimeUnit.DAYS.toMillis(days.toLong())) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                PERIODIC_WORK + "_catchup", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AutoCleanWorker>().build()
            )
        }
    }
}
