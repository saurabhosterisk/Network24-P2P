package com.network24.player.core.remote

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.network24.player.features.dashboard.home.HomeFont

/** Pop-ups and the help bar for Remote Help, in the app's look (big, plain words - many users are not technical). */
object RemoteUi {

    private const val BAR_TAG = "n24_help_bar"
    private val surface = Color.parseColor("#14161B")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#B8BDC7")
    private val accent = Color.parseColor("#7C5CFF")
    private val good = Color.parseColor("#3DD68C")

    private fun d(a: Activity) = a.resources.displayMetrics.let { m -> m.density * minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }

    private fun text(a: Activity, s: CharSequence, size: Float, color: Int, weight: Int, lines: Int = 6) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * d(a)); setTextColor(color); typeface = HomeFont.of(a, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false; setLineSpacing(0f, 1.2f)
    }

    private fun button(a: Activity, s: String, primary: Boolean, onClick: () -> Unit): TextView {
        val k = d(a)
        return text(a, s, 16f, if (primary) Color.parseColor("#08090C") else textMain, 700, 1).apply {
            gravity = Gravity.CENTER
            setPadding((22 * k).toInt(), (12 * k).toInt(), (22 * k).toInt(), (12 * k).toInt())
            background = GradientDrawable().apply { setColor(if (primary) Color.WHITE else 0x26FFFFFF); cornerRadius = 24 * k }
            isFocusable = true; isClickable = true
            val ring = GradientDrawable().apply { cornerRadius = 24 * k; setStroke((3 * k).toInt(), if (primary) accent else Color.WHITE) }
            setOnFocusChangeListener { v, has -> v.foreground = if (has) ring else null; v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(110).start() }
            setOnClickListener { onClick() }
        }
    }

    /**
     * The app's pop-up card (also used by reminders and Watch Party invites): optional [chip] (small label on top, in
     * [chipColor]) and channel [logo], a big title, the text, and buttons - the first one is the main (white) one.
     */
    fun popup(a: Activity, title: String, body: String, buttons: List<Pair<String, () -> Unit>>, onCancel: (() -> Unit)? = null,
              chip: String? = null, chipColor: Int = accent, logo: String? = null, note: String? = null) = dialog(a, title, body, buttons, onCancel, chip, chipColor, logo, note)

    private fun dialog(a: Activity, title: String, body: String, buttons: List<Pair<String, () -> Unit>>, onCancel: (() -> Unit)? = null,
                       chip: String? = null, chipColor: Int = accent, logo: String? = null, note: String? = null) {
        if (a.isFinishing || a.isDestroyed) return
        val k = d(a)
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(a).apply { setBackgroundColor(0xCC000000.toInt()) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((30 * k).toInt(), (26 * k).toInt(), (30 * k).toInt(), (24 * k).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#2A1E4A"), surface)).apply {
                cornerRadius = 20 * k; setStroke((1 * k).toInt().coerceAtLeast(1), 0x33FFFFFF)
            }
            clipChildren = false; clipToPadding = false
        }
        if (chip != null || logo != null) {
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, (14 * k).toInt()) }
            if (chip != null) top.addView(text(a, chip.uppercase(), 11.5f, chipColor, 800, 1).apply {
                letterSpacing = 0.14f; setPadding((10 * k).toInt(), (5 * k).toInt(), (10 * k).toInt(), (5 * k).toInt())
                background = GradientDrawable().apply { setColor((chipColor and 0x00FFFFFF) or 0x26000000); cornerRadius = 12 * k; setStroke((1 * k).toInt().coerceAtLeast(1), (chipColor and 0x00FFFFFF) or 0x66000000) }
            })
            top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            if (logo != null) top.addView(android.widget.ImageView(a).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_END
                coil.Coil.imageLoader(a).enqueue(coil.request.ImageRequest.Builder(a).data(logo.takeIf { it.isNotBlank() }).target(this).build())
            }, LinearLayout.LayoutParams((120 * k).toInt(), (48 * k).toInt()))
            card.addView(top)
        }
        card.addView(text(a, title, 24f, textMain, 800, 3))
        card.addView(text(a, body, 16f, textMain, 600, 3).apply { setPadding(0, (10 * k).toInt(), 0, 0); alpha = 0.92f })
        card.addView(text(a, note ?: "", 14f, textSub, 500, 2).apply { setPadding(0, (6 * k).toInt(), 0, 0); visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE })
        card.addView(View(a), LinearLayout.LayoutParams(1, (22 * k).toInt()))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; clipChildren = false; clipToPadding = false }
        var first: View? = null
        buttons.forEachIndexed { i, (label, action) ->
            val b = button(a, label, i == 0) { dlg.dismiss(); action() }
            if (i == 0) first = b
            row.addView(b, 0, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginEnd = (12 * k).toInt() })
        }
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, FrameLayout.LayoutParams((560 * k).toInt().coerceAtMost(a.resources.displayMetrics.widthPixels - (32 * k).toInt()), -2, Gravity.CENTER))
        dlg.setContentView(root)
        // the screen behind stays visible, dimmed
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.setOnCancelListener { onCancel?.invoke() }
        dlg.show()
        first?.post { first?.requestFocus() }
    }

    fun confirm(a: Activity, title: String, body: String, yes: String, no: String, onNo: (() -> Unit)? = null, onYes: () -> Unit) {
        dialog(a, title, body, listOf(yes to onYes, no to { onNo?.invoke(); Unit }), onCancel = onNo)
    }

    fun message(a: Activity, body: String) {
        dialog(a, "Message from Network24", body, listOf("OK" to {}))
    }

    /** "Your help code" / "Support is helping you" bar at the bottom of the screen (touch it on a phone to stop). */
    fun bar(a: Activity) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        val old = decor.findViewWithTag<View>(BAR_TAG)
        val label = HelpSession.barText()
        if (label == null) { old?.let { decor.removeView(it) }; return }
        val k = d(a)
        val live = HelpSession.state == "live"
        val full = label
        val tv = (old as? TextView) ?: text(a, "", 15f, textMain, 700, 1).apply {
            tag = BAR_TAG
            isFocusable = false
            setPadding((22 * k).toInt(), (10 * k).toInt(), (22 * k).toInt(), (10 * k).toInt())
            elevation = 30 * k
            setOnClickListener { v ->
                val act = v.context as? Activity ?: return@setOnClickListener
                confirm(act, "Stop remote help?", "Support will not see your screen any more.", "Stop help", "Keep helping") { HelpSession.stop(act) }
            }
            decor.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (18 * k).toInt() })
        }
        tv.text = full
        tv.background = GradientDrawable().apply { setColor(if (live) Color.parseColor("#E61B5E3A") else Color.parseColor("#E62A1E4A")); cornerRadius = 22 * k; setStroke((1.5f * k).toInt().coerceAtLeast(1), if (live) good else accent) }
        tv.bringToFront()
    }
}
