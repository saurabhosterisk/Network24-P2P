package com.network24.player.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Auto Volume Leveling (Settings > Special features, and the full-screen player
 * button). Channels are mastered at very different loudness - a news channel
 * can be much louder than the next one - so customers kept reaching for the TV
 * remote after every channel change. Off by default.
 */
object AutoVolume {
    @Volatile
    var enabled: Boolean = false
}

/**
 * Brings every channel to roughly the same loudness: measures the programme's
 * average level (slowly, so normal loud/quiet moments inside a show are kept)
 * and turns the whole signal up or down towards one target, within +/-12 dB.
 * Gain changes are ramped sample by sample (no clicks) and a soft limiter keeps
 * boosted peaks from clipping. Works on the decoded 16-bit PCM, so it covers
 * every device and every codec the player decodes (including the FFmpeg ones);
 * audio passed through untouched to a soundbar (e.g. Dolby) is not changed.
 * With the switch off the gain eases back to 1 and the audio is unchanged.
 */
class AutoVolumeProcessor : BaseAudioProcessor() {

    private var sampleRate = 48_000
    private var channels = 2

    // Running mean square of the programme (samples normalised to -1..1), -1 = not known yet.
    private var meanSquare = -1.0
    private var gain = 1.0
    private var secondsSinceFlush = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(size)
        val frameBytes = 2 * channels
        val blockFrames = (sampleRate / 20).coerceAtLeast(1)       // 50 ms blocks
        val blockSeconds = blockFrames.toDouble() / sampleRate

        var pos = input.position()
        val end = input.limit()
        while (pos < end) {
            val frames = minOf(blockFrames, (end - pos) / frameBytes)
            if (frames <= 0) break
            val samples = frames * channels

            // Level of this block
            var sum = 0.0
            for (i in 0 until samples) {
                val s = input.getShort(pos + i * 2) / 32768.0
                sum += s * s
            }
            val blockMs = sum / samples

            // Follow the programme level; ignore near-silence so pauses don't pump the gain up.
            if (blockMs > SILENCE_MS) {
                if (meanSquare < 0) {
                    meanSquare = blockMs
                } else {
                    val tau = if (secondsSinceFlush < SETTLE_SECONDS) FAST_TAU else SLOW_TAU
                    meanSquare += (blockMs - meanSquare) * (blockSeconds / tau).coerceAtMost(1.0)
                }
            }
            secondsSinceFlush += frames.toDouble() / sampleRate

            val target = when {
                !AutoVolume.enabled -> 1.0
                meanSquare <= 0 -> gain
                else -> sqrt(TARGET_MS / meanSquare).coerceIn(MIN_GAIN, MAX_GAIN)
            }
            val startGain = gain
            val endGain = gain + (target - gain) * (blockSeconds / GAIN_SMOOTH_SECONDS).coerceAtMost(1.0)

            for (f in 0 until frames) {
                val g = startGain + (endGain - startGain) * f / frames
                for (c in 0 until channels) {
                    val idx = pos + (f * channels + c) * 2
                    output.putShort(limit(input.getShort(idx) * g))
                }
            }
            gain = endGain
            pos += frames * frameBytes
        }
        input.position(end)
        output.flip()
    }

    /** Soft knee above ~-0.3 dBFS, then a hard stop - boosted peaks never wrap around. */
    private fun limit(value: Double): Short {
        val magnitude = kotlin.math.abs(value)
        val limited = if (magnitude <= KNEE) magnitude else KNEE + (magnitude - KNEE) * 0.1
        val clamped = limited.coerceAtMost(32767.0)
        return (if (value < 0) -clamped else clamped).toInt().toShort()
    }

    override fun onFlush() {
        // New channel (or seek): its loudness is unknown. Never start louder than
        // unity - a quiet channel's boost must not blast out of a loud one.
        meanSquare = -1.0
        gain = minOf(gain, 1.0)
        secondsSinceFlush = 0.0
    }

    override fun onReset() {
        meanSquare = -1.0
        gain = 1.0
        secondsSinceFlush = 0.0
    }

    private companion object {
        const val TARGET_MS = 0.01                 // about -20 dBFS RMS
        const val SILENCE_MS = 3.0e-6              // about -55 dBFS
        const val MIN_GAIN = 0.25                  // -12 dB
        const val MAX_GAIN = 4.0                   // +12 dB
        const val FAST_TAU = 0.5                   // seconds, right after a channel change
        const val SLOW_TAU = 4.0                   // seconds, once settled
        const val SETTLE_SECONDS = 2.0
        const val GAIN_SMOOTH_SECONDS = 0.4
        const val KNEE = 32000.0
    }
}
