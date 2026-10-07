package com.network24.player.core.remote

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.home.HomeFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sign-in without typing (login screen): the TV shows a 6-digit code and a QR. Support types the code in the console
 * (Remote Help > Sign in a TV), or the customer scans the QR with the phone and signs in there; the TV then signs
 * itself in. A code lives 10 minutes and is replaced by a new one.
 */
class TvCodePanel(private val act: AppCompatActivity, parent: ViewGroup, private val onReady: (String, String) -> Unit) {

    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val d = act.resources.displayMetrics.density
    private val codeView: TextView
    private val qr: ImageView
    private var job: Job? = null

    init {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#E6211A3D"), Color.parseColor("#E614161B"))).apply {
                cornerRadius = 22 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0x26FFFFFF)
            }
        }
        card.addView(text("NO KEYBOARD?  SIGN IN WITH A CODE", 11f, Color.parseColor("#A894FF"), 800).apply { letterSpacing = 0.12f })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (14 * d).toInt(), 0, 0) }
        qr = ImageView(act).apply {
            setPadding((5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 10 * d }
        }
        row.addView(qr, LinearLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt()))
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), 0, 0, 0) }
        codeView = text("··· ···", 30f, Color.WHITE, 800).apply { letterSpacing = 0.1f; isSingleLine = true }
        words.addView(codeView)
        words.addView(text("Call Network24 support and read them this code, or scan the QR with your phone.", 12.5f, Color.parseColor("#B8BDC7"), 500).apply {
            setPadding(0, (8 * d).toInt(), 0, 0); maxLines = 3; setLineSpacing(0f, 1.15f)
        })
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        // fixed width: the brand column is narrow and squeezed the code into two lines
        parent.addView(card, LinearLayout.LayoutParams((360 * d).toInt(), -2).apply { topMargin = (20 * d).toInt() })
    }

    private fun text(s: String, size: Float, color: Int, weight: Int) = TextView(act).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = HomeFont.of(act, weight); includeFontPadding = false
    }

    private suspend fun post(vararg f: Pair<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder(); f.forEach { form.add(it.first, it.second) }
        runCatching {
            client.newCall(Request.Builder().url(PreferenceManager.SERVER_URL + "/support_api.php").post(form.build()).build()).execute()
                .use { JSONObject(it.body?.string().orEmpty()) }
        }.getOrNull()
    }

    fun start() {
        if (job?.isActive == true) return
        job = act.lifecycleScope.launch {
            while (isActive) {
                val n = post("action" to "rm_code_new", "device_id" to RemoteAgent.deviceId(act), "model" to RemoteAgent.model())
                val code = n?.optString("code").orEmpty()
                val secret = n?.optString("secret").orEmpty()
                if (code.length != 6) { codeView.text = "······"; delay(20_000); continue }
                codeView.text = code.substring(0, 3) + " " + code.substring(3)
                qr.setImageBitmap(qrBitmap("https://play.web24.live/tv/?c=$code"))
                val until = System.currentTimeMillis() + (n!!.optInt("expires_in", 600) - 20) * 1000L
                while (isActive && System.currentTimeMillis() < until) {
                    delay(3000)
                    val p = post("action" to "rm_code_poll", "code" to code, "secret" to secret) ?: continue
                    when (p.optString("state")) {
                        "ready" -> { codeView.text = "Signing in…"; onReady(p.optString("username"), p.optString("password")); return@launch }
                        "expired" -> break
                    }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun qrBitmap(url: String): Bitmap? = runCatching {
        val m = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 300, 300)
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
    }.getOrNull()
}
