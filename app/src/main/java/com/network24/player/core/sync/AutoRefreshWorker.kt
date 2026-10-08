package com.network24.player.core.sync

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.network24.player.core.preferences.PreferenceManager
import java.util.concurrent.TimeUnit

/**
 * Settings > Auto Refresh: re-downloads the channel list and the TV guide on
 * a schedule, the way TiviMate's playlist/EPG update interval works.
 * Customers used to log out every night just to get new channels (game
 * channels are added and renamed all the time).
 */
class AutoRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferenceManager(applicationContext)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if ((!manual && prefs.getAutoRefreshHours() <= 0) || prefs.getLoginCredentials() == null) {
            return Result.success()
        }

        val sync = SyncManager(applicationContext)
        if (sync.syncLiveChannelsAll(force = true) is SyncResult.Error ||
            sync.syncFullEpg(force = true) is SyncResult.Error
        ) {
            // A dropped connection shouldn't wait a whole interval.
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        prefs.setLastDataRefreshMs(System.currentTimeMillis())
        prefs.setRefreshedVersionCode(com.network24.player.BuildConfig.VERSION_CODE)
        // Open screens reload their guide (BaseActivity listens for this).
        applicationContext.sendBroadcast(
            Intent(ACTION_EPG_UPDATED).setPackage(applicationContext.packageName)
        )
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "n24_auto_refresh"
        private const val CATCH_UP_WORK = "n24_auto_refresh_now"
        private const val ACTION_EPG_UPDATED = "ACTION_EPG_UPDATED"
        private const val MAX_RETRIES = 3
        private const val KEY_MANUAL = "manual"
        /** Unique name of the "Refresh now" job, observed by the Auto Refresh screen. */
        const val MANUAL_WORK = "n24_refresh_now"

        private val networkRequired = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Keeps the periodic job in line with the setting. [intervalChanged]
         * replaces an existing schedule (the user picked a new interval);
         * otherwise an already scheduled job is left alone.
         */
        fun schedule(context: Context, intervalChanged: Boolean = false) {
            val workManager = WorkManager.getInstance(context)
            val hours = PreferenceManager(context).getAutoRefreshHours()
            if (hours <= 0) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(networkRequired)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                if (intervalChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Settings > Auto Refresh > "Refresh now": same work, right away, even when Auto Refresh is off. */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_MANUAL to true))
                    .setConstraints(networkRequired)
                    .build()
            )
        }

        /**
         * Whenever the app is opened with data older than an hour, or on the first
         * launch after an app update, refresh channels, categories and the guide now
         * (Fire OS and battery savers also delay the periodic job a lot).
         */
        fun refreshIfDue(context: Context) {
            val prefs = PreferenceManager(context)
            if (prefs.getLoginCredentials() == null) return
            val updated = prefs.getRefreshedVersionCode() != com.network24.player.BuildConfig.VERSION_CODE
            val age = System.currentTimeMillis() - prefs.getLastDataRefreshMs()
            val stale = prefs.getAutoRefreshHours() > 0 && age >= TimeUnit.HOURS.toMillis(1)
            if (!updated && !stale) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                CATCH_UP_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_MANUAL to true))
                    .setConstraints(networkRequired)
                    .build()
            )
        }
    }
}
