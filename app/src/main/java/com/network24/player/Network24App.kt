package com.network24.player

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.compat.Network24DeviceCompatibility
import com.network24.player.core.diagnostics.Network24CrashReporter

class Network24App : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        var currentActivity: Activity? = null

        // Purane alert ko track karne ke liye taaki overlap na ho
        private var currentAlertView: View? = null

        fun showGlobalAlert(title: String, message: String) {
            val activity = currentActivity

            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                Handler(Looper.getMainLooper()).post {
                    val decorView = activity.window.decorView as ViewGroup

                    // Agar koi purana alert show ho raha hai, toh pehle usko hata do
                    currentAlertView?.let { decorView.removeView(it) }

                    // --- Custom Alert UI Banana (Bina XML ke) ---
                    val alertOverlay = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL

                        // Design: Dark background with rounded corners & red border
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#E6121212")) // 90% opaque dark background
                            cornerRadius = 16f
                            setStroke(2, Color.parseColor("#E53935")) // Red border
                        }

                        // Padding andar ki taraf
                        setPadding(40, 30, 40, 30)

                        // TV focus ko block na kare iske liye zaroori:
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }

                    // Title Text (Red color)
                    val titleView = TextView(activity).apply {
                        text = title
                        setTextColor(Color.parseColor("#FF5252"))
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                    }

                    // Message Text (White color)
                    val messageView = TextView(activity).apply {
                        text = message
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setPadding(0, 10, 0, 0)
                    }

                    alertOverlay.addView(titleView)
                    alertOverlay.addView(messageView)

                    // --- Position Set Karna (Bottom-Right) ---
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.END // END ka matlab Right
                        setMargins(0, 0, 60, 60) // Right aur Bottom se thoda margin
                    }

                    // Screen par dikhana aur animation lagana
                    alertOverlay.alpha = 0f
                    decorView.addView(alertOverlay, params)
                    alertOverlay.animate().alpha(1f).setDuration(300).start() // Fade in

                    currentAlertView = alertOverlay

                    // --- 10 Seconds baad automatically hatana ---
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Fade out animation
                        alertOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                            decorView.removeView(alertOverlay)
                            if (currentAlertView == alertOverlay) {
                                currentAlertView = null
                            }
                        }.start()
                    }, 10000) // 10000 ms = 10 seconds
                }
            }
        }
    }

    private lateinit var prefs: PreferenceManager

    private val legacyTv: Boolean by lazy { Network24DeviceCompatibility.isLegacyTv(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
        prefs = PreferenceManager(this)
        registerActivityLifecycleCallbacks(this)
        Network24CrashReporter.initialize(this, legacyTv)

        if (!legacyTv) {
            // Phones ke liye push notifications (topic) enable
            // FCM requires Google Play services. Keep this guarded as a
            // second line of defense for devices with incomplete/quirky
            // feature declarations.
            try {
                FirebaseMessaging.getInstance().subscribeToTopic("channel_down_alerts")
                    .addOnSuccessListener { Log.d("FCM", "Subscribed: channel_down_alerts") }
                    .addOnFailureListener { e -> Log.e("FCM", "Subscribe failed", e) }
            } catch (error: Throwable) {
                Log.w("FCM", "Push notifications unavailable on this device", error)
            }
        } else {
            Log.i("Network24App", "TV device detected; using TV-safe startup and skipping mobile push setup")
        }

        if (!legacyTv) initializeApp()
    }

    private fun initializeApp() {
        // Future setups (Crashlytics, Theme, etc.)
        listenForChannelDownAlerts()
    }

    private fun listenForChannelDownAlerts() {
        val db = FirebaseFirestore.getInstance()
        val appStartTime = java.util.Date()

        db.collection("rooms")
            .document("channel_down")
            .collection("messages")
            .whereGreaterThan("ts", appStartTime)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("Network24App", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null && !snapshots.isEmpty) {
                    for (dc in snapshots.documentChanges) {
                        if (dc.type == DocumentChange.Type.ADDED) {
                            val doc = dc.document
                            val messageText = doc.getString("text") ?: "No message"
                            val senderId = doc.getString("senderId") ?: ""

                            val currentSenderId = getMySenderId()
                            if (senderId != currentSenderId) {
                                showGlobalAlert("🚨 Channel Down Alert", messageText)
                            }
                        }
                    }
                }
            }
    }

    private fun getMySenderId(): String {
        val senderName = prefs.getUsername().trim().ifEmpty { "guest" }
        val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "device"
        return senderName.lowercase().replace(" ", "_") + "_" + deviceId.takeLast(6)
    }

    // --- Activity Lifecycle Tracking ---
    override fun onActivityResumed(activity: Activity) { currentActivity = activity }
    override fun onActivityStarted(activity: Activity) { currentActivity = activity }
    override fun onActivityPaused(activity: Activity) {
        if (currentActivity == activity) currentActivity = null
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
