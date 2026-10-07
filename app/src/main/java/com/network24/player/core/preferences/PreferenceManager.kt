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
        // Main server. app.web24.live -> 185.134.22.131; op.web24.live ->
        // 185.134.22.150 is the old name (see getServer()).
        const val SERVER_HOST = "app.web24.live"

        // https since build 89 (Main and every load balancer have the *.web24.live certificate; Main sends https
        // requests on to https://sN.web24.live:8443). A TV where https does not work falls back to plain http on its
        // own (ServerRoute.check at start-up, result remembered).
        const val HTTPS_URL = "https://$SERVER_HOST"
        const val HTTP_URL = "http://$SERVER_HOST:8080"
        @Volatile @JvmStatic var useHttps = true
        val SERVER_URL: String get() = if (useHttps) HTTPS_URL else HTTP_URL
        private const val LEGACY_SERVER_HOST = "op.web24.live"

        private const val KEY_SERVER = "server"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER = "remember"

        private const val KEY_STATUS = "status"
        private const val KEY_EXPIRY = "expiry"
        private const val KEY_ACTIVE_CONNECTIONS = "active_connections"
        private const val KEY_MAX_CONNECTIONS = "max_connections"
        private const val KEY_IS_TRIAL = "is_trial"
        private const val KEY_VPN_PERSISTENT_ACCESS = "vpn_persistent_access"

        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_FIRST_SETUP_PENDING = "first_setup_pending"
        private const val KEY_DISABLED_CATEGORIES = "disabled_live_category_ids"
        private const val KEY_DISABLED_CATEGORIES_CACHED = "disabled_live_category_ids_cached"
        private const val KEY_AUTO_RECONNECT_MODE = "auto_reconnect_mode"
        private const val KEY_AUTO_REFRESH_HOURS = "auto_refresh_hours"
        private const val KEY_LAST_DATA_REFRESH_MS = "last_data_refresh_ms"
        // Settings > Auto Refresh default: every 8 hours (0 = off)
        const val DEFAULT_AUTO_REFRESH_HOURS = 8
        private const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"

        private const val KEY_VPN_ENABLED = "vpn_enabled"
        private const val KEY_AI_ASSISTANT = "ai_assistant_enabled"
        private const val KEY_AUTO_VOLUME = "auto_volume_enabled"
        private const val KEY_VPN_DEVICE_PRIVATE_KEY = "vpn_device_private_key"
        private const val KEY_VPN_DEVICE_PUBLIC_KEY = "vpn_device_public_key"
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

    // Logins saved before the switch to app.web24.live still point at
    // op.web24.live (185.134.22.150), which some ISPs throttle. Both names
    // reach the same Main server, so move saved logins over without a
    // re-login.
    fun getServer(): String {
        val saved = prefs.getString(KEY_SERVER, "") ?: ""
        // our own server (old or new name, http or https) always follows the current route (https, or http if this
        // TV cannot do https)
        if (saved.contains(SERVER_HOST) || saved.contains(LEGACY_SERVER_HOST)) return SERVER_URL
        return saved
    }
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
        isTrial: Boolean,
        vpnPersistentAccess: Boolean = hasPersistentVpnAccess()
    ) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_STATUS, status)
            .putLong(KEY_EXPIRY, expiry)
            .putInt(KEY_ACTIVE_CONNECTIONS, activeConnections)
            .putInt(KEY_MAX_CONNECTIONS, maxConnections)
            .putBoolean(KEY_IS_TRIAL, isTrial)
            .putBoolean(KEY_VPN_PERSISTENT_ACCESS, vpnPersistentAccess)
            .apply()
    }

    fun getStatus(): String = prefs.getString(KEY_STATUS, "Unknown") ?: "Unknown"
    fun getExpiry(): Long = prefs.getLong(KEY_EXPIRY, 0L)
    fun getActiveConnections(): Int = prefs.getInt(KEY_ACTIVE_CONNECTIONS, 0)
    fun getMaxConnections(): Int = prefs.getInt(KEY_MAX_CONNECTIONS, 0)
    fun isTrial(): Boolean = prefs.getBoolean(KEY_IS_TRIAL, false)

    // player_api.php's user_info.vpn_access == "1" - "1" grants persistent
    // Secure Relay (stays on across app backgrounding); "0"/absent keeps
    // the default per-session behavior (torn down on background).
    fun hasPersistentVpnAccess(): Boolean = prefs.getBoolean(KEY_VPN_PERSISTENT_ACCESS, false)

    // -------------------------
    // Sync time
    // -------------------------

    fun setLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    /**
     * Set on every successful login (new install, or logout -> login). The Dashboard then downloads the channels
     * AND the full TV Guide with a visible progress screen, and clears it only when the guide really arrived -
     * new customers used to see no EPG until they found Refresh TV Guide in the 3-dot menu.
     */
    fun setFirstSetupPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_FIRST_SETUP_PENDING, pending).apply()
    }

    fun isFirstSetupPending(): Boolean = prefs.getBoolean(KEY_FIRST_SETUP_PENDING, false)

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

    fun getAutoRefreshHours(): Int =
        prefs.getInt(KEY_AUTO_REFRESH_HOURS, DEFAULT_AUTO_REFRESH_HOURS)

    fun setAutoRefreshHours(hours: Int) {
        prefs.edit().putInt(KEY_AUTO_REFRESH_HOURS, hours).apply()
    }

    /** When channels + TV guide were last fully refreshed (auto or manual). */
    fun getLastDataRefreshMs(): Long = prefs.getLong(KEY_LAST_DATA_REFRESH_MS, 0L)

    fun setLastDataRefreshMs(timeMs: Long) {
        prefs.edit().putLong(KEY_LAST_DATA_REFRESH_MS, timeMs).apply()
    }

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
    // VPN (Secure Relay)
    // -------------------------

    fun isVpnEnabled(): Boolean = prefs.getBoolean(KEY_VPN_ENABLED, false)

    // Auto Volume Leveling (Settings > Special features / full-screen button), off by default.
    fun isAutoVolumeEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_VOLUME, false)

    fun setAutoVolumeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_VOLUME, enabled).apply()
        com.network24.player.core.audio.AutoVolume.enabled = enabled
    }

    // AI Support Assistant button in the full-screen player (Settings > Special features), off by default.
    fun isAiAssistantEnabled(): Boolean = prefs.getBoolean(KEY_AI_ASSISTANT, false)

    fun setAiAssistantEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_ASSISTANT, enabled).apply()
    }

    fun setVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_ENABLED, enabled).apply()
    }

    fun getVpnDevicePrivateKey(): String? = prefs.getString(KEY_VPN_DEVICE_PRIVATE_KEY, null)
    fun getVpnDevicePublicKey(): String? = prefs.getString(KEY_VPN_DEVICE_PUBLIC_KEY, null)

    fun saveVpnDeviceKeyPair(privateKey: String, publicKey: String) {
        prefs.edit()
            .putString(KEY_VPN_DEVICE_PRIVATE_KEY, privateKey)
            .putString(KEY_VPN_DEVICE_PUBLIC_KEY, publicKey)
            .apply()
    }

    // -------------------------
    // Maintenance
    // -------------------------

    fun clear() {
        prefs.edit().clear().apply()
    }
}
