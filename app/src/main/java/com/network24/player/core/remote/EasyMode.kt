package com.network24.player.core.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.R
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.core.parental.ParentalLock
import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.ChannelLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Easy Mode: for people who are not comfortable with technology (turned on from the console's Remote Help page, or
 * by the customer). One plain screen instead of the full app: the time, big numbered tiles of their own channels
 * (favourites - support sets them up remotely), OK plays, a number key plays that tile, and one big "Get help" button.
 */
object EasyMode {
    private const val PREFS = "n24_remote"

    fun isOn(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("easy_mode", false)

    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("easy_mode", on).apply()
    }

    /** The home that fits: Easy Mode home or the normal one. */
    fun goHome(act: Activity) {
        val cls = if (isOn(act)) EasyHomeActivity::class.java else DashboardActivity::class.java
        act.startActivity(Intent(act, cls).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class EasyHomeActivity : AppCompatActivity() {

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#17191F")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#B8BDC7")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")

    private var d = 1f
    private fun dp(v: Int) = (v * d).toInt()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var grid: LinearLayout
    private lateinit var hint: TextView
    private var channels: List<ChannelEntity> = emptyList()
    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!EasyMode.isOn(this)) { startActivity(Intent(this, DashboardActivity::class.java)); finish(); return }
        val m = resources.displayMetrics
        d = m.density * minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        if (!EasyMode.isOn(this)) { startActivity(Intent(this, DashboardActivity::class.java)); finish(); return }
        handler.post(tick)
        load()
    }

    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun text(s: CharSequence, size: Float, color: Int, weight: Int, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(this@EasyHomeActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun focusable(v: View, radius: Float, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val r = GradientDrawable().apply { cornerRadius = radius * d; setStroke(dp(4), ring) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
            if (has) view.post { view.requestRectangleOnScreen(android.graphics.Rect(-dp(24), -dp(24), view.width + dp(24), view.height + dp(24)), false) }
        }
    }

    private fun pill(s: String, primary: Boolean, onClick: () -> Unit) = text(s, 18f, if (primary) bg else textMain, 800).apply {
        gravity = Gravity.CENTER; setPadding(dp(26), dp(14), dp(26), dp(14))
        background = GradientDrawable().apply { setColor(if (primary) Color.WHITE else 0x26FFFFFF); cornerRadius = 28 * d }
        focusable(this, 28f, if (primary) accent else Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF1A1438"), bg, bg))
        }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(44), dp(28), dp(44), 0); clipChildren = false; clipToPadding = false }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val times = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text("", 44f, textMain, 800)
        date = text("", 18f, textSub, 600).apply { setPadding(0, dp(6), 0, 0) }
        times.addView(clock); times.addView(date)
        head.addView(times, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("Get help", true) { HelpSession.request(this) })
        head.addView(pill("Full app", false) {
            RemoteUi.confirm(this, "Leave Easy Mode?", "The full Network24 app opens, with all channels and menus. You can come back to Easy Mode from Settings.", "Open full app", "Stay here") {
                EasyMode.set(this, false); EasyMode.goHome(this)
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        page.addView(head)

        hint = text("Choose a channel and press OK", 20f, accentSoft, 700).apply { setPadding(0, dp(26), 0, dp(14)) }
        page.addView(hint)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, dp(40)) }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        scroll.addView(grid)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun load() {
        lifecycleScope.launch {
            val db = DatabaseProvider.get(this@EasyHomeActivity)
            val (list, source) = withContext(Dispatchers.IO) {
                val locked = ParentalLock.activeLockedIds(this@EasyHomeActivity)
                val favIds = runCatching { db.favoritesDao().getByType("LIVE_CHANNEL") }.getOrDefault(emptyList()).mapNotNull { it.itemId.toIntOrNull() }
                val favs = if (favIds.isEmpty()) emptyList() else db.channelDao().getByStreamIds(favIds).let { found ->
                    val by = found.associateBy { it.streamId }; favIds.reversed().mapNotNull { by[it] }
                }.filter { it.categoryId !in locked }
                if (favs.isNotEmpty()) favs to "fav" else {
                    val recent = runCatching { LiveHistoryRepository(this@EasyHomeActivity).getRecentlyWatched() }.getOrDefault(emptyList())
                        .mapNotNull { it.stream_id }
                    val rec = if (recent.isEmpty()) emptyList() else db.channelDao().getByStreamIds(recent).let { f ->
                        val by = f.associateBy { it.streamId }; recent.mapNotNull { by[it] }
                    }.filter { it.categoryId !in locked }
                    if (rec.isNotEmpty()) rec.take(18) to "recent" else db.channelDao().getAll().filter { it.categoryId !in locked }.take(12) to "any"
                }
            }
            val now = withContext(Dispatchers.IO) {
                val ids = list.mapNotNull { it.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct()
                val out = HashMap<String?, EpgEntity>()
                ids.chunked(400).forEach { part -> runCatching { db.epgDao().getNowByEpgChannelIds(part, System.currentTimeMillis()) }.getOrNull()?.forEach { if (it.epgChannelId !in out) out[it.epgChannelId] = it } }
                out
            }
            channels = list
            hint.text = when (source) {
                "fav" -> "Your channels  ·  choose one and press OK"
                "recent" -> "Channels you watched  ·  press Get help and support will set up your own list"
                else -> "Press Get help and Network24 support will set up your channels"
            }
            draw(now)
        }
    }

    private fun draw(now: Map<String?, EpgEntity>) {
        val focusedIndex = (currentFocus?.tag as? Int)
        grid.removeAllViews()
        val cols = if (resources.displayMetrics.widthPixels / d >= 800) 3 else 2
        var row: LinearLayout? = null
        var first: View? = null
        channels.forEachIndexed { i, ch ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; tag = i
                setPadding(dp(20), dp(16), dp(20), dp(18))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#2A2148"), surface)).apply { cornerRadius = 20 * d; setStroke(dp(1).coerceAtLeast(1), 0x22FFFFFF) }
                focusable(this, 20f)
                setOnClickListener { ChannelLauncher.play(this@EasyHomeActivity, channels, ch) }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(text("${i + 1}", 22f, bg, 800).apply {
                gravity = Gravity.CENTER; background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 22 * d }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_END
                load(ch.icon?.takeIf { it.isNotBlank() }) { error(R.drawable.app_logo) }
            }, LinearLayout.LayoutParams(dp(110), dp(54)))
            tile.addView(top)
            tile.addView(text(cleanName(ch.name), 24f, textMain, 800).apply { setPadding(0, dp(14), 0, 0) })
            val prog = ch.epgChannelId?.let { now[it] }?.title?.takeIf { it.isNotBlank() } ?: "Live TV"
            tile.addView(text(prog, 16f, textSub, 600).apply { setPadding(0, dp(6), 0, 0) })
            row!!.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % cols > 0) marginStart = dp(18) })
            if (i == (focusedIndex ?: 0)) first = tile
        }
        // keep the row width even when the last row is short
        val rest = (cols - channels.size % cols) % cols
        repeat(rest) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(18) }) }
        first?.post { first?.requestFocus() }
    }

    private fun cleanName(n: String?) = (n ?: "Channel").replace(Regex("^[A-Z]{2,3}(-[A-Z]+)?\\s*\\|\\s*"), "").trim()

    // number keys play that tile
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val i = keyCode - KeyEvent.KEYCODE_1
            channels.getOrNull(i)?.let { ChannelLauncher.play(this, channels, it); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
