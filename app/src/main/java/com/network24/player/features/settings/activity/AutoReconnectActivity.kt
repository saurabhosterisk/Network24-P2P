package com.network24.player.features.settings.activity

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.preferences.PreferenceManager.AutoReconnectMode

/**
 * Settings > Auto Reconnect: choose whether the player retries a channel
 * that stops or fails to load, and how quickly. Same layout as
 * AutoRefreshActivity.
 */
class AutoReconnectActivity : BaseActivity() {

    private lateinit var prefs: PreferenceManager
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var optionsContainer: LinearLayout

    private class Option(
        val mode: AutoReconnectMode,
        val title: String,
        val subtitle: String,
        val detail: String
    )

    // Attempts and timings match PlayerManager's recovery settings.
    private val options = listOf(
        Option(
            AutoReconnectMode.STANDARD,
            "Standard",
            "Recommended • up to 5 retries, about 1.5 minutes",
            "Up to 5 retries over about 1.5 minutes - best for short drops and busy game times"
        ),
        Option(
            AutoReconnectMode.FAST,
            "Fast",
            "Up to 3 quick retries, about 45 seconds",
            "Up to 3 quick retries over about 45 seconds - you find out sooner if a channel is down"
        ),
        Option(
            AutoReconnectMode.OFF,
            "Off",
            "Don't retry - show the error straight away",
            "A channel that stops will not be retried automatically"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // built in code in the app's look
        kit = SettingsKit(this)
        val page = kit.page("Auto reconnect", "What the player does when a channel stops")
        page.hero.addView(kit.orb(R.drawable.ic_tv))
        page.hero.addView(kit.text("NOW", 10f, kit.textSub, 800).apply { letterSpacing = 0.14f; setPadding(0, kit.dp(22), 0, 0) })
        statusTitle = kit.text("", 24f, kit.textMain, 800).apply { setPadding(0, kit.dp(6), 0, 0) }
        page.hero.addView(statusTitle)
        statusDetail = kit.text("", 13f, kit.textSub, 600, lines = 4).apply { setPadding(0, kit.dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        page.hero.addView(statusDetail)
        page.content.addView(kit.label("Choose"))
        optionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        page.content.addView(optionsContainer)
        page.content.addView(kit.text("A short drop on the stream is fixed by retrying; if a channel is really down you see why after the last try.", 12f, kit.textSub, 500, lines = 3).apply { setPadding(kit.dp(4), kit.dp(10), 0, 0) })
        setContentView(setupGlobalRightDrawer(page.root, page.menu))

        prefs = PreferenceManager(this)

        buildOptions()
        bindStatus()
    }

    private lateinit var kit: SettingsKit
    private var focusedOnce = false

    private fun buildOptions() {
        val hadFocus = optionsContainer.hasFocus()
        optionsContainer.removeAllViews()
        val selected = prefs.getAutoReconnectMode()
        options.forEach { o ->
            optionsContainer.addView(kit.option(o.title, o.subtitle.removePrefix("Recommended • "), o.mode == selected, if (o.mode == AutoReconnectMode.STANDARD) "RECOMMENDED" else null) { selectMode(o.mode) }.apply { tag = o.mode })
        }
        val target = optionsContainer.findViewWithTag<View>(selected)
        if (hadFocus || !focusedOnce) { focusedOnce = true; target?.post { target.requestFocus() } }
    }

    private fun selectMode(mode: AutoReconnectMode) {
        if (mode == prefs.getAutoReconnectMode()) return
        prefs.setAutoReconnectMode(mode)
        buildOptions()
        bindStatus()
        Toast.makeText(
            this,
            if (mode == AutoReconnectMode.OFF) "Auto Reconnect turned off"
            else "Auto Reconnect set to ${options.first { it.mode == mode }.title}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun bindStatus() {
        val option = options.first { it.mode == prefs.getAutoReconnectMode() }
        statusTitle.text = option.title
        statusDetail.text = option.detail
    }
}
