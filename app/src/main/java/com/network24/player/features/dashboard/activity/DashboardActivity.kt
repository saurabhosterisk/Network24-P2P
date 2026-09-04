package com.network24.player.features.dashboard.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
        private const val PAYMENT_URL = "https://osterisktechnology.com/makepayment.html"
        private const val DISCORD_INVITE_URL = "https://discord.gg/fvPDxQK"
        private const val CINEMA_PRO_3_PACKAGE = "com.infahash.fvision.cpro3"
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
        loadDashboard()
        if (intent.getBooleanExtra(EXTRA_REFRESH_ACCOUNT, false)) refreshAccountInfo()
        binding.cardLiveTv.post { binding.cardLiveTv.requestFocus() }; setupDrawerAndMenu(); setClickListeners(); setupDashboardCardInteractions(); handler.post(clockRunnable); syncInitialData(false)
    }

    private fun askNotificationPermissionIfNeeded() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults) }
    private fun hasCredentials() = prefs.getServer().isNotBlank() && prefs.getUsername().isNotBlank() && prefs.getPassword().isNotBlank()
    private fun loadDashboard() { binding.txtUserName.text = prefs.getUsername(); binding.txtStatus.text = prefs.getStatus(); binding.txtPlan.text = if (prefs.isTrial()) "Trial" else "Premium"; binding.txtConnections.text = "${prefs.getActiveConnections()} / ${prefs.getMaxConnections()}"; val expiry = prefs.getExpiry(); if (expiry > 0) { val expiryDate = Date(expiry * 1000); binding.txtExpiry.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(expiryDate); val remainingDays = TimeUnit.MILLISECONDS.toDays(expiryDate.time - System.currentTimeMillis()); binding.txtRemaining.text = if (remainingDays > 0) "$remainingDays Days" else "Expired"; binding.btnRenew.visibility = if (remainingDays <= 15) View.VISIBLE else View.GONE } else { binding.txtExpiry.text = "--"; binding.txtRemaining.text = "--"; binding.btnRenew.visibility = View.GONE } }

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
                        isTrial = userInfo.is_trial == "1"
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
        val cards = listOf(binding.cardLiveTv, binding.cardFavorites, binding.cardNotification, binding.cardSupport, binding.cardSettings, binding.cardLiveEvents)
        val cardColor = ContextCompat.getColor(this, R.color.card); val focusedCardColor = ContextCompat.getColor(this, R.color.selection_surface); val density = resources.displayMetrics.density; val normalElevation = 3f * density; val focusedElevation = 7f * density; val focusedStroke = (2f * density).toInt()
        cards.forEach { card ->
            card.isFocusable = true; card.isClickable = true; card.strokeWidth = 0; card.strokeColor = Color.TRANSPARENT; card.cardElevation = normalElevation; enlargeDashboardIcons(card, 42)
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
                R.id.action_refresh_all -> { syncInitialData(true); true }
                R.id.action_refresh_guide -> { refreshTvGuide(); true }
                R.id.action_master_search -> { startActivity(Intent(this, MasterChannelSearchActivity::class.java)); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.action_exit_app -> { confirmExitApp(); true }
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
        binding.cardLiveTv.setOnClickListener { startActivity(Intent(this, LiveCategoryActivity::class.java)) }
        binding.cardFavorites.setOnClickListener { startActivity(Intent(this, FavoriteChannelsActivity::class.java)) }
        binding.cardNotification.setOnClickListener { openCinemaPro3() }
        binding.cardSupport.setOnClickListener { showDiscordJoin() }
        binding.cardSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.cardLiveEvents.setOnClickListener { startActivity(Intent(this, LiveCategoryActivity::class.java).apply { putExtra("epg_mode", true) }) }
        binding.btnRenew.setOnClickListener { showRenewPaymentQr() }
    }

    private fun openCinemaPro3() { val launchIntent = packageManager.getLaunchIntentForPackage(CINEMA_PRO_3_PACKAGE); if (launchIntent != null) { launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(launchIntent) } catch (_: Exception) { Toast.makeText(this, "Unable to open Cinema Pro 3.", Toast.LENGTH_SHORT).show() } } else Toast.makeText(this, "Cinema Pro 3 is not installed on this device.", Toast.LENGTH_LONG).show() }
    private fun showDiscordJoin() { val qrSize = 720; val matrix: BitMatrix = MultiFormatWriter().encode(DISCORD_INVITE_URL, BarcodeFormat.QR_CODE, qrSize, qrSize); val pixels = IntArray(qrSize * qrSize); for (y in 0 until qrSize) { val offset = y * qrSize; for (x in 0 until qrSize) pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE }; val qrBitmap = Bitmap.createBitmap(pixels, 0, qrSize, qrSize, qrSize, Bitmap.Config.ARGB_8888); val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(28, 8, 28, 12) }; val instruction = TextView(this).apply { text = "Join our Discord community"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.rgb(30, 30, 30)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 4, 0, 10) }; val steps = TextView(this).apply { text = "1. Open your phone's camera.\n2. Point the camera at the QR code below.\n3. Tap the link that appears on your phone.\n4. Tap Join in Discord to enter the server."; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.DKGRAY); setLineSpacing(2f, 1.05f); setPadding(8, 0, 8, 10) }; val imageView = ImageView(this).apply { setImageBitmap(qrBitmap); adjustViewBounds = true; setPadding(8, 8, 8, 12); contentDescription = "QR code to join our Discord server" }; val linkView = TextView(this).apply { text = DISCORD_INVITE_URL; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.rgb(88, 101, 242)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(8, 2, 8, 8) }; container.addView(instruction); container.addView(steps); container.addView(imageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); container.addView(linkView); AlertDialog.Builder(this).setView(container).setNegativeButton("Close", null).setPositiveButton("Open Discord") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL))) }.show() }

    private fun showRenewPaymentQr() { val qrSize = 720; val matrix: BitMatrix = MultiFormatWriter().encode(PAYMENT_URL, BarcodeFormat.QR_CODE, qrSize, qrSize); val pixels = IntArray(qrSize * qrSize); for (y in 0 until qrSize) { val offset = y * qrSize; for (x in 0 until qrSize) pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE }; val qrBitmap = Bitmap.createBitmap(pixels, 0, qrSize, qrSize, qrSize, Bitmap.Config.ARGB_8888); val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(28, 8, 28, 12) }; val instruction = TextView(this).apply { text = "Renew your subscription in just a few steps"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.rgb(30, 30, 30)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 4, 0, 10) }; val steps = TextView(this).apply { text = "1. Open your phone's camera.\n2. Point the camera at the QR code below.\n3. Tap the link that appears on your phone.\n4. Follow the instructions on the payment page to renew your subscription."; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.DKGRAY); setLineSpacing(2f, 1.05f); setPadding(8, 0, 8, 10) }; val imageView = ImageView(this).apply { setImageBitmap(qrBitmap); adjustViewBounds = true; setPadding(8, 8, 8, 12); contentDescription = "QR code to open the subscription payment page" }; val scanHint = TextView(this).apply { text = "📱 Scan this code with another phone to open the payment page."; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.rgb(55, 55, 55)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(8, 2, 8, 8) }; container.addView(instruction); container.addView(steps); container.addView(imageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); container.addView(scanHint); AlertDialog.Builder(this).setView(container).setNegativeButton("Close", null).setPositiveButton("Open Payment Page") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAYMENT_URL))) }.show() }
    private fun syncInitialData(forceRefresh: Boolean = false) {
        if (!hasCredentials() || isInitialSyncRunning) return

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

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(clockRunnable) }
}
