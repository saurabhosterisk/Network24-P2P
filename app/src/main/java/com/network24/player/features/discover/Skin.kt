package com.network24.player.features.discover

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.network24.player.features.dashboard.home.HomeFont

/**
 * The app look for the Game Center, team and event pages (they were built before the new design and used the old
 * grey cards and the system font): Manrope text and dark glass cards with a white focus ring.
 */
object Skin {
    val surface = Color.parseColor("#14161B")
    val line = Color.parseColor("#1FFFFFFF")
    val accentSoft = Color.parseColor("#A894FF")
    val textMain = Color.parseColor("#F2F3F5")
    val textSub = Color.parseColor("#9BA1AD")

    fun font(t: TextView, bold: Boolean) { t.typeface = HomeFont.of(t.context, if (bold) 800 else 500) }

    /** Rounded card; with [focusable] it also shows the 3 dp white ring (and a tiny grow) while it has the focus. */
    fun card(v: View, radiusDp: Int = 16, focusable: Boolean = true) {
        val d = v.resources.displayMetrics.density
        val r = radiusDp * d
        v.background = GradientDrawable().apply { setColor(surface); cornerRadius = r; setStroke(d.toInt().coerceAtLeast(1), line) }
        if (!focusable) return
        val ring = GradientDrawable().apply { cornerRadius = r - 1.5f * d; setStroke((3 * d).toInt(), Color.WHITE); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { x, has ->
            x.foreground = if (has) ring else null
            x.animate().scaleX(if (has) 1.01f else 1f).scaleY(if (has) 1.01f else 1f).setDuration(120).start()
        }
    }
}
