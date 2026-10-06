package com.network24.player.core.parental

import android.content.Context
import com.network24.player.core.preferences.PreferenceManager

/**
 * Parental lock: categories the customer locked with a PIN (same lock as play.web24.live - the PIN hash and
 * the locked category ids are kept on Main per account, see WebStateRepository). Locked categories stay in the
 * category list with a padlock; their channels are left out of favourites, recently watched, search and the AI
 * buttons until the PIN is entered. After the PIN, everything is open for 60 minutes (in memory only, so an app
 * restart locks it again).
 */
object ParentalLock {

    private const val PREFS = "n24_parental_lock"
    private const val UNLOCK_MS = 60 * 60_000L

    @Volatile
    private var unlockedUntil = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(context: Context, name: String) =
        name + ":" + PreferenceManager(context.applicationContext).getUsername().trim().lowercase()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(key(context, "enabled"), false)

    /** Ids of the categories the customer locked (whether or not they are unlocked right now). */
    fun lockedIds(context: Context): Set<String> =
        prefs(context).getStringSet(key(context, "cats"), emptySet())?.toSet() ?: emptySet()

    /** Until the customer sets their own PIN, the PIN is this (same default on Main and on the web player). */
    const val DEFAULT_PIN = "0000"

    /** true when the customer set their own PIN (else DEFAULT_PIN applies). */
    fun hasCustomPin(context: Context): Boolean =
        prefs(context).getBoolean(key(context, "custom_pin"), false)

    /** Saves the account's lock as Main returned it. Returns true when the locked categories changed. */
    fun save(context: Context, enabled: Boolean, cats: Collection<String>, customPin: Boolean = false): Boolean {
        val newCats = if (enabled) cats.toSet() else emptySet()
        prefs(context).edit().putBoolean(key(context, "custom_pin"), enabled && customPin).apply()
        if (isEnabled(context) == enabled && lockedIds(context) == newCats) return false
        prefs(context).edit()
            .putBoolean(key(context, "enabled"), enabled)
            .putStringSet(key(context, "cats"), newCats)
            .apply()
        return true
    }

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    fun unlock() {
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
    }

    fun relock() {
        unlockedUntil = 0L
    }

    /** Categories that are locked right now (empty when the lock is off or was opened with the PIN). */
    fun activeLockedIds(context: Context): Set<String> =
        if (!isEnabled(context) || isUnlocked()) emptySet() else lockedIds(context)

    fun isLocked(context: Context, categoryId: String?): Boolean =
        categoryId != null && activeLockedIds(context).contains(categoryId)
}
