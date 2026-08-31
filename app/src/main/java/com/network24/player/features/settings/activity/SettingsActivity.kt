package com.network24.player.features.settings.activity

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import com.network24.player.BuildConfig
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.cache.memory.MemoryCache
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.live.activity.ManageCategoriesActivity
import com.network24.player.features.login.activity.LoginActivity

class SettingsActivity : BaseActivity() {

    private lateinit var prefs: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager(this)

        val contentRoot = layoutInflater.inflate(
            R.layout.activity_settings,
            null,
            false
        ) as ViewGroup
        setContentView(
            setupGlobalRightDrawer(
                contentRoot,
                contentRoot.findViewById(R.id.btnMore)
            )
        )

        findViewById<android.view.View>(R.id.settingsBack).setOnClickListener {
            finish()
        }

        bindAccount()
        bindActions()
        updateAutoReconnectSummary()

        findViewById<android.widget.TextView>(R.id.appVersion).text =
            "Network24  •  Version ${BuildConfig.VERSION_NAME}"
    }

    private fun bindAccount() {
        val username = prefs.getUsername().ifBlank { "Network24 Account" }
        findViewById<android.widget.TextView>(R.id.accountName).text = username

        val expiry = prefs.getExpiry()
        val expiryText = if (expiry > 0L) {
            java.text.SimpleDateFormat(
                "dd MMM yyyy",
                java.util.Locale.getDefault()
            ).format(java.util.Date(expiry * 1000L))
        } else {
            "Not available"
        }

        val status = prefs.getStatus().ifBlank { "Unknown" }
        val connections =
            "${prefs.getActiveConnections()} / ${prefs.getMaxConnections()}"

        findViewById<android.widget.TextView>(R.id.accountDetails).text =
            "Status: $status\nExpiry: $expiryText\nConnections: $connections"
    }

    private fun bindActions() {
        findViewById<android.view.View>(R.id.clearMemory).setOnClickListener {
            MemoryCache.clearAll()
            Toast.makeText(
                this,
                "Temporary memory cleared",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<android.view.View>(R.id.forceRefresh).setOnClickListener {
            MemoryCache.clearAll()
            prefs.setLastSyncTime(0L)
            Toast.makeText(
                this,
                "Cache cleared. Fresh data will load on the next refresh.",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<android.view.View>(R.id.manageCategories).setOnClickListener {
            startActivity(Intent(this, ManageCategoriesActivity::class.java))
        }

        findViewById<android.view.View>(R.id.autoReconnect).setOnClickListener {
            showAutoReconnectOptions()
        }

        findViewById<android.view.View>(R.id.aboutDeviceInfo).setOnClickListener {
            showAboutDeviceInfo()
        }

        findViewById<android.view.View>(R.id.logout).setOnClickListener {
            showLogoutConfirmation()
        }

        findViewById<android.view.View>(R.id.exitApp).setOnClickListener {
            showExitConfirmation()
        }
    }

    private fun showAutoReconnectOptions() {
        val modes = PreferenceManager.AutoReconnectMode.entries.toTypedArray()
        val labels = arrayOf(
            "Off — do not retry failed streams",
            "Standard — retry over 30 seconds",
            "Fast — retry over 15 seconds"
        )
        val selectedIndex = modes.indexOf(prefs.getAutoReconnectMode())

        AlertDialog.Builder(this)
            .setTitle("Auto Reconnect")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                prefs.setAutoReconnectMode(modes[which])
                updateAutoReconnectSummary()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAutoReconnectSummary() {
        val summary = when (prefs.getAutoReconnectMode()) {
            PreferenceManager.AutoReconnectMode.OFF ->
                "Off — failed streams will not retry automatically"

            PreferenceManager.AutoReconnectMode.STANDARD ->
                "Standard — retry over 30 seconds"

            PreferenceManager.AutoReconnectMode.FAST ->
                "Fast — retry over 15 seconds"
        }

        findViewById<android.widget.TextView>(R.id.autoReconnectSummary).text = summary
    }

    private fun showAboutDeviceInfo() {
        AlertDialog.Builder(this)
            .setTitle("About / Device Information")
            .setMessage(buildDeviceInfo())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildDeviceInfo(): String {
        val isTvDevice = packageManager.hasSystemFeature(
            PackageManager.FEATURE_LEANBACK
        )
        val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" }
        val model = Build.MODEL.orEmpty().ifBlank { "Unknown" }
        val deviceName = Build.DEVICE.orEmpty()
            .ifBlank { Build.PRODUCT.orEmpty() }
            .ifBlank { "Unknown" }
        val isAmazon = manufacturer.equals("Amazon", ignoreCase = true)
        val isFireTvModel = model.startsWith("AFT", ignoreCase = true) ||
            deviceName.startsWith("AFT", ignoreCase = true)

        val platform = when {
            isAmazon && (isTvDevice || isFireTvModel) -> "Fire TV / Fire OS"
            isTvDevice -> "Android TV"
            else -> "Android Mobile"
        }

        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.orEmpty().ifBlank { "Not available" }
        } else {
            "Not available"
        }

        return """
            APPLICATION
            App name: ${getString(R.string.app_name)}
            App version: ${BuildConfig.VERSION_NAME}
            Build number: ${BuildConfig.VERSION_CODE}

            DEVICE
            Platform: $platform
            Manufacturer: $manufacturer
            Device model: $model
            Device name: $deviceName

            OPERATING SYSTEM
            Android version: ${Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" }}
            Android SDK version: ${Build.VERSION.SDK_INT}
            Security patch level: $securityPatch
        """.trimIndent()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("Are you sure you want to logout?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Logout") { _, _ ->
                prefs.clear()
                startActivity(Intent(this, LoginActivity::class.java))
                finishAffinity()
            }
            .show()
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Exit Network24?")
            .setMessage("Your login and session will be kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit") { _, _ ->
                finishAffinity()
                finishAndRemoveTask()
            }
            .show()
    }
}
