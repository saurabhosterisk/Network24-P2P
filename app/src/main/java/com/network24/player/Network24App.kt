package com.network24.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import com.network24.player.core.compat.Network24DeviceCompatibility
import com.network24.player.core.diagnostics.Network24CrashReporter

class Network24App : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        var currentActivity: Activity? = null
    }

    private val legacyTv: Boolean by lazy { Network24DeviceCompatibility.isLegacyTv(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
        registerActivityLifecycleCallbacks(this)
        Network24CrashReporter.initialize(this, legacyTv)
    }

    // --- Activity Lifecycle Tracking ---
    override fun onActivityResumed(activity: Activity) { currentActivity = activity }
    override fun onActivityStarted(activity: Activity) { currentActivity = activity }
    override fun onActivityPaused(activity: Activity) {
        if (currentActivity == activity) currentActivity = null
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
