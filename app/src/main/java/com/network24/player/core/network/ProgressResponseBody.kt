package com.network24.player.core.network

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * Wraps a response body so [listener] is told the download percentage as bytes
 * arrive. The length normally comes from Content-Length. For transparently gzip
 * decoded responses OkHttp removes that header, so callers may provide the
 * original uncompressed byte length through a custom response header.
 */
class ProgressResponseBody(
    private val delegate: ResponseBody,
    private val listener: DownloadProgressListener,
    advertisedContentLength: Long? = null
) : ResponseBody() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastReportedPercent = -1
    private val totalContentLength = advertisedContentLength
        ?.takeIf { it > 0L }
        ?: delegate.contentLength()
    private val bufferedSource: BufferedSource by lazy { countingSource(delegate.source()).buffer() }

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = totalContentLength
    override fun source(): BufferedSource = bufferedSource

    private fun countingSource(source: Source): Source {
        val totalLength = totalContentLength
        return object : ForwardingSource(source) {
            var totalBytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead != -1L) totalBytesRead += bytesRead
                if (totalLength > 0) {
                    val percent = ((totalBytesRead * 100) / totalLength).toInt().coerceIn(0, 100)
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        mainHandler.post { listener.onProgress(percent) }
                    }
                }
                return bytesRead
            }
        }
    }

    companion object {
        const val UNCOMPRESSED_LENGTH_HEADER = "X-Uncompressed-Length"
    }
}
