package com.network24.player.core.compat

import android.content.Context

/** Device gates for legacy TV playback and optional modern transports. */
object Network24DeviceCompatibility {
    /**
     * Fire TV devices can run newer Android API levels while still having no
     * Google Play services. Detect the TV form factor independently of API
     * level so mobile-only services (for example FCM) are never initialized
     * on Fire TV/Android TV devices.
     */
    fun isLegacyTv(context: Context): Boolean {
        val packageManager = context.packageManager
        return packageManager.hasSystemFeature("android.software.leanback") ||
            packageManager.hasSystemFeature("android.hardware.type.television")
    }
}
