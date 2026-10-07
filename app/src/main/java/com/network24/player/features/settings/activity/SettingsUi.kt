package com.network24.player.features.settings.activity

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.network24.player.R
import com.network24.player.features.dashboard.home.HomeFont

/**
 * Settings in the app's look, built in code but with the same view ids the screen's logic uses
 * (SettingsActivity only finds views by id): a profile panel on the left (avatar orb, name, plan, status, expiry,
 * screens in use with a bar, VPN state, refresh) and on the right the settings as glass tiles in groups, each with
 * a coloured icon, a title, a line about what it does and a switch or an arrow. A section rail on the far left
 * jumps between the groups.
 */
class SettingsUi(private val act: AppCompatActivity) {
    private val res = act.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = res.displayMetrics.density * uiScale
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val cyan = Color.parseColor("#22D3EE")
    private val live = Color.parseColor("#E5484D")

    private lateinit var scroll: ScrollView
    private lateinit var groups: LinearLayout
    private val sectionViews = LinkedHashMap<String, View>()

    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(act).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(act, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    private fun focusable(v: View, radius: Float, scale: Float = 1.02f, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val r = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
            if (has) view.post { view.requestRectangleOnScreen(android.graphics.Rect(-dp(20), -dp(20), view.width + dp(20), view.height + dp(20)), false) }
        }
    }

    fun build(): ViewGroup {
        val root = FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF15112E"), bg, bg))
        }
        val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), 0) }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            page.setPadding(dp(48) + b.left, dp(24) + b.top, dp(48) + b.right, b.bottom); ins
        }

        // header
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(act).apply {
            id = R.id.settingsBack; setImageResource(R.drawable.ic_back); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Back"
            background = shape(0x1AFFFFFF, 21f); focusable(this, 21f, 1.08f)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        words.addView(text("Settings", 22f, textMain, 800))
        words.addView(text("Your account, playback and the app", 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageView(act).apply {
            id = R.id.btnMore; setImageResource(R.drawable.ic_more_vert); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Menu"
            background = shape(0x1AFFFFFF, 21f); focusable(this, 21f, 1.08f)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        page.addView(head)

        val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        body.addView(profile(), LinearLayout.LayoutParams(dp(300), -1).apply { topMargin = dp(18); bottomMargin = dp(18) })

        groups = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(8), 0, dp(8), dp(40)) }
        scroll = ScrollView(act).apply { id = R.id.settingsScroll; isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24)); addView(groups) }
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(20) })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        group("App & data") {
            row(R.id.manageCategories, R.drawable.ic_list, "#7C5CFF", "Manage categories", "Choose which live categories you see", null)
            row(R.id.parentalLock, R.drawable.ic_lock, "#E5484D", "Parental lock", "Lock categories (like Adults Only) with a PIN", null, subId = R.id.parentalLockStatus)
            row(R.id.autoRefresh, R.drawable.ic_sync, "#22D3EE", "Auto update channels & guide", "", null, subId = R.id.autoRefreshSummary)
            row(R.id.forceRefresh, R.drawable.ic_clear_cache, "#F5B841", "Fresh start", "Clear recently watched, recent searches and temporary files", null, arrow = false)
        }
        group("Playback") {
            row(R.id.autoReconnect, R.drawable.ic_tv, "#7C5CFF", "Auto reconnect", "", null, subId = R.id.autoReconnectSummary)
            row(R.id.autoVolume, R.drawable.ic_auto_volume, "#22D3EE", "Auto volume leveling", "Keeps every channel at the same volume", R.id.autoVolumeSwitch)
            row(R.id.softwareDecoding, R.drawable.ic_decoder, "#F5B841", "Software decoding", "Try it if a channel shows green or broken video · off again when the app closes", R.id.softwareDecodingSwitch)
        }
        group("Special features") {
            row(R.id.vpnTunnel, R.drawable.ic_vpn, "#3DD68C", "Virtual Private Network", "Off", R.id.vpnTunnelSwitch, subId = R.id.vpnTunnelSummary)
            row(R.id.aiAssistant, R.drawable.ic_ai, "#A894FF", "AI support assistant", "AI button in the full-screen player: fix channels, find shows", R.id.aiAssistantSwitch)
        }
        group("About") {
            row(R.id.aboutDeviceInfo, R.drawable.ic_info, "#9BA1AD", "About this device", "App, device and system details", null)
            row(R.id.checkForUpdates, R.drawable.ic_download, "#7C5CFF", "Check for updates", "Check if a newer app version is available", null, subId = R.id.checkForUpdatesSummary, arrow = false)
        }
        group("Sign out") {
            row(R.id.logout, R.drawable.ic_logout, "#E5484D", "Log out", "Sign out of this device", null, arrow = false, danger = true)
            row(R.id.exitApp, R.drawable.ic_close, "#9BA1AD", "Exit app", "Close Network24 and keep your login", null, arrow = false)
        }
        groups.addView(text("", 11f, textSub, 600).apply { id = R.id.appVersion; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        return root
    }

    // ------------------------------------------------------------------------------------------------ profile
    private fun profile() = LinearLayout(act).apply {
        id = R.id.accountCard
        orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(18))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF241D4A"), surface)).apply { cornerRadius = dpf(20f); setStroke(dp(1), 0x337C5CFF) }

        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("N", 26f, Color.WHITE, 800).apply {
            id = R.id.accountAvatar; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, cyan)).apply { shape = GradientDrawable.OVAL }
        }, LinearLayout.LayoutParams(dp(62), dp(62)))
        top.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ImageButton(act).apply {
            id = R.id.btnRefreshAccount; setImageResource(R.drawable.ic_refresh); setColorFilter(textMain); contentDescription = "Refresh account"
            setPadding(dp(9), dp(9), dp(9), dp(9)); scaleType = ImageView.ScaleType.FIT_CENTER; background = shape(0x1AFFFFFF, 19f); focusable(this, 19f, 1.08f)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(top)
        addView(text("", 20f, textMain, 800).apply { id = R.id.accountName; setPadding(0, dp(14), 0, 0) })
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        chips.addView(text("", 12f, Color.parseColor("#A894FF"), 700).apply { id = R.id.accountPlan })
        chips.addView(text("", 11f, textMain, 800).apply { id = R.id.accountStatusChip; setPadding(dp(8), dp(3), dp(8), dp(3)); background = shape(Color.WHITE, 10f) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addView(chips)

        addView(stat(R.id.expiryTile, "PLAN ENDS", R.id.expiryValue, R.id.expirySub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val conn = stat(R.id.connectionsTile, "SCREENS IN USE", R.id.connectionsValue, R.id.connectionsSub)
        conn.addView(LinearProgressIndicator(act).apply {
            id = R.id.connectionsBar; max = 100; trackCornerRadius = dp(3); trackThickness = dp(5)
            setIndicatorColor(accent, cyan); trackColor = 0x26FFFFFF
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(conn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(stat(R.id.relayTile, "VPN", R.id.relayValue, R.id.relaySub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        addView(text("", 11f, textSub, 600, lines = 2).apply { id = R.id.accountUpdated })
    }

    private fun stat(boxId: Int, label: String, valueId: Int, subId: Int) = LinearLayout(act).apply {
        id = boxId; orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(0x14FFFFFF, 12f)
        addView(text(label, 10f, textSub, 800).apply { letterSpacing = 0.14f })
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, dp(5), 0, 0) }
        r.addView(text("", 16f, textMain, 800).apply { id = valueId; fontFeatureSettings = "tnum" })
        r.addView(text("", 11f, textSub, 600).apply { id = subId; setPadding(dp(8), 0, 0, dp(1)) })
        addView(r)
    }

    // ------------------------------------------------------------------------------------------------ groups and rows
    private var currentGroup: LinearLayout? = null

    private fun group(title: String, rows: () -> Unit) {
        val g = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        g.addView(text(title.uppercase(), 11f, Color.parseColor("#A894FF"), 800).apply { letterSpacing = 0.16f; setPadding(dp(4), dp(20), 0, dp(8)) })
        currentGroup = g
        rows()
        groups.addView(g)
        sectionViews[title] = g
    }

    private fun row(id: Int, icon: Int, color: String, title: String, sub: String, switchId: Int?, subId: Int? = null, arrow: Boolean = true, danger: Boolean = false) {
        val c = Color.parseColor(color)
        val r = LinearLayout(act).apply {
            this.id = id; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(16), dp(12))
            background = shape(surface, 14f, line)
            focusable(this, 14f, 1.02f, if (danger) live else Color.WHITE)
        }
        r.addView(FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf((c and 0x00FFFFFF) or 0x44000000, (c and 0x00FFFFFF) or 0x14000000)).apply { cornerRadius = dpf(11f) }
            addView(ImageView(act).apply { setImageResource(icon); setColorFilter(c) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(12), 0) }
        col.addView(text(title, 15f, if (danger) Color.parseColor("#FF8A8E") else textMain, 700))
        col.addView(text(sub, 12f, textSub, 500, lines = 2).apply { subId?.let { this.id = it }; setPadding(0, dp(4), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (switchId != null) {
            r.addView(SwitchMaterial(act).apply {
                this.id = switchId; isFocusable = false; isClickable = false
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#C9CDD4")))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0x40FFFFFF))
            })
        } else if (arrow) {
            r.addView(text("›", 22f, textSub, 600))
        }
        currentGroup?.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    /** UP / DOWN only within the settings list; LEFT from a row goes to the profile's refresh. */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val f = act.currentFocus ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isInside(f, groups)) { act.findViewById<View>(R.id.btnRefreshAccount)?.requestFocus(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && f.id == R.id.btnRefreshAccount) { firstRow()?.requestFocus(); return true }
        return false
    }

    fun firstRow(): View? = act.findViewById(R.id.manageCategories)

    private fun isInside(v: View, g: View): Boolean { var p: Any? = v; while (p is View) { if (p === g) return true; p = p.parent }; return false }
}
