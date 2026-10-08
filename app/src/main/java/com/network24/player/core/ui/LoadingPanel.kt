package com.network24.player.core.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.network24.player.R
import com.network24.player.features.dashboard.home.HomeFont

/**
 * "Please wait" card in the app's look (it replaced a white Android dialog): logo, a headline and a detail line,
 * a gradient progress bar with the percent, and - while channels / the TV Guide are downloaded - the steps
 * Channels -> TV Guide -> Ready. Callers keep passing one text like "Setting up your TV Guide… This can take a
 * minute. 42%"; the percent at the end drives the bar.
 */
class LoadingPanel(private val a: Activity) {
    private val k = a.resources.displayMetrics.let { m -> m.density * minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val cyan = Color.parseColor("#22D3EE")
    private val good = Color.parseColor("#3DD68C")

    private val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val headline: TextView
    private val detail: TextView
    private val percent: TextView
    private val bar = Bar(a)
    private val steps = LinearLayout(a)
    private val stepViews = ArrayList<TextView>()

    val isShowing get() = dialog.isShowing

    init {
        val root = FrameLayout(a).apply { setBackgroundColor(0xD9050608.toInt()) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(30), px(26), px(30), px(26))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#2A1E4A"), Color.parseColor("#14161B"))).apply {
                cornerRadius = 22 * k; setStroke(px(1).coerceAtLeast(1), 0x33FFFFFF)
            }
        }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(a).apply { setImageResource(R.drawable.app_logo_wide); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }, LinearLayout.LayoutParams(-2, px(32)))
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        percent = text("", 28f, textMain, 800)
        top.addView(percent)
        card.addView(top)

        headline = text("", 22f, textMain, 800, 2).apply { setPadding(0, px(24), 0, 0) }
        card.addView(headline)
        detail = text("", 14f, textSub, 500, 2).apply { setPadding(0, px(6), 0, 0) }
        card.addView(detail)
        card.addView(bar, LinearLayout.LayoutParams(-1, px(6)).apply { topMargin = px(20) })

        steps.orientation = LinearLayout.HORIZONTAL
        steps.gravity = Gravity.CENTER_VERTICAL
        listOf("Channels", "TV Guide", "Ready").forEachIndexed { i, s ->
            if (i > 0) steps.addView(View(a).apply { setBackgroundColor(0x26FFFFFF) }, LinearLayout.LayoutParams(px(22), px(1)).apply { marginStart = px(8); marginEnd = px(8) })
            val t = text(s, 12f, textSub, 700, 1).apply { setPadding(px(10), px(5), px(10), px(5)) }
            stepViews += t
            steps.addView(t)
        }
        card.addView(steps, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(18) })

        val w = minOf(px(520), a.resources.displayMetrics.widthPixels - px(32))
        root.addView(card, FrameLayout.LayoutParams(w, -2, Gravity.CENTER))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
    }

    fun show(message: String) {
        val m = Regex("\\s*(\\d{1,3})%\\s*$").find(message)
        val pct = m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        val words = (if (m != null) message.substring(0, m.range.first) else message).trim()
        // "Setting up your TV Guide… This can take a minute." -> headline + detail
        val cut = Regex("(…|\\.\\.\\.|\\.)\\s+").find(words)
        val head = if (cut != null) words.substring(0, cut.range.first) + cut.groupValues[1].let { if (it == ".") "" else "…" } else words
        val rest = if (cut != null) words.substring(cut.range.last + 1).trim() else ""
        headline.text = head
        detail.text = rest.replaceFirstChar { it.uppercaseChar() }
        detail.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        percent.text = pct?.let { "$it%" } ?: ""
        bar.set(pct)

        val low = words.lowercase()
        // the steps only while the catalogue / guide is downloaded, not for a quick "Loading channels…"
        val sync = "setting up" in low || "refresh" in low || "guide" in low || "download" in low
        val step = when {
            !sync -> -1
            "guide" in low -> 1
            else -> 0
        }
        steps.visibility = if (step < 0) View.GONE else View.VISIBLE
        stepViews.forEachIndexed { i, t ->
            val (fg, bg, label) = when {
                i < step -> Triple(good, 0x1F3DD68C, "✓  " + t.text.toString().removePrefix("✓  "))
                i == step -> Triple(textMain, (accent and 0x00FFFFFF) or 0x40000000, t.text.toString().removePrefix("✓  "))
                else -> Triple(textSub, 0x0FFFFFFF, t.text.toString().removePrefix("✓  "))
            }
            t.text = label; t.setTextColor(fg)
            t.background = GradientDrawable().apply { setColor(bg); cornerRadius = 12 * k }
        }
        if (!dialog.isShowing && !a.isFinishing && !a.isDestroyed) dialog.show()
    }

    fun hide() {
        bar.stop()
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }

    private fun px(v: Int) = (v * k).toInt()

    private fun text(s: String, size: Float, color: Int, weight: Int, lines: Int = 1) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color); typeface = HomeFont.of(a, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false; setLineSpacing(0f, 1.15f)
    }

    /** Rounded track with a violet -> cyan fill; without a percent a short glowing piece runs across. */
    private inner class Bar(c: Context) : View(c) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var value = 0f
        private var target: Int? = null
        private var run = 0f
        private var anim: ValueAnimator? = null

        fun set(p: Int?) {
            target = p
            anim?.cancel()
            anim = if (p == null) ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { run = it.animatedValue as Float; invalidate() }
                start()
            } else ValueAnimator.ofFloat(value, p / 100f).apply {
                duration = 350
                addUpdateListener { value = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() { anim?.cancel(); anim = null }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            r.set(0f, 0f, w, h); canvas.drawRoundRect(r, h / 2, h / 2, track)
            val (from, to) = if (target == null) { val len = w * 0.28f; val x = -len + (w + len) * run; x.coerceAtLeast(0f) to (x + len).coerceAtMost(w) } else 0f to w * value
            if (to - from < 1f) return
            fill.shader = LinearGradient(from, 0f, to, 0f, accent, cyan, Shader.TileMode.CLAMP)
            r.set(from, 0f, to, h); canvas.drawRoundRect(r, h / 2, h / 2, fill)
        }
    }
}
