package com.network24.player.features.live.activity

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.network24.player.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper implementations for EpgChannelListActivity's sticky-date label and
 * now-line marker. Kept as extensions so this rendering logic stays out of
 * the already-large activity file.
 */
fun EpgChannelListActivity.updateStickyDate(scrollX: Int) {
    val safeMinuteWidth = minuteWidthDp.coerceAtLeast(1f)
    val minutesFromStart = scrollX / safeMinuteWidth
    val end = timelineEnd
    val timestamp = timelineStart + (minutesFromStart * 60_000L).toLong()
    val date = Date(timestamp.coerceAtMost((end - 1L).coerceAtLeast(timestamp)))
    findViewById<android.widget.TextView>(R.id.stickyDate)?.text =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault())
            .format(date)
            .uppercase(Locale.getDefault())
}

fun EpgChannelListActivity.isTodayTimeline(): Boolean =
    timelineStart <= System.currentTimeMillis() &&
        timelineEnd > System.currentTimeMillis()

fun EpgChannelListActivity.addNowLine(parent: FrameLayout, start: Long, heightDp: Int) {
    val now = System.currentTimeMillis()
    val end = timelineEnd
    if (now < start || now >= end) return
    val minutes = ((now - start).coerceAtLeast(0L) / 60_000L).toFloat()
    val left = (minutes * minuteWidthDp).toInt()
    val line = View(this).apply {
        setBackgroundColor(Color.rgb(255, 152, 0))
        isFocusable = false
        isClickable = false
    }
    val params = FrameLayout.LayoutParams(
        dp(2),
        dp(heightDp),
        Gravity.TOP or Gravity.START
    )
    params.leftMargin = left
    parent.addView(line, params)
}
