package com.network24.player.features.live.activity

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.network24.player.R
import com.network24.player.core.base.DrawerFocusStyler
import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.settings.activity.SettingsActivity

/** Binds the existing Live right-side drawer menu to the Live With EPG screen. */
class EpgDrawerMenuBinder @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bound = false
    private var backCallback: OnBackPressedCallback? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bound) return
        val activity = context as? EpgChannelListActivity ?: return
        val drawer = activity.findViewById<DrawerLayout>(R.id.drawerLayout) ?: return
        val more = activity.findViewById<View>(R.id.btnMore) ?: return
        val nav = activity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.rightNav) ?: return

        bound = true
        more.setOnClickListener { drawer.openDrawer(GravityCompat.END) }
        DrawerFocusStyler.bind(nav)

        nav.setNavigationItemSelectedListener { item ->
            drawer.closeDrawer(GravityCompat.END)
            when (item.itemId) {
                R.id.action_home -> {
                    activity.startActivity(Intent(activity, DashboardActivity::class.java).putExtra(DashboardActivity.EXTRA_REFRESH_ACCOUNT, true))
                    activity.finish()
                    true
                }
                R.id.action_recently_watched -> {
                    activity.startActivity(Intent(activity, RecentlyWatchedActivity::class.java))
                    true
                }
                R.id.action_refresh_all -> {
                    activity.loadChannels()
                    true
                }
                R.id.action_refresh_guide -> {
                    activity.refreshGuideFromMenu()
                    true
                }
                R.id.action_master_search -> {
                    activity.startActivity(Intent(activity, MasterChannelSearchActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    true
                }
                R.id.action_exit_app -> {
                    activity.confirmExitApp()
                    true
                }
                else -> false
            }
        }

        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView.id != R.id.rightNav) return
                nav.post { nav.focusFirstFocusableDescendant() }
            }
        })

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.END)) {
                    drawer.closeDrawer(GravityCompat.END)
                } else {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
    }

    override fun onDetachedFromWindow() {
        backCallback?.remove()
        backCallback = null
        bound = false
        super.onDetachedFromWindow()
    }

}

private fun View.focusFirstFocusableDescendant(): Boolean {
    if (this is android.view.ViewGroup) {
        for (index in 0 until childCount) {
            if (getChildAt(index).focusFirstFocusableDescendant()) return true
        }
    }
    return visibility == View.VISIBLE && isEnabled && isFocusable && requestFocus()
}
