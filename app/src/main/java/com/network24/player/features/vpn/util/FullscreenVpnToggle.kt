package com.network24.player.features.vpn.util

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.vpn.TunnelManager
import com.network24.player.features.vpn.repository.ProvisionedTunnel
import com.network24.player.features.vpn.repository.VpnProvisioningRepository
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

/**
 * Lets a fullscreen live player toggle Secure Relay, and switch to a
 * different Secure Relay server, directly - from the same
 * connect/disconnect flow as the Settings screen's Secure Relay row
 * (VpnProvisioningRepository + TunnelManager), without duplicating that
 * flow in every screen that hosts a fullscreen player (Live TV, EPG,
 * Favorites all have their own copy of the fullscreen controls).
 *
 * Must be constructed and [register]ed from onCreate, before the
 * Activity reaches STARTED - registerForActivityResult requires that.
 */
class FullscreenVpnToggle(
    private val activity: AppCompatActivity,
    private val toggleButton: ImageButton,
    private val rotateButton: ImageButton,
    // Called after every tap on either button, success or failure alike -
    // lets the host Activity refresh its fullscreen-overlay auto-hide
    // timer the same way every other fullscreen control button does.
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

    // True from the moment either button is tapped until the attempt
    // resolves (success or failure) - shared by both buttons so a tap on
    // one while the other is mid-flight is a no-op instead of a race.
    // The system VPN consent screen pauses/resumes this Activity, and
    // refresh() would otherwise forcibly resync the buttons from
    // TunnelManager's still-DOWN state mid-attempt, snapping back to
    // "off" before the async connect has had a chance to finish - same
    // reasoning as SettingsActivity's isConnecting.
    private var isConnecting = false

    // True only while a consent-screen round trip belongs to a rotate
    // (not a plain toggle-on) - tells the consent-result callback which
    // of startVpnTunnel()/rotateVpnServer() to resume into.
    private var pendingRotate = false

    private lateinit var consentLauncher: ActivityResultLauncher<Intent>

    fun register() {
        consentLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                if (pendingRotate) rotateVpnServer() else startVpnTunnel()
            } else {
                pendingRotate = false
                isConnecting = false
                refresh()
                toast("VPN permission was not granted")
            }
        }
        toggleButton.setOnClickListener { toggle() }
        rotateButton.setOnClickListener { requestRotate() }
        refresh()
    }

    /** Resyncs the toggle button's visual state with the real tunnel state - call from onResume(). */
    fun refresh() {
        if (isConnecting) return
        val actuallyConnected = TunnelManager.currentState(activity) == Tunnel.State.UP
        if (actuallyConnected != prefs.isVpnEnabled()) {
            prefs.setVpnEnabled(actuallyConnected)
        }
        updateVisualState(actuallyConnected)
    }

    private fun toggle() {
        onInteraction()
        if (isConnecting) return
        if (prefs.isVpnEnabled()) {
            stopVpnTunnel()
        } else {
            isConnecting = true
            pendingRotate = false
            requestConsentThen()
        }
    }

    // Rotating while Secure Relay is already off is just a normal
    // connect (there's nothing to exclude yet) - vpn_api.php's
    // request_peer already does that.
    private fun requestRotate() {
        onInteraction()
        if (isConnecting) return
        isConnecting = true
        pendingRotate = true
        requestConsentThen()
    }

    private fun requestConsentThen() {
        val consentIntent = GoBackend.VpnService.prepare(activity)
        if (consentIntent != null) {
            consentLauncher.launch(consentIntent)
        } else if (pendingRotate) {
            rotateVpnServer()
        } else {
            startVpnTunnel()
        }
    }

    private fun startVpnTunnel() {
        isConnecting = true
        toast("Connecting to Secure Relay…")
        activity.lifecycleScope.launch {
            val result = vpnRepository.provision()
            result.onSuccess { tunnel ->
                bringUpAndFinish(tunnel, "Secure Relay connected")
            }.onFailure {
                handleFailure(it.message ?: VPN_UNREACHABLE_MESSAGE)
                isConnecting = false
                refresh()
            }
        }
    }

    private fun rotateVpnServer() {
        isConnecting = true
        pendingRotate = false
        toast("Switching Secure Relay server…")
        activity.lifecycleScope.launch {
            // Local teardown first - vpn_api.php's rotate_peer releases
            // the old peer server-side regardless, but the local tunnel
            // still needs to stop pointing at a server that's about to
            // have this device's peer removed from it.
            try {
                TunnelManager.bringDown(activity)
            } catch (e: Exception) {
                // Already down or never came up.
            }
            val result = vpnRepository.rotate()
            result.onSuccess { tunnel ->
                bringUpAndFinish(tunnel, "Connected to a different Secure Relay server")
            }.onFailure {
                handleFailure(it.message ?: VPN_UNREACHABLE_MESSAGE)
                isConnecting = false
                refresh()
            }
        }
    }

    private suspend fun bringUpAndFinish(tunnel: ProvisionedTunnel, successMessage: String) {
        try {
            TunnelManager.bringUp(activity, tunnel.config)
            prefs.setVpnEnabled(true)
            toast(
                tunnel.serverId?.let { "$successMessage (server #$it)" } ?: successMessage
            )
        } catch (e: Exception) {
            handleFailure(VPN_UNREACHABLE_MESSAGE)
        } finally {
            isConnecting = false
            refresh()
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

    // The rotate button only makes sense once Secure Relay is actually
    // connected - rotating "to a different server" when there's no
    // current server to be different from is just a plain connect,
    // which the toggle button already does.
    private fun updateVisualState(connected: Boolean) {
        toggleButton.setColorFilter(if (connected) Color.parseColor("#FFC107") else Color.WHITE)
        rotateButton.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
