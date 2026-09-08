package com.network24.player.features.splash.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.network24.player.BuildConfig
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.login.activity.LoginActivity
import com.network24.player.features.updater.manager.UpdateManager

class SplashActivity : BaseActivity() {

    private lateinit var tvVersion: TextView
    private lateinit var pbSplash: ProgressBar

    private var isRouted = false
    private var isInstallingApk = false // 🔥 Naya flag install state track karne ke liye

    // Timeout handler in case the server takes too long to respond
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (!isRouted) {
            Toast.makeText(this, "Network timeout, starting app...", Toast.LENGTH_SHORT).show()
            routeNext()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // If the installed build is newer than the one we last recorded, an
        // update genuinely took effect since the last cold start - skip the
        // check once and go straight to login. This is derived from the real
        // installed VERSION_CODE (not a hand-set flag), so it can never get
        // stuck pointing at a false "just updated" state.
        val updatePrefs = getSharedPreferences("network24_update", MODE_PRIVATE)
        val lastSeenVersion = updatePrefs.getInt("last_seen_version_code", -1)
        val currentVersion = BuildConfig.VERSION_CODE
        if (lastSeenVersion in 0 until currentVersion) {
            updatePrefs.edit().putInt("last_seen_version_code", currentVersion).apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        updatePrefs.edit().putInt("last_seen_version_code", currentVersion).apply()

        tvVersion = findViewById(R.id.tvVersion)
        pbSplash = findViewById(R.id.pbSplash)

        val version = BuildConfig.VERSION_NAME
        val code = BuildConfig.VERSION_CODE
        tvVersion.text = "Version $version ($code)"

        checkUpdate()
    }

    private fun checkUpdate() {
        // Start a 5-second timer
        timeoutHandler.postDelayed(timeoutRunnable, 5000)

        try {
            UpdateManager.checkForUpdate(
                onNoUpdate = {
                    runOnUiThread {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        routeNext()
                    }
                },
                onUpdateAvailable = { update ->
                    runOnUiThread {
                        timeoutHandler.removeCallbacks(timeoutRunnable)

                        UpdateManager.downloadApk(
                            this,
                            "${update.apk}?t=${System.currentTimeMillis()}"
                        ) { progress ->
                            // Ensure UI updates are safe
                            if (isFinishing || isDestroyed) return@downloadApk

                            when {
                                progress in 0..100 -> {
                                    tvVersion.text = "Downloading update... $progress%"
                                }
                                progress > 100 -> {
                                    tvVersion.text = "Installing update..."
                                    pbSplash.isIndeterminate = true
                                    isInstallingApk = true // 🔥 Flag set kiya kyunki install screen open ho rahi hai
                                }
                                progress == -1 -> {
                                    // Handle Failed Download
                                    Toast.makeText(this, "Download failed, continuing...", Toast.LENGTH_SHORT).show()
                                    routeNext()
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            timeoutHandler.removeCallbacks(timeoutRunnable)
            routeNext()
        }
    }

    // 🔥 Naya method: Jab user Android install screen se wapas aaye
    override fun onResume() {
        super.onResume()
        // Agar install command bheja gaya tha, aur app wapas onResume mein aa gayi iska matlab:
        // App update nahi hui (Ya toh user ne cancel kiya, ya purana version hone ki wajah se OS ne reject kar diya)
        if (isInstallingApk) {
            isInstallingApk = false
            Toast.makeText(this, "Update skipped or failed. Starting app.....", Toast.LENGTH_SHORT).show()
            routeNext()
        }
    }

    private fun routeNext() {
        if (isFinishing || isDestroyed || isRouted) return
        isRouted = true // Ensure this runs only once

        val prefs = PreferenceManager(this)

        if (prefs.isRememberMe()) {
            val savedUser = prefs.getUsername().orEmpty().trim()
            val savedPass = prefs.getPassword().orEmpty().trim()

            if (savedUser.isNotEmpty() && savedPass.isNotEmpty()) {
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
                return
            }
        }

        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }
}
