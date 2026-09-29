package com.network24.player.features.support.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.Gson
import com.network24.player.common.models.AiAskResponse
import com.network24.player.common.models.AiPollResponse
import com.network24.player.common.models.SupportChannel
import com.network24.player.common.models.SupportError
import com.network24.player.common.models.SupportMessage
import com.network24.player.core.api.ApiClient
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.ByteArrayOutputStream

/** Messages of one channel plus the name this customer posts under. */
data class SupportPage(val messages: List<SupportMessage>, val you: String?)

/**
 * Talks to Main Server's support_api.php, which bridges the N24 Discord
 * support channels into the app. The customer signs in with their N24
 * login - no Discord account involved.
 */
class SupportRepository(private val prefs: PreferenceManager) {

    companion object {
        // Same host as login/streams (app.web24.live), like vpn_api.php.
        private const val SUPPORT_API_BASE_URL = PreferenceManager.SERVER_URL + "/"

        // Main accepts request bodies up to 3 MB; this keeps pictures well
        // below that while staying readable (screenshots of error screens).
        private const val MAX_IMAGE_SIDE = 1920
        private const val IMAGE_BYTES_LIMIT = 2_500_000
        private const val FALLBACK_ERROR = "Live Support is not reachable right now. Please check your connection and try again."
    }

    private val api get() = ApiClient.supportApi(SUPPORT_API_BASE_URL)
    private val text = "text/plain".toMediaType()

    suspend fun channels(): Result<List<SupportChannel>> = call {
        api.channels(prefs.getUsername(), prefs.getPassword())
    }.map { it.channels.orEmpty() }

    suspend fun latest(channelId: String): Result<SupportPage> = call {
        api.messages(prefs.getUsername(), prefs.getPassword(), channelId)
    }.map { SupportPage(it.messages.orEmpty(), it.you) }

    suspend fun older(channelId: String, beforeId: String): Result<List<SupportMessage>> = call {
        api.older(prefs.getUsername(), prefs.getPassword(), channelId, beforeId)
    }.map { it.messages.orEmpty() }

    // ---------------------------------------------------------------- AI assistant

    suspend fun aiPoll(after: Int): Result<AiPollResponse> = call {
        api.aiPoll(prefs.getUsername(), prefs.getPassword(), after)
    }

    suspend fun aiClear(): Result<Unit> = call {
        api.aiClear(prefs.getUsername(), prefs.getPassword())
    }.map { }

    /** quick = a fix mode ("buffering"...) for the ready-made buttons, "" for typed questions. */
    suspend fun aiAsk(text: String, quick: String, streamId: Int, streamName: String): Result<AiAskResponse> = call {
        api.aiAsk(prefs.getUsername(), prefs.getPassword(), text, quick, streamId, streamName, accountSummary())
    }

    /** What the AI may use to answer account questions (no password). */
    private fun accountSummary(): String {
        val expiry = prefs.getExpiry()
        val expiryText = if (expiry > 0L) {
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(expiry * 1000L))
        } else {
            "no expiry date"
        }
        val plan = if (prefs.isTrial()) "trial" else "premium"
        return "status ${prefs.getStatus()}, $plan, expires $expiryText, " +
            "${prefs.getActiveConnections()} of ${prefs.getMaxConnections()} connections in use"
    }

    suspend fun send(
        context: Context,
        channelId: String,
        message: String,
        replyToId: String?,
        image: Uri?
    ): Result<SupportMessage?> {
        val imagePart = if (image != null) {
            val bytes = withContext(Dispatchers.IO) { shrinkImage(context, image) }
                ?: return Result.failure(Exception("That picture could not be opened. Please pick another one."))
            MultipartBody.Part.createFormData(
                "image",
                "picture.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType())
            )
        } else {
            null
        }
        return call {
            api.send(
                username = prefs.getUsername().toRequestBody(text),
                password = prefs.getPassword().toRequestBody(text),
                action = "send".toRequestBody(text),
                channel = channelId.toRequestBody(text),
                text = message.toRequestBody(text),
                replyTo = (replyToId ?: "").toRequestBody(text),
                image = imagePart
            )
        }.map { it.message }
    }

    /**
     * Server wording is shown as-is (support_api.php keeps it customer
     * friendly); only network failures fall back to a local message.
     */
    private suspend fun <T> call(block: suspend () -> Response<T>): Result<T> {
        return try {
            val response = block()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                val error = try {
                    Gson().fromJson(response.errorBody()?.string(), SupportError::class.java)
                } catch (e: Exception) {
                    null
                }
                Result.failure(Exception(error?.message ?: FALLBACK_ERROR))
            }
        } catch (e: Exception) {
            Result.failure(Exception(FALLBACK_ERROR))
        }
    }

    /** Decodes, scales to MAX_IMAGE_SIDE and re-encodes as JPEG under the upload limit. */
    private fun shrinkImage(context: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_SIDE || bounds.outHeight / (sample * 2) >= MAX_IMAGE_SIDE) {
            sample *= 2
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }

        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= IMAGE_BYTES_LIMIT || quality <= 40) {
                return out.toByteArray()
            }
            quality -= 15
        }
    }
}
