package com.network24.player.core.preferences

import android.content.Context
import android.content.SharedPreferences
import com.network24.player.common.models.LoginCredentials

class PreferenceManager(context: Context) {

    enum class AutoReconnectMode {
        OFF,
        STANDARD,
        FAST
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("network24", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER = "server"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER = "remember"

        private const val KEY_STATUS = "status"
        private const val KEY_EXPIRY = "expiry"
        private const val KEY_ACTIVE_CONNECTIONS = "active_connections"
        private const val KEY_MAX_CONNECTIONS = "max_connections"
        private const val KEY_IS_TRIAL = "is_trial"

        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_DISABLED_CATEGORIES = "disabled_live_category_ids"
        private const val KEY_DISABLED_CATEGORIES_CACHED = "disabled_live_category_ids_cached"
        private const val KEY_AUTO_RECONNECT_MODE = "auto_reconnect_mode"
        private const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"

        private const val KEY_VPN_ENABLED = "vpn_enabled"
        private const val KEY_VPN_DEVICE_PRIVATE_KEY = "vpn_device_private_key"
        private const val KEY_VPN_DEVICE_PUBLIC_KEY = "vpn_device_public_key"
        private const val KEY_VPN_ASSIGNED_IP = "vpn_assigned_ip"
        private const val KEY_VPN_SERVER_PUBLIC_KEY = "vpn_server_public_key"
        private const val KEY_VPN_ENDPOINT = "vpn_endpoint"
        private const val KEY_VPN_ALLOWED_IPS = "vpn_allowed_ips"
        private const val KEY_VPN_PROVISIONED_AT = "vpn_provisioned_at"
        private const val KEY_VPN_TUNNEL_ACTIVE = "vpn_tunnel_active"
    }

    // -------------------------
    // Login / Credentials
    // -------------------------

    fun saveLogin(
        server: String,
        username: String,
        password: String,
        remember: Boolean
    ) {
        val previousUsername = getUsername()
        val editor = prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_REMEMBER, remember)
        if (previousUsername.isNotBlank() && !previousUsername.equals(username, ignoreCase = true)) {
            editor.remove(KEY_DISABLED_CATEGORIES)
                .remove(KEY_DISABLED_CATEGORIES_CACHED)
        }
        editor.apply()
    }

    fun getServer(): String = prefs.getString(KEY_SERVER, "") ?: ""
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    fun isRememberMe(): Boolean = prefs.getBoolean(KEY_REMEMBER, false)

    /**
     * Always returns a LoginCredentials object (may contain empty strings).
     * Useful when you want a non-null object.
     */
    fun getCredentials(): LoginCredentials {
        return LoginCredentials(
            server = getServer(),
            username = getUsername(),
            password = getPassword()
        )
    }

    /**
     * Returns null when credentials are missing.
     * This matches SyncManager usage.
     */
    fun getLoginCredentials(): LoginCredentials? {
        val server = getServer().trim()
        val username = getUsername().trim()
        val password = getPassword()

        if (server.isBlank() || username.isBlank() || password.isBlank()) return null

        return LoginCredentials(
            server = server,
            username = username,
            password = password
        )
    }

    // -------------------------
    // User Info
    // -------------------------

    fun saveUserInfo(
        username: String,
        status: String,
        expiry: Long,
        activeConnections: Int,
        maxConnections: Int,
        isTrial: Boolean
    ) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_STATUS, status)
            .putLong(KEY_EXPIRY, expiry)
            .putInt(KEY_ACTIVE_CONNECTIONS, activeConnections)
            .putInt(KEY_MAX_CONNECTIONS, maxConnections)
            .putBoolean(KEY_IS_TRIAL, isTrial)
            .apply()
    }

    fun getStatus(): String = prefs.getString(KEY_STATUS, "Unknown") ?: "Unknown"
    fun getExpiry(): Long = prefs.getLong(KEY_EXPIRY, 0L)
    fun getActiveConnections(): Int = prefs.getInt(KEY_ACTIVE_CONNECTIONS, 0)
    fun getMaxConnections(): Int = prefs.getInt(KEY_MAX_CONNECTIONS, 0)
    fun isTrial(): Boolean = prefs.getBoolean(KEY_IS_TRIAL, false)

    // -------------------------
    // Sync time
    // -------------------------

    fun setLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    // -------------------------
    // Live category settings cache
    // -------------------------

    fun setDisabledLiveCategoryIds(ids: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_DISABLED_CATEGORIES, ids.toSet())
            .putBoolean(KEY_DISABLED_CATEGORIES_CACHED, true)
            .apply()
    }

    fun getDisabledLiveCategoryIds(): Set<String>? {
        if (!prefs.getBoolean(KEY_DISABLED_CATEGORIES_CACHED, false)) return null
        return prefs.getStringSet(KEY_DISABLED_CATEGORIES, emptySet())?.toSet() ?: emptySet()
    }

    // -------------------------
    // Playback preferences
    // -------------------------

    fun setAutoReconnectMode(mode: AutoReconnectMode) {
        prefs.edit().putString(KEY_AUTO_RECONNECT_MODE, mode.name).apply()
    }

    fun getAutoReconnectMode(): AutoReconnectMode {
        val storedMode = prefs.getString(
            KEY_AUTO_RECONNECT_MODE,
            AutoReconnectMode.STANDARD.name
        )
        return AutoReconnectMode.entries.firstOrNull { it.name == storedMode }
            ?: AutoReconnectMode.STANDARD
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBTITLES_ENABLED, enabled).apply()
    }

    fun areSubtitlesEnabled(): Boolean = prefs.getBoolean(KEY_SUBTITLES_ENABLED, false)

    // -------------------------
    // VPN tunnel
    // -------------------------

    data class VpnProvisioningState(
        val devicePrivateKey: String,
        val devicePublicKey: String,
        val assignedIp: String,
        val serverPublicKey: String,
        val endpoint: String,
        val allowedIps: List<String>
    )

    fun setVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_ENABLED, enabled).apply()
    }

    // Default false: the VPN tunnel is opt-in - it only starts once the user
    // turns it on from the Secure Relay switch in Settings.
    fun isVpnEnabled(): Boolean = prefs.getBoolean(KEY_VPN_ENABLED, false)

    fun saveVpnDeviceKeyPair(privateKey: String, publicKey: String) {
        prefs.edit()
            .putString(KEY_VPN_DEVICE_PRIVATE_KEY, privateKey)
            .putString(KEY_VPN_DEVICE_PUBLIC_KEY, publicKey)
            .apply()
    }

    fun getVpnDevicePrivateKey(): String? = prefs.getString(KEY_VPN_DEVICE_PRIVATE_KEY, null)
    fun getVpnDevicePublicKey(): String? = prefs.getString(KEY_VPN_DEVICE_PUBLIC_KEY, null)

    fun saveVpnProvisioning(
        assignedIp: String,
        serverPublicKey: String,
        endpoint: String,
        allowedIps: List<String>
    ) {
        prefs.edit()
            .putString(KEY_VPN_ASSIGNED_IP, assignedIp)
            .putString(KEY_VPN_SERVER_PUBLIC_KEY, serverPublicKey)
            .putString(KEY_VPN_ENDPOINT, endpoint)
            .putStringSet(KEY_VPN_ALLOWED_IPS, allowedIps.toSet())
            .putLong(KEY_VPN_PROVISIONED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getVpnProvisioning(): VpnProvisioningState? {
        val devicePrivateKey = getVpnDevicePrivateKey() ?: return null
        val devicePublicKey = getVpnDevicePublicKey() ?: return null
        val assignedIp = prefs.getString(KEY_VPN_ASSIGNED_IP, null) ?: return null
        val serverPublicKey = prefs.getString(KEY_VPN_SERVER_PUBLIC_KEY, null) ?: return null
        val endpoint = prefs.getString(KEY_VPN_ENDPOINT, null) ?: return null
        val allowedIps = prefs.getStringSet(KEY_VPN_ALLOWED_IPS, emptySet())?.toList() ?: emptyList()

        return VpnProvisioningState(
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            assignedIp = assignedIp,
            serverPublicKey = serverPublicKey,
            endpoint = endpoint,
            allowedIps = allowedIps
        )
    }

    // Set by TunnelManager right after a setState() call it made itself
    // succeeds/fails - GoBackend's own running-tunnel query is unreliable to
    // call from a freshly constructed instance (its service binding is
    // asynchronous), so this persisted flag is the source of truth for UI.
    fun setVpnTunnelActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_TUNNEL_ACTIVE, active).apply()
    }

    fun isVpnTunnelActive(): Boolean = prefs.getBoolean(KEY_VPN_TUNNEL_ACTIVE, false)

    fun clearVpnProvisioning() {
        prefs.edit()
            .remove(KEY_VPN_ASSIGNED_IP)
            .remove(KEY_VPN_SERVER_PUBLIC_KEY)
            .remove(KEY_VPN_ENDPOINT)
            .remove(KEY_VPN_ALLOWED_IPS)
            .remove(KEY_VPN_PROVISIONED_AT)
            .apply()
    }

    // -------------------------
    // Maintenance
    // -------------------------

    fun clear() {
        prefs.edit().clear().apply()
    }
}
