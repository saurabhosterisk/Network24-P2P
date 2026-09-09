package com.network24.player.core.base

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
            itemBackground = ContextCompat.getDrawable(context, R.drawable.bg_navigation_item)
            itemIconTintList = ContextCompat.getColorStateList(context, R.color.navigation_item_content)
            itemTextColor = ContextCompat.getColorStateList(context, R.color.navigation_item_content)
            inflateMenu(R.menu.menu_live_right_drawer)
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
            R.id.action_refresh_all -> refreshAllCatalogData()
            R.id.action_refresh_guide -> {
                refreshTvGuide()
                true
            }
            R.id.action_master_search -> {
                startActivity(Intent(this, MasterChannelSearchActivity::class.java))
                true
            }
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

        val negativeView = view.findViewById<TextView>(R.id.dialogNegative)
        negativeView.text = negativeText
        negativeView.setOnClickListener {
            dialog.dismiss()
            onNegative?.invoke()
        }

        dialog.setOnShowListener {
            val target = rows.getOrNull(selectedIndex) ?: negativeView
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

    private var loadingDialog: AlertDialog? = null

    internal fun showLoader(message: String = "Loading...") {
        if (loadingDialog == null) {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_loading, null)
            loadingDialog = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create()
            loadingDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        if (!isFinishing && loadingDialog?.isShowing == false) {
            loadingDialog?.show()
        }
        loadingDialog?.findViewById<TextView>(R.id.txtLoadingMessage)?.text = message
    }

    internal fun hideLoader() {
        if (loadingDialog != null && loadingDialog!!.isShowing) {
            loadingDialog?.dismiss()
        }
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

    protected fun refreshTvGuide(
        loadingMessage: String = "Updating TV Guide… This can take a minute."
    ) {
        showLoader(loadingMessage)
        lifecycleScope.launch {
            val syncManager = SyncManager(this@BaseActivity)
            val channelsResult = syncManager.syncLiveChannelsAll(force = true) { percent ->
                showLoader("Refreshing channel data… $percent%")
            }

            if (channelsResult is SyncResult.Error) {
                hideLoader()
                Toast.makeText(
                    this@BaseActivity,
                    "Channel data refresh failed: ${channelsResult.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val result = syncManager.syncFullEpg(force = true) { percent ->
                showLoader("$loadingMessage $percent%")
            }
            hideLoader()

            when (result) {
                is SyncResult.Success -> {
                    Toast.makeText(this@BaseActivity, "TV Guide Updated", Toast.LENGTH_SHORT).show()
                    onTvGuideUpdated()
                    sendBroadcast(Intent(ACTION_EPG_UPDATED))
                }
                is SyncResult.Error -> {
                    Toast.makeText(this@BaseActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
