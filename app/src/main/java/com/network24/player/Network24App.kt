package com.network24.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.network24.player.core.compat.Network24DeviceCompatibility
import com.network24.player.core.diagnostics.Network24CrashReporter
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.vpn.TunnelManager
import com.network24.player.features.vpn.repository.VpnProvisioningRepository
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

        // Secure Relay is an explicit, per-session choice, not a
        // persistent background service: it never reconnects on its own
        // when the app is (re)opened, and it's torn down the moment the
        // app leaves the foreground (see the observer below) - so it is
        // only ever on while the user is actively in the app and has
        // just turned it on.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val prefs = PreferenceManager(this@Network24App)
                if (!prefs.isVpnEnabled()) return
                prefs.setVpnEnabled(false)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        TunnelManager.bringDown(this@Network24App)
                    } catch (e: Exception) {
                        // Best-effort - nothing more to do if this fails.
                    }
                    try {
                        VpnProvisioningRepository(prefs).release()
                    } catch (e: Exception) {
                        // Best-effort - server prunes stale peers independently.
                    }
                }
            }
        })
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
