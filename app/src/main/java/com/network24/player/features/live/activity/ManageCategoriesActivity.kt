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
    private lateinit var adapter: ManageCategoryAdapter
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentRoot = layoutInflater.inflate(
            R.layout.activity_manage_categories,
            null,
            false
        ) as ViewGroup
        setContentView(
            setupGlobalRightDrawer(
                contentRoot,
                contentRoot.findViewById(R.id.btnMore)
            )
        )

        prefs = PreferenceManager(this)
        repository = LiveRepository(this)
        settingsRepository = CategorySettingsRepository(FirebaseFirestore.getInstance())

        findViewById<View>(R.id.manageCategoriesBack).setOnClickListener { finish() }
        recycler = findViewById(R.id.rvManageCategories)
        progress = findViewById(R.id.progressManageCategories)
        emptyText = findViewById(R.id.txtManageCategoriesEmpty)

        adapter = ManageCategoryAdapter(onChanged = ::onCategoryChanged)
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
                    recycler.post { recycler.getChildAt(0)?.requestFocus() }
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
                emptyText.text = e.message ?: "Unable to load categories"
            }
        }
    }

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
                Toast.makeText(
                    this@ManageCategoriesActivity,
                    "Could not save category setting",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
