package com.network24.player.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AutoVolumeProcessorTest {

    private val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @After
    fun tearDown() {
        AutoVolume.enabled = false
    }

    /** Plays [seconds] of a stereo sine at [amplitude]; returns output RMS (dBFS) of the last second. */
    private fun run(processor: AutoVolumeProcessor, amplitude: Double, seconds: Int): Double {
        val frames = 48_000 / 20
        var lastSecond = DoubleArray(0)
        val collected = ArrayList<Double>()
        repeat(seconds * 20) { block ->
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            for (f in 0 until frames) {
                val t = (block * frames + f) / 48_000.0
                val s = (amplitude * sin(2 * PI * 440 * t) * 32767).toInt().toShort()
                input.putShort(s); input.putShort(s)
            }
            input.flip()
            processor.queueInput(input)
            val out = processor.output
            while (out.hasRemaining()) collected.add(out.short / 32768.0)
        }
        lastSecond = collected.takeLast(96_000).toDoubleArray()
        val ms = lastSecond.sumOf { it * it } / lastSecond.size
        return 10 * log10(ms)
    }

    private fun newProcessor() = AutoVolumeProcessor().apply {
        configure(format)
        flush()
    }

    @Test
    fun loudAndQuietChannelsEndUpClose() {
        AutoVolume.enabled = true
        val loud = run(newProcessor(), amplitude = 0.35, seconds = 8)    // sine RMS about -12 dBFS
        val quiet = run(newProcessor(), amplitude = 0.05, seconds = 8)   // about -29 dBFS
        println("loud -> %.1f dB, quiet -> %.1f dB".format(loud, quiet))
        assertEquals(-20.0, loud, 1.5)
        assertEquals(-20.0, quiet, 1.5)
    }

    @Test
    fun veryQuietIsLimitedToPlus12dB() {
        AutoVolume.enabled = true
        val out = run(newProcessor(), amplitude = 0.005, seconds = 8)    // about -49 dBFS
        println("very quiet -> %.1f dB".format(out))
        assertEquals(-49.0 + 12.0, out, 1.5)
    }

    @Test
    fun offLeavesAudioUnchanged() {
        AutoVolume.enabled = false
        val out = run(newProcessor(), amplitude = 0.7, seconds = 4)
        println("off -> %.1f dB".format(out))
        assertEquals(20 * log10(0.7 / sqrt(2.0)), out, 0.3)
    }

    @Test
    fun channelChangeNeverStartsAboveUnity() {
        AutoVolume.enabled = true
        val p = newProcessor()
        run(p, amplitude = 0.05, seconds = 8)          // quiet channel: boosted
        p.flush()                                      // switch to a loud channel
        val frames = 48_000 / 20
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val s = (0.7 * sin(2 * PI * 440 * f / 48_000.0) * 32767).toInt().toShort()
            input.putShort(s); input.putShort(s)
        }
        input.flip()
        p.queueInput(input)
        val out = p.output
        var peak = 0
        while (out.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(out.short.toInt()))
        println("first 50 ms after switch, peak = $peak")
        assertTrue("no blast after a channel change", peak <= (0.7 * 32767).toInt() + 2)
    }
}
