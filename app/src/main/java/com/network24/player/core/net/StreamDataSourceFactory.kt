package com.network24.player.core.net

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

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
}
