package com.network24.player.features.parental

import android.content.Context
import com.google.gson.Gson
import com.network24.player.common.models.SupportError
import com.network24.player.common.models.WebLock
import com.network24.player.core.api.ApiClient
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Account state shared with the web player (play.web24.live), kept on Main by support_api.php:
 * recently watched channels and the parental lock (PIN hash + locked category ids).
 */
class WebStateRepository(context: Context) {

    companion object {
        private val BASE_URL get() = PreferenceManager.SERVER_URL + "/"
        private const val FALLBACK_ERROR = "Network24 could not be reached. Please check your connection and try again."
        private const val SYNC_EVERY_MS = 30_000L

        @Volatile
        private var lastSyncMs = 0L
        private val syncMutex = Mutex()
    }

    private val appContext = context.applicationContext
    private val prefs = PreferenceManager(appContext)
    private val api get() = ApiClient.supportApi(BASE_URL)

    private fun user() = prefs.getUsername()
    private fun pass() = prefs.getPassword()

    /**
     * Reads the account's recently watched list and parental lock. Returns true when the lock changed (the caller
     * then redraws its lists). Runs at most every 30 s unless forced.
     */
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (user().isBlank() || pass().isBlank()) return@withContext false
        syncMutex.withLock {
            if (!force && System.currentTimeMillis() - lastSyncMs < SYNC_EVERY_MS) return@withLock false
            val state = call { api.webState(user(), pass()) }.getOrNull() ?: return@withLock false
            lastSyncMs = System.currentTimeMillis()
            // only the parental lock comes from the account; recently watched is this device's own list
            saveLock(state.lock)
        }
    }

    private fun saveLock(lock: WebLock?): Boolean =
        ParentalLock.save(appContext, lock?.enabled == true, lock?.cats.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }, lock?.custom_pin == true)

    suspend fun verifyPin(pin: String): Result<Unit> = withContext(Dispatchers.IO) {
        call { api.lockVerify(user(), pass(), pin) }.map { }
    }

    suspend fun setLock(pin: String, currentPin: String, categoryIds: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        call { api.lockSet(user(), pass(), pin, currentPin, categoryIds) }.map { saveLock(it.lock); ParentalLock.relock() }
    }

    suspend fun lockOff(currentPin: String): Result<Unit> = withContext(Dispatchers.IO) {
        call { api.lockOff(user(), pass(), currentPin) }.map { saveLock(it.lock); ParentalLock.relock() }
    }

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
}
