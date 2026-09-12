package com.network24.player.core.net

import android.util.Log

/**
 * Tracks how long DataSource.open() takes - i.e. DNS + TCP connect + TLS
 * handshake + response headers, before any body bytes are read. Added to
 * pin down a real client report of multi-second-to-multi-minute channel
 * switch delays that were already ruled out as WiFi signal quality,
 * encoder cold-start and LB/backend response time (all measured clean on
 * a real device). What's left is exactly this: time spent opening the
 * connection itself, which this class makes visible without needing a
 * live ADB session.
 */
object ConnectionTimingTracker {
    private const val TAG = "ConnTiming"
    private const val SLOW_THRESHOLD_MS = 1_500L

    @Volatile var lastOpenElapsedMs: Long = 0L
        private set
    @Volatile var lastOpenWasManifest: Boolean = false
        private set
    @Volatile var slowOpenCount: Int = 0
        private set
    @Volatile var slowestOpenMs: Long = 0L
        private set

    // Called by PlayerManager.play() whenever a new channel/stream URL is
    // set, so counts reflect the current channel only.
    fun reset() {
        lastOpenElapsedMs = 0L
        lastOpenWasManifest = false
        slowOpenCount = 0
        slowestOpenMs = 0L
    }

    fun reportOpen(path: String, elapsedMs: Long) {
        val isManifest = path.endsWith(".m3u8")

        lastOpenElapsedMs = elapsedMs
        lastOpenWasManifest = isManifest
        if (elapsedMs > slowestOpenMs) slowestOpenMs = elapsedMs
        if (elapsedMs >= SLOW_THRESHOLD_MS) {
            slowOpenCount++
            Log.w(
                TAG,
                "SLOW connection setup: ${elapsedMs}ms for ${if (isManifest) "manifest" else "segment"} " +
                    "(DNS+connect+TLS+headers, before first byte) path=$path"
            )
        } else {
            Log.i(
                TAG,
                "connection setup: ${elapsedMs}ms for ${if (isManifest) "manifest" else "segment"} path=$path"
            )
        }
    }
}
