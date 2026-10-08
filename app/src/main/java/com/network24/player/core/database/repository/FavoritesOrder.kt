package com.network24.player.core.database.repository

import android.content.Context

/**
 * How the favorites are sorted wherever they are listed (favorites screen, home row, MultiView picker):
 * "my" = the viewer's own arrangement (the position column; never arranged = newest first), "name" = A-Z,
 * "num" = the channel number. Testers wanted their favorites laid out like their old cable line-up.
 */
object FavoritesOrder {
    const val MY = "my"
    const val NAME = "name"
    const val NUM = "num"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("n24_favorites", Context.MODE_PRIVATE)
    fun mode(c: Context): String = prefs(c).getString("sort", MY) ?: MY
    fun setMode(c: Context, mode: String) { prefs(c).edit().putString("sort", mode).apply() }
    fun label(mode: String) = when (mode) { NAME -> "Name A-Z"; NUM -> "Channel number"; else -> "My order" }

    /** [items] already in "my order" (the DAO's order); re-sorted when another mode is on. */
    fun <T> apply(c: Context, items: List<T>, name: (T) -> String, num: (T) -> Int): List<T> = when (mode(c)) {
        NAME -> items.sortedBy { name(it).lowercase() }
        NUM -> items.sortedWith(compareBy<T> { num(it).let { n -> if (n <= 0) Int.MAX_VALUE else n } }.thenBy { name(it).lowercase() })
        else -> items
    }
}
