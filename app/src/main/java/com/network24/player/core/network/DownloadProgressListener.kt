package com.network24.player.core.network

/** Reports download progress (0-100) for a single tagged Retrofit/OkHttp request. */
fun interface DownloadProgressListener {
    fun onProgress(percent: Int)
}
