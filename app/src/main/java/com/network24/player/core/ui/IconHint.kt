package com.network24.player.core.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.network24.player.features.dashboard.home.HomeFont

/**
 * The name of a round top-bar icon (Search, Live Support, Account, Settings, Menu), shown in a small pill under it
 * while it has focus - on a TV remote the icon alone does not say what it does.
 */
object IconHint {
    private const val TAG = "n24_icon_hint"

    /** Call after the icon's own focus handling is set: it keeps that and adds the hint. */
    fun attach(icon: View, label: String) {
        val old = icon.onFocusChangeListener
        icon.setOnFocusChangeListener { v, has ->
            old?.onFocusChange(v, has)
            if (has) show(v, label) else hide(v)
        }
    }

    private fun show(icon: View, label: String) {
        val a = icon.context as? Activity ?: return
        val decor = a.window?.decorView as? ViewGroup ?: return
        hide(icon)
        val m = a.resources.displayMetrics
        val k = m.density * minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f)
        val pill = TextView(a).apply {
            tag = TAG
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * k)
            setTextColor(Color.parseColor("#F2F3F5"))
            typeface = HomeFont.of(a, 700)
            setPadding((10 * k).toInt(), (5 * k).toInt(), (10 * k).toInt(), (5 * k).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E614161B")); cornerRadius = 12 * k; setStroke((1 * k).toInt().coerceAtLeast(1), 0x33FFFFFF)
            }
            alpha = 0f
            elevation = 20 * k
        }
        decor.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pill.post {
            if (!icon.hasFocus() && !icon.isFocused) { decor.removeView(pill); return@post }
            val at = IntArray(2).also { icon.getLocationInWindow(it) }
            val cx = at[0] + icon.width / 2f
            val margin = 12 * k
            val top = at[1] + icon.height * 1.08f + 8 * k
            pill.translationX = (cx - pill.width / 2f).coerceIn(margin, decor.width - pill.width - margin)
            pill.translationY = top - 4 * k
            pill.animate().alpha(1f).translationY(top).setDuration(140).start()
        }
    }

    private fun hide(icon: View) {
        val decor = (icon.context as? Activity)?.window?.decorView as? ViewGroup ?: return
        for (i in decor.childCount - 1 downTo 0) {
            val c = decor.getChildAt(i)
            if (c.tag == TAG) decor.removeViewAt(i)
        }
    }
}
