package com.network24.player.features.player.ui

import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

/**
 * Keeps subtitles readable above the full-screen control overlay. Without
 * this they were drawn at the default position, right on top of the Now/Next
 * rows and the button row, in every full-screen player.
 */
object SubtitlePlacement {

    // The bottom overlay (Now/Next + progress + buttons) covers about the
    // lower third of the screen.
    private const val ABOVE_CONTROLS_FRACTION = 0.33f

    fun update(playerView: PlayerView, controlsVisible: Boolean) {
        playerView.subtitleView?.setBottomPaddingFraction(
            if (controlsVisible) ABOVE_CONTROLS_FRACTION else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
        )
    }
}
