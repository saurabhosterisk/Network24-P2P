package com.network24.player.core.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.network24.player.R
import com.network24.player.features.dashboard.home.HomeFont

/**
 * "Update Channels & Guide" in the app's look: a card with the three steps (channels and categories, downloading
 * the TV guide, saving it), each with its own bar and a ✓ when done, then a summary (how many channels and
 * programmes) and Done. Back cannot cancel half-way (a half-saved guide would be worse than the old one).
 */
class UpdatePanel(
    context: Context,
    private val title: String = "Updating channels & guide",
    private val intro: String = "Getting the newest channels and the full TV guide. This can take a minute - keep the app open.",
    private val doneTitle: String = "Everything is up to date"
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val res = context.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = res.displayMetrics.density * uiScale
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val good = Color.parseColor("#3DD68C")
    private val bad = Color.parseColor("#E5484D")

    private class Step(val row: View, val mark: TextView, val title: TextView, val sub: TextView, val bar: ProgressBar)
    private val steps = ArrayList<Step>()
    private lateinit var heading: TextView
    private lateinit var note: TextView
    private lateinit var done: TextView
    private var finished = false
    private lateinit var spinner: android.widget.ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        setCancelable(false)
        val root = FrameLayout(context).apply { setBackgroundColor(0xE608090C.toInt()) }
        // measured at its full height even when taller than the screen (otherwise the last row, Done, is squeezed
        // out of the card); the listener below then scales the whole card down to fit
        val card = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(26), dp(30), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF1E1A3A"), Color.parseColor("#FF14161B"))).apply { cornerRadius = dpf(20f); setStroke(dp(1), 0x337C5CFF) }
        }
        // a turning sync mark in a violet glow
        val orb = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, Color.parseColor("#22D3EE"))).apply { shape = GradientDrawable.OVAL } }
        spinner = android.widget.ImageView(context).apply { setImageResource(R.drawable.ic_refresh); setColorFilter(Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        orb.addView(spinner, FrameLayout.LayoutParams(-1, -1))
        card.addView(orb, LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(16) })
        spinner.animate().rotationBy(360f * 400).setDuration(1_200_000L).setInterpolator(android.view.animation.LinearInterpolator()).start()
        heading = text(title, 22f, textMain, 800)
        card.addView(heading)
        note = text(intro, 13f, textSub, 600, 3).apply { minLines = 2; setPadding(0, dp(6), 0, dp(14)); setLineSpacing(0f, 1.15f) }
        card.addView(note)
        listOf("Channels & categories", "Downloading the TV guide", "Saving the TV guide").forEach { card.addView(step(it)) }
        done = text("Done", 15f, Color.parseColor("#08090C"), 800).apply {
            gravity = Gravity.CENTER; setPadding(dp(28), dp(11), dp(28), dp(11)); background = shape(Color.WHITE, 12f); visibility = View.INVISIBLE
            isFocusable = true; isClickable = true; setOnClickListener { dismiss() }
            val ring = GradientDrawable().apply { cornerRadius = dpf(12f); setStroke(dp(3), accent); setColor(Color.TRANSPARENT) }
            setOnFocusChangeListener { v, has -> v.foreground = if (has) ring else null }
        }
        card.addView(done, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); gravity = Gravity.END })
        root.addView(card, FrameLayout.LayoutParams(dp(520).coerceAtMost(res.displayMetrics.widthPixels - dp(32)), -2, Gravity.CENTER))
        // phones in landscape are shorter than the card: scale it down just enough to fit. Done is INVISIBLE (not
        // GONE) from the start so the card has one height, and one size, from the first frame to the last.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val room = root.height - dp(24)
            if (card.height <= 0 || room <= 0) return@addOnLayoutChangeListener
            if (card.scaleX != 1f) return@addOnLayoutChangeListener
            val s = minOf(1f, room.toFloat() / card.height)
            card.pivotX = card.width / 2f; card.pivotY = card.height / 2f
            if (card.scaleX != s) { card.scaleX = s; card.scaleY = s }
        }
        setContentView(root)
    }

    private fun text(s: String, size: Float, color: Int, weight: Int, lines: Int = 1) = TextView(context).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(context, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius) }

    private fun step(label: String): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)); alpha = 0.45f }
        val mark = text("${steps.size + 1}", 13f, textMain, 800).apply { gravity = Gravity.CENTER; background = shape(0x26FFFFFF, 14f) }
        row.addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val title = text(label, 15f, textMain, 700)
        val sub = text("Waiting", 12f, textSub, 600).apply { setPadding(0, dp(3), 0, 0); fontFeatureSettings = "tnum" }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressDrawable = context.getDrawable(R.drawable.home_progress) }
        col.addView(title); col.addView(sub)
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        steps += Step(row, mark, title, sub, bar)
        return row
    }

    /** Step [i] (0-2) is running at [percent] (-1 = no number yet). */
    fun progress(i: Int, percent: Int) {
        val s = steps.getOrNull(i) ?: return
        s.row.alpha = 1f
        s.mark.background = shape(accent, 14f)
        s.sub.text = if (percent >= 0) "$percent%" else "Starting…"
        s.bar.isIndeterminate = percent < 0
        if (percent >= 0) s.bar.progress = percent
    }

    fun complete(i: Int, detail: String = "Done") {
        val s = steps.getOrNull(i) ?: return
        s.row.alpha = 1f
        s.mark.text = "✓"; s.mark.background = shape(good, 14f); s.mark.setTextColor(Color.parseColor("#08090C"))
        s.sub.text = detail; s.bar.isIndeterminate = false; s.bar.progress = 100
    }

    fun success(summary: String) {
        finished = true
        heading.text = doneTitle
        note.text = summary
        showDone()
    }

    fun failed(i: Int, message: String) {
        finished = true
        steps.getOrNull(i)?.let { s -> s.mark.text = "!"; s.mark.background = shape(bad, 14f); s.sub.text = message; s.sub.maxLines = 3 }
        heading.text = "Could not finish the update"
        note.text = "Your channels and guide stay as they were. Please try again in a few minutes."
        showDone()
    }

    private fun showDone() { spinner.animate().cancel(); spinner.rotation = 0f; setCancelable(true); done.visibility = View.VISIBLE; done.post { done.requestFocus() } }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (!finished && event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
}
