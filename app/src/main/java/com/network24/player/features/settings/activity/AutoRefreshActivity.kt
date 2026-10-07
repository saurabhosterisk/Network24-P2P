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
        8 to ("Every 8 hours" to "Fresh guide, light on data"),
        12 to ("Every 12 hours" to "Twice a day"),
        24 to ("Once a day" to "Lightest on data")
    )

    // Only react to a refresh started while this screen is open.
    private var refreshStartedHere = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // built in code in the app's look
        kit = SettingsKit(this)
        val page = kit.page("Auto update", "Keep channels and the TV guide fresh on their own")
        page.hero.addView(kit.orb(R.drawable.ic_sync))
        page.hero.addView(kit.text("LAST UPDATE", 10f, kit.textSub, 800).apply { letterSpacing = 0.14f; setPadding(0, kit.dp(22), 0, 0) })
        statusTitle = kit.text("", 20f, kit.textMain, 800, lines = 2).apply { setPadding(0, kit.dp(6), 0, 0) }
        page.hero.addView(statusTitle)
        statusDetail = kit.text("", 13f, kit.textSub, 600, lines = 3).apply { setPadding(0, kit.dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        page.hero.addView(statusDetail)
        progress = kit.progressBar().apply { visibility = View.GONE }
        page.hero.addView(progress, LinearLayout.LayoutParams(-1, kit.dp(6)).apply { topMargin = kit.dp(14) })
        page.hero.addView(kit.spacer())
        refreshNow = kit.button("Update now", true) {}
        page.hero.addView(refreshNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) })
        page.content.addView(kit.label("How often"))
        optionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        page.content.addView(optionsContainer)
        page.content.addView(kit.text("Updates run in the background while the device is on. A full update takes about a minute.", 12f, kit.textSub, 500, lines = 3).apply { setPadding(kit.dp(4), kit.dp(10), 0, 0) })
        setContentView(setupGlobalRightDrawer(page.root, page.menu))

        prefs = PreferenceManager(this)
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

    private lateinit var kit: SettingsKit

    private fun buildOptions() {
        val hadFocus = optionsContainer.hasFocus()
        optionsContainer.removeAllViews()
        val selected = prefs.getAutoRefreshHours()
        options.forEach { (hours, texts) ->
            val row = kit.option(texts.first, texts.second, hours == selected, if (hours == 8) "RECOMMENDED" else null) { selectInterval(hours) }
            row.tag = hours
            optionsContainer.addView(row)
        }
        val target = optionsContainer.findViewWithTag<View>(selected)
        if (hadFocus || !focusedOnce) { focusedOnce = true; target?.post { target.requestFocus() } }
    }
    private var focusedOnce = false

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
        refreshNow.alpha = if (running) 0.5f else 1f
        refreshNow.text = if (running) "Updating…" else "Update now"
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
