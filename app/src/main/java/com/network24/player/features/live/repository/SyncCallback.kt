package com.network24.player.features.live.repository

interface SyncCallback {
    fun onSuccess()
    fun onError(message: String)
    fun onProgress(percent: Int) {}
}