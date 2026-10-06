package com.network24.player.core.base

import android.graphics.PorterDuff
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView

/**
 * Keeps NavigationView row content readable on TV devices. Material applies the
 * focus state to the row background, but not consistently to the row's text and icon.
 */
object DrawerFocusStyler {

    fun bind(navigationView: NavigationView) {
        navigationView.post {
            itemContainer(navigationView, navigationView.findFocus())?.let { updateContent(it, true) }
        }

        navigationView.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            itemContainer(navigationView, oldFocus)?.let { updateContent(it, false) }
            itemContainer(navigationView, newFocus)?.let { updateContent(it, true) }
        }

        // A menu action often opens a dialog ("Updating TV Guide...") in its own
        // window: the row never gets a "lost focus" callback and stayed white,
        // invisible on the white drawer, the next time the drawer opened. Reset
        // every row whenever this drawer opens or closes, then restyle the row
        // that really has focus.
        navigationView.post {
            drawerLayoutOf(navigationView)?.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    if (drawerView !== navigationView) return
                    resetAll(navigationView)
                    navigationView.post {
                        itemContainer(navigationView, navigationView.findFocus())?.let { updateContent(it, true) }
                    }
                }

                override fun onDrawerClosed(drawerView: View) {
                    if (drawerView === navigationView) resetAll(navigationView)
                }
            })
        }
    }

    private fun drawerLayoutOf(view: View): DrawerLayout? {
        var current = view.parent
        while (current != null) {
            if (current is DrawerLayout) return current
            current = current.parent
        }
        return null
    }

    /**
     * Forces every row back to its unfocused color. Call this each time the
     * drawer opens. A row's color is set directly (see updateContent below)
     * rather than through a state-list, so if a row's "lose focus" callback
     * is ever missed - e.g. the activity is backgrounded (not just internally
     * refocused) while a row is focused, which doesn't fire a global focus
     * change - that row would otherwise stay stuck white (invisible on the
     * white drawer background) the next time the drawer opens.
     */
    fun resetAll(navigationView: NavigationView) {
        findRecyclerView(navigationView)?.let { recyclerView ->
            for (index in 0 until recyclerView.childCount) {
                updateContent(recyclerView.getChildAt(index), focused = false)
            }
        }
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRecyclerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun itemContainer(navigationView: NavigationView, focusedView: View?): View? {
        var current = focusedView
        while (current != null && current !== navigationView) {
            if (current.parent is RecyclerView && belongsTo(navigationView, current)) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun belongsTo(container: View, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === container) return true
            current = current.parent as? View
        }
        return false
    }

    private fun updateContent(itemView: View, focused: Boolean) {
        val colorRes = if (focused) android.R.color.white else com.network24.player.R.color.drawer_item_text
        val color = ContextCompat.getColor(itemView.context, colorRes)
        updateDescendants(itemView, color)
    }

    private fun updateDescendants(view: View, color: Int) {
        when (view) {
            is TextView -> {
                view.setTextColor(color)
                (view.compoundDrawables + view.compoundDrawablesRelative)
                    .filterNotNull()
                    .forEach { drawable ->
                        DrawableCompat.setTint(drawable.mutate(), color)
                    }
            }
            is ImageView -> view.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                updateDescendants(view.getChildAt(index), color)
            }
        }
    }
}
