package com.network24.player.common.utils

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Formats EPG program start/stop timestamps as a short local time (e.g. "09:30 PM").
 * Was previously duplicated in ChannelListActivity, FavoriteChannelsActivity, and ChannelAdapter.
 */
object EpgTimeFormatter {
    fun format(timeMs: Long?): String {
        if (timeMs == null || timeMs == 0L) return ""
        return try {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(timeMs)
        } catch (_: Exception) {
            ""
        }
    }
}
