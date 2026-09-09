package com.network24.player.features.updater.manager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.network24.player.BuildConfig
import com.network24.player.features.updater.api.UpdateClient
import com.network24.player.features.updater.models.UpdateResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

object UpdateManager {
    private const val TAG = "UpdateManager"

    // The most recently downloaded update APK, kept around so a caller can
    // retry the actual install once the user grants the "install unknown
    // apps" permission and returns - without this the download (already
    // paid for in data/time) would just be thrown away and the app would
    // silently stay on the old version.
    private var pendingApkFile: File? = null

    fun checkForUpdate(
        onNoUpdate: () -> Unit,
        onUpdateAvailable: (UpdateResponse) -> Unit
    ) {
        UpdateClient.service.checkUpdate()
            .enqueue(object : Callback<UpdateResponse> {
                override fun onResponse(
                    call: Call<UpdateResponse>,
                    response: Response<UpdateResponse>
                ) {
                    if (!response.isSuccessful || response.body() == null) {
                        onNoUpdate() // Error API response pe app aage badhegi
                        return
                    }
                    val update = response.body()!!
                    Log.d(TAG, "Server Version : ${update.versionCode}")
                    Log.d(TAG, "Current Version: ${BuildConfig.VERSION_CODE}")

                    if (update.versionCode > BuildConfig.VERSION_CODE) {
                        onUpdateAvailable(update)
                    } else {
                        onNoUpdate()
                    }
                }

                override fun onFailure(
                    call: Call<UpdateResponse>,
                    t: Throwable
                ) {
                    Log.e(TAG, "Update Check Failed", t)
                    onNoUpdate() // Internet na hone par app atke nahi
                }
            })
    }

    fun downloadApk(
        activity: Activity,
        url: String,
        onProgress: (Int) -> Unit
    ) {
        // Delete old downloaded APK files
        activity.getExternalFilesDir(null)
            ?.listFiles()
            ?.forEach { file ->
                if (file.extension.equals("apk", true)) {
                    file.delete()
                }
            }

        thread {
            // The download runs on a background thread with no lifecycle owner, so every
            // hop back to the UI must confirm the activity is still alive first.
            fun postToUi(action: () -> Unit) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) action()
                }
            }

            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "HTTP Code = ${response.code}")

                if (!response.isSuccessful) {
                    response.close()
                    postToUi { onProgress(-1) } // 🔥 FIX: Notify UI on failure
                    return@thread
                }

                val body = response.body
                if (body == null) {
                    response.close()
                    postToUi { onProgress(-1) } // 🔥 FIX: Notify UI on failure
                    return@thread
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastProgress = -1

                Log.d(TAG, "Content Length = ${body.contentLength()}")

                val apkFile = File(
                    activity.getExternalFilesDir(null),
                    "Network24_Update.apk"
                )

                response.use {
                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)

                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                if (totalBytes > 0) {
                                    val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        postToUi { onProgress(progress) }
                                    }
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "APK Download Complete")
                pendingApkFile = apkFile

                postToUi {
                    onProgress(101)
                    if (!installApk(activity, apkFile)) {
                        // Permission wasn't granted - we only opened the
                        // "allow installs" settings screen. Tell the caller
                        // so it knows to retry (not give up) once the user
                        // comes back with the permission granted.
                        onProgress(102)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download Failed", e)
                postToUi { onProgress(-1) } // 🔥 FIX: Notify UI on exception
            }
        }
    }

    /**
     * Call this from onResume() after sending the user to the "install
     * unknown apps" permission screen (progress == 102). If the permission
     * has since been granted, this actually launches the installer for the
     * APK that was already downloaded - without it the finished download
     * would be discarded and the app would silently stay on the old version.
     * Returns false if the permission still isn't granted or there's no
     * pending download to install.
     */
    fun retryInstallAfterPermission(activity: Activity): Boolean {
        val apkFile = pendingApkFile ?: return false
        if (!activity.packageManager.canRequestPackageInstalls()) return false
        return installApk(activity, apkFile)
    }

    /** Returns true if the real package installer was launched, false if we only opened the permission screen. */
    private fun installApk(
        activity: Activity,
        apkFile: File
    ): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            return false
        }

        pendingApkFile = null

        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".provider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        return true
    }
}
