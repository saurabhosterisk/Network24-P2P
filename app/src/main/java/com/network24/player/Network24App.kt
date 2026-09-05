package com.network24.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import com.network24.player.core.compat.Network24DeviceCompatibility
import com.network24.player.core.diagnostics.Network24CrashReporter
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.vpn.TunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        reconnectVpnSilentlyIfAlreadyConsented()
    }

    /**
     * Best-effort reconnect on process start for a device that already
     * completed the VPN consent dialog in a previous session (e.g. after a
     * reboot). Never shows a dialog here - if consent isn't already granted,
     * DashboardActivity.attemptVpnSetup() handles that with an Activity
     * available. Any failure here is a silent no-op.
     */
    private fun reconnectVpnSilentlyIfAlreadyConsented() {
        val prefs = PreferenceManager(this)
        if (!prefs.isVpnEnabled() || prefs.getVpnProvisioning() == null) return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val tunnelManager = TunnelManager(this@Network24App)
                if (tunnelManager.requestConsentIntent() == null) {
                    tunnelManager.start(prefs)
                }
            }
        }
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
