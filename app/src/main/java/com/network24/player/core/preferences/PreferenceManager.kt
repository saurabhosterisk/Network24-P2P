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
    // Maintenance
    // -------------------------

    fun clear() {
        prefs.edit().clear().apply()
    }
}
