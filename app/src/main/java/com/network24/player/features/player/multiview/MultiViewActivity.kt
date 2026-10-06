package com.network24.player.features.player.multiview

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityMultiviewBinding
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.launch

/**
 * MultiView: up to four channels at once.
 *
 * TV remote: the window with the focus plays the sound; OK opens the window's options (change channel, big
 * window, full screen, remove); UP from the top windows reaches the control bar (Back, Channels, Layout).
 * Touch: tap = sound from that window, double tap = big window / back to the grid, hold = options, tap on an
 * empty window = channel picker. The control bar hides after a few seconds and comes back with any key or tap.
 */
class MultiViewActivity : BaseActivity(), MultiPlayerManager.Listener {
    private lateinit var binding: ActivityMultiviewBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var multiPlayer: MultiPlayerManager
    private lateinit var picker: MultiViewPicker

    private val selected = arrayOfNulls<LiveChannel>(4)
    private val failed = BooleanArray(4)
    private var focusedSlot = 0
    private var audioSlot = 0
    private var bigSlot = -1
    private val touch by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private val slots: Array<FrameLayout> by lazy { arrayOf(binding.slot1, binding.slot2, binding.slot3, binding.slot4) }
    private val labels: Array<TextView> by lazy { arrayOf(binding.label1, binding.label2, binding.label3, binding.label4) }
    private val playerViews by lazy { arrayOf(binding.playerView1, binding.playerView2, binding.playerView3, binding.playerView4) }
    private val progressBars by lazy { arrayOf(binding.progress1, binding.progress2, binding.progress3, binding.progress4) }
    private val speakers = arrayOfNulls<ImageView>(4)

    private lateinit var bar: LinearLayout
    private lateinit var barSub: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnLayout: TextView
    private lateinit var btnChannels: TextView
    /** The bar's buttons left to right; the remote moves along them with LEFT / RIGHT. */
    private val barButtons by lazy { listOf<View>(btnBack, btnChannels, btnLayout, binding.btnMore) }
    private val handler = Handler(Looper.getMainLooper())
    private val hideBar = Runnable { setBar(false) }
    private lateinit var gridSet: ConstraintSet
    private val gV = View.generateViewId(); private val gH1 = View.generateViewId(); private val gH2 = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiviewBinding.inflate(layoutInflater)
        setContentView(setupGlobalRightDrawer(binding.root, binding.btnMore))
        prefs = PreferenceManager(this)
        multiPlayer = MultiPlayerManager(this, this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildGuidelines()
        gridSet = ConstraintSet().apply { clone(binding.root) }
        buildBar()

        // the Live TV player behind MultiView would keep streaming (an extra connection) and hold the decoder
        PlayerManager.pause()
        PlayerState.currentChannel()?.let { setSlot(0, it) }

        slots.forEachIndexed { index, slot ->
            slot.foreground = getDrawable(R.drawable.bg_multiview_slot_selector)
            // the layout can change (big window), so the remote finds the neighbour by position
            slot.nextFocusUpId = View.NO_ID; slot.nextFocusDownId = View.NO_ID; slot.nextFocusLeftId = View.NO_ID; slot.nextFocusRightId = View.NO_ID
            speakers[index] = ImageView(this).apply {
                setImageResource(R.drawable.ic_speaker); setPadding(dp(5), dp(5), dp(5), dp(5)); visibility = View.GONE
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC6750A4")) }
            }
            slot.addView(speakers[index], FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(34)) })
            slot.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) { focusedSlot = index; if (!touch) setAudio(index) }
            }
            if (touch) {
                val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        showBar()
                        if (selected[index] == null) showChannelPicker(index) else setAudio(index)
                        return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean { if (selected[index] != null) toggleBig(index); return true }
                    override fun onLongPress(e: MotionEvent) { windowMenu(index) }
                })
                slot.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
            } else {
                slot.setOnClickListener { if (selected[index] == null) showChannelPicker(index) else windowMenu(index) }
            }
            if (selected[index] == null) labels[index].text = emptyText(index)
        }

        picker = MultiViewPicker(this, binding.root, { selected },
            onPick = { slot, ch -> if (ch == null) clearSlot(slot) else setSlot(slot, ch) },
            onClosed = { slot -> binding.btnMore.visibility = View.VISIBLE; showBar(); slots[slot].requestFocus() })

        binding.slot1.requestFocus()
        onBackPressedDispatcher.addCallback(this) {
            when {
                picker.isOpen -> picker.close()
                bigSlot >= 0 -> toggleBig(bigSlot)
                else -> finish()
            }
        }
        showBar()
        refreshAudio()
        // one channel playing: go straight on to choosing the others
        if (selected.count { it != null } <= 1) binding.root.post { if (!isFinishing) showChannelPicker(if (selected[0] == null) 0 else 1) }
    }

    private fun emptyText(i: Int) = "${i + 1} · " + if (touch) "Tap to add a channel" else "Press OK to add a channel"

    // ------------------------------------------------------------------------------------------------ control bar
    private fun buildBar() {
        bar = LinearLayout(this).apply {
            id = View.generateViewId(); orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(70), dp(26)); elevation = dp(18).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT))
        }
        btnBack = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE); setBackgroundResource(R.drawable.bg_back_focus)
            contentDescription = "Back"; isFocusable = true; setOnClickListener { finish() }
        }
        bar.addView(btnBack, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
        titles.addView(TextView(this).apply { text = "MultiView"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        barSub = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#CCFFFFFF")); maxLines = 1 }
        titles.addView(barSub)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        btnChannels = pill("＋  Channels") { showChannelPicker(firstEmpty() ?: focusedSlot) }
        bar.addView(btnChannels)
        btnLayout = pill("Big window") { toggleBig(if (bigSlot >= 0) bigSlot else audioSlot) }
        bar.addView(btnLayout, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(10) })
        binding.root.addView(bar, ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID; startToStart = ConstraintLayout.LayoutParams.PARENT_ID; endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })
        binding.btnMore.bringToFront()
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_outline_pill); setPadding(dp(18), 0, dp(18), 0); isFocusable = true; isClickable = true
        layoutParams = LinearLayout.LayoutParams(-2, dp(42))
        setOnClickListener { onClick() }
    }

    private fun setBar(show: Boolean) {
        handler.removeCallbacks(hideBar)
        // never hide while the remote is on one of its buttons
        if (!show && (bar.hasFocus() || binding.btnMore.isFocused)) { handler.postDelayed(hideBar, 5000); return }
        bar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnMore.visibility = if (show && !picker.isOpen) View.VISIBLE else View.GONE
        if (show) handler.postDelayed(hideBar, 5000)
    }

    private fun showBar() { if (::bar.isInitialized) setBar(true) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::picker.isInitialized && !picker.isOpen) {
            showBar()
            val focus = currentFocus
            val inBar = focus != null && barButtons.any { it === focus }
            // The bar lies over the top windows, so Android's own focus search mixed windows and buttons up:
            // UP from a top window goes to the nearest button (Back on the left, Big window on the right) ...
            val slot = slots.indexOfFirst { it === focus }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && slot >= 0 && slots[slot].top < dp(90)) {
                val right = slots[slot].left + slots[slot].width / 2 > binding.root.width / 2
                (if (right) btnLayout else btnBack).requestFocus(); return true
            }
            // ... LEFT / RIGHT walk along the buttons only ...
            if (inBar && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val i = barButtons.indexOfFirst { it === focus } + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                barButtons.getOrNull(i)?.takeIf { it.visibility == View.VISIBLE }?.requestFocus()
                return true
            }
            if (inBar && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) return true
            // ... and DOWN from the bar goes to the window right under it
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inBar) {
                val x = IntArray(2).also { currentFocus?.getLocationOnScreen(it) }[0]
                val target = if (bigSlot >= 0) bigSlot else if (x < binding.root.width / 2) 0 else 1
                slots[target].requestFocus(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) showBar()
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------------------------------------ windows
    private fun setSlot(slot: Int, channel: LiveChannel) {
        selected[slot] = channel
        failed[slot] = false
        labels[slot].text = "${slot + 1} · ${channel.name ?: "Unknown Channel"}"
        progressBars[slot].visibility = View.VISIBLE
        multiPlayer.attach(slot, playerViews[slot])
        multiPlayer.play(slot, buildStreamUrl(channel))
        if (selected.count { it != null } == 1) audioSlot = slot
        refreshAudio()
    }

    private fun clearSlot(slot: Int) {
        selected[slot] = null
        failed[slot] = false
        labels[slot].text = emptyText(slot)
        progressBars[slot].visibility = View.GONE
        multiPlayer.clear(slot)
        if (bigSlot == slot) toggleBig(slot)
        // the sound moves to another window that still plays
        if (audioSlot == slot) audioSlot = selected.indexOfFirst { it != null }.coerceAtLeast(0)
        refreshAudio()
    }

    private fun firstEmpty(): Int? = selected.indexOfFirst { it == null }.takeIf { it >= 0 }

    private fun setAudio(slot: Int) {
        if (selected[slot] == null) return
        audioSlot = slot
        refreshAudio()
    }

    private fun refreshAudio() {
        multiPlayer.setAudioFocus(audioSlot)
        speakers.forEachIndexed { i, v -> v?.visibility = if (i == audioSlot && selected[i] != null) View.VISIBLE else View.GONE }
        val ch = selected[audioSlot]
        if (::barSub.isInitialized) barSub.text = (if (ch != null) "Sound: window ${audioSlot + 1} · ${ch.name}   ·   " else "") +
            if (touch) "tap a window for its sound · double-tap = big · hold = options" else "move to a window for its sound · OK = options"
    }

    /** One window big (70 %), the other three in a column next to it; again = back to the 2 x 2 grid. */
    private fun toggleBig(slot: Int) {
        bigSlot = if (bigSlot == slot) -1 else slot
        if (bigSlot < 0) {
            gridSet.applyTo(binding.root)
            btnLayout.text = "Big window"
        } else {
            val set = ConstraintSet().apply { clone(gridSet) }
            val m = dp(3)
            val others = (0..3).filter { it != bigSlot }
            listOf(bigSlot).forEach { i ->
                val id = slots[i].id
                set.clear(id); set.constrainWidth(id, 0); set.constrainHeight(id, 0)
                set.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, m)
                set.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, m)
                set.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, m)
                set.connect(id, ConstraintSet.END, gV, ConstraintSet.START, m)
            }
            val tops = listOf(ConstraintSet.PARENT_ID to ConstraintSet.TOP, gH1 to ConstraintSet.BOTTOM, gH2 to ConstraintSet.BOTTOM)
            val bottoms = listOf(gH1 to ConstraintSet.TOP, gH2 to ConstraintSet.TOP, ConstraintSet.PARENT_ID to ConstraintSet.BOTTOM)
            others.forEachIndexed { n, i ->
                val id = slots[i].id
                set.clear(id); set.constrainWidth(id, 0); set.constrainHeight(id, 0)
                set.connect(id, ConstraintSet.START, gV, ConstraintSet.END, m)
                set.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, m)
                set.connect(id, ConstraintSet.TOP, tops[n].first, tops[n].second, m)
                set.connect(id, ConstraintSet.BOTTOM, bottoms[n].first, bottoms[n].second, m)
            }
            set.applyTo(binding.root)
            btnLayout.text = "Grid"
            setAudio(bigSlot)
            slots[bigSlot].requestFocus()
        }
    }

    private fun buildGuidelines() {
        fun guide(id: Int, vertical: Boolean, pct: Float) = binding.root.addView(Guideline(this).apply { this.id = id },
            ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                orientation = if (vertical) ConstraintLayout.LayoutParams.VERTICAL else ConstraintLayout.LayoutParams.HORIZONTAL; guidePercent = pct
            })
        guide(gV, true, 0.70f); guide(gH1, false, 1f / 3); guide(gH2, false, 2f / 3)
    }

    /** OK on a window (TV) / hold (touch): what to do with it. */
    private fun windowMenu(slot: Int) {
        val ch = selected[slot] ?: run { showChannelPicker(slot); return }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (failed[slot]) items += "Try again" to { setSlot(slot, ch) }
        items += "Change channel" to { showChannelPicker(slot) }
        if (touch || audioSlot != slot) items += "Sound from this window" to { setAudio(slot) }
        items += (if (bigSlot == slot) "Back to the grid" else "Big window") to { toggleBig(slot) }
        items += "Watch full screen" to { fullScreen(ch) }
        items += "Remove from MultiView" to { clearSlot(slot) }
        showChoiceDialog(title = "Window ${slot + 1} · ${ch.name.orEmpty()}", items = items.map { it.first }, selectedIndex = -1, focusIndex = 0) { which -> items[which].second() }
    }

    /** Leaves MultiView and plays this channel full screen. */
    private fun fullScreen(ch: LiveChannel) {
        val id = ch.stream_id ?: return
        lifecycleScope.launch {
            val entity = DatabaseProvider.get(this@MultiViewActivity).channelDao().getByStreamIds(listOf(id)).firstOrNull()
            if (entity == null) { Toast.makeText(this@MultiViewActivity, "This channel is not available.", Toast.LENGTH_SHORT).show(); return@launch }
            multiPlayer.release()
            ChannelLauncher.play(this@MultiViewActivity, listOf(entity), entity)
            finish()
        }
    }

    /** Every category and its channels; the picked channel plays in this window (see [MultiViewPicker]). */
    private fun showChannelPicker(slot: Int) {
        val cat = selected.firstOrNull { it != null }?.category_id ?: PlayerState.currentChannel()?.category_id
        // the control bar and the menu button sit above the picker and took the remote's focus
        handler.removeCallbacks(hideBar)
        bar.visibility = View.GONE
        binding.btnMore.visibility = View.GONE
        picker.open(slot, cat)
    }

    private fun buildStreamUrl(channel: LiveChannel): String {
        val server = prefs.getServer().trim().trimEnd('/')
        return "$server/live/${prefs.getUsername().trim()}/${prefs.getPassword().trim()}/${channel.stream_id}.m3u8"
    }

    override fun onLoading(slot: Int) {
        runOnUiThread { if (slot in 0..3) progressBars[slot].visibility = View.VISIBLE }
    }

    override fun onReady(slot: Int) {
        runOnUiThread {
            if (slot !in 0..3) return@runOnUiThread
            progressBars[slot].visibility = View.GONE
            failed[slot] = false
            // A retry can recover after onError() replaced the name.
            selected[slot]?.let { labels[slot].text = "${slot + 1} · ${it.name ?: "Unknown Channel"}" }
        }
    }

    override fun onStart() {
        super.onStart()
        multiPlayer.resumeAll()
    }

    override fun onStop() {
        multiPlayer.pauseAll()
        super.onStop()
    }

    override fun onError(slot: Int, message: String) {
        runOnUiThread {
            if (slot !in 0..3) return@runOnUiThread
            progressBars[slot].visibility = View.GONE
            failed[slot] = true
            labels[slot].text = "${slot + 1} · ${selected[slot]?.name.orEmpty()} · not playing - " + if (touch) "hold for Try again" else "OK for Try again"
            Toast.makeText(this, "Window ${slot + 1}: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            val player = playerViews[audioSlot].player
            if (player?.isPlaying == true) player.pause() else player?.play()
            return true
        }
        if (picker.isOpen) return super.onKeyDown(keyCode, event)
        // the remote's menu key: options of the window with the focus
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            windowMenu(slots.indexOfFirst { it.isFocused }.takeIf { it >= 0 } ?: focusedSlot)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideBar)
        multiPlayer.release()
        PlayerManager.resume()
        super.onDestroy()
    }
}
