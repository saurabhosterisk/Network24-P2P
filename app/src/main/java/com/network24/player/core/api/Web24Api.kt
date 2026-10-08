package com.network24.player.core.api

import android.content.Context
import android.util.Base64
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Small JSON calls for the newer features (trending channels, scores, connections, catch-up guide, show search).
 * Everything goes to the same Main server the rest of the app uses; the answers are the same the web player gets.
 */
class Web24Api(context: Context) {

    class Error(message: String) : Exception(message)

    private val prefs = PreferenceManager(context.applicationContext)
    private val base get() = prefs.getServer().trim().trimEnd('/').ifBlank { PreferenceManager.SERVER_URL }

    /** support_api.php (form POST, like the rest of the app's support calls). */
    suspend fun support(action: String, vararg fields: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("username", prefs.getUsername()).add("password", prefs.getPassword())
            .add("doc_user", prefs.getUsername()).add("action", action)
        fields.forEach { (k, v) -> form.add(k, v) }
        val req = Request.Builder().url(PreferenceManager.SERVER_URL + "/support_api.php").post(form.build()).header("User-Agent", UA).build()
        val t0 = System.currentTimeMillis()
        val text = try {
            client.newCall(req).execute().use { r -> r.body?.string().orEmpty().also { android.util.Log.i("N24Api", "support $action: HTTP ${r.code} ${it.length} chars in ${System.currentTimeMillis() - t0} ms") } }
        } catch (e: Exception) {
            android.util.Log.w("N24Api", "support $action failed: ${e.javaClass.simpleName}: ${e.message} (${PreferenceManager.SERVER_URL})")
            throw Error("Network24 could not be reached. Please check your connection and try again.")
        }
        val j = runCatching { JSONObject(text) }.getOrNull() ?: run { android.util.Log.w("N24Api", "support $action: not JSON (${text.length} chars): ${text.take(120)}"); throw Error("Unexpected answer from the server.") }
        if (j.has("error") || (j.has("result") && !j.optBoolean("result"))) throw Error(j.optString("message", "Something went wrong."))
        j
    }

    /** player_api.php with any action. */
    suspend fun player(action: String, vararg params: Pair<String, Any>): String = withContext(Dispatchers.IO) {
        val u = "$base/player_api.php".toHttpUrl().newBuilder().addQueryParameter("username", prefs.getUsername())
            .addQueryParameter("password", prefs.getPassword()).addQueryParameter("action", action)
        params.forEach { (k, v) -> u.addQueryParameter(k, v.toString()) }
        try {
            client.newCall(Request.Builder().url(u.build()).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) throw Error("The server answered ${r.code}.")
                r.body?.string().orEmpty()
            }
        } catch (e: Error) { throw e } catch (e: Exception) {
            throw Error("Network24 could not be reached. Please check your connection and try again.")
        }
    }

    data class Programme(val title: String, val description: String, val start: Long, val end: Long, val hasArchive: Boolean)

    /** The whole guide the panel keeps for one channel (a few days back and ahead), with catch-up flags. Times in ms. */
    suspend fun fullGuide(streamId: Int): List<Programme> {
        val o = runCatching { JSONObject(player("get_simple_data_table", "stream_id" to streamId)) }.getOrNull() ?: return emptyList()
        val a = o.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Programme(b64(it.optString("title")).trim(), b64(it.optString("description")).trim(),
                it.optString("start_timestamp").toLongOrNull()?.times(1000) ?: 0L, it.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: 0L,
                it.optInt("has_archive") == 1)
        }.filter { it.start > 0 && it.end > it.start }.sortedBy { it.start }
    }

    /** Catch-up of a past programme: the panel's timeshift link, start written in the panel's time zone. */
    fun catchupUrl(streamId: Int, p: Programme): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone(SERVER_TZ) }
        val minutes = ((p.end - p.start) / 60_000).coerceAtLeast(1)
        return "$base/timeshift/${prefs.getUsername()}/${prefs.getPassword()}/$minutes/${f.format(java.util.Date(p.start))}/$streamId.ts"
    }

    companion object {
        const val UA = "N24PlayerPlayer"
        const val SERVER_TZ = "Europe/London"
        val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        fun b64(s: String): String = runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)
        fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
}
