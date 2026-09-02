package com.network24.player.core.net

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import kotlin.math.min

/**
 * Single source of truth for stream HTTP/media-source configuration.
 * Normal playback and MultiView use the same request configuration.
 */
@Suppress("UnsafeOptInUsageError")
object StreamDataSourceFactory {
    const val USER_AGENT = "N24PlayerPlayer"

    fun createDataSourceFactory(): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            // Fire OS 6 can take longer to resolve/connect to IPTV hosts than
            // newer phones. Avoid treating that initial delay as a dead stream.
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        return DataSource.Factory {
            CountingDataSource(httpFactory.createDataSource())
        }
    }

    fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        return DefaultMediaSourceFactory(createDataSourceFactory())
            // IPTV HLS segments can be lost during a short route/jitter burst.
            // Retry before PlayerManager recovery takes over; this does not
            // duplicate or reload the whole stream.
            .setLoadErrorHandlingPolicy(IptvLoadErrorHandlingPolicy())
    }

    fun createRenderersFactory(context: Context): DefaultRenderersFactory {
        return DefaultRenderersFactory(context.applicationContext).apply {
            setEnableDecoderFallback(true)
        }
    }

    /**
     * Kept as a separate entry point for MultiView so its renderer strategy can
     * be changed later without touching the player manager. The FFmpeg
     * extension is currently not included in this project, so this uses the
     * standard Media3 renderer selection for now.
     */
    fun createSoftwareRenderersFactory(context: Context): DefaultRenderersFactory {
        return createRenderersFactory(context)
    }

    /**
     * IPTV routes drop and re-establish HTTP connections often even when the
     * stream itself is healthy — this mirrors how FFmpeg-based players (e.g.
     * ijkplayer) silently retry at the socket level via their "reconnect"
     * option instead of surfacing every blip as a fatal error. Connection-
     * level IOExceptions (timeouts, resets, unexpected EOF) get several fast
     * retries; an explicit bad HTTP status from the server does not, since
     * retrying won't fix a 403/404/5xx.
     */
    private class IptvLoadErrorHandlingPolicy :
        DefaultLoadErrorHandlingPolicy(TRANSIENT_RETRY_COUNT) {

        override fun getRetryDelayMsFor(
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long {
            val exception = loadErrorInfo.exception
            val isTransientConnectionError = exception is IOException &&
                exception !is HttpDataSource.InvalidResponseCodeException

            val maxRetries = if (isTransientConnectionError) TRANSIENT_RETRY_COUNT else FATAL_RETRY_COUNT
            if (loadErrorInfo.errorCount > maxRetries) return C.TIME_UNSET

            return if (isTransientConnectionError) {
                min(200L * loadErrorInfo.errorCount, 1_000L)
            } else {
                min(500L * loadErrorInfo.errorCount, 2_000L)
            }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = TRANSIENT_RETRY_COUNT

        private companion object {
            const val TRANSIENT_RETRY_COUNT = 6
            const val FATAL_RETRY_COUNT = 3
        }
    }
}
