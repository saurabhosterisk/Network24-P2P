package com.network24.player.features.vpn.repository

import com.network24.player.core.api.ApiClient
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.common.models.VpnProvisionResponse
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import retrofit2.Response

/** A ready-to-use tunnel Config plus which vpn_servers row it came from. */
data class ProvisionedTunnel(val config: Config, val serverId: Int?)

/**
 * Talks to Main Server's vpn_api.php to obtain a WireGuard peer
 * assignment for this device, then turns that into a ready-to-use
 * Config. The device's own private key never leaves the device - only
 * the public key is ever sent.
 */
class VpnProvisioningRepository(private val prefs: PreferenceManager) {

    companion object {
        // Main Server itself (not an LB) - vpn_servers/vpn_peers live in
        // its DB and vpn_api.php runs there directly. Uses the same host as
        // login/streams (app.web24.live), not the 185.134.22.150 address
        // that some ISPs throttle.
        private val VPN_API_BASE_URL get() = PreferenceManager.SERVER_URL + "/"

        // Backup route when a customer's ISP blocks Main: network24.biz
        // (Hostinger, different network) forwards the same request to Main.
        // Only used when Main can't be reached at all - the VPN is exactly
        // what such a customer needs, and they can't get it through Main.
        private const val VPN_API_BACKUP_URL = "https://network24.biz/app/"
    }

    /**
     * Calls Main first; if Main can't be reached at all (blocked, timed
     * out), tries the network24.biz backup. A reply from Main - even an
     * error like a wrong password - is final.
     */
    private suspend fun <T> withFallback(
        call: suspend (com.network24.player.core.api.VpnApiService) -> Response<T>
    ): Response<T> {
        return try {
            call(ApiClient.vpnApi(VPN_API_BASE_URL))
        } catch (e: java.io.IOException) {
            call(ApiClient.vpnApi(VPN_API_BACKUP_URL))
        }
    }

    private fun ensureDeviceKeyPair(): Pair<String, String> {
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

    suspend fun provision(): Result<ProvisionedTunnel> =
        provision(prefs.getUsername(), prefs.getPassword())

    /**
     * With explicit credentials: the login screen turns the VPN on before
     * the customer is signed in (for when their ISP blocks Main), using the
     * username/password they just typed.
     */
    suspend fun provision(username: String, password: String): Result<ProvisionedTunnel> {
        val (privateKey, publicKey) = ensureDeviceKeyPair()
        val response = try {
            withFallback { api ->
                api.requestPeer(
                    username = username,
                    password = password,
                    publicKey = publicKey
                )
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return toTunnelResult(response, privateKey)
    }

    /**
     * Always drops whatever server this device is currently assigned to
     * and gets a different one (vpn_api.php's rotate_peer excludes the
     * server it just released) - for a user who wants to try another
     * Secure Relay server because the current one is still buffering.
     * Repeated calls cycle through the available servers rather than
     * building up a permanent exclusion list - each call only ever
     * excludes whichever single server the device is on right now.
     */
    suspend fun rotate(): Result<ProvisionedTunnel> {
        val (privateKey, publicKey) = ensureDeviceKeyPair()
        val response = try {
            withFallback { api ->
                api.rotatePeer(
                    username = prefs.getUsername(),
                    password = prefs.getPassword(),
                    publicKey = publicKey
                )
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return toTunnelResult(response, privateKey)
    }

    // Every failure message the user can see comes from the server
    // (vpn_api.php's "message" field) so wording can be changed without
    // an app update - the one exception is when the server couldn't be
    // reached at all (see SettingsActivity/FullscreenVpnToggle), which
    // by definition has no server response to carry a message.
    private fun toTunnelResult(response: Response<VpnProvisionResponse>, privateKey: String): Result<ProvisionedTunnel> {
        val body = response.body()
        if (!response.isSuccessful || body == null || !body.result) {
            return Result.failure(IllegalStateException(body?.message))
        }
        val assignedIp = body.assignedIp
        val serverPublicKey = body.serverPublicKey
        val endpoint = body.endpoint
        if (assignedIp == null || serverPublicKey == null || endpoint == null) {
            return Result.failure(IllegalStateException(null as String?))
        }

        return try {
            val config = Config.Builder()
                .setInterface(
                    Interface.Builder()
                        .parsePrivateKey(privateKey)
                        .addAddress(InetNetwork.parse("$assignedIp/32"))
                        .parseDnsServers(body.dns ?: "1.1.1.1")
                        .build()
                )
                .addPeer(
                    Peer.Builder()
                        .parsePublicKey(serverPublicKey)
                        .parseEndpoint(endpoint)
                        .parseAllowedIPs(body.allowedIps ?: "0.0.0.0/0")
                        .build()
                )
                .build()
            Result.success(ProvisionedTunnel(config, body.serverId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun release() = release(prefs.getUsername(), prefs.getPassword())

    /** With explicit credentials, for the login screen (nothing saved yet). */
    suspend fun release(username: String, password: String) {
        val publicKey = prefs.getVpnDevicePublicKey() ?: return
        if (username.isBlank() || password.isBlank()) return
        try {
            withFallback { api ->
                api.releasePeer(
                    username = username,
                    password = password,
                    publicKey = publicKey
                )
            }
        } catch (e: Exception) {
            // Best-effort - the server prunes stale peers independently,
            // and the local tunnel is already brought down regardless.
        }
    }
}
