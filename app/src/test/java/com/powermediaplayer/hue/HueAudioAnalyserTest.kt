package com.powermediaplayer.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for [HueAudioAnalyser.process] (audit TEST GAP, 2026-08-25).
 * Feeds synthetic Visualizer-style FFT frames (interleaved re/im signed bytes; magnitude
 * = |re| + |im|) and asserts the FFT→6-band mapping, silence handling, bass-vs-treble
 * discrimination, output range, and edge-size safety — no device / Visualizer needed.
 *
 * Band edges (Hz): 20, 60, 250, 500, 2000, 4000, 16000. binHz = (sampleRate/2) / n.
 */
class HueAudioAnalyserTest {

    private val sampleRate = 44100
    private val fftBytes = 1024          // n = 512 bins; binHz ≈ 43 Hz
    private val n get() = fftBytes / 2

    private fun binForHz(hz: Float): Int {
        val binHz = (sampleRate / 2f) / n
        return (hz / binHz).toInt().coerceIn(1, n - 1)
    }

    /** FFT frame with magnitude [amp] (0..127) in every bin whose frequency is in [loHz,hiHz]. */
    private fun frame(loHz: Float, hiHz: Float, amp: Int = 120): ByteArray {
        val fft = ByteArray(fftBytes)
        val lo = binForHz(loHz)
        val hi = binForHz(hiHz)
        for (k in lo..hi) {
            fft[2 * k] = amp.toByte()       // real
            fft[2 * k + 1] = 0              // imaginary
        }
        return fft
    }

    private fun silence() = ByteArray(fftBytes)

    @Test
    fun silence_allBandsZero_noBeat() {
        val a = HueAudioAnalyser()
        val r = a.process(silence(), sampleRate, uptimeMs = 0L)
        for (band in 0 until 6) {
            assertEquals("band $band should be ~0 on silence", 0f, r.bands[band], 1e-3f)
        }
        assertTrue("no beat on silence", !r.beat)
    }

    @Test
    fun bassHeavy_lowBandExceedsTrebleBand() {
        val a = HueAudioAnalyser()
        var r = a.process(silence(), sampleRate, 0L)
        // Feed several bass frames (20-60 Hz) so the EMA builds.
        repeat(6) { r = a.process(frame(25f, 55f), sampleRate, (it + 1) * 40L) }
        assertTrue(
            "bass band[0]=${r.bands[0]} should exceed treble band[5]=${r.bands[5]}",
            r.bands[0] > r.bands[5]
        )
        assertTrue("bass band should be clearly non-zero", r.bands[0] > 0.05f)
    }

    @Test
    fun trebleHeavy_highBandExceedsBassBand() {
        val a = HueAudioAnalyser()
        var r = a.process(silence(), sampleRate, 0L)
        // Feed treble frames (4-16 kHz) → band 5.
        repeat(6) { r = a.process(frame(5000f, 15000f), sampleRate, (it + 1) * 40L) }
        assertTrue(
            "treble band[5]=${r.bands[5]} should exceed bass band[0]=${r.bands[0]}",
            r.bands[5] > r.bands[0]
        )
    }

    @Test
    fun allBandOutputs_withinUnitRange() {
        val a = HueAudioAnalyser()
        var r = a.process(silence(), sampleRate, 0L)
        repeat(10) { r = a.process(frame(20f, 16000f, amp = 127), sampleRate, (it + 1) * 40L) }
        for (band in 0 until 6) {
            assertTrue("band $band in [0,1]", r.bands[band] in 0f..1f)
            assertTrue("normalisedBand $band in [0,1]", r.normalisedBands[band] in 0f..1f)
        }
        assertTrue("beatStrength >= 0", r.beatStrength >= 0f)
        assertTrue("normalisedBeatStrength in [0,1]", r.normalisedBeatStrength in 0f..1f)
    }

    @Test
    fun tinyFft_returnsEarly_noCrash() {
        val a = HueAudioAnalyser()
        val r = a.process(ByteArray(8), sampleRate, 0L) // n = 4 < 8 → early return
        assertTrue(!r.beat)
        assertEquals(0f, r.beatStrength, 0f)
    }

    @Test
    fun bpm_defaultsToSaneValue() {
        val a = HueAudioAnalyser()
        val r = a.process(silence(), sampleRate, 0L)
        assertTrue("bpm default in a musical range", r.bpm in 60f..200f)
    }

    @Test
    fun emaRelease_bandDecaysAfterSilence() {
        val a = HueAudioAnalyser()
        var r = a.process(silence(), sampleRate, 0L)
        repeat(6) { r = a.process(frame(25f, 55f), sampleRate, (it + 1) * 40L) }
        val peak = r.bands[0]
        // Now feed silence — the slow-release EMA should decay the band below its peak.
        repeat(6) { r = a.process(silence(), sampleRate, (it + 7) * 40L) }
        assertTrue("band decays after silence (peak=$peak now=${r.bands[0]})", r.bands[0] < peak)
    }

    @Test
    fun paletteHz_isPositive() {
        val a = HueAudioAnalyser()
        var r = a.process(silence(), sampleRate, 0L)
        repeat(4) { r = a.process(frame(20f, 16000f), sampleRate, (it + 1) * 40L) }
        assertTrue("paletteHz positive", r.paletteHz > 0f)
    }
}
