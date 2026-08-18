package com.network24.player.core.p2p

import com.network24.player.core.preferences.PreferenceManager
import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

/** Exchanges the already validated IPTV account session for a short-lived P2P token. */
class Network24AccountTokenProvider(
    private val context: Context,
    private val preferences: PreferenceManager,
    private val tokenEndpoint: String = "https://p2p.web24.live/api/v1/client/token",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
) : Network24TokenProvider {
    @Volatile private var cachedResult: Network24TokenResult? = null
    @Volatile private var expiresAtMs: Long = 0

    override fun getToken(callback: (Network24TokenResult?) -> Unit) {
        if (cachedResult != null && expiresAtMs > System.currentTimeMillis() + 15_000) {
            callback(cachedResult)
            return
        }
        val credentials = preferences.getLoginCredentials()
        if (credentials == null) { callback(null); return }
        val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: return callback(null)
        val body = JSONObject().apply {
            put("server", credentials.server)
            put("username", credentials.username)
            put("password", credentials.password)
            put("device_id", deviceId)
            put("device_type", "ANDROID")
            put("app_version", "2.1")
            put("protocol_version", 1)
        }.toString().toRequestBody(JSON)
        client.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = callback(null)
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) { callback(null); return }
                    val result = runCatching {
                        val json = JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject
                        val token = json.stringValue("token")
                            ?: return@runCatching null
                        val expiresInMs = json.longValue("expires_in")
                            .takeIf { it > 0L } ?: 300L
                        val boundedExpiresInMs = expiresInMs
                            .coerceIn(30L, 3_600L) * 1_000L
                        Network24TokenResult(token, parseTurnServers(json)) to boundedExpiresInMs
                    }.getOrNull()
                    if (result == null) {
                        callback(null)
                        return
                    }
                    cachedResult = result.first
                    expiresAtMs = System.currentTimeMillis() + result.second
                    callback(result.first)
                }
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun parseTurnServers(json: JsonObject): List<Network24IceServer> {
            val turn = json.get("turn")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            val username = turn.stringValue("username") ?: return emptyList()
            val password = turn.stringValue("password") ?: return emptyList()
            val urlsElement = turn.get("urls") ?: return emptyList()
            val urls = if (urlsElement.isJsonArray) {
                urlsElement.asJsonArray.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?.takeIf(String::isNotBlank)
                }
            } else {
                listOfNotNull(urlsElement.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.takeIf(String::isNotBlank))
            }
            return urls
                .asSequence()
                .filter { it.startsWith("turn:") || it.startsWith("turns:") }
                .distinct()
                .map { Network24IceServer(it, username, password) }
                .toList()
        }

        private fun JsonObject.stringValue(name: String): String? = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf(String::isNotBlank)

        private fun JsonObject.longValue(name: String): Long = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong ?: 0L
    }
}
