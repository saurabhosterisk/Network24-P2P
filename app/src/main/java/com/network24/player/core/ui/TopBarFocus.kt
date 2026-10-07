package com.network24.player.core.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.View

/**
 * UP from a page into the top bar lands on the page's own tab (TV Guide, Live TV...), not on whatever sits
 * straight above (it was always "Home" from the chips at the left).
 */
object TopBarFocus {
    fun up(activity: Activity, hereTab: View?, event: KeyEvent): Boolean {
        if (hereTab == null || event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_DPAD_UP) return false
        val bar = (hereTab.parent?.parent?.parent as? View) ?: return false
        val f = activity.currentFocus ?: return false
        if (inside(f, bar)) return false
        val next = f.focusSearch(View.FOCUS_UP) ?: return false
        if (!inside(next, bar)) return false
        return hereTab.requestFocus()
    }

    private fun inside(v: View, group: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === group) return true; p = p.parent }
        return false
    }
}
