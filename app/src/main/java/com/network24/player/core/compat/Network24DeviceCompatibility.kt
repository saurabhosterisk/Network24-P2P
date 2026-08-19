package com.network24.player.core.compat

import android.content.Context
import android.os.Build

/** Device gates for legacy TV playback and optional modern transports. */
object Network24DeviceCompatibility {
    private const val FIRST_MODERN_P2P_API = 23

    fun supportsP2p(): Boolean = Build.VERSION.SDK_INT >= FIRST_MODERN_P2P_API

    fun isLegacyTv(context: Context): Boolean =
        Build.VERSION.SDK_INT < FIRST_MODERN_P2P_API &&
            context.packageManager.hasSystemFeature("android.software.leanback")
}
