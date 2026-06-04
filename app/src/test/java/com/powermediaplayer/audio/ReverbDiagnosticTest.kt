package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline reproducer for the reported on-device crackle: feeds a steady
 * sine through the processor across multiple buffers and a mid-stream
 * preset switch, then scans the output for sample-to-sample
 * discontinuities (clicks) and measures wet-level versus dry. Prints the
 * numbers so the thresholds are evidence, not guesses.
 */
class ReverbDiagnosticTest {

    private val sr = 48_000
    private val format = AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT)

    private fun sineBuffer(frames: Int, startFrame: Int, amp: Double): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amp * 32767 * sin(2.0 * Math.PI * 440.0 * (startFrame + i) / sr)).toInt().toShort()
            b.putShort(v); b.putShort(v)
        }
        b.flip(); return b
    }

    @Test
    fun scanForClicksAndMeasureLevels() {
        var preset = 3
        val p = ReverbAudioProcessor({ preset }, { 1f })
        p.configure(format)
        p.flush()

        val bufFrames = 4096
        var frame = 0
        var prevL = 0
        var maxDeltaSteady = 0
        var maxDeltaSwitch = 0
        var sumSqDry = 0.0
        var sumSqOut = 0.0
        var nOut = 0L

        fun runBuffers(count: Int, trackDelta: (Int) -> Unit) {
            repeat(count) {
                p.queueInput(sineBuffer(bufFrames, frame, 0.5))
                frame += bufFrames
                val out = p.output
                val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (sb.hasRemaining()) {
                    val l = sb.get().toInt(); sb.get() // skip R
                    trackDelta(abs(l - prevL))
                    sumSqOut += l.toDouble() * l; nOut++
                    prevL = l
                }
                out.position(out.limit())
            }
        }

        // Dry reference RMS of the same sine.
        sumSqDry = 0.5 * 32767 * 0.5 * 32767 / 2.0 // amp^2/2

        // 2 s steady — let the tail build, then measure.
        runBuffers(24) { d -> if (frame > sr) maxDeltaSteady = maxOf(maxDeltaSteady, d) }
        val steadyRms = sqrt(sumSqOut / nOut)

        // Mid-stream preset switch 3 → 5 → 1 while playing.
        preset = 5
        runBuffers(6) { d -> maxDeltaSwitch = maxOf(maxDeltaSwitch, d) }
        preset = 1
        runBuffers(6) { d -> maxDeltaSwitch = maxOf(maxDeltaSwitch, d) }

        val dryRms = sqrt(sumSqDry)
        // 440 Hz sine max natural sample delta at this amplitude:
        val naturalDelta = (2.0 * Math.PI * 440.0 / sr * 0.5 * 32767).toInt()
        println("REVERB-DIAG steadyRms=${steadyRms.toInt()} dryRms=${dryRms.toInt()} " +
            "ratio=${"%.2f".format(steadyRms / dryRms)} " +
            "maxDeltaSteady=$maxDeltaSteady maxDeltaSwitch=$maxDeltaSwitch " +
            "naturalSineDelta=$naturalDelta")
    }
}
