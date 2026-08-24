package com.network24.player.features.live.activity

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityLiveCategoryBinding
import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.live.adapter.CategoryAdapter
import com.network24.player.features.live.adapter.FavoriteCategoryAdapter
import com.network24.player.features.live.models.LiveCategory
import com.network24.player.features.live.repository.CategorySettingsRepository
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback
import com.network24.player.features.login.activity.LoginActivity
import com.network24.player.features.settings.activity.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class LiveCategoryActivity : BaseActivity() {

    private lateinit var binding: ActivityLiveCategoryBinding
    private lateinit var repository: LiveRepository
    private lateinit var prefs: PreferenceManager
    private lateinit var favRepo: FavoritesRepository
    private lateinit var categorySettingsRepository: CategorySettingsRepository
    private val epgMode by lazy { intent.getBooleanExtra("epg_mode", false) }

    private val allCategories = mutableListOf<LiveCategory>()
    private val favoriteCategories = mutableListOf<LiveCategory>()
    private var disabledCategoryIds: Set<String> = emptySet()

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var favoriteAdapter: FavoriteCategoryAdapter
    private var rightNav: NavigationView? = null
    private var dataInitializationStarted = false
    private var initialLoadStarted = false
    private var initialLoadCompleted = false
    private var categoryLoadInFlight = false
    private var activityStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        activityStartMs = SystemClock.elapsedRealtime()
        tracePerf("LiveCategory.superOnCreate") { super.onCreate(savedInstanceState) }
        tracePerf("LiveCategory.bindingInflate") {
            binding = ActivityLiveCategoryBinding.inflate(layoutInflater)
        }
        tracePerf("LiveCategory.setContentView") { setContentView(binding.root) }
        registerDrawerBackHandler(binding.drawerLayout)

        if (epgMode) {
            binding.txtTitle.text = "LIVE WITH EPG"
            binding.searchLayout.hint = "Search EPG Categories"
        }

        tracePerf("LiveCategory.setupDrawer") { setupDrawerAndMenu() }
        tracePerf("LiveCategory.setupRecyclerViews") { setupRecyclerViews() }
        tracePerf("LiveCategory.setupSearch") { setupSearch() }

        // Repository construction reaches Room/SharedPreferences/Firebase
        // setup. Defer that work until after the first UI traversal and do the
        // actual construction on IO; View creation remains on the main thread.
        binding.root.post { initializeDataAndLoad() }
        logPerf("LiveCategory.onCreateScheduled", SystemClock.elapsedRealtime() - activityStartMs)
    }

    override fun onResume() {
        super.onResume()
        if (dataInitializationStarted && initialLoadCompleted) loadCategoriesFromDB()
    }

    private inline fun <T> tracePerf(name: String, block: () -> T): T {
        val startMs = SystemClock.elapsedRealtime()
        Trace.beginSection("N24:$name")
        return try {
            block()
        } finally {
            Trace.endSection()
            logPerf(name, SystemClock.elapsedRealtime() - startMs)
        }
    }

    private fun logPerf(name: String, durationMs: Long) {
        Log.i(PERF_TAG, "phase=$name duration_ms=$durationMs")
    }

    private fun initializeDataAndLoad() {
        if (dataInitializationStarted || isFinishing || isDestroyed) return
        dataInitializationStarted = true
        val startMs = SystemClock.elapsedRealtime()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localPrefs = PreferenceManager(applicationContext)
                val localRepository = LiveRepository(applicationContext)
                val localDb = DatabaseProvider.get(applicationContext)
                val firestore = FirebaseFirestore.getInstance()
                val localFavRepo = FavoritesRepository(localDb.favoritesDao(), firestore)
                val localSettingsRepo = CategorySettingsRepository(firestore, localPrefs)
                withContext(Dispatchers.Main) {
                    prefs = localPrefs
                    repository = localRepository
                    favRepo = localFavRepo
                    categorySettingsRepository = localSettingsRepo
                    logPerf("LiveCategory.dataInitialization", SystemClock.elapsedRealtime() - startMs)
                    ensureInitialSyncThenLoad()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(PERF_TAG, "phase=LiveCategory.dataInitialization failed", error)
                    Toast.makeText(this@LiveCategoryActivity, error.message ?: "Initial load failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun ensureInitialSyncThenLoad() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        val startMs = SystemClock.elapsedRealtime()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categories = repository.getCategories(server = prefs.getServer(), username = prefs.getUsername(), password = prefs.getPassword(), forceRefresh = false)
                withContext(Dispatchers.Main) {
                    logPerf("LiveCategory.initialCategoryRead", SystemClock.elapsedRealtime() - startMs)
                    if (categories.isNotEmpty()) loadCategoriesFromDB() else forceRefreshData(isInitialSync = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@LiveCategoryActivity, e.message ?: "Initial load failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun loadCategoriesFromDB() {
        if (categoryLoadInFlight) return
        categoryLoadInFlight = true
        binding.edtSearch.clearFocus()
        val startMs = SystemClock.elapsedRealtime()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categories = repository.getCategories(server = prefs.getServer(), username = prefs.getUsername(), password = prefs.getPassword(), forceRefresh = false)
                val disabled = categorySettingsRepository.getDisabledCategoryIds(prefs.getUsername())
                val favoriteIds = favRepo.getFavoriteItemIds("LIVE_CATEGORY")
                withContext(Dispatchers.Main) {
                    disabledCategoryIds = disabled
                    logPerf("LiveCategory.categoryLoad", SystemClock.elapsedRealtime() - startMs)
                    updateUIWithCategories(categories, favoriteIds)
                    categoryLoadInFlight = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    categoryLoadInFlight = false
                    Toast.makeText(this@LiveCategoryActivity, e.message ?: "Unknown Error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUIWithCategories(categories: List<LiveCategory>, favoriteIds: Set<String>) {
        val startMs = SystemClock.elapsedRealtime()
        allCategories.clear()
        allCategories.addAll(categories.filterNot { disabledCategoryIds.contains(it.category_id) })
        categoryAdapter.updateList(allCategories)
        favoriteCategories.clear()
        favoriteCategories.addAll(allCategories.filter { favoriteIds.contains(it.category_id) })
        binding.txtCategoryCount.text = "${allCategories.size} Categories"
        favoriteAdapter.updateList(favoriteCategories)
        updateFavoritesSectionVisibility()
        initialLoadCompleted = true
        logPerf("LiveCategory.adapterPopulation", SystemClock.elapsedRealtime() - startMs)
        binding.rvCategories.post { binding.rvCategories.postDelayed({ binding.rvCategories.layoutManager?.findViewByPosition(0)?.requestFocus() }, 50) }
    }

    private var isRefreshing = false

    private fun forceRefreshData(isInitialSync: Boolean = false) {
        if (isRefreshing) return
        isRefreshing = true
        val msg = if (isInitialSync) "Downloading Categories for the first time…" else "Refreshing categories & channels…"
        runCallbackSyncWithLoader(loadingMessage = msg, successMessage = "Channels Updated Successfully!") { onSuccess, onError ->
            repository.syncAllData(server = prefs.getServer(), username = prefs.getUsername(), password = prefs.getPassword(), callback = object : SyncCallback {
                override fun onSuccess() {
                    lifecycleScope.launch(Dispatchers.Main) {
                        isRefreshing = false
                        prefs.setLastSyncTime(System.currentTimeMillis())
                        onSuccess()
                        loadCategoriesFromDB()
                    }
                }
                override fun onError(message: String) {
                    lifecycleScope.launch(Dispatchers.Main) { isRefreshing = false; onError("Failed to refresh: $message") }
                }
            })
        }
    }

    private fun setupDrawerAndMenu() {
        binding.btnMore.setOnClickListener {
            ensureRightDrawerInflated()
            openRightDrawer(binding.drawerLayout)
        }
    }

    private fun ensureRightDrawerInflated(): NavigationView {
        rightNav?.let { return it }
        val nav = tracePerf("LiveCategory.rightDrawerInflate") {
            binding.rightNavStub.inflate() as NavigationView
        }
        rightNav = nav
        setupOptionalRightDrawerMenu(binding.drawerLayout, nav) { itemId ->
            when (itemId) {
                R.id.action_home -> { startActivity(Intent(this, DashboardActivity::class.java)); finish(); true }
            R.id.action_recently_watched -> { startActivity(Intent(this, RecentlyWatchedActivity::class.java)); true }
                R.id.action_refresh_all -> { forceRefreshData(); true }
                R.id.action_refresh_guide -> { refreshTvGuide(); true }
                R.id.action_search_guide -> { startActivity(Intent(this, ProgramSearchActivity::class.java)); true }
                R.id.action_master_search -> { startActivity(Intent(this, MasterChannelSearchActivity::class.java)); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.action_exit_app -> { confirmExitApp(); true }
                else -> false
            }
        }
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView.id == nav.id) nav.post {
                    focusFirstFocusableDescendant(nav)
                }
            }
        })
        return nav
    }

    private fun setupRecyclerViews() {
        val columns = if (resources.configuration.smallestScreenWidthDp >= 600) 6 else 3
        binding.rvCategories.layoutManager = GridLayoutManager(this, columns)
        binding.rvFavorite.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val snapHelper = object : LinearSnapHelper() {
            override fun calculateDistanceToFinalSnap(layoutManager: RecyclerView.LayoutManager, targetView: View): IntArray { val out = IntArray(2); val viewStart = targetView.left - layoutManager.getLeftDecorationWidth(targetView); out[0] = viewStart - layoutManager.paddingLeft; out[1] = 0; return out }
            override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
                if (layoutManager !is LinearLayoutManager) return null
                val firstVisible = layoutManager.findFirstVisibleItemPosition(); val firstView = layoutManager.findViewByPosition(0)
                if (firstVisible == 0 && firstView != null) { val viewStart = firstView.left - layoutManager.getLeftDecorationWidth(firstView); val distance = abs(viewStart - layoutManager.paddingLeft); if (distance < firstView.width / 2) return null }
                var closestChild: View? = null; var closestDistance = Int.MAX_VALUE
                for (i in 0 until layoutManager.childCount) { val child = layoutManager.getChildAt(i) ?: continue; val viewStart = child.left - layoutManager.getLeftDecorationWidth(child); val distance = abs(viewStart - layoutManager.paddingLeft); if (distance < closestDistance) { closestDistance = distance; closestChild = child } }
                return closestChild
            }
        }
        snapHelper.attachToRecyclerView(binding.rvFavorite)
        categoryAdapter = CategoryAdapter(listener = { openCategory(it) }, onLongClick = { addToFavorites(it) })
        favoriteAdapter = FavoriteCategoryAdapter(columns = columns, listener = { openCategory(it) }, onLongClick = { removeFromFavorites(it) })
        binding.rvCategories.adapter = categoryAdapter
        binding.rvFavorite.adapter = favoriteAdapter
        binding.rvCategories.setHasFixedSize(true)
        binding.rvFavorite.setHasFixedSize(true)
    }

    private fun setupSearch() {
        binding.edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filter(keyword: String) {
        val filtered = allCategories.filter { it.category_name.contains(keyword, true) }
        categoryAdapter.updateList(filtered)
    }

    private fun openCategory(category: LiveCategory) {
        if (disabledCategoryIds.contains(category.category_id)) return
        if (epgMode) {
            startActivity(Intent(this, EpgChannelListActivity::class.java).apply {
                putExtra("category_id", category.category_id)
                putExtra("category_name", category.category_name)
            })
        } else {
            startActivity(Intent(this, ChannelListActivity::class.java).apply {
                putExtra("category_id", category.category_id)
                putExtra("category_name", category.category_name)
            })
        }
    }

    private fun addToFavorites(category: LiveCategory) {
        val userId = prefs.getUsername()
        lifecycleScope.launch {
            try {
                val existing = favRepo.getFavoriteItemIds("LIVE_CATEGORY")
                if (existing.contains(category.category_id)) { Toast.makeText(this@LiveCategoryActivity, "${category.category_name} already in Favorites", Toast.LENGTH_SHORT).show(); return@launch }
                favRepo.addFavorite(userId, "LIVE_CATEGORY", category.category_id)
                favoriteCategories.add(category); favoriteAdapter.updateList(favoriteCategories); updateFavoritesSectionVisibility()
                Toast.makeText(this@LiveCategoryActivity, "${category.category_name} added to Favorites", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(this@LiveCategoryActivity, "Could not save category favorite", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun removeFromFavorites(category: LiveCategory) {
        lifecycleScope.launch {
            try {
                favRepo.removeFavorite(prefs.getUsername(), "LIVE_CATEGORY", category.category_id)
                favoriteCategories.removeAll { it.category_id == category.category_id }; favoriteAdapter.updateList(favoriteCategories); updateFavoritesSectionVisibility()
                Toast.makeText(this@LiveCategoryActivity, "${category.category_name} removed from Favorites", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(this@LiveCategoryActivity, "Could not update category favorite", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateFavoritesSectionVisibility() {
        val hasFav = favoriteCategories.isNotEmpty()
        binding.favoritesSection.visibility = if (hasFav) View.VISIBLE else View.GONE
        binding.txtFavoriteCount.text = "${favoriteCategories.size} Favorites"
    }

    companion object {
        private const val PERF_TAG = "N24-PERF"
    }
}
