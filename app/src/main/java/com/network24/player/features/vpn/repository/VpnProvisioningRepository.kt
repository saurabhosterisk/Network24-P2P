package com.network24.player.features.vpn.repository

import com.network24.player.core.api.ApiClient
import com.network24.player.core.preferences.PreferenceManager
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair

/**
 * Talks to Main Server's vpn_api.php to obtain a WireGuard peer
 * assignment for this device, then turns that into a ready-to-use
 * Config. The device's own private key never leaves the device - only
 * the public key is ever sent.
 */
class VpnProvisioningRepository(private val prefs: PreferenceManager) {

    companion object {
        // Main Server itself (not an LB) - vpn_servers/vpn_peers live in
        // its DB and vpn_api.php runs there directly.
        private const val VPN_API_BASE_URL = "http://185.134.22.150:8080/"
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

    suspend fun provision(): Result<Config> {
        val username = prefs.getUsername()
        val password = prefs.getPassword()
        val (privateKey, publicKey) = ensureDeviceKeyPair()

        // Every failure message the user can see comes from the server
        // (vpn_api.php's "message" field) so wording can be changed
        // without an app update - the one exception is when the server
        // couldn't be reached at all (see SettingsActivity), which by
        // definition has no server response to carry a message.
        val response = try {
            ApiClient.vpnApi(VPN_API_BASE_URL).requestPeer(
                username = username,
                password = password,
                publicKey = publicKey
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }

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
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun release() {
        val username = prefs.getUsername()
        val password = prefs.getPassword()
        val publicKey = prefs.getVpnDevicePublicKey() ?: return
        if (username.isBlank() || password.isBlank()) return
        try {
            ApiClient.vpnApi(VPN_API_BASE_URL).releasePeer(
                username = username,
                password = password,
                publicKey = publicKey
            )
        } catch (e: Exception) {
            // Best-effort - the server prunes stale peers independently,
            // and the local tunnel is already brought down regardless.
        }
    }
}
