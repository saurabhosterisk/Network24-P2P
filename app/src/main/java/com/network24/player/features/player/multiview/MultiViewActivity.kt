package com.network24.player.features.player.multiview

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityMultiviewBinding
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.features.player.state.PlayerState

class MultiViewActivity : BaseActivity(), MultiPlayerManager.Listener {
    private lateinit var binding: ActivityMultiviewBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var multiPlayer: MultiPlayerManager

    private val channels = mutableListOf<LiveChannel>()
    private val selected = arrayOfNulls<LiveChannel>(4)
    private var focusedSlot = 0

    private val slots: Array<FrameLayout> by lazy {
        arrayOf(binding.slot1, binding.slot2, binding.slot3, binding.slot4)
    }
    private val labels: Array<TextView> by lazy {
        arrayOf(binding.label1, binding.label2, binding.label3, binding.label4)
    }
    private val playerViews by lazy {
        arrayOf(binding.playerView1, binding.playerView2, binding.playerView3, binding.playerView4)
    }
    private val progressBars by lazy {
        arrayOf(binding.progress1, binding.progress2, binding.progress3, binding.progress4)
    }

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

        channels.addAll(PlayerState.channels)
        val current = PlayerState.currentChannel()
        if (current != null) setSlot(0, current)

        slots.forEachIndexed { index, slot ->
            slot.foreground = getDrawable(R.drawable.bg_multiview_slot_selector)
            slot.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedSlot = index
                    multiPlayer.setAudioFocus(index)
                }
            }
            slot.setOnClickListener { showChannelPicker(index) }
        }

        binding.slot1.requestFocus()
        onBackPressedDispatcher.addCallback(this) { finish() }
    }

    private fun setSlot(slot: Int, channel: LiveChannel) {
        selected[slot] = channel
        labels[slot].text = channel.name ?: "Unknown Channel"
        progressBars[slot].visibility = android.view.View.VISIBLE
        multiPlayer.attach(slot, playerViews[slot])
        multiPlayer.play(slot, buildStreamUrl(channel))
        multiPlayer.setAudioFocus(focusedSlot)
    }

    private fun clearSlot(slot: Int) {
        selected[slot] = null
        labels[slot].text = "Select channel"
        progressBars[slot].visibility = android.view.View.GONE
        multiPlayer.clear(slot)
    }

    private fun showChannelPicker(slot: Int) {
        if (channels.isEmpty()) {
            Toast.makeText(this, "No channels available", Toast.LENGTH_SHORT).show()
            return
        }

        val names = channels.map { it.name ?: "Unknown Channel" }.toTypedArray()
        val currentIndex = selected[slot]?.let { selectedChannel ->
            channels.indexOfFirst { it.stream_id == selectedChannel.stream_id }
        } ?: -1

        AlertDialog.Builder(this)
            .setTitle("Choose channel for Window ${slot + 1}")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                setSlot(slot, channels[which])
                dialog.dismiss()
            }
            .setNegativeButton("Clear") { _, _ -> clearSlot(slot) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun buildStreamUrl(channel: LiveChannel): String {
        val server = prefs.getServer().trim().trimEnd('/')
        return "$server/live/${prefs.getUsername().trim()}/${prefs.getPassword().trim()}/${channel.stream_id}.m3u8"
    }

    override fun onLoading(slot: Int) {
        runOnUiThread {
            if (slot in 0..3) progressBars[slot].visibility = android.view.View.VISIBLE
        }
    }

    override fun onReady(slot: Int) {
        runOnUiThread {
            if (slot in 0..3) progressBars[slot].visibility = android.view.View.GONE
        }
    }

    override fun onError(slot: Int, message: String) {
        runOnUiThread {
            if (slot !in 0..3) return@runOnUiThread
            progressBars[slot].visibility = android.view.View.GONE
            labels[slot].text = "Playback error"
            Toast.makeText(this, "Window ${slot + 1}: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            val player = playerViews[focusedSlot].player
            if (player?.isPlaying == true) player.pause() else player?.play()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showChannelPicker(focusedSlot)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        multiPlayer.release()
        super.onDestroy()
    }
}
