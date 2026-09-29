package com.network24.player.core.audio

import android.graphics.Color
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.network24.player.core.preferences.PreferenceManager

/**
 * The full-screen player's Auto Volume Leveling button: yellow when on, white
 * when off (same look as the subtitle button). It flips the same setting as
 * Settings > Special features, and takes effect at once on the playing channel.
 */
object AutoVolumeButton {

    fun bind(activity: AppCompatActivity, button: ImageView, afterToggle: () -> Unit = {}) {
        val prefs = PreferenceManager(activity)
        fun paint() = button.setColorFilter(if (prefs.isAutoVolumeEnabled()) ON_COLOR else Color.WHITE)
        paint()
        button.setOnClickListener {
            val enabled = !prefs.isAutoVolumeEnabled()
            prefs.setAutoVolumeEnabled(enabled)
            paint()
            Toast.makeText(
                activity,
                if (enabled) "Auto Volume Leveling On" else "Auto Volume Leveling Off",
                Toast.LENGTH_SHORT
            ).show()
            afterToggle()
        }
        // Changed in Settings meanwhile? Show the current state when coming back.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = paint()
        })
    }

    private val ON_COLOR = Color.parseColor("#FFC107")
}
