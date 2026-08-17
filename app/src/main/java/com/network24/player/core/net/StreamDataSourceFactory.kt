package com.network24.player.core.net

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.network24.player.core.p2p.Network24HybridDataSource
import com.network24.player.core.p2p.Network24PeerSegmentFetcher
import com.network24.player.core.p2p.Network24SegmentCache

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
            .setDefaultRequestProperties(
                mapOf("Connection" to "close")
            )

        return DataSource.Factory {
            CountingDataSource(httpFactory.createDataSource())
        }
    }

    /**
     * Opt-in factory for P2P playback. Existing callers must continue using
     * createMediaSourceFactory() until the server feature flag is enabled.
     */
    fun createHybridDataSourceFactory(
        context: Context,
        peerFetcher: Network24PeerSegmentFetcher?,
        p2pTimeoutMs: Long = 1_500L
    ): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Connection" to "close"))
        val cache = Network24SegmentCache(context)
        return DataSource.Factory {
            Network24HybridDataSource(httpFactory.createDataSource(), cache, peerFetcher, p2pTimeoutMs)
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
