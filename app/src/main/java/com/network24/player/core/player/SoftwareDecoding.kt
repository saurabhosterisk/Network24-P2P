package com.network24.player.core.player

import android.graphics.Color
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.network24.player.features.player.manager.PlayerManager

/**
 * Software video decoding (Settings > Special features, and a full-screen
 * button): a way out for a channel that shows green / broken frames or stutters
 * on the device's hardware decoder. Software decoding costs a lot of CPU, so it
 * is only a troubleshooting switch - it is never saved and is off again after
 * the app is closed or restarted.
 */
object SoftwareDecoding {
    @Volatile
    var enabled: Boolean = false
}

/**
 * Video: software decoders first while [SoftwareDecoding] is on, with the
 * hardware ones still behind them (formats the device can't decode in software,
 * e.g. 4K HEVC, keep playing). Audio and everything else: Media3's default.
 */
object N24CodecSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val selector = if (SoftwareDecoding.enabled && MimeTypes.isVideo(mimeType)) {
            MediaCodecSelector.PREFER_SOFTWARE
        } else {
            MediaCodecSelector.DEFAULT
        }
        return selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
    }
}

/** The full-screen player's HW/SW button: yellow = software decoding on. */
object SoftwareDecodingButton {

    fun bind(activity: AppCompatActivity, button: ImageView, afterToggle: () -> Unit = {}) {
        fun paint() = button.setColorFilter(if (SoftwareDecoding.enabled) ON_COLOR else Color.WHITE)
        paint()
        button.setOnClickListener {
            SoftwareDecoding.enabled = !SoftwareDecoding.enabled
            paint()
            // The decoder is chosen when a stream starts: reload the channel so it applies now.
            PlayerManager.retryCurrent()
            Toast.makeText(
                activity,
                if (SoftwareDecoding.enabled) "Software Decoding On" else "Hardware Decoding On",
                Toast.LENGTH_SHORT
            ).show()
            afterToggle()
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = paint()
        })
    }

    private val ON_COLOR = Color.parseColor("#FFC107")
}
