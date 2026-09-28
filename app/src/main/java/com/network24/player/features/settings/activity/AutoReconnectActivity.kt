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
        val contentRoot = layoutInflater.inflate(R.layout.activity_auto_reconnect, null, false) as ViewGroup
        setContentView(setupGlobalRightDrawer(contentRoot, contentRoot.findViewById(R.id.btnMore)))

        prefs = PreferenceManager(this)
        statusTitle = findViewById(R.id.reconnectStatusTitle)
        statusDetail = findViewById(R.id.reconnectStatusDetail)
        optionsContainer = findViewById(R.id.reconnectOptions)

        findViewById<View>(R.id.autoReconnectBack).setOnClickListener { finish() }

        buildOptions()
        bindStatus()
    }

    private fun buildOptions() {
        optionsContainer.removeAllViews()
        val selected = prefs.getAutoReconnectMode()
        val density = resources.displayMetrics.density
        options.forEach { option ->
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
                setOnClickListener { selectMode(option.mode) }
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = option.title
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(this).apply {
                text = option.subtitle
                textSize = 12f
                setTextColor(getColor(R.color.text_hint))
            })
            row.addView(texts)
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 22f
                setTextColor(getColor(R.color.primary_light))
                visibility = if (option.mode == selected) View.VISIBLE else View.INVISIBLE
            })
            optionsContainer.addView(row)
        }
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
