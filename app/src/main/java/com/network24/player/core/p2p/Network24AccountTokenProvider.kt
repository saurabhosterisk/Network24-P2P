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
import java.util.concurrent.TimeUnit

/** Exchanges the already validated IPTV account session for a short-lived P2P token. */
class Network24AccountTokenProvider(
    private val context: Context,
    private val preferences: PreferenceManager,
    private val tokenEndpoint: String = "https://p2p.web24.live/api/v1/client/token",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
) : Network24TokenProvider {
    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0

    override fun getToken(callback: (String?) -> Unit) {
        if (cachedToken != null && expiresAtMs > System.currentTimeMillis() + 15_000) {
            callback(cachedToken)
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
                    val json = JSONObject(it.body?.string().orEmpty())
                    cachedToken = json.optString("token").takeIf(String::isNotBlank)
                    expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 300) * 1000
                    callback(cachedToken)
                }
            }
        })
    }

    companion object { private val JSON = "application/json; charset=utf-8".toMediaType() }
}
