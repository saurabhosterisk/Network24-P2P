package com.network24.player.core.net

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

class CountingDataSource(
    private val upstream: DataSource
) : DataSource {

    private var windowBytes: Long = 0L
    private var windowStartMs: Long = 0L

    // Keep this fairly large to minimize overhead.
    // 512 KB is a good balance for live streaming.
    private val reportBytesThreshold: Long = 512L * 1024L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        windowBytes = 0L
        windowStartMs = SystemClock.elapsedRealtime()

        val openStartMs = windowStartMs
        val result = upstream.open(dataSpec)
        val openElapsedMs = SystemClock.elapsedRealtime() - openStartMs
        ConnectionTimingTracker.reportOpen(dataSpec.uri.path ?: dataSpec.uri.toString(), openElapsedMs)

        return result
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        if (read > 0) {
            windowBytes += read

            // Only touch clock + report when threshold reached (rare).
            if (windowBytes >= reportBytesThreshold) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStartMs
                if (elapsed > 0L) {
                    SpeedMonitor.reportSample(windowBytes, elapsed)
                }
                windowBytes = 0L
                windowStartMs = now
            }
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    @Throws(IOException::class)
    override fun close() {
        try {
            upstream.close()
        } finally {
            // Final flush, minimal.
            if (windowBytes > 0L) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStartMs
                if (elapsed > 0L) {
                    SpeedMonitor.reportSample(windowBytes, elapsed)
                }
            }
            windowBytes = 0L
        }
    }
}
