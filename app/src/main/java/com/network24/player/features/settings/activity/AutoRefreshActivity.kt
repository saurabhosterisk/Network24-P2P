package com.network24.player.features.settings.activity

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.sync.AutoRefreshWorker
import java.util.concurrent.TimeUnit

/**
 * Settings > Auto Refresh Channels & TV Guide: pick how often channels and the
 * TV guide are downloaded in the background, see when that last happened,
 * and start a refresh right away with REFRESH NOW.
 */
class AutoRefreshActivity : BaseActivity() {

    private lateinit var prefs: PreferenceManager
    private lateinit var refreshNow: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var optionsContainer: LinearLayout

    private val options = listOf(
        0 to ("Off" to "Only when you tap Refresh Now"),
        4 to ("Every 4 hours" to "Best on busy game days"),
        8 to ("Every 8 hours" to "Recommended"),
        12 to ("Every 12 hours" to "Twice a day"),
        24 to ("Once a day" to "Lightest on data")
    )

    // Only react to a refresh started while this screen is open.
    private var refreshStartedHere = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentRoot = layoutInflater.inflate(R.layout.activity_auto_refresh, null, false) as ViewGroup
        setContentView(setupGlobalRightDrawer(contentRoot, contentRoot.findViewById(R.id.btnMore)))

        prefs = PreferenceManager(this)
        refreshNow = findViewById(R.id.btnRefreshNow)
        statusTitle = findViewById(R.id.refreshStatusTitle)
        statusDetail = findViewById(R.id.refreshStatusDetail)
        progress = findViewById(R.id.refreshProgress)
        optionsContainer = findViewById(R.id.intervalOptions)

        findViewById<View>(R.id.autoRefreshBack).setOnClickListener { finish() }
        refreshNow.setOnClickListener {
            refreshStartedHere = true
            AutoRefreshWorker.refreshNow(this)
        }

        buildOptions()
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(AutoRefreshWorker.MANUAL_WORK)
            .observe(this) { infos -> onManualRefreshState(infos.lastOrNull()?.state) }
    }

    override fun onResume() {
        super.onResume()
        bindStatus()
    }

    private fun buildOptions() {
        optionsContainer.removeAllViews()
        val selected = prefs.getAutoRefreshHours()
        val density = resources.displayMetrics.density
        options.forEach { (hours, texts) ->
            val (title, subtitle) = texts
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_settings_action)
                isClickable = true
                isFocusable = true
                setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (64 * density).toInt()
                ).apply { topMargin = (10 * density).toInt() }
                setOnClickListener { selectInterval(hours) }
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(getColor(R.color.text_hint))
            })
            row.addView(texts)
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 22f
                setTextColor(getColor(R.color.primary_light))
                visibility = if (hours == selected) View.VISIBLE else View.INVISIBLE
            })
            optionsContainer.addView(row)
        }
    }

    private fun selectInterval(hours: Int) {
        if (hours == prefs.getAutoRefreshHours()) return
        prefs.setAutoRefreshHours(hours)
        AutoRefreshWorker.schedule(this, intervalChanged = true)
        buildOptions()
        bindStatus()
        Toast.makeText(
            this,
            if (hours == 0) "Auto Refresh turned off" else "Channels & TV guide will refresh ${options.first { it.first == hours }.second.first.lowercase()}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun bindStatus() {
        val last = prefs.getLastDataRefreshMs()
        val now = System.currentTimeMillis()
        statusTitle.text = when {
            last <= 0L -> "Not refreshed yet"
            now - last < 60_000L -> "Updated just now"
            else -> "Updated " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS)
                .toString().lowercase()
        }
        val hours = prefs.getAutoRefreshHours()
        statusDetail.text = when {
            hours <= 0 -> "Automatic refresh is off"
            last <= 0L -> "Next automatic refresh within ${hours} hours"
            else -> {
                val next = last + TimeUnit.HOURS.toMillis(hours.toLong())
                if (next <= now) "Next automatic refresh: when the app is next opened"
                else "Next automatic refresh " + DateUtils.getRelativeTimeSpanString(
                    next, now, DateUtils.MINUTE_IN_MILLIS
                ).toString().lowercase()
            }
        }
    }

    private fun onManualRefreshState(state: WorkInfo.State?) {
        val running = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        refreshNow.isEnabled = !running
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            statusTitle.text = "Refreshing channels & TV guide…"
            statusDetail.text = if (state == WorkInfo.State.ENQUEUED) "Waiting for an internet connection"
            else "This can take a minute on large channel lists"
            return
        }
        bindStatus()
        if (!refreshStartedHere) return
        when (state) {
            WorkInfo.State.SUCCEEDED -> Toast.makeText(this, "Channels & TV guide updated", Toast.LENGTH_SHORT).show()
            WorkInfo.State.FAILED -> {
                statusDetail.text = "Couldn't refresh - check your connection and try again"
                Toast.makeText(this, "Refresh failed", Toast.LENGTH_SHORT).show()
            }
            else -> Unit
        }
        refreshStartedHere = false
    }
}
