package com.network24.player.core.net

import android.content.Context
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * https or http to Main. Since build 89 the app talks https (app.web24.live:443; channels then come from
 * https://sN.web24.live:8443). Some networks / very old TVs cannot do https - for them the app falls back to the old
 * http://app.web24.live:8080 by itself: the last result is remembered (used straight away at the next start) and
 * checked again in the background at every start.
 */
object ServerRoute {

    private const val PREFS = "n24_route"
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    fun init(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        PreferenceManager.useHttps = p.getBoolean("https", true)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val https = reachable(PreferenceManager.HTTPS_URL)
            // only fall back when https fails but http works (no internet at all = keep https)
            val use = https || !reachable(PreferenceManager.HTTP_URL)
            PreferenceManager.useHttps = use
            android.util.Log.i("N24Api", "route: https=$https -> using ${PreferenceManager.SERVER_URL}")
            p.edit().putBoolean("https", use).apply()
        }
    }

    private fun reachable(base: String): Boolean = runCatching {
        client.newCall(Request.Builder().url("$base/player_api.php").header("User-Agent", "N24Route").build()).execute().use { it.code in 200..499 }
    }.getOrDefault(false)
}
