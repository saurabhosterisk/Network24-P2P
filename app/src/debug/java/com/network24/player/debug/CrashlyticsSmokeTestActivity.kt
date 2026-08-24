package com.network24.player.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** ADB-only smoke test. This source set is excluded from release builds. */
class CrashlyticsSmokeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Crashlytics smoke test running…" })

        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(true)
            setCustomKey("test_run", "adb_crashlytics_smoke")
            log("event=adb_crashlytics_smoke_test")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            throw RuntimeException("Network24 Crashlytics ADB smoke test")
        }, 1_500L)
    }
}
