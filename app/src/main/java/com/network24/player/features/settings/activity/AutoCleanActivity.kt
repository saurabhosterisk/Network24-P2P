package com.network24.player.features.settings.activity

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.sync.AutoCleanWorker
import java.util.concurrent.TimeUnit

/**
 * Settings > Auto clean: the "Fresh start" clean-up on a schedule - pick how often, see when it last ran and how
 * much it freed, or clean right away. Same layout as Auto update.
 */
class AutoCleanActivity : BaseActivity() {

    private lateinit var prefs: PreferenceManager
    private lateinit var kit: SettingsKit
    private lateinit var cleanNow: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var optionsContainer: LinearLayout

    private val options = listOf(
        0 to ("Off" to "Only when you tap Clean now"),
        1 to ("Every day" to "For sticks with very little storage"),
        3 to ("Every 3 days" to "Keeps the app light"),
        7 to ("Every week" to "A good balance"),
        14 to ("Every 2 weeks" to "Lightest touch")
    )

    private var cleanStartedHere = false
    private var focusedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = SettingsKit(this)
        val page = kit.page("Auto clean", "Clear temporary files on a schedule, so the app stays fast")
        page.hero.addView(kit.orb(R.drawable.ic_clear_cache))
        page.hero.addView(kit.text("LAST CLEAN", 10f, kit.textSub, 800).apply { letterSpacing = 0.14f; setPadding(0, kit.dp(22), 0, 0) })
        statusTitle = kit.text("", 20f, kit.textMain, 800, lines = 2).apply { setPadding(0, kit.dp(6), 0, 0) }
        page.hero.addView(statusTitle)
        statusDetail = kit.text("", 13f, kit.textSub, 600, lines = 3).apply { setPadding(0, kit.dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        page.hero.addView(statusDetail)
        progress = kit.progressBar().apply { visibility = View.GONE }
        page.hero.addView(progress, LinearLayout.LayoutParams(-1, kit.dp(6)).apply { topMargin = kit.dp(14) })
        page.hero.addView(kit.spacer())
        // what a clean does - in the card, where there is room (under the list it was cut by the screen edge)
        page.hero.addView(kit.text("Clears recently watched, recent searches, saved pictures and other temporary files. Your login, favorites, reminders, parental lock and settings stay as they are.", 12f, kit.textSub, 500, lines = 6).apply { setPadding(0, kit.dp(12), 0, 0); setLineSpacing(0f, 1.2f) })
        cleanNow = kit.button("Clean now", true) {}
        page.hero.addView(cleanNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) })
        page.content.addView(kit.label("How often"))
        optionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        page.content.addView(optionsContainer)
        setContentView(setupGlobalRightDrawer(page.root, page.menu))

        prefs = PreferenceManager(this)
        cleanNow.setOnClickListener {
            cleanStartedHere = true
            AutoCleanWorker.cleanNow(this)
        }
        buildOptions()
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(AutoCleanWorker.MANUAL_WORK)
            .observe(this) { infos -> onManualState(infos.lastOrNull()?.state) }
    }

    override fun onResume() {
        super.onResume()
        bindStatus()
    }

    private fun buildOptions() {
        val hadFocus = optionsContainer.hasFocus()
        optionsContainer.removeAllViews()
        val selected = prefs.getAutoCleanDays()
        options.forEach { (days, texts) ->
            val row = kit.option(texts.first, texts.second, days == selected, if (days == 7) "RECOMMENDED" else null) { selectInterval(days) }
            row.tag = days
            optionsContainer.addView(row)
        }
        val target = optionsContainer.findViewWithTag<View>(selected)
        if (hadFocus || !focusedOnce) { focusedOnce = true; target?.post { target.requestFocus() } }
    }

    private fun selectInterval(days: Int) {
        if (days == prefs.getAutoCleanDays()) return
        prefs.setAutoCleanDays(days)
        AutoCleanWorker.schedule(this, intervalChanged = true)
        buildOptions()
        bindStatus()
        Toast.makeText(this, if (days == 0) "Auto clean turned off" else "The app will clean itself ${options.first { it.first == days }.second.first.lowercase()}", Toast.LENGTH_SHORT).show()
    }

    private fun bindStatus() {
        val last = prefs.getLastCleanMs()
        val now = System.currentTimeMillis()
        val freed = prefs.getLastCleanFreedBytes()
        statusTitle.text = when {
            last <= 0L -> "Not cleaned yet"
            now - last < 60_000L -> "Cleaned just now"
            else -> "Cleaned " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
        }
        val days = prefs.getAutoCleanDays()
        val freedText = if (last > 0L && freed > 1_000_000) "${freed / 1_000_000} MB freed last time. " else ""
        statusDetail.text = freedText + when {
            days <= 0 -> "Automatic clean is off"
            last <= 0L -> "Next automatic clean within $days day" + (if (days > 1) "s" else "")
            else -> {
                val next = last + TimeUnit.DAYS.toMillis(days.toLong())
                if (next <= now) "Next automatic clean: when the app is next opened"
                else "Next automatic clean " + DateUtils.getRelativeTimeSpanString(next, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
            }
        }
    }

    private fun onManualState(state: WorkInfo.State?) {
        val running = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        cleanNow.isEnabled = !running
        cleanNow.alpha = if (running) 0.5f else 1f
        cleanNow.text = if (running) "Cleaning…" else "Clean now"
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) { statusTitle.text = "Cleaning up…"; statusDetail.text = "Just a moment"; return }
        bindStatus()
        if (!cleanStartedHere) return
        if (state == WorkInfo.State.SUCCEEDED) {
            val freed = prefs.getLastCleanFreedBytes()
            Toast.makeText(this, "All clean" + (if (freed > 1_000_000) " - ${freed / 1_000_000} MB freed" else ""), Toast.LENGTH_LONG).show()
        }
        cleanStartedHere = false
    }
}
