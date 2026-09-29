package com.network24.player.features.settings.activity

import android.content.Intent
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
import com.network24.player.core.sync.AutoRefreshWorker
import com.network24.player.core.vpn.TunnelManager
import com.network24.player.features.live.activity.ManageCategoriesActivity
import com.network24.player.features.login.activity.LoginActivity
import com.network24.player.features.login.repository.LoginRepository
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
    private var accountRefreshRunning = false

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
        findViewById<android.widget.ImageButton>(R.id.btnRefreshAccount)
            .setOnClickListener { refreshAccount() }
        findViewById<TextView>(R.id.accountUpdated).text = "Tap refresh for the latest account details"
        updateAutoReconnectSummary()
        updateAutoRefreshSummary()

        findViewById<android.widget.TextView>(R.id.appVersion).text =
            "Network24  •  Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
    }

    override fun onResume() {
        super.onResume()
        updateAutoRefreshSummary()
        updateAutoReconnectSummary()
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
        findViewById<TextView>(R.id.accountName).text = username
        findViewById<TextView>(R.id.accountAvatar).text =
            username.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "N"
        findViewById<TextView>(R.id.accountPlan).text =
            if (prefs.isTrial()) "Trial account" else "Premium account"

        // Status pill: green when active, red when expired/banned/disabled,
        // amber for anything else the server reports.
        val status = prefs.getStatus().ifBlank { "Unknown" }
        val statusColor = when (status.lowercase()) {
            "active" -> getColor(R.color.success)
            "expired", "banned", "disabled" -> getColor(R.color.error)
            else -> getColor(R.color.warning)
        }
        findViewById<TextView>(R.id.accountStatusChip).apply {
            text = "● ${status.replaceFirstChar { it.uppercase() }}"
            setTextColor(statusColor)
            background.mutate().setTint(
                android.graphics.Color.argb(
                    0x33,
                    android.graphics.Color.red(statusColor),
                    android.graphics.Color.green(statusColor),
                    android.graphics.Color.blue(statusColor)
                )
            )
        }

        // Expiry tile. exp_date 0/null means the line has no end date.
        val expiry = prefs.getExpiry()
        val expiryValue = findViewById<TextView>(R.id.expiryValue)
        val expirySub = findViewById<TextView>(R.id.expirySub)
        if (expiry > 0L) {
            val expiryMs = expiry * 1000L
            expiryValue.text = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(expiryMs))
            val daysLeft = java.util.concurrent.TimeUnit.MILLISECONDS
                .toDays(expiryMs - System.currentTimeMillis())
            expirySub.text = when {
                daysLeft < 0 -> "Expired"
                daysLeft == 0L -> "Expires today"
                daysLeft == 1L -> "1 day left"
                else -> "$daysLeft days left"
            }
            expirySub.setTextColor(
                getColor(if (daysLeft <= 7) R.color.warning else R.color.text_secondary)
            )
        } else {
            expiryValue.text = "No expiry"
            expirySub.text = "Ongoing"
            expirySub.setTextColor(getColor(R.color.text_secondary))
        }

        // Connections tile with a usage bar.
        val active = prefs.getActiveConnections()
        val max = prefs.getMaxConnections()
        findViewById<TextView>(R.id.connectionsValue).text = if (max > 0) "$active / $max" else "$active"
        findViewById<TextView>(R.id.connectionsSub).text = "in use now"
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.connectionsBar)
            .progress = if (max > 0) ((active * 100) / max).coerceIn(if (active > 0) 2 else 0, 100) else 0

        bindRelayTile()
    }

    private fun bindRelayTile() {
        val connected = TunnelManager.currentState(this) == Tunnel.State.UP
        findViewById<TextView>(R.id.relayValue).apply {
            text = if (connected) "Connected" else "Off"
            setTextColor(getColor(if (connected) R.color.success else R.color.text_primary))
        }
        findViewById<TextView>(R.id.relaySub).text =
            if (prefs.hasPersistentVpnAccess()) "Always available" else "Per session"
    }

    /** Re-reads the account from the server (same call the dashboard uses). */
    private fun refreshAccount() {
        if (accountRefreshRunning) return
        val refreshButton = findViewById<android.widget.ImageButton>(R.id.btnRefreshAccount)
        val updated = findViewById<TextView>(R.id.accountUpdated)
        accountRefreshRunning = true
        updated.text = "Updating..."
        refreshButton.animate().rotationBy(360f).setDuration(600).start()
        lifecycleScope.launch {
            try {
                val response = LoginRepository().login(
                    server = prefs.getServer(),
                    username = prefs.getUsername(),
                    password = prefs.getPassword()
                )
                val userInfo = response.body()?.user_info
                if (response.isSuccessful && userInfo?.auth == 1) {
                    prefs.saveUserInfo(
                        username = userInfo.username ?: prefs.getUsername(),
                        status = userInfo.status ?: prefs.getStatus(),
                        expiry = userInfo.exp_date?.toLongOrNull() ?: prefs.getExpiry(),
                        activeConnections = userInfo.active_cons?.toIntOrNull() ?: prefs.getActiveConnections(),
                        maxConnections = userInfo.max_connections?.toIntOrNull() ?: prefs.getMaxConnections(),
                        isTrial = userInfo.is_trial == "1",
                        vpnPersistentAccess = userInfo.vpn_access?.let { it == "1" } ?: prefs.hasPersistentVpnAccess()
                    )
                    bindAccount()
                    updated.text = "Updated just now"
                } else {
                    updated.text = "Couldn't update right now - showing last known details"
                }
            } catch (_: Exception) {
                // Keep the last known values when the server is unreachable.
                updated.text = "Couldn't update right now - showing last known details"
            } finally {
                accountRefreshRunning = false
            }
        }
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

        findViewById<android.view.View>(R.id.autoRefresh).setOnClickListener {
            startActivity(Intent(this, AutoRefreshActivity::class.java))
        }
        findViewById<android.view.View>(R.id.autoReconnect).setOnClickListener {
            startActivity(Intent(this, AutoReconnectActivity::class.java))
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

        val aiSwitch = findViewById<SwitchMaterial>(R.id.aiAssistantSwitch)
        aiSwitch.isChecked = prefs.isAiAssistantEnabled()
        findViewById<android.view.View>(R.id.aiAssistant).setOnClickListener {
            aiSwitch.isChecked = !aiSwitch.isChecked
            prefs.setAiAssistantEnabled(aiSwitch.isChecked)
        }

        findViewById<android.view.View>(R.id.aboutDeviceInfo).setOnClickListener {
            startActivity(Intent(this, AboutDeviceActivity::class.java))
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

    private fun updateAutoRefreshSummary() {
        val hours = prefs.getAutoRefreshHours()
        val interval = when (hours) {
            0 -> "Off"
            24 -> "Once a day"
            else -> "Every $hours hours"
        }
        val last = prefs.getLastDataRefreshMs()
        val lastText = when {
            last <= 0L -> ""
            System.currentTimeMillis() - last < 60_000L -> " • last refreshed just now"
            else -> " • last refreshed " + android.text.format.DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
            ).toString().lowercase()
        }
        findViewById<TextView>(R.id.autoRefreshSummary).text = interval + lastText
    }

    private fun updateAutoReconnectSummary() {
        val summary = when (prefs.getAutoReconnectMode()) {
            PreferenceManager.AutoReconnectMode.OFF ->
                "Off — failed streams will not retry automatically"

            PreferenceManager.AutoReconnectMode.STANDARD ->
                "Standard — up to 5 retries (about 1.5 minutes)"

            PreferenceManager.AutoReconnectMode.FAST ->
                "Fast — up to 3 quick retries (about 45 seconds)"
        }

        findViewById<android.widget.TextView>(R.id.autoReconnectSummary).text = summary
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
            result.onSuccess { tunnel ->
                try {
                    TunnelManager.bringUp(this@SettingsActivity, tunnel.config)
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
        bindRelayTile()
        val summaryView = findViewById<TextView>(R.id.vpnTunnelSummary)
        val error = vpnErrorMessage
        when {
            prefs.isVpnEnabled() -> {
                summaryView.setTextColor(getColor(R.color.text_hint))
                summaryView.text = "Connected — traffic routed through the VPN"
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
