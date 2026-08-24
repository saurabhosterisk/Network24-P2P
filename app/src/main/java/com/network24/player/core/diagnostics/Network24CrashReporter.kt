package com.network24.player.core.diagnostics

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Centralized, privacy-safe diagnostics for production crash investigation.
 * Never pass credentials, tokens, full IPTV URLs, or message contents here.
 */
object Network24CrashReporter {
    private const val TAG = "N24-CrashReporter"

    private fun instance(): FirebaseCrashlytics? = try {
        FirebaseCrashlytics.getInstance()
    } catch (error: Throwable) {
        // Legacy Fire TV devices may not provide every Firebase runtime service.
        Log.w(TAG, "Crash reporting unavailable on this device", error)
        null
    }

    fun initialize(context: Context, legacyTv: Boolean) {
        val crashlytics = instance() ?: return
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            crashlytics.setCustomKey("app_version_name", packageInfo.versionName ?: "unknown")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            crashlytics.setCustomKey("app_version_code", versionCode)
            crashlytics.setCustomKey("android_api", Build.VERSION.SDK_INT)
            crashlytics.setCustomKey("device_manufacturer", Build.MANUFACTURER)
            crashlytics.setCustomKey("device_model", Build.MODEL)
            crashlytics.setCustomKey("legacy_tv", legacyTv)
            crashlytics.log("event=app_start")
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to initialize crash diagnostics", error)
        }
    }

    fun activityStarted(activity: Activity) {
        val crashlytics = instance() ?: return
        try {
            val activityName = activity.javaClass.simpleName
            crashlytics.setCustomKey("current_activity", activityName)
            crashlytics.log("event=activity_start name=$activityName")
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to record activity breadcrumb", error)
        }
    }

    fun log(event: String) {
        instance()?.log("event=$event")
    }

    fun recordException(error: Throwable, context: String) {
        val crashlytics = instance() ?: return
        try {
            crashlytics.setCustomKey("last_error_context", context)
            crashlytics.recordException(error)
        } catch (reportingError: Throwable) {
            Log.w(TAG, "Unable to record non-fatal exception", reportingError)
        }
    }
}
