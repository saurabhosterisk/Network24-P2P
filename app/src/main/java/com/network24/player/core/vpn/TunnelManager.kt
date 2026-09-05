package com.network24.player.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.vpn.repository.VpnProvisioningRepository
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps the WireGuard GoBackend so the app's own network calls (login, EPG,
 * live-stream playback) can transparently ride a per-app tunnel to the relay
 * instead of exposing the real backend/LB-node IPs. Every public entry point
 * swallows its own failures - like LoginActivity.loginWithFallback(), the app
 * must keep working over the normal direct/relay HTTP path if the tunnel
 * can't be provisioned or started for any reason.
 */
class TunnelManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend: GoBackend by lazy { GoBackend(appContext) }
    private val tunnel = N24Tunnel()

    private class N24Tunnel : Tunnel {
        override fun getName(): String = "network24"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    /**
     * Reflects the last setState() outcome persisted by whichever
     * TunnelManager instance actually changed it - GoBackend.getRunningTunnelNames()
     * can't be queried reliably from a freshly constructed instance since its
     * service binding is asynchronous.
     */
    fun isActive(prefs: PreferenceManager): Boolean = prefs.isVpnTunnelActive()

    /** Null if consent was already granted in a previous session. */
    fun requestConsentIntent(): Intent? = VpnService.prepare(appContext)

    private fun getOrCreateDeviceKeyPair(prefs: PreferenceManager): Pair<String, String> {
        val existingPrivate = prefs.getVpnDevicePrivateKey()
        val existingPublic = prefs.getVpnDevicePublicKey()
        if (existingPrivate != null && existingPublic != null) {
            return existingPrivate to existingPublic
        }

        val keyPair = KeyPair()
        val privateKey = keyPair.privateKey.toBase64()
        val publicKey = keyPair.publicKey.toBase64()
        prefs.saveVpnDeviceKeyPair(privateKey, publicKey)
        return privateKey to publicKey
    }

    suspend fun ensureProvisioned(
        prefs: PreferenceManager,
        repository: VpnProvisioningRepository,
        server: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val (_, publicKey) = getOrCreateDeviceKeyPair(prefs)

            val response = repository.provision(server, username, password, publicKey)
            if (!response.isSuccessful) return@withContext false

            val body = response.body() ?: return@withContext false
            val assignedIp = body.assigned_ip ?: return@withContext false
            val serverPublicKey = body.server_public_key ?: return@withContext false
            val endpoint = body.endpoint ?: return@withContext false
            val allowedIps = body.allowed_ips ?: return@withContext false

            prefs.saveVpnProvisioning(assignedIp, serverPublicKey, endpoint, allowedIps)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildConfig(state: PreferenceManager.VpnProvisioningState): Config {
        val wgInterface = Interface.Builder()
            .parsePrivateKey(state.devicePrivateKey)
            .parseAddresses(state.assignedIp)
            .includeApplication(appContext.packageName)
            .build()

        val peer = Peer.Builder()
            .parsePublicKey(state.serverPublicKey)
            .parseEndpoint(state.endpoint)
            .parseAllowedIPs(state.allowedIps.joinToString(","))
            .parsePersistentKeepalive("25")
            .build()

        return Config.Builder()
            .setInterface(wgInterface)
            .addPeer(peer)
            .build()
    }

    suspend fun start(prefs: PreferenceManager): Boolean = withContext(Dispatchers.IO) {
        val started = try {
            val provisioning = prefs.getVpnProvisioning() ?: return@withContext false
            val config = buildConfig(provisioning)
            backend.setState(tunnel, Tunnel.State.UP, config)
            true
        } catch (_: Exception) {
            false
        }
        prefs.setVpnTunnelActive(started)
        started
    }

    suspend fun stop(prefs: PreferenceManager) = withContext(Dispatchers.IO) {
        try {
            val provisioning = prefs.getVpnProvisioning()
            val config = provisioning?.let { buildConfig(it) }
            backend.setState(tunnel, Tunnel.State.DOWN, config)
        } catch (_: Exception) {
            // Ignore: tearing the tunnel down must never crash the caller.
        } finally {
            prefs.setVpnTunnelActive(false)
        }
    }
}
