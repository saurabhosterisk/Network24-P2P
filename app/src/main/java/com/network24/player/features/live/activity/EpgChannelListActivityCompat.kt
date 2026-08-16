package com.network24.player.features.live.activity

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.network24.player.R
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.player.manager.PlayerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compatibility implementations for helper methods accidentally removed from
 * EpgChannelListActivity during the DPAD focus-navigation change.
 *
 * Kept as extensions so the existing activity implementation remains intact
 * while restoring the original runtime behaviour without another large rewrite.
 */
private fun EpgChannelListActivity.privateField(name: String): Any? = runCatching {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}.getOrNull()

private fun EpgChannelListActivity.privateMethod(name: String, vararg types: Class<*>): java.lang.reflect.Method? = runCatching {
    javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
}.getOrNull()

private fun EpgChannelListActivity.compatDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun EpgChannelListActivity.compatTimelineStart(): Long =
    (privateField("timelineStart") as? Long) ?: 0L

private fun EpgChannelListActivity.compatTimelineEnd(): Long =
    (privateField("timelineEnd") as? Long) ?: Long.MAX_VALUE

private fun EpgChannelListActivity.compatMinuteWidth(): Float =
    (privateField("minuteWidthDp") as? Float) ?: 9f

fun EpgChannelListActivity.updateStickyDate(scrollX: Int) {
    val safeMinuteWidth = compatMinuteWidth().coerceAtLeast(1f)
    val minutesFromStart = scrollX / safeMinuteWidth
    val end = compatTimelineEnd()
    val timestamp = compatTimelineStart() + (minutesFromStart * 60_000L).toLong()
    val date = Date(timestamp.coerceAtMost((end - 1L).coerceAtLeast(timestamp)))
    findViewById<android.widget.TextView>(R.id.stickyDate)?.text =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault())
            .format(date)
            .uppercase(Locale.getDefault())
}

fun EpgChannelListActivity.isTodayTimeline(): Boolean =
    compatTimelineStart() <= System.currentTimeMillis() &&
        compatTimelineEnd() > System.currentTimeMillis()

fun EpgChannelListActivity.addNowLine(parent: FrameLayout, start: Long, heightDp: Int) {
    val now = System.currentTimeMillis()
    val end = compatTimelineEnd()
    if (now < start || now >= end) return
    val minutes = ((now - start).coerceAtLeast(0L) / 60_000L).toFloat()
    val left = (minutes * compatMinuteWidth()).toInt()
    val line = View(this).apply {
        setBackgroundColor(Color.rgb(255, 152, 0))
        isFocusable = false
        isClickable = false
    }
    val params = FrameLayout.LayoutParams(
        compatDp(2),
        compatDp(heightDp),
        Gravity.TOP or Gravity.START
    )
    params.leftMargin = left
    parent.addView(line, params)
}

fun EpgChannelListActivity.playChannel(channel: LiveChannel, program: EpgEntity? = null) {
    val streamId = channel.stream_id ?: return
    val prefs = privateField("prefs") as? PreferenceManager ?: return
    val binding = privateField("binding") ?: return
    val playerView = runCatching {
        binding.javaClass.getDeclaredField("playerView").apply { isAccessible = true }.get(binding)
    }.getOrNull() ?: return

    val server = prefs.getServer().trim().trimEnd('/')
    val url = "$server/live/${prefs.getUsername()}/${prefs.getPassword()}/$streamId.m3u8"

    val selected = privateField("selectedChannel") as? LiveChannel
    privateFieldSet("expectingFullscreenReturn", selected?.stream_id == streamId)
    privateFieldSet("pendingFocusChannelId", streamId)
    privateFieldSet(
        "pendingFocusProgramKey",
        program?.let {
            "${channel.stream_id}|${it.startTimestamp ?: Long.MAX_VALUE}|${it.stopTimestamp ?: Long.MIN_VALUE}"
        }
    )
    privateFieldSet("selectedChannel", channel)

    privateMethod("updateTopInfo", LiveChannel::class.java, EpgEntity::class.java)
        ?.invoke(this, channel, program)
        ?: privateMethod("updateTopInfo", LiveChannel::class.java)?.invoke(this, channel)

    PlayerManager.play(this, playerView as androidx.media3.ui.PlayerView, url, channel.stream_id.toString())

    val focusViews = privateField("channelFocusViews") as? MutableList<*> ?: return
    val channels = (privateField("channels") as? List<*>)
        ?.filterIsInstance<LiveChannel>()
        ?: return
    focusViews.forEachIndexed { index, view ->
        val channelAtIndex = channels.getOrNull(index) ?: return@forEachIndexed
        if (view is View) {
            val method = privateMethod("channelBackground", LiveChannel::class.java, Boolean::class.javaPrimitiveType!!)
            val drawable = method?.invoke(this, channelAtIndex, view.hasFocus())
            if (drawable is android.graphics.drawable.Drawable) view.background = drawable
        }
    }
}

private fun EpgChannelListActivity.privateFieldSet(name: String, value: Any?) {
    runCatching {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }
}
