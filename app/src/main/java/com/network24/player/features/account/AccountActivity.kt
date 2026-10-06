package com.network24.player.features.account

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.network24.player.core.api.Web24Api
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.discover.FeatureListActivity
import com.network24.player.features.discover.Fmt
import com.network24.player.features.discover.Row
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Account & Connections: plan, expiry, the connections playing right now (stop one), plans and renewal. */
class AccountActivity : FeatureListActivity() {
    private val prefs by lazy { PreferenceManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Account & Connections", "A connection is one channel playing at a time. You can be signed in on many devices; stopping a connection frees it at once.")
        action("Refresh") { load() }
        load()
    }

    private fun load() {
        loading(true)
        lifecycleScope.launch {
            val api = Web24Api(this@AccountActivity)
            val d = runCatching { api.support("connections") }
            loading(false)
            val rows = mutableListOf<Row>()
            val exp = prefs.getExpiry() * 1000
            val days = if (exp > 0) com.network24.player.core.util.ExpiryDays.from(exp).toInt() else null
            rows += Row("plan", prefs.getUsername(), (if (prefs.isTrial()) "Trial" else "Premium") + " · ${prefs.getMaxConnections()} connection" + (if (prefs.getMaxConnections() == 1) "" else "s"),
                if (exp > 0) "Active until ${Fmt.date(exp)}" + (days?.let { " · $it days left" } ?: "") else "", showIcon = false, lead = "PLAN",
                badge = "Renew", badgeColor = if ((days ?: 99) <= 7) Color.parseColor("#FFC107") else 0) { PaymentQr.show(this@AccountActivity) }
            d.onSuccess { j ->
                val list = Web24Api.objects(j.optJSONArray("connections"))
                rows += Row("count", "${list.size} of ${j.optInt("max_connections", prefs.getMaxConnections())} connections playing now", if (list.isEmpty()) "Nothing is playing on your account right now" else "Select one below to stop it", showIcon = false, lead = "NOW")
                list.forEach { c ->
                    val mins = ((System.currentTimeMillis() / 1000 - c.optLong("since")) / 60).coerceAtLeast(0)
                    rows += Row("c" + c.optString("activity_id"), c.optString("stream_name").ifBlank { "Channel " + c.optString("stream_id") },
                        "${device(c.optString("user_agent"))} · ${if (c.optBoolean("this_network")) "this internet connection" else "network " + c.optString("ip")}",
                        "Playing for " + if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min", showIcon = false, lead = "▶", badge = "Stop", badgeColor = Color.parseColor("#FF5252")) {
                        showConfirmDialog("Stop this connection?", "The device watching ${c.optString("stream_name")} stops playing.", "Stop", onPositive = {
                            lifecycleScope.launch {
                                runCatching { api.support("connection_stop", "activity_id" to c.optString("activity_id")) }
                                    .onSuccess { toast("Connection stopped"); delay(1200); load() }.onFailure { toast(it.message ?: "Could not stop it") }
                            }
                        })
                    }
                }
            }.onFailure { rows += Row("err", "Connections could not be loaded", it.message.orEmpty(), showIcon = false) }
            rows += Row("p1", "1 connection", "Watch on 1 device at a time", showIcon = false, lead = "$75", badge = "per year")
            rows += Row("p2", "2 connections", "Watch on 2 devices at a time", showIcon = false, lead = "$120", badge = "per year")
            rows += Row("p3", "Extra connection", "Added to the \$75 or \$120 plan, same login (not for trials)", showIcon = false, lead = "+$40", badge = "per year")
            rows += Row("pay", "Pay by card", "Scan the QR code with your phone - renewals cost the same", showIcon = false, lead = "💳", badge = "Open") { PaymentQr.show(this@AccountActivity) }
            rows += Row("web", "Web player", "Watch on iPhone, iPad, Mac and PC: play.web24.live", showIcon = false, lead = "🌐") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.web24.live"))) }.onFailure { toast("Open play.web24.live on another device") }
            }
            show(rows, "")
        }
    }

    private fun device(ua: String): String = when {
        Regex("N24PlayerPlayer|N24Player", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> "Network24 app"
        ua.contains("TiviMate", true) -> "TiviMate"; ua.contains("Smarters", true) -> "IPTV Smarters"
        Regex("AppleCoreMedia|iPhone|iPad").containsMatchIn(ua) -> "iPhone / iPad (web player)"
        ua.contains("Chrome/") -> "Browser"; ua.contains("Safari/") -> "Safari"; ua.contains("VLC", true) -> "VLC"
        ua.isBlank() -> "Unknown app"; else -> ua.split(' ', '/').first()
    }
}

/** The renewal QR (same as on the home screen): scan with a phone to open the payment page. */
object PaymentQr {
    const val PAYMENT_URL = "https://osterisktechnology.com/makepayment.html"
    fun show(a: Activity) {
        val size = 720
        val m = MultiFormatWriter().encode(PAYMENT_URL, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        val bmp = Bitmap.createBitmap(px, 0, size, size, size, Bitmap.Config.ARGB_8888)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(28, 8, 28, 12) }
        box.addView(TextView(a).apply { text = "Renew your subscription"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.rgb(30, 30, 30)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 4, 0, 10) })
        box.addView(TextView(a).apply { text = "Scan the code with your phone's camera and follow the payment page."; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.DKGRAY) })
        box.addView(ImageView(a).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(8, 8, 8, 12) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(a).setView(box).setNegativeButton("Close", null)
            .setPositiveButton("Open Payment Page") { _, _ -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAYMENT_URL))) } }.show()
    }
}
