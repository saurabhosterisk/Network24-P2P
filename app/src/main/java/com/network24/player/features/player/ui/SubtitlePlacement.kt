package com.network24.player.features.player.ui

import androidx.media3.ui.PlayerView

/**
 * Keeps subtitles clear of the full-screen player's bottom bar (now/next, seek bar and buttons) while that bar is
 * shown, and puts them back to the normal spot when it hides.
 *
 * The whole subtitle layer is slid up rather than changing the bottom padding: TV captions (CEA-608, what our US
 * channels carry) place every line on a fixed caption row and ignore the padding, so they used to sit right on top
 * of the buttons. Sliding the layer moves positioned and unpositioned cues alike.
 */
object SubtitlePlacement {

    // The bottom overlay covers roughly the lower 30 % of the screen; captions already sit ~8 % above the edge.
    private const val LIFT_FRACTION = 0.43f  // the info panel now covers the lower ~45 %
    private const val ANIM_MS = 200L

    fun update(playerView: PlayerView, controlsVisible: Boolean) {
        val subtitles = playerView.subtitleView ?: return
        val lift = if (controlsVisible) -playerView.height * LIFT_FRACTION else 0f
        subtitles.animate().cancel()
        subtitles.animate().translationY(lift).setDuration(ANIM_MS).start()
    }
}
