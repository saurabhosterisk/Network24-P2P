package com.network24.player.features.player.program

import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.network24.player.R
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.features.player.manager.PlayerManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen player: a panel from the left with what is playing right now on
 * this channel, from the TV guide - title, time, progress, the full
 * description and what's next. The channel keeps playing behind it.
 */
class ProgramInfoDrawer(
    private val activity: AppCompatActivity,
    button: View,
    private val onOpen: () -> Unit = {}
) {
    private var host: FrameLayout? = null

    var isOpen = false
        private set

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    init {
        button.setOnClickListener { if (isOpen) close() else open() }
    }

    /** Remote keys the open panel handles itself instead of the player screen. */
    fun handlesKey(keyCode: Int): Boolean = isOpen && keyCode in NAV_KEYS

    fun open() {
        onOpen()
        val frame = host ?: build().also { host = it }
        frame.visibility = View.VISIBLE
        isOpen = true
        back.remove()
        activity.onBackPressedDispatcher.addCallback(activity, back)
        back.isEnabled = true
        slideIn(frame)
        load(frame)
        if (!frame.isInTouchMode) frame.post { frame.findViewById<View>(R.id.progScroll).requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        back.isEnabled = false
        host?.let { slideOut(it) }
    }

    private fun build(): FrameLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = activity.resources.displayMetrics.density
        val screen = activity.resources.displayMetrics.widthPixels
        val width = (screen * 0.38f).toInt().coerceAtLeast((340 * density).toInt()).coerceAtMost(screen)

        // Keeps remote focus inside the panel (the player's hidden controls sit underneath).
        val frame = object : FrameLayout(activity) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                val next = super.focusSearch(focused, direction)
                return if (next == null || isInside(this, next)) next else focused
            }
        }.apply { elevation = 100f }
        val panel = LayoutInflater.from(activity).inflate(R.layout.layout_program_drawer, frame, false)
        frame.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.findViewById<View>(R.id.progClose).setOnClickListener { close() }
        return frame
    }

    /** Fills the panel from the TV guide stored in the app (no network call). */
    private fun load(frame: FrameLayout) {
        val streamId = PlayerManager.currentStreamId
        activity.lifecycleScope.launch {
            val db = DatabaseProvider.get(activity)
            val channel = runCatching { db.channelDao().getByStreamIds(listOf(streamId)).firstOrNull() }.getOrNull()
            val epgId = channel?.epgChannelId?.takeIf { it.isNotBlank() }
            val now = System.currentTimeMillis()
            val current = epgId?.let { runCatching { db.epgDao().getNowByEpgChannelId(it, now) }.getOrNull() }
            val next = epgId?.let { runCatching { db.epgDao().getNextByEpgChannelId(it, now) }.getOrNull() }
            show(frame, channel?.name, channel?.icon, current, next, now)
        }
    }

    private fun show(
        frame: FrameLayout,
        channelName: String?,
        logoUrl: String?,
        current: EpgEntity?,
        next: EpgEntity?,
        now: Long
    ) {
        frame.findViewById<TextView>(R.id.progChannel).text = channelName?.trim().orEmpty()
        frame.findViewById<ImageView>(R.id.progLogo).load(logoUrl) {
            placeholder(R.drawable.app_logo)
            error(R.drawable.app_logo)
        }
        val title = frame.findViewById<TextView>(R.id.progTitle)
        val timeRow = frame.findViewById<View>(R.id.progTimeRow)
        val time = frame.findViewById<TextView>(R.id.progTime)
        val left = frame.findViewById<TextView>(R.id.progLeft)
        val progress = frame.findViewById<ProgressBar>(R.id.progProgress)
        val description = frame.findViewById<TextView>(R.id.progDescription)
        val nowLabel = frame.findViewById<TextView>(R.id.progNowLabel)
        val nextBox = frame.findViewById<View>(R.id.progNextBox)

        if (current == null) {
            nowLabel.visibility = View.GONE
            title.text = "TV Guide Unavailable"
            timeRow.visibility = View.GONE
            progress.visibility = View.GONE
            description.text = "There's no guide information for this channel, so we can't show what's playing right now."
        } else {
            nowLabel.visibility = View.VISIBLE
            title.text = current.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled programme"
            val start = current.startTimestamp ?: 0L
            val stop = current.stopTimestamp ?: 0L
            timeRow.visibility = View.VISIBLE
            time.text = "${clock(start)}  –  ${clock(stop)}"
            left.text = minutesLeft(stop - now)
            progress.visibility = View.VISIBLE
            progress.progress = if (stop > start) (((now - start) * 1000) / (stop - start)).toInt().coerceIn(0, 1000) else 0
            description.text = current.description?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "No description is available for this programme."
        }

        if (next != null && current != null) {
            nextBox.visibility = View.VISIBLE
            frame.findViewById<TextView>(R.id.progNextTitle).text =
                next.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled programme"
            frame.findViewById<TextView>(R.id.progNextTime).text = clock(next.startTimestamp ?: 0L)
        } else {
            nextBox.visibility = View.GONE
        }
        frame.findViewById<View>(R.id.progScroll).scrollTo(0, 0)
    }

    private fun clock(ms: Long): String =
        if (ms <= 0) "--" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).uppercase(Locale.getDefault())

    private fun minutesLeft(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return if (min < 60) "$min MIN LEFT" else "${min / 60} H ${min % 60} MIN LEFT"
    }

    private fun slideIn(frame: FrameLayout) {
        val panel = frame.getChildAt(0) ?: return
        panel.animate().cancel()
        val distance = panel.width.takeIf { it > 0 } ?: panel.layoutParams.width
        panel.translationX = -distance.toFloat()
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
    }

    private fun slideOut(frame: FrameLayout) {
        val panel = frame.getChildAt(0) ?: run { frame.visibility = View.GONE; return }
        panel.animate().cancel()
        panel.animate().translationX(-panel.width.toFloat()).alpha(0f).setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (!isOpen) frame.visibility = View.GONE }
            .start()
    }

    private fun isInside(frame: ViewGroup, view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === frame) return true
            v = v.parent as? View
        }
        return false
    }

    private companion object {
        const val ANIM_MS = 220L
        val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
    }
}
