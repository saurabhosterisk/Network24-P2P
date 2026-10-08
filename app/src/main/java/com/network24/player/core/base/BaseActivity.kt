package com.network24.player.core.base

import com.network24.player.core.compat.UnsupportedDeviceGate
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.network24.player.R
import com.network24.player.core.sync.SyncManager
import com.network24.player.core.sync.SyncResult
import com.network24.player.core.diagnostics.Network24CrashReporter
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.live.activity.MasterChannelSearchActivity
import com.network24.player.features.live.activity.RecentlyWatchedActivity
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback
import com.network24.player.features.settings.activity.SettingsActivity
import kotlinx.coroutines.launch

open class BaseActivity : AppCompatActivity() {

    /** True on devices older than Android 7.0: the "not supported" screen is showing instead. */
    protected var blockedByGate = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UnsupportedDeviceGate.isUnsupported) {
            blockedByGate = true
            UnsupportedDeviceGate.show(this)
            return
        }
        // Watch Party invites reach the customer on any screen
        com.network24.player.features.chat.ChatInvites.attach(this)
        // a reminder pops up on any screen 2 minutes before it starts
        com.network24.player.features.reminders.ReminderAlert.attach(this)
        Network24CrashReporter.activityStarted(this)
        enableFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun enableFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullscreen()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideLoader()
        super.onDestroy()
    }

    protected fun setupOptionalRightDrawerMenu(
        drawerLayout: DrawerLayout?,
        navView: NavigationView?,
        onMenuClick: (Int) -> Boolean
    ) {
        if (drawerLayout == null || navView == null) return
        DrawerFocusStyler.bind(navView)
        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.END)
            onMenuClick(item.itemId)
            // Always report "not selected" - this menu is a one-shot action
            // list (Home, Settings, Exit...), not a persistent destination
            // indicator. Returning the action's own result here would let
            // NavigationView mark that item permanently "checked", and on
            // returning to this same Activity instance later, the checked
            // item's text/icon render white with no matching background -
            // invisible against the drawer's white background.
            false
        }
    }

    protected fun openRightDrawer(drawerLayout: DrawerLayout?) {
        drawerLayout?.openDrawer(GravityCompat.END)
    }

    protected fun closeRightDrawer(drawerLayout: DrawerLayout?) {
        drawerLayout?.closeDrawer(GravityCompat.END)
    }

    /**
     * Adds the same right-side menu used by the dashboard to activities whose
     * layout does not need a permanent DrawerLayout of its own.
     */
    /** The app look for the More drawer: dark panel (as the sign-in card), more room per row, Manrope text, rounded focus pill. */
    protected fun styleNavDrawer(nav: NavigationView) {
        nav.setBackgroundColor(android.graphics.Color.parseColor("#FF14161B"))
        nav.itemBackground = ContextCompat.getDrawable(this, R.drawable.bg_drawer_item)
        nav.itemVerticalPadding = dp(8)
        nav.itemHorizontalPadding = dp(24)
        nav.itemIconPadding = dp(18)
        nav.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tf = com.network24.player.features.dashboard.home.HomeFont.of(this, 700)
            fun walk(x: View) { if (x is TextView) { x.typeface = tf; x.textSize = 16f }; if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
            walk(v)
        }
    }

    protected fun setupGlobalRightDrawer(
        contentRoot: ViewGroup,
        moreButton: View
    ): DrawerLayout {
        val drawerLayout = DrawerLayout(this).apply {
            id = View.generateViewId()
        }
        drawerLayout.addView(
            contentRoot,
            DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.NO_GRAVITY }
        )

        val navView = NavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(ContextCompat.getColor(context, R.color.drawer_bg))
            itemBackground = ContextCompat.getDrawable(context, R.drawable.bg_navigation_item)
            itemIconTintList = ContextCompat.getColorStateList(context, R.color.navigation_item_content)
            itemTextColor = ContextCompat.getColorStateList(context, R.color.navigation_item_content)
            inflateMenu(R.menu.menu_live_right_drawer)
            styleNavDrawer(this)
        }
        drawerLayout.addView(
            navView,
            DrawerLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            }
        )

        moreButton.isFocusable = true
        moreButton.isClickable = true
        moreButton.setOnClickListener { openRightDrawer(drawerLayout) }
        setupOptionalRightDrawerMenu(drawerLayout, navView, ::handleGlobalMenuAction)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView === navView) {
                    DrawerFocusStyler.resetAll(navView)
                    navView.post { focusFirstFocusableDescendant(navView) }
                }
            }
        })
        registerDrawerBackHandler(drawerLayout)
        return drawerLayout
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun handleGlobalMenuAction(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_home -> {
                startActivity(
                    Intent(this, DashboardActivity::class.java)
                        .putExtra(DashboardActivity.EXTRA_REFRESH_ACCOUNT, true)
                )
                finish()
                true
            }
            R.id.action_recently_watched -> {
                startActivity(Intent(this, RecentlyWatchedActivity::class.java))
                true
            }
            R.id.action_refresh_all -> { updateEverything(); true }
            R.id.action_refresh_guide -> { updateEverything(); true }
            R.id.action_master_search -> {
                com.network24.player.features.search.SearchOverlay.show(this)
                true
            }
            R.id.action_events -> { startActivity(Intent(this, com.network24.player.features.sports.SportsActivity::class.java)); true }
            R.id.action_trending -> { startActivity(Intent(this, com.network24.player.features.discover.TrendingActivity::class.java)); true }
            R.id.action_catchup -> { startActivity(Intent(this, com.network24.player.features.catchup.CatchupActivity::class.java)); true }
            R.id.action_reminders -> { startActivity(Intent(this, com.network24.player.features.reminders.RemindersActivity::class.java)); true }
            R.id.action_account -> { com.network24.player.features.account.AccountCenter.show(this); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_exit_app -> {
                confirmExitApp()
                true
            }
            else -> false
        }
    }

    private fun refreshAllCatalogData(): Boolean {
        val prefs = PreferenceManager(this)
        if (prefs.getServer().isBlank() || prefs.getUsername().isBlank() || prefs.getPassword().isBlank()) {
            return true
        }

        runCallbackSyncWithLoader(
            loadingMessage = "Refreshing categories & channels…",
            successMessage = "Channels Updated Successfully!"
        ) { ok, fail ->
            LiveRepository(this).syncAllData(
                server = prefs.getServer(),
                username = prefs.getUsername(),
                password = prefs.getPassword(),
                callback = object : SyncCallback {
                    override fun onSuccess() = ok()
                    override fun onError(message: String) = fail("Failed to update: $message")
                    override fun onProgress(percent: Int) {
                        showLoader("Refreshing categories & channels… $percent%")
                    }
                }
            )
        }
        return true
    }

    fun confirmExitApp() {
        if (isFinishing) return
        showConfirmDialog(
            title = "Exit Network24?",
            message = "Your login and session will be kept.",
            positiveText = "Exit",
            onPositive = {
                // Close every Activity in the app task, then remove that task.
                finishAffinity()
                finishAndRemoveTask()
            }
        )
    }

    /**
     * A fully custom-drawn confirmation dialog instead of android.app.AlertDialog.
     *
     * Fire OS forcibly re-skins platform AlertDialog with its own system accent
     * color, ignoring android:alertDialogTheme and even an explicit style passed
     * to the Builder constructor - confirmed by testing both and getting the
     * identical unstyled system look either way. Every button also inherited
     * that same flat, non-focus-aware system style, so a D-pad user had no way
     * to tell which button was selected.
     *
     * This bypasses AlertDialog entirely: a plain Dialog hosting our own layout
     * (dialog_confirm.xml), transparent window background so nothing but our
     * own rounded card shows, and buttons using the exact same
     * bg_settings_action / bg_primary_action drawables (and the same
     * maroon-fill + white-outline focused look) as every other button in the
     * app - Dashboard tiles, Settings rows, fullscreen player controls - so a
     * dialog no longer looks or behaves like a different app bolted on.
     */
    /**
     * Theme_Translucent_NoTitleBar is a full-screen theme (only the
     * background is transparent) - the layout/gravity calls below make the
     * inflated card float centered at its own wrap_content size instead of
     * stretching to fill the window, and FLAG_DIM_BEHIND restores the modal
     * scrim that theme doesn't provide on its own.
     */
    private fun createFloatingDialog(view: View): Dialog {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }
        return dialog
    }

    protected fun showConfirmDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String = "Cancel",
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ) {
        if (isFinishing || isDestroyed) return

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        val dialog = createFloatingDialog(view)
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message

        val positiveView = view.findViewById<TextView>(R.id.dialogPositive)
        val negativeView = view.findViewById<TextView>(R.id.dialogNegative)
        positiveView.text = positiveText
        negativeView.text = negativeText

        positiveView.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        negativeView.setOnClickListener {
            dialog.dismiss()
            onNegative?.invoke()
        }

        // Cancel (back button / outside touch) should behave like the
        // negative action, not silently do nothing.
        dialog.setOnCancelListener {
            onNegative?.invoke()
        }

        dialog.setOnShowListener {
            negativeView.post {
                negativeView.requestFocus()
            }
        }

        dialog.show()
    }

    protected fun showChoiceDialog(
        title: String,
        items: List<String>,
        selectedIndex: Int,
        negativeText: String = "Cancel",
        onNegative: (() -> Unit)? = null,
        focusIndex: Int = selectedIndex,
        onSelect: (Int) -> Unit
    ) {
        if (isFinishing || isDestroyed) return

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_choice, null)
        val dialog = createFloatingDialog(view)
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogTitle).text = title

        val container = view.findViewById<ViewGroup>(R.id.choiceContainer)
        val rows = items.mapIndexed { index, label ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_dialog_choice, container, false)
            row.findViewById<TextView>(R.id.choiceLabel).text = label
            row.findViewById<TextView>(R.id.choiceCheck).visibility =
                if (index == selectedIndex) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                dialog.dismiss()
                onSelect(index)
            }
            container.addView(row)
            row
        }

        // ScrollView ignores android:maxHeight, so on a short landscape phone a
        // long list pushed the bottom button off screen. Cap the list to the
        // space left after the title and the button.
        val density = resources.displayMetrics.density
        val maxListHeight = minOf(
            (420 * density).toInt(),
            resources.displayMetrics.heightPixels - (230 * density).toInt()
        ).coerceAtLeast((120 * density).toInt())
        container.measure(
            View.MeasureSpec.makeMeasureSpec((552 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (container.measuredHeight > maxListHeight) {
            view.findViewById<View>(R.id.choiceScroll).layoutParams.height = maxListHeight
        }

        val negativeView = view.findViewById<TextView>(R.id.dialogNegative)
        negativeView.text = negativeText
        negativeView.setOnClickListener {
            dialog.dismiss()
            onNegative?.invoke()
        }

        dialog.setOnShowListener {
            val target = rows.getOrNull(focusIndex) ?: negativeView
            target.post { target.requestFocus() }
        }

        dialog.show()
    }

    protected fun showInfoDialog(
        title: String,
        message: String,
        buttonText: String = "Close",
        onClose: (() -> Unit)? = null
    ) {
        if (isFinishing || isDestroyed) return

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        val dialog = createFloatingDialog(view)
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message

        val positiveView = view.findViewById<TextView>(R.id.dialogPositive)
        positiveView.text = buttonText
        positiveView.setOnClickListener {
            dialog.dismiss()
            onClose?.invoke()
        }
        dialog.setOnCancelListener { onClose?.invoke() }

        dialog.setOnShowListener {
            positiveView.post { positiveView.requestFocus() }
        }

        dialog.show()
    }

    protected class ProgressDialogHandle(
        private val dialog: Dialog,
        private val messageView: TextView
    ) {
        fun setMessage(text: String) {
            messageView.text = text
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    protected fun showProgressDialog(title: String, initialMessage: String): ProgressDialogHandle {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val dialog = createFloatingDialog(view)
        dialog.setCancelable(false)

        view.findViewById<TextView>(R.id.dialogTitle).text = title
        val messageView = view.findViewById<TextView>(R.id.dialogMessage)
        messageView.text = initialMessage

        dialog.show()
        return ProgressDialogHandle(dialog, messageView)
    }

    /** Uses only public View APIs so drawer focus stays stable across Material versions. */
    protected fun focusFirstFocusableDescendant(view: android.view.View): Boolean {
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                if (focusFirstFocusableDescendant(view.getChildAt(index))) return true
            }
        }
        return view.visibility == android.view.View.VISIBLE &&
            view.isEnabled &&
            view.isFocusable &&
            view.requestFocus()
    }

    protected fun registerDrawerBackHandler(drawerLayout: DrawerLayout) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    closeRightDrawer(drawerLayout)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private var loadingPanel: com.network24.player.core.ui.LoadingPanel? = null

    internal fun showLoader(message: String = "Loading...") {
        if (isFinishing || isDestroyed) return
        (loadingPanel ?: com.network24.player.core.ui.LoadingPanel(this).also { loadingPanel = it }).show(message)
    }

    internal fun hideLoader() {
        loadingPanel?.hide()
    }

    protected val ACTION_EPG_UPDATED: String = "ACTION_EPG_UPDATED"
    private var epgReceiver: BroadcastReceiver? = null

    protected fun registerEpgRefresh(onUpdated: () -> Unit) {
        if (epgReceiver != null) return
        epgReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_EPG_UPDATED) onUpdated()
            }
        }
        ContextCompat.registerReceiver(
            this,
            epgReceiver,
            IntentFilter(ACTION_EPG_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    protected fun unregisterEpgRefresh() {
        try {
            if (epgReceiver != null) unregisterReceiver(epgReceiver)
        } catch (_: Exception) {
        } finally {
            epgReceiver = null
        }
    }

    protected fun runCallbackSyncWithLoader(
        loadingMessage: String = "Please wait…",
        successMessage: String? = null,
        start: ((() -> Unit), ((String) -> Unit)) -> Unit
    ) {
        showLoader(loadingMessage)

        val onSuccess = {
            hideLoader()
            if (!successMessage.isNullOrBlank()) {
                Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
            }
        }

        val onError: (String) -> Unit = { msg ->
            hideLoader()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        start(onSuccess, onError)
    }

    /**
     * Refreshes channel metadata before downloading XMLTV. Providers can add
     * an epg_channel_id to an existing stream without changing its stream_id;
     * retaining the old catalogue made a newly enabled EPG invisible until
     * logout/login rebuilt the catalogue.
     */
    protected open fun onTvGuideUpdated() = Unit

    /**
     * @param refreshChannelsFirst false when the caller has just refreshed the channels itself.
     * @param onDone called with true when the guide was saved, false otherwise (the caller then shows its own
     *        message instead of the generic error toast).
     */
    protected fun refreshTvGuide(
        loadingMessage: String = "Updating TV Guide… This can take a minute.",
        refreshChannelsFirst: Boolean = true,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        showLoader(loadingMessage)
        lifecycleScope.launch {
            val syncManager = SyncManager(this@BaseActivity)
            val channelsResult = if (!refreshChannelsFirst) SyncResult.Success else syncManager.syncLiveChannelsAll(force = true) { percent ->
                showLoader("Refreshing channel data… $percent%")
            }

            if (channelsResult is SyncResult.Error) {
                hideLoader()
                if (onDone != null) { onDone(false); return@launch }
                Toast.makeText(
                    this@BaseActivity,
                    "Channel data refresh failed: ${channelsResult.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val result = syncManager.syncFullEpg(
                force = true,
                onProgress = { percent -> showLoader("$loadingMessage $percent%") },
                onSaving = { percent -> showLoader("Saving TV Guide… This can take a minute. $percent%") }
            )
            hideLoader()

            when (result) {
                is SyncResult.Success -> {
                    // Channels + guide were just refreshed by hand; Auto Refresh
                    // counts its interval from here.
                    PreferenceManager(this@BaseActivity).apply { setLastDataRefreshMs(System.currentTimeMillis()); setRefreshedVersionCode(com.network24.player.BuildConfig.VERSION_CODE) }
                    Toast.makeText(this@BaseActivity, "TV Guide Updated", Toast.LENGTH_SHORT).show()
                    onTvGuideUpdated()
                    sendBroadcast(Intent(ACTION_EPG_UPDATED))
                    onDone?.invoke(true)
                }
                is SyncResult.Error -> {
                    if (onDone != null) onDone(false)
                    else Toast.makeText(this@BaseActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * The menu's "Update Channels & Guide": channels and categories first (a channel can get its guide id), then the
     * whole TV guide, shown step by step on [com.network24.player.core.ui.UpdatePanel] with a summary at the end.
     */
    /** After the update panel closes (the home puts the remote back on Watch now). */
    protected open fun onUpdateClosed() = Unit

    /**
     * @param setup first start after a login: the same panel titled "Setting up Network24", the categories are
     *        downloaded too (nothing is saved yet), and [onFinished] says whether everything was saved.
     */
    fun updateEverything(setup: Boolean = false, onFinished: ((Boolean) -> Unit)? = null) {
        val prefs = PreferenceManager(this)
        if (prefs.getServer().isBlank() || prefs.getUsername().isBlank() || prefs.getPassword().isBlank()) return
        val panel = if (setup) com.network24.player.core.ui.UpdatePanel(this,
            title = "Setting up Network24",
            intro = "Downloading your channels and the TV guide for the first time. This takes a minute - keep the app open.",
            doneTitle = "You're all set")
        else com.network24.player.core.ui.UpdatePanel(this)
        panel.setOnDismissListener { onUpdateClosed() }
        panel.show()
        lifecycleScope.launch {
            val sync = SyncManager(this@BaseActivity)
            panel.progress(0, -1)
            if (setup) {
                val cats = sync.syncLiveCategories(force = true)
                if (cats is SyncResult.Error) { panel.failed(0, cats.message); onFinished?.invoke(false); return@launch }
            }
            val ch = sync.syncLiveChannelsAll(force = true) { p -> runOnUiThread { panel.progress(0, p) } }
            if (ch is SyncResult.Error) { panel.failed(0, ch.message); onFinished?.invoke(false); return@launch }
            if (setup) { com.network24.player.core.cache.memory.MemoryCache.clearAll(); prefs.setLastSyncTime(System.currentTimeMillis()) }
            val db = com.network24.player.core.database.DatabaseProvider.get(this@BaseActivity)
            val channels = runCatching { db.channelDao().countAll() }.getOrDefault(0)
            panel.complete(0, String.format(java.util.Locale.US, "%,d channels", channels))
            panel.progress(1, -1)
            var saving = false
            val epg = sync.syncFullEpg(force = true,
                onProgress = { p -> runOnUiThread { panel.progress(1, p) } },
                onSaving = { p -> runOnUiThread { if (!saving) { saving = true; panel.complete(1, "Downloaded") }; panel.progress(2, p) } })
            if (epg is SyncResult.Error) { panel.failed(if (saving) 2 else 1, epg.message); onFinished?.invoke(false); return@launch }
            if (!saving) panel.complete(1, "Downloaded")
            val now = System.currentTimeMillis()
            val programmes = runCatching { db.epgDao().countProgramsInWindow(now, now + 7 * 86_400_000L) }.getOrDefault(0)
            panel.complete(2, String.format(java.util.Locale.US, "%,d programmes", programmes))
            prefs.setLastDataRefreshMs(now); prefs.setRefreshedVersionCode(com.network24.player.BuildConfig.VERSION_CODE)
            onTvGuideUpdated()
            sendBroadcast(Intent(ACTION_EPG_UPDATED))
            panel.success(String.format(java.util.Locale.US, "%,d channels and %,d programmes for the coming days are ready.", channels, programmes))
            onFinished?.invoke(true)
        }
    }

    /** Same choice dialog as Settings, for helpers that only hold the activity (e.g. the player's panels). */
    fun pickOne(title: String, items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) =
        showChoiceDialog(title = title, items = items, selectedIndex = selectedIndex, onSelect = onSelect)
}
