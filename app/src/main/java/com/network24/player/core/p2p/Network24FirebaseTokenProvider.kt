package com.network24.player.core.p2p

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject

/** Firebase ID token -> Network24 short-lived P2P token broker client. */
class Network24FirebaseTokenProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val deviceId: String,
    private val deviceType: String,
    private val appVersion: String,
    private val tokenEndpoint: String = "https://p2p.web24.live/api/v1/client/token",
    private val client: OkHttpClient = OkHttpClient()
) : Network24TokenProvider {
    @Volatile private var cachedToken: String? = null

    override fun getToken(callback: (String?) -> Unit) {
        cachedToken?.let { callback(it); return }
        val user = auth.currentUser
        if (user == null) {
            auth.signInAnonymously().addOnSuccessListener { result -> requestBrokerToken(result.user, callback) }
                .addOnFailureListener { callback(null) }
        } else requestBrokerToken(user, callback)
    }

    private fun requestBrokerToken(user: FirebaseUser?, callback: (String?) -> Unit) {
        user?.getIdToken(false)?.addOnSuccessListener { result ->
            val firebaseToken = result.token
            if (firebaseToken.isNullOrBlank()) { callback(null); return@addOnSuccessListener }
            val body = JSONObject().apply {
                put("firebase_id_token", firebaseToken)
                put("device_id", deviceId)
                put("device_type", deviceType)
                put("app_version", appVersion)
                put("protocol_version", 1)
            }.toString().toRequestBody(JSON)
            client.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) = callback(null)
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (!it.isSuccessful) { callback(null); return }
                        val token = JSONObject(it.body?.string().orEmpty()).optString("token").takeIf(String::isNotBlank)
                        cachedToken = token
                        callback(token)
                    }
                }
            })
        }?.addOnFailureListener { callback(null) } ?: callback(null)
    }

    companion object { private val JSON = "application/json; charset=utf-8".toMediaType() }
}
