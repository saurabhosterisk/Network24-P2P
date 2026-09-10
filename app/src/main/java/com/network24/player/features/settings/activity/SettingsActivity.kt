package com.network24.player.features.settings.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.network24.player.BuildConfig
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.cache.memory.MemoryCache
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.vpn.TunnelManager
import com.network24.player.features.live.activity.ManageCategoriesActivity
import com.network24.player.features.login.activity.LoginActivity
import com.network24.player.features.updater.manager.UpdateManager
import com.network24.player.features.updater.models.UpdateResponse
import com.network24.player.features.vpn.repository.VpnProvisioningRepository
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    companion object {
        // The one Secure-Relay failure message this app ever makes up
        // itself - used only when there's no server response to relay a
        // message from (network failure, or the local tunnel failing to
        // start). Every other failure text comes from vpn_api.php's
        // "message" field, so wording changes don't need an app update.
        private const val VPN_UNREACHABLE_MESSAGE = "Couldn't Connect to VPN Servers."
    }

    private lateinit var prefs: PreferenceManager
    private lateinit var vpnRepository: VpnProvisioningRepository
    private lateinit var vpnConsentLauncher: ActivityResultLauncher<Intent>
    private var vpnErrorMessage: String? = null

    // True from the moment the user flips Secure Relay on until the
    // attempt resolves (success or failure). The system VPN consent
    // dialog pauses/resumes this Activity, and onResume()'s bindVpnToggle()
    // would otherwise forcibly resync the switch from TunnelManager's
    // still-DOWN state mid-attempt, snapping it back to "Off" (and
    // overwriting the "Connecting..." text) before the async connect
    // has had a chance to finish.
    private var isConnecting = false

    // Same "install unknown apps" permission dance as SplashActivity - true
    // only while waiting to come back from that settings screen, so
    // onResume() can retry the actual install instead of discarding an
    // already-finished download.
    private var awaitingUpdatePermission = false
    private var updateProgressDialog: ProgressDialogHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager(this)
        vpnRepository = VpnProvisioningRepository(prefs)
        vpnConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnTunnel()
            } else {
                isConnecting = false
                findViewById<SwitchMaterial>(R.id.vpnTunnelSwitch).isChecked = false
                Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_SHORT).show()
            }
        }

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
            "Network24  •  Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
    }

    override fun onResume() {
        super.onResume()
        // Secure Relay turns itself off whenever the app leaves the
        // foreground (Network24App), so the switch needs to reflect that
        // on return - e.g. this Activity was merely paused (not
        // recreated) while the user was away.
        bindVpnToggle()

        if (awaitingUpdatePermission) {
            awaitingUpdatePermission = false
            if (UpdateManager.retryInstallAfterPermission(this)) {
                // Real installer just launched - leave the progress dialog
                // up until the user returns from THAT screen.
                updateProgressDialog?.setMessage("Installing update...")
            } else {
                updateProgressDialog?.dismiss()
                updateProgressDialog = null
                Toast.makeText(this, "Update skipped or failed.", Toast.LENGTH_SHORT).show()
            }
        }
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

        // The switch itself is no longer individually focusable/clickable
        // (see activity_settings.xml) so it renders the same D-pad focus
        // highlight as every other settings row - this just flips it,
        // which re-triggers the existing OnCheckedChangeListener wired in
        // bindVpnToggle() that does the actual connect/disconnect work.
        findViewById<android.view.View>(R.id.vpnTunnel).setOnClickListener {
            val switch = findViewById<SwitchMaterial>(R.id.vpnTunnelSwitch)
            switch.isChecked = !switch.isChecked
        }

        findViewById<android.view.View>(R.id.aboutDeviceInfo).setOnClickListener {
            showAboutDeviceInfo()
        }

        findViewById<android.view.View>(R.id.checkForUpdates).setOnClickListener {
            checkForUpdates()
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

        showChoiceDialog(
            title = "Auto Reconnect",
            items = labels.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            prefs.setAutoReconnectMode(modes[which])
            updateAutoReconnectSummary()
        }
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
        showInfoDialog(
            title = "About / Device Information",
            message = buildDeviceInfo()
        )
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

    private fun bindVpnToggle() {
        val switch = findViewById<SwitchMaterial>(R.id.vpnTunnelSwitch)
        switch.setOnCheckedChangeListener(null)
        // Source of truth is the real backend tunnel state, not the
        // stored flag - a force-kill (or the OS reclaiming the process)
        // skips Network24App's normal teardown-on-background path, which
        // would otherwise leave the switch showing "Connected" for a
        // tunnel that isn't actually running any more. Skipped while a
        // connect attempt is in flight (isConnecting) - the system VPN
        // consent dialog resumes this Activity before the tunnel is up,
        // and resyncing here would wrongly snap the switch back to Off.
        if (!isConnecting) {
            val actuallyConnected = TunnelManager.currentState(this) == Tunnel.State.UP
            if (actuallyConnected != prefs.isVpnEnabled()) {
                prefs.setVpnEnabled(actuallyConnected)
            }
            switch.isChecked = actuallyConnected
            updateVpnSummary()
        }
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isConnecting = true
                val consentIntent = GoBackend.VpnService.prepare(this)
                if (consentIntent != null) {
                    vpnConsentLauncher.launch(consentIntent)
                } else {
                    startVpnTunnel()
                }
            } else {
                stopVpnTunnel()
            }
        }
    }

    private fun startVpnTunnel() {
        isConnecting = true
        vpnErrorMessage = null
        findViewById<TextView>(R.id.vpnTunnelSummary).text = "Connecting..."
        lifecycleScope.launch {
            val result = vpnRepository.provision()
            result.onSuccess { config ->
                try {
                    TunnelManager.bringUp(this@SettingsActivity, config)
                    prefs.setVpnEnabled(true)
                    findViewById<SwitchMaterial>(R.id.vpnTunnelSwitch).isChecked = true
                    updateVpnSummary()
                } catch (e: Exception) {
                    handleVpnStartFailure(VPN_UNREACHABLE_MESSAGE)
                } finally {
                    isConnecting = false
                }
            }.onFailure {
                // it.message is wwwdir/vpn_api.php's own "message" field
                // (see VpnProvisioningRepository) - this is the only wording
                // the client ever makes up itself, used only when there was
                // no server response to carry a message (network failure,
                // or a local-only failure like the tunnel not starting).
                handleVpnStartFailure(it.message ?: VPN_UNREACHABLE_MESSAGE)
                isConnecting = false
            }
        }
    }

    private fun handleVpnStartFailure(message: String) {
        prefs.setVpnEnabled(false)
        findViewById<SwitchMaterial>(R.id.vpnTunnelSwitch).isChecked = false
        vpnErrorMessage = message
        updateVpnSummary()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun stopVpnTunnel() {
        vpnErrorMessage = null
        lifecycleScope.launch {
            try {
                TunnelManager.bringDown(this@SettingsActivity)
            } catch (e: Exception) {
                // Already down or never came up - state below is what matters.
            }
            vpnRepository.release()
            prefs.setVpnEnabled(false)
            updateVpnSummary()
        }
    }

    private fun updateVpnSummary() {
        val summaryView = findViewById<TextView>(R.id.vpnTunnelSummary)
        val error = vpnErrorMessage
        when {
            prefs.isVpnEnabled() -> {
                summaryView.setTextColor(getColor(R.color.text_hint))
                summaryView.text = "Connected — traffic routed through Secure Relay"
            }
            error != null -> {
                summaryView.setTextColor(getColor(R.color.error))
                summaryView.text = error
            }
            else -> {
                summaryView.setTextColor(getColor(R.color.text_hint))
                summaryView.text = "Off"
            }
        }
    }

    private fun checkForUpdates() {
        val summary = findViewById<TextView>(R.id.checkForUpdatesSummary)
        summary.text = "Checking..."
        UpdateManager.checkForUpdate(
            onNoUpdate = {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    summary.text = "Check if a newer app version is available"
                    Toast.makeText(this, "You're on the latest version", Toast.LENGTH_SHORT).show()
                }
            },
            onUpdateAvailable = { update ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    summary.text = "Check if a newer app version is available"
                    showConfirmDialog(
                        title = "Update Available",
                        message = "A newer version (build ${update.versionCode}) is available. Download and install it now?",
                        positiveText = "Update",
                        negativeText = "Later",
                        onPositive = { startUpdateDownload(update) }
                    )
                }
            }
        )
    }

    private fun startUpdateDownload(update: UpdateResponse) {
        val progressDialog = showProgressDialog("Downloading Update", "Starting...")
        updateProgressDialog = progressDialog

        UpdateManager.downloadApk(
            this,
            "${update.apk}?t=${System.currentTimeMillis()}"
        ) { progress ->
            if (isFinishing || isDestroyed) return@downloadApk
            when {
                progress in 0..100 -> progressDialog.setMessage("Downloading update... $progress%")
                progress == 102 -> {
                    // Only the "allow installs" permission screen opened -
                    // onResume() retries the real install once the user
                    // comes back, so keep the dialog up instead of
                    // dismissing it here.
                    progressDialog.setMessage("Waiting for install permission...")
                    awaitingUpdatePermission = true
                }
                progress > 100 -> {
                    progressDialog.setMessage("Installing update...")
                    progressDialog.dismiss()
                    updateProgressDialog = null
                }
                progress == -1 -> {
                    progressDialog.dismiss()
                    updateProgressDialog = null
                    Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLogoutConfirmation() {
        showConfirmDialog(
            title = "Log out?",
            message = "Are you sure you want to logout?",
            positiveText = "Logout",
            onPositive = {
                prefs.clear()
                startActivity(Intent(this, LoginActivity::class.java))
                finishAffinity()
            }
        )
    }

    private fun showExitConfirmation() {
        showConfirmDialog(
            title = "Exit Network24?",
            message = "Your login and session will be kept.",
            positiveText = "Exit",
            onPositive = {
                finishAffinity()
                finishAndRemoveTask()
            }
        )
    }
}
