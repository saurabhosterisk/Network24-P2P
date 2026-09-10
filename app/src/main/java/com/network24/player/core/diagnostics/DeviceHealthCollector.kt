package com.network24.player.core.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager

data class DeviceHealthSnapshot(
    val networkType: String,
    val internetValidated: Boolean,
    val wifiRssiDbm: Int?,
    val wifiLinkSpeedMbps: Int?,
    val availableRamMb: Long,
    val totalRamMb: Long,
    val freeStorageMb: Long,
    // False on devices with no physical battery (Fire TV / most Android TV
    // boxes) - those still answer ACTION_BATTERY_CHANGED, but with a fixed
    // placeholder level/temperature that means nothing, so batteryPercent
    // and batteryTemperatureC are only ever non-null when this is true.
    val batteryPresent: Boolean,
    val batteryPercent: Int?,
    val batteryTemperatureC: Double?
)

object DeviceHealthCollector {
    fun collect(context: Context): DeviceHealthSnapshot {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        } else {
            null
        }

        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            capabilities != null -> "Other"
            else -> "Offline"
        }

        val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } else {
            networkType != "Offline"
        }

        var wifiRssi: Int? = null
        var wifiLinkSpeed: Int? = null
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            if (info != null && info.rssi in -100..0) wifiRssi = info.rssi
            if (info != null && info.linkSpeed >= 0) wifiLinkSpeed = info.linkSpeed
        }

        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.getMemoryInfo(memory)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeStorageMb = (statFs.availableBytes / MB).coerceAtLeast(0L)

        val battery = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        // EXTRA_PRESENT is what actually distinguishes "this device has a
        // real battery" from a battery-less device's fixed placeholder
        // reply - level/scale/temperature come back looking valid either way.
        val batteryPresent = battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
        val batteryPercent = battery?.takeIf { batteryPresent }?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        }
        val batteryTemperatureC = battery?.takeIf { batteryPresent }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10.0)

        return DeviceHealthSnapshot(
            networkType = networkType,
            internetValidated = validated,
            wifiRssiDbm = wifiRssi,
            wifiLinkSpeedMbps = wifiLinkSpeed,
            availableRamMb = memory.availMem / MB,
            totalRamMb = memory.totalMem / MB,
            freeStorageMb = freeStorageMb,
            batteryPresent = batteryPresent,
            batteryPercent = batteryPercent,
            batteryTemperatureC = batteryTemperatureC
        )
    }

    private const val MB = 1024L * 1024L
}
