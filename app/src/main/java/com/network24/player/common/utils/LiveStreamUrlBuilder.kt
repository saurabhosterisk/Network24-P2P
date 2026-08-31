package com.network24.player.common.utils

import com.network24.player.core.preferences.PreferenceManager

/**
 * Builds the Xtream-style live stream URL from saved credentials.
 * Was previously duplicated in ChannelListActivity and FavoriteChannelsActivity.
 */
object LiveStreamUrlBuilder {
    fun build(prefs: PreferenceManager, streamId: Any?): String {
        val server = prefs.getServer().trim().trimEnd('/')
        return "$server/live/${prefs.getUsername()}/${prefs.getPassword()}/$streamId.m3u8"
    }
}
