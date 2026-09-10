package com.network24.player.features.vpn.util

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.vpn.TunnelManager
import com.network24.player.features.vpn.repository.VpnProvisioningRepository
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

/**
 * Lets a fullscreen live player toggle Secure Relay directly, from the
 * same connect/disconnect flow as the Settings screen's Secure Relay
 * row (VpnProvisioningRepository + TunnelManager), without duplicating
 * that flow in every screen that hosts a fullscreen player (Live TV,
 * EPG, Favorites all have their own copy of the fullscreen controls).
 *
 * Must be constructed and [register]ed from onCreate, before the
 * Activity reaches STARTED - registerForActivityResult requires that.
 */
class FullscreenVpnToggle(
    private val activity: AppCompatActivity,
    private val button: ImageButton,
    // Called after every tap, success or failure alike - lets the host
    // Activity refresh its fullscreen-overlay auto-hide timer the same
    // way every other fullscreen control button does.
    private val onInteraction: () -> Unit = {}
) {
    companion object {
        // Same one client-side message SettingsActivity falls back to when
        // there's no server response to relay a message from (network
        // failure, or the local tunnel failing to start).
        private const val VPN_UNREACHABLE_MESSAGE = "Couldn't Connect to VPN Servers."
    }

    private val prefs = PreferenceManager(activity)
    private val vpnRepository = VpnProvisioningRepository(prefs)

    // True from the moment the user taps the button on until the attempt
    // resolves (success or failure). The system VPN consent screen
    // pauses/resumes this Activity, and refresh() would otherwise
    // forcibly resync the button from TunnelManager's still-DOWN state
    // mid-attempt, snapping it back to "off" before the async connect
    // has had a chance to finish - same reasoning as SettingsActivity's
    // isConnecting.
    private var isConnecting = false
    private lateinit var consentLauncher: ActivityResultLauncher<Intent>

    fun register() {
        consentLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startVpnTunnel()
            } else {
                isConnecting = false
                refresh()
                toast("VPN permission was not granted")
            }
        }
        button.setOnClickListener { toggle() }
        refresh()
    }

    /** Resyncs the button's visual state with the real tunnel state - call from onResume(). */
    fun refresh() {
        if (isConnecting) return
        val actuallyConnected = TunnelManager.currentState(activity) == Tunnel.State.UP
        if (actuallyConnected != prefs.isVpnEnabled()) {
            prefs.setVpnEnabled(actuallyConnected)
        }
        applyTint(actuallyConnected)
    }

    private fun toggle() {
        onInteraction()
        if (isConnecting) return
        if (prefs.isVpnEnabled()) {
            stopVpnTunnel()
        } else {
            isConnecting = true
            val consentIntent = GoBackend.VpnService.prepare(activity)
            if (consentIntent != null) {
                consentLauncher.launch(consentIntent)
            } else {
                startVpnTunnel()
            }
        }
    }

    private fun startVpnTunnel() {
        isConnecting = true
        toast("Connecting to Secure Relay…")
        activity.lifecycleScope.launch {
            val result = vpnRepository.provision()
            result.onSuccess { config ->
                try {
                    TunnelManager.bringUp(activity, config)
                    prefs.setVpnEnabled(true)
                    toast("Secure Relay connected")
                } catch (e: Exception) {
                    handleFailure(VPN_UNREACHABLE_MESSAGE)
                } finally {
                    isConnecting = false
                    refresh()
                }
            }.onFailure {
                // it.message is wwwdir/vpn_api.php's own "message" field
                // (see VpnProvisioningRepository) - the one exception is
                // when there was no server response to carry a message at
                // all (network failure), which falls back to
                // VPN_UNREACHABLE_MESSAGE above.
                handleFailure(it.message ?: VPN_UNREACHABLE_MESSAGE)
                isConnecting = false
                refresh()
            }
        }
    }

    private fun handleFailure(message: String) {
        prefs.setVpnEnabled(false)
        toast(message)
    }

    private fun stopVpnTunnel() {
        activity.lifecycleScope.launch {
            try {
                TunnelManager.bringDown(activity)
            } catch (e: Exception) {
                // Already down or never came up - state below is what matters.
            }
            vpnRepository.release()
            prefs.setVpnEnabled(false)
            refresh()
            toast("Secure Relay disconnected")
        }
    }

    private fun applyTint(connected: Boolean) {
        button.setColorFilter(if (connected) Color.parseColor("#FFC107") else Color.WHITE)
    }

    private fun toast(message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
