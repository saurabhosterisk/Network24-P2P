package com.network24.player.core.api

import android.content.Context
import org.json.JSONArray

/**
 * How much each live channel is watched (Main's rolling score from its viewer samples), so rows on the home
 * screen can put the channels people really watch first instead of going alphabetically. Fetched at most every
 * 30 minutes and kept on the device; an empty map means "no data yet" and rows stay alphabetical.
 */
object Popularity {
    private const val PREFS = "n24_pop"
    private const val MAX_AGE = 30 * 60_000L
    private var memory: Map<Int, Float>? = null

    suspend fun scores(context: Context): Map<Int, Float> {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = System.currentTimeMillis() - p.getLong("at", 0L) < MAX_AGE
        memory?.takeIf { fresh }?.let { return it }
        if (!fresh) {
            runCatching { Web24Api(context).support("popularity").optJSONArray("streams") }.getOrNull()?.let { a ->
                p.edit().putString("json", a.toString()).putLong("at", System.currentTimeMillis()).apply()
                memory = null
            }
        }
        return memory ?: parse(p.getString("json", null)).also { memory = it }
    }

    private fun parse(json: String?): Map<Int, Float> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optInt(0) to it.optDouble(1).toFloat() } }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** The stream ids of [channels] with the most watched first; channels without a score keep their order after them. */
    fun <T> rank(channels: List<T>, scores: Map<Int, Float>, id: (T) -> Int): List<T> =
        if (scores.isEmpty()) channels else channels.sortedByDescending { scores[id(it)] ?: 0f }
}
