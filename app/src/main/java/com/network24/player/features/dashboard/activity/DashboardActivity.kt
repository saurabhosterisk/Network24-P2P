package com.network24.player.features.dashboard.activity

import com.network24.player.core.sync.AutoRefreshWorker
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.network24.player.core.vpn.TunnelManager
import com.wireguard.android.backend.Tunnel
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.internal.NavigationMenuView
import com.google.android.material.card.MaterialCardView
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.sync.SyncManager
import com.network24.player.core.sync.SyncResult
import com.network24.player.databinding.ActivityDashboardBinding
import com.network24.player.features.live.activity.FavoriteChannelsActivity
import com.network24.player.features.live.activity.LiveCategoryActivity
import com.network24.player.features.live.activity.MasterChannelSearchActivity
import com.network24.player.features.live.activity.RecentlyWatchedActivity
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback
import com.network24.player.features.login.activity.LoginActivity
import com.network24.player.features.login.repository.LoginRepository
import com.network24.player.features.settings.activity.SettingsActivity
import com.network24.player.features.support.activity.LiveSupportActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class DashboardActivity : BaseActivity() {
    companion object {
        const val EXTRA_REFRESH_ACCOUNT = "refresh_account_on_dashboard"
        private const val REQ_POST_NOTIFICATIONS = 9001
        private const val DISCORD_INVITE_URL = "https://discord.gg/fvPDxQK"
    }
    private lateinit var binding: ActivityDashboardBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var repository: LiveRepository
    private val handler = Handler(Looper.getMainLooper())
    private val loginRepository = LoginRepository()
    private var isAccountRefreshRunning = false
    private var isInitialSyncRunning = false
    private val clockRunnable = object : Runnable { override fun run() { val now = Date(); binding.txtClock.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now); binding.txtDate.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(now); handler.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityDashboardBinding.inflate(layoutInflater); setContentView(binding.root); registerDrawerBackHandler(binding.drawerLayout); askNotificationPermissionIfNeeded(); prefs = PreferenceManager(this); repository = LiveRepository(this)
        if (!hasCredentials()) { startActivity(Intent(this, LoginActivity::class.java)); finishAffinity(); return }
        // Easy Mode (Remote Help): the simple home instead of this one
        if (com.network24.player.core.remote.EasyMode.isOn(this)) { startActivity(Intent(this, com.network24.player.core.remote.EasyHomeActivity::class.java)); finish(); return }
        loadDashboard()
        if (intent.getBooleanExtra(EXTRA_REFRESH_ACCOUNT, false)) refreshAccountInfo()
        // Catch up if the scheduled Auto Refresh hasn't run for a whole interval.
        AutoRefreshWorker.refreshIfDue(this)
        setupDrawerAndMenu(); setClickListeners(); setupDashboardCardInteractions(); handler.post(clockRunnable); syncInitialData(false)
        setupHome()
    }

    private lateinit var home: com.network24.player.features.dashboard.home.HomeScreen
    private var homeLoadedAt = 0L
    private var accountCheckedAt = 0L

    /** The new home (live billboard + rows) in place of the old 3 x 3 grid; the old views stay (hidden) for the account / sync code. */
    private fun setupHome() {
        fun open(cls: Class<*>, extra: (Intent.() -> Unit)? = null) = startActivity(Intent(this, cls).apply { extra?.invoke(this) })
        val tabs = listOf<Pair<String, () -> Unit>>(
            "Home" to {},
            "Live TV" to { open(com.network24.player.features.livetv.LiveTvActivity::class.java) },
            "Movies" to { openCinemaPro3() },
            "Sports" to { open(com.network24.player.features.sports.SportsActivity::class.java) },
            "TV Guide" to { open(com.network24.player.features.guide.TvGuideActivity::class.java) },
            "Catch-up" to { open(com.network24.player.features.catchup.CatchupActivity::class.java) },
        )
        home = com.network24.player.features.dashboard.home.HomeScreen(this, openMenu = { openRightDrawer(binding.drawerLayout) },
            openAccount = { com.network24.player.features.account.AccountCenter.show(this) }, tabs = tabs, support = { com.network24.player.features.help.HelpCenter.show(this) })
        binding.headerCard.visibility = View.GONE; binding.menuContainer.visibility = View.GONE; binding.accountCard.visibility = View.GONE
        binding.contentRoot.setPadding(0, 0, 0, 0)
        binding.contentRoot.addView(home.root, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID; bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID; endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        })
        home.refreshAccount()
    }

    override fun onStart() { super.onStart(); if (::home.isInitialized) home.onStart() }
    override fun onStop() { if (::home.isInitialized) home.onStop(); super.onStop() }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && ::home.isInitialized) home.userActive()
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN && ::home.isInitialized
            && !binding.drawerLayout.isDrawerOpen(binding.rightNav) && home.handleBack()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onUpdateClosed() { if (::home.isInitialized) home.focusWatch() }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ::home.isInitialized) home.userActive()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) updateVpnStatus()
        // expiry / plan from the server (a renewal shows at once instead of the old "Renew" chip), at most every 2 min
        if (!isFinishing && hasCredentials() && System.currentTimeMillis() - accountCheckedAt > 120_000) { accountCheckedAt = System.currentTimeMillis(); refreshAccountInfo() }
        // rows again after watching something (Continue watching changed), at most every 20 s
        if (::home.isInitialized && System.currentTimeMillis() - homeLoadedAt > 20_000) { homeLoadedAt = System.currentTimeMillis(); home.load() }
        // recently watched + parental lock shared with play.web24.live (at most every 30 s)
        lifecycleScope.launch { com.network24.player.features.parental.WebStateRepository(this@DashboardActivity).sync() }
    }

    /**
     * Account card "VPN" row: On = the N24 VPN tunnel is up; Other VPN = another
     * app (e.g. Speedify) routes the device, which support needs to know (some
     * sites time out through it); otherwise Off. The WireGuard backend is only
     * touched when the N24 VPN is switched on, so the dashboard stays fast.
     */
    private fun updateVpnStatus() {
        val ourTunnelUp = prefs.isVpnEnabled() &&
            TunnelManager.currentState(this) == Tunnel.State.UP
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        val deviceVpn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val (text, colorRes) = when {
            ourTunnelUp -> "On" to R.color.success
            deviceVpn -> "Other VPN" to R.color.warning
            else -> "Off" to R.color.text_hint
        }
        binding.txtVpnStatus.text = text
        binding.txtVpnStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun askNotificationPermissionIfNeeded() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults) }
    private fun hasCredentials() = prefs.getServer().isNotBlank() && prefs.getUsername().isNotBlank() && prefs.getPassword().isNotBlank()
    private fun loadDashboard() { binding.txtUserName.text = prefs.getUsername(); binding.txtStatus.text = prefs.getStatus(); binding.txtPlan.text = if (prefs.isTrial()) "Trial" else "Premium"; binding.txtConnections.text = "${prefs.getActiveConnections()} / ${prefs.getMaxConnections()}"; val expiry = prefs.getExpiry(); if (expiry > 0) { val expiryDate = Date(expiry * 1000); binding.txtExpiry.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(expiryDate); val expired = expiryDate.time <= System.currentTimeMillis(); val remainingDays = com.network24.player.core.util.ExpiryDays.from(expiryDate.time); binding.txtRemaining.text = when { expired -> "Expired"; remainingDays == 0L -> "Ends today"; remainingDays == 1L -> "1 Day"; else -> "$remainingDays Days" }; binding.btnRenew.visibility = View.GONE } else { binding.txtExpiry.text = "--"; binding.txtRemaining.text = "--"; binding.btnRenew.visibility = View.GONE }; if (::home.isInitialized) home.refreshAccount() }

    private fun refreshAccountInfo() {
        if (isAccountRefreshRunning || !hasCredentials()) return

        isAccountRefreshRunning = true
        lifecycleScope.launch {
            try {
                val response = loginRepository.login(
                    server = prefs.getServer(),
                    username = prefs.getUsername(),
                    password = prefs.getPassword()
                )
                val userInfo = response.body()?.user_info
                if (response.isSuccessful && userInfo?.auth == 1) {
                    prefs.saveUserInfo(
                        username = userInfo.username ?: prefs.getUsername(),
                        status = userInfo.status ?: prefs.getStatus(),
                        expiry = userInfo.exp_date?.toLongOrNull() ?: prefs.getExpiry(),
                        activeConnections = userInfo.active_cons?.toIntOrNull() ?: prefs.getActiveConnections(),
                        maxConnections = userInfo.max_connections?.toIntOrNull() ?: prefs.getMaxConnections(),
                        isTrial = userInfo.is_trial == "1",
                        vpnPersistentAccess = userInfo.vpn_access?.let { it == "1" } ?: prefs.hasPersistentVpnAccess()
                    )
                    loadDashboard()
                    binding.txtAccountUpdated.text = "Live • Updated just now"
                } else {
                    binding.txtAccountUpdated.text = "Live • Update unavailable"
                }
            } catch (_: Exception) {
                // Keep the last known account values when the provider is temporarily unreachable.
                binding.txtAccountUpdated.text = "Live • Update unavailable"
            } finally {
                isAccountRefreshRunning = false
            }
        }
    }

    private fun setupDashboardCardInteractions() {
        val cards = listOf(binding.cardLiveTv, binding.cardFavorites, binding.cardLiveEvents, binding.cardEvents, binding.cardCatchup, binding.cardTrending, binding.cardNotification, binding.cardSupport, binding.cardSettings)
        val cardColor = ContextCompat.getColor(this, R.color.card); val focusedCardColor = ContextCompat.getColor(this, R.color.selection_surface); val density = resources.displayMetrics.density; val normalElevation = 3f * density; val focusedElevation = 7f * density; val focusedStroke = (2f * density).toInt()
        cards.forEach { card ->
            card.isFocusable = true; card.isClickable = true; card.strokeWidth = 0; card.strokeColor = Color.TRANSPARENT; card.cardElevation = normalElevation; enlargeDashboardIcons(card, 36)
            card.setOnFocusChangeListener { view, hasFocus -> val materialCard = view as MaterialCardView; if (hasFocus) { materialCard.setCardBackgroundColor(focusedCardColor); materialCard.strokeWidth = focusedStroke; materialCard.strokeColor = Color.WHITE; materialCard.cardElevation = focusedElevation } else { materialCard.setCardBackgroundColor(cardColor); materialCard.strokeWidth = 0; materialCard.strokeColor = Color.TRANSPARENT; materialCard.cardElevation = normalElevation } }
        }
    }
    private fun enlargeDashboardIcons(card: ViewGroup, sizeDp: Int) { val sizePx = (sizeDp * resources.displayMetrics.density).toInt(); for (index in 0 until card.childCount) when (val child = card.getChildAt(index)) { is ImageView -> { child.layoutParams = child.layoutParams.apply { width = sizePx; height = sizePx }; child.scaleType = ImageView.ScaleType.CENTER_INSIDE; child.requestLayout() }; is ViewGroup -> enlargeDashboardIcons(child, sizeDp) } }
    private fun setupDrawerAndMenu() {
        binding.btnMore.setOnClickListener { openRightDrawer(binding.drawerLayout) }
        setupOptionalRightDrawerMenu(binding.drawerLayout, binding.rightNav) { itemId ->
            when (itemId) {
                R.id.action_home -> { refreshAccountInfo(); closeRightDrawer(binding.drawerLayout); true }
                R.id.action_recently_watched -> { startActivity(Intent(this, RecentlyWatchedActivity::class.java)); true }
                R.id.action_refresh_all -> { updateEverything(); true }
                R.id.action_refresh_guide -> { updateEverything(); true }
                R.id.action_master_search -> { com.network24.player.features.search.SearchOverlay.show(this); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.action_exit_app -> { confirmExitApp(); true }
                R.id.action_events -> { startActivity(Intent(this, com.network24.player.features.sports.SportsActivity::class.java)); true }
                R.id.action_trending -> { startActivity(Intent(this, com.network24.player.features.discover.TrendingActivity::class.java)); true }
                R.id.action_catchup -> { startActivity(Intent(this, com.network24.player.features.catchup.CatchupActivity::class.java)); true }
                R.id.action_reminders -> { startActivity(Intent(this, com.network24.player.features.reminders.RemindersActivity::class.java)); true }
                R.id.action_account -> { com.network24.player.features.account.AccountCenter.show(this); true }
                else -> false
            }
        }
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView.id == binding.rightNav.id) {
                    binding.rightNav.post { focusFirstFocusableDescendant(binding.rightNav) }
                }
            }
        })
    }

    private fun setClickListeners() {
        binding.cardLiveTv.setOnClickListener { startActivity(Intent(this, com.network24.player.features.livetv.LiveTvActivity::class.java)) }
        binding.cardFavorites.setOnClickListener { startActivity(Intent(this, FavoriteChannelsActivity::class.java)) }
        binding.cardNotification.setOnClickListener { openCinemaPro3() }
        binding.cardSupport.setOnClickListener { com.network24.player.features.help.HelpCenter.show(this) }
        binding.cardSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.cardLiveEvents.setOnClickListener { startActivity(Intent(this, com.network24.player.features.guide.TvGuideActivity::class.java)) }
        binding.cardEvents.setOnClickListener { startActivity(Intent(this, com.network24.player.features.sports.SportsActivity::class.java)) }
        binding.cardCatchup.setOnClickListener { startActivity(Intent(this, com.network24.player.features.catchup.CatchupActivity::class.java)) }
        binding.cardTrending.setOnClickListener { startActivity(Intent(this, com.network24.player.features.discover.TrendingActivity::class.java)) }
        binding.accountCard.setOnClickListener { com.network24.player.features.account.AccountCenter.show(this) }
    }

    private fun openCinemaPro3() = com.network24.player.features.discover.CinemaPro.open(this)
    private fun showDiscordJoin() { val qrSize = 720; val matrix: BitMatrix = MultiFormatWriter().encode(DISCORD_INVITE_URL, BarcodeFormat.QR_CODE, qrSize, qrSize); val pixels = IntArray(qrSize * qrSize); for (y in 0 until qrSize) { val offset = y * qrSize; for (x in 0 until qrSize) pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE }; val qrBitmap = Bitmap.createBitmap(pixels, 0, qrSize, qrSize, qrSize, Bitmap.Config.ARGB_8888); val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(28, 8, 28, 12) }; val instruction = TextView(this).apply { text = "Join our Discord community"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.rgb(30, 30, 30)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 4, 0, 10) }; val steps = TextView(this).apply { text = "1. Open your phone's camera.\n2. Point the camera at the QR code below.\n3. Tap the link that appears on your phone.\n4. Tap Join in Discord to enter the server."; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.DKGRAY); setLineSpacing(2f, 1.05f); setPadding(8, 0, 8, 10) }; val imageView = ImageView(this).apply { setImageBitmap(qrBitmap); adjustViewBounds = true; setPadding(8, 8, 8, 12); contentDescription = "QR code to join our Discord server" }; val linkView = TextView(this).apply { text = DISCORD_INVITE_URL; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.rgb(88, 101, 242)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(8, 2, 8, 8) }; container.addView(instruction); container.addView(steps); container.addView(imageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); container.addView(linkView); AlertDialog.Builder(this).setView(container).setNegativeButton("Close", null).setPositiveButton("Open Discord") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL))) }.show() }

    private fun syncInitialData(forceRefresh: Boolean = false) {
        if (!hasCredentials() || isInitialSyncRunning) return
        if (prefs.isFirstSetupPending()) { runFirstSetup(); return }

        val lastSyncTime = prefs.getLastSyncTime()
        val currentTime = System.currentTimeMillis()
        val twentyFourHoursInMillis = 24L * 60L * 60L * 1000L
        val isFirstSync = lastSyncTime <= 0L
        val isScheduledSyncDue = isFirstSync ||
            currentTime - lastSyncTime >= twentyFourHoursInMillis

        if (!forceRefresh && !isScheduledSyncDue) {
            return
        }

        isInitialSyncRunning = true
        val refreshFullEpg = isScheduledSyncDue
        val loadingMessage = "Refreshing categories & channels…"

        runCallbackSyncWithLoader(
            loadingMessage = loadingMessage,
            successMessage = "Channels Updated Successfully!"
        ) { ok, fail ->
            repository.syncAllData(
                server = prefs.getServer(),
                username = prefs.getUsername(),
                password = prefs.getPassword(),
                callback = object : SyncCallback {
                    override fun onSuccess() {
                        isInitialSyncRunning = false
                        prefs.setLastSyncTime(System.currentTimeMillis())
                        ok()

                        if (refreshFullEpg) {
                            refreshInitialEpgInBackground()
                        }
                    }

                    override fun onError(message: String) {
                        isInitialSyncRunning = false
                        fail("Failed to update: $message")
                    }

                    override fun onProgress(percent: Int) {
                        showLoader("$loadingMessage $percent%")
                    }
                }
            )
        }
    }

    /**
     * First start after a login (new install, or logout -> login): the same as "Refresh Channels" followed by
     * "Refresh TV Guide" from the 3-dot menu, with the progress on screen. The pending flag is cleared only once
     * the TV Guide was saved, so an interrupted setup runs again the next time the app opens.
     */
    private fun runFirstSetup() {
        isInitialSyncRunning = true
        val channelsMessage = "Setting up Network24… downloading your channels"
        showLoader(channelsMessage)
        repository.syncAllData(
            server = prefs.getServer(),
            username = prefs.getUsername(),
            password = prefs.getPassword(),
            callback = object : SyncCallback {
                override fun onSuccess() {
                    runOnUiThread {
                        prefs.setLastSyncTime(System.currentTimeMillis())
                        refreshTvGuide(
                            loadingMessage = "Setting up your TV Guide… This can take a minute.",
                            refreshChannelsFirst = false
                        ) { saved ->
                            isInitialSyncRunning = false
                            if (saved) {
                                prefs.setFirstSetupPending(false)
                            } else {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    "The TV Guide could not be downloaded right now. Network24 will try again the next time you open the app, or use ⋮ → Refresh TV Guide.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        isInitialSyncRunning = false
                        hideLoader()
                        Toast.makeText(
                            this@DashboardActivity,
                            "Could not download the channels right now ($message). Network24 will try again the next time you open the app.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onProgress(percent: Int) {
                    runOnUiThread { showLoader("$channelsMessage $percent%") }
                }
            }
        )
    }

    private fun refreshInitialEpgInBackground() {
        lifecycleScope.launch {
            when (val result = SyncManager(this@DashboardActivity).syncFullEpg(force = true)) {
                SyncResult.Success -> sendBroadcast(Intent(ACTION_EPG_UPDATED))
                is SyncResult.Error -> android.util.Log.w(
                    "N24_SYNC",
                    "Initial TV Guide refresh failed: ${result.message}"
                )
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(clockRunnable); if (::home.isInitialized) home.onDestroy() }
}
