package com.network24.player.features.live.activity

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.live.adapter.ManageCategoryAdapter
import com.network24.player.features.live.repository.CategorySettingsRepository
import com.network24.player.features.live.repository.LiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageCategoriesActivity : BaseActivity() {

    private lateinit var prefs: PreferenceManager
    private lateinit var repository: LiveRepository
    private lateinit var settingsRepository: CategorySettingsRepository
    private lateinit var adapter: com.network24.player.features.settings.activity.SettingsKit.CategorySwitches
    private lateinit var kit: com.network24.player.features.settings.activity.SettingsKit
    private lateinit var countText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // built in code in the app's look: the count on the left, every category with a switch on the right
        kit = com.network24.player.features.settings.activity.SettingsKit(this)
        val page = kit.page("Manage categories", "Choose which live categories you see", scrolls = false)
        page.hero.addView(kit.orb(R.drawable.ic_list))
        page.hero.addView(kit.text("SHOWN", 10f, kit.textSub, 800).apply { letterSpacing = 0.14f; setPadding(0, kit.dp(22), 0, 0) })
        countText = kit.text("…", 26f, kit.textMain, 800).apply { setPadding(0, kit.dp(6), 0, 0); fontFeatureSettings = "tnum" }
        page.hero.addView(countText)
        page.hero.addView(kit.text("Switch off the categories you never watch. They disappear from Live TV, the TV Guide, Search and the home rows on every device with your login. Switch them on again any time.", 13f, kit.textSub, 600, lines = 8).apply { setPadding(0, kit.dp(10), 0, 0); setLineSpacing(0f, 1.15f) })
        progress = kit.progressBar()
        page.content.addView(progress, android.widget.LinearLayout.LayoutParams(-1, kit.dp(4)))
        emptyText = kit.text("No categories yet.", 15f, kit.textSub, 600, lines = 3).apply { visibility = View.GONE; setPadding(kit.dp(8), kit.dp(30), 0, 0) }
        page.content.addView(emptyText)
        recycler = RecyclerView(this).apply { clipToPadding = false; setPadding(kit.dp(4), kit.dp(10), kit.dp(4), kit.dp(30)); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(kit.dp(24)) }
        page.content.addView(recycler, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(setupGlobalRightDrawer(page.root, page.menu))

        prefs = PreferenceManager(this)
        repository = LiveRepository(this)
        settingsRepository = CategorySettingsRepository(FirebaseFirestore.getInstance())

        adapter = kit.CategorySwitches("Shown", "Hidden") { c, on -> onCategoryChanged(c, on); updateCount() }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        loadCategories()
    }

    private fun loadCategories() {
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val categories = withContext(Dispatchers.IO) {
                    repository.getCategories(
                        server = prefs.getServer(),
                        username = prefs.getUsername(),
                        password = prefs.getPassword(),
                        forceRefresh = false
                    )
                }
                val disabled = withContext(Dispatchers.IO) {
                    settingsRepository.getDisabledCategoryIds(prefs.getUsername())
                }

                progress.visibility = View.GONE
                if (categories.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                } else {
                    adapter.updateList(categories, disabled)
                    updateCount()
                    recycler.post { recycler.getChildAt(0)?.requestFocus() }
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
                emptyText.text = e.message ?: "Unable to load categories"
            }
        }
    }

    private fun updateCount() { countText.text = "${adapter.onCount()} of ${adapter.itemCount}" }

    private fun onCategoryChanged(category: com.network24.player.features.live.models.LiveCategory, enabled: Boolean) {
        lifecycleScope.launch {
            try {
                settingsRepository.setCategoryEnabled(
                    prefs.getUsername(),
                    category.category_id,
                    enabled
                )
                Toast.makeText(
                    this@ManageCategoriesActivity,
                    if (enabled) "${category.category_name} enabled" else "${category.category_name} disabled",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                adapter.setEnabled(category.category_id, !enabled)
                updateCount()
                Toast.makeText(
                    this@ManageCategoriesActivity,
                    "Could not save category setting",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
