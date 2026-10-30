package io.embodit.theremin.domain

import io.embodit.theremin.domain.audio.SynthCore
import io.embodit.theremin.domain.model.Timbre
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synth against the design's audio acceptance (`acceptance.mustPass`, `audio.renderCheck`): pitch,
 * harmonic levels, loudness, no clicks, no block-rate zipper. Also writes the offline render the build
 * station listens to / analyses: `build/render/` of the module.
 */
class SynthTest {
    private val fs = 48_000
    private val block = 192

    /** Renders with targets from [ctrl] (time s -> midi, volume) once per 4 ms block, like the AudioTrack thread. */
    private fun render(timbre: Timbre, seconds: Double, vibrato: Boolean = true, ctrl: (Double) -> Pair<Double, Double>): FloatArray {
        val core = SynthCore(fs)
        core.timbre = timbre
        core.vibratoEnabled = vibrato
        core.roomWet = 0.0 // analysis: the dry voice (the room is checked by ear in performance_v014.wav)
        val n = (seconds * fs).toInt()
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val len = minOf(block, n - i)
            val (m, v) = ctrl(i.toDouble() / fs)
            core.render(out, i, len, m, v)
            i += len
        }
        return out
    }

    private fun rise(t: Double, on: Double, off: Double, level: Double = 1.0): Double =
        when {
            t < on -> 0.0
            t < on + 0.15 -> level * (t - on) / 0.15
            t < off -> level
            t < off + 0.15 -> level * (1 - (t - off) / 0.15)
            else -> 0.0
        }

    /** Goertzel magnitude of [f] over a Hann-windowed segment. */
    private fun mag(x: FloatArray, from: Int, to: Int, f: Double): Double {
        val n = to - from
        val w = 2 * PI * f / fs
        var s1 = 0.0
        var s2 = 0.0
        for (k in 0 until n) {
            val hann = 0.5 - 0.5 * cos(2 * PI * k / (n - 1))
            val s0 = x[from + k] * hann + 2 * cos(w) * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - 2 * cos(w) * s1 * s2)
    }

    /** Peak frequency near [guess] by a fine Goertzel scan (0.01 Hz). */
    private fun peak(x: FloatArray, from: Int, to: Int, guess: Double, span: Double = 3.0): Double {
        var best = guess
        var bestM = 0.0
        var f = guess - span
        while (f <= guess + span) {
            val m = mag(x, from, to, f)
            if (m > bestM) {
                bestM = m
                best = f
            }
            f += 0.01
        }
        return best
    }

    private fun db(a: Double, ref: Double) = 20 * log10(max(a, 1e-12) / ref)

    @Test
    fun a4Calibration() {
        val x = render(Timbre.SINE, 1.2) { t -> 69.0 to rise(t, 0.0, 2.0) }
        val f = peak(x, (0.3 * fs).toInt(), (1.1 * fs).toInt(), 440.0)
        assertEquals(440.0, f, 0.44) // 0.1 %
    }

    @Test
    fun timbreHarmonicsMatchTheSpec() {
        val expect = mapOf(
            Timbre.SINE to listOf(-200.0, -200.0, -200.0),
            // v0.1.4: softer partials under a 3.2 kHz low-pass (the one-pole takes about 0.5 dB off h2)
            Timbre.CLASSIC to listOf(-18.5, -27.0, -200.0),
            Timbre.WARM to listOf(-12.5, -200.0, -200.0),
            Timbre.BRIGHT to listOf(-12.2, -17.5, -29.0),
        )
        val f0 = 261.6256
        for ((timbre, levels) in expect) {
            val x = render(timbre, 2.0, vibrato = false) { t -> 60.0 to rise(t, 0.2, 1.6) }
            val a = (0.5 * fs).toInt()
            val b = (1.4 * fs).toInt()
            val f = peak(x, a, b, f0, 1.0)
            assertEquals("$timbre f0", f0, f, f0 * 0.001)
            val ref = mag(x, a, b, f)
            listOf(2, 3, 5).forEachIndexed { k, h ->
                val l = db(mag(x, a, b, f * h), ref)
                if (levels[k] < -100) assertTrue("$timbre h$h=$l", l < -60) else assertEquals("$timbre h$h", levels[k], l, 2.0)
            }
        }
    }

    @Test
    fun loudnessAndPeak() {
        val rms = Timbre.entries.associateWith { t ->
            val x = render(t, 1.5, vibrato = false) { tt -> 60.0 to rise(tt, 0.0, 9.0) }
            val a = (0.5 * fs).toInt()
            sqrt((a until x.size).sumOf { (x[it] * x[it]).toDouble() } / (x.size - a))
        }
        val ref = rms.getValue(Timbre.SINE)
        for ((t, r) in rms) assertEquals("$t rms", 0.0, db(r, ref), 1.5)
        val sine = render(Timbre.SINE, 1.0) { 60.0 to 1.0 }
        val peak = sine.drop(fs / 2).maxOf { abs(it) }.toDouble()
        assertEquals(-9.0, db(peak, 1.0), 1.0)
    }

    @Test
    fun noClicksOnRepeatedOnOff() {
        // silent -> full -> silent twenty times, hand jumps (block-step targets), classic timbre
        val x = render(Timbre.CLASSIC, 8.0) { t -> 60.0 + (t * 3).toInt() % 5 to if (((t * 5).toInt() % 2) == 0) 1.0 else 0.0 }
        var maxStep = 0.0
        for (i in 1 until x.size) maxStep = max(maxStep, abs(x[i] - x[i - 1]).toDouble())
        assertTrue("max step $maxStep", maxStep < 0.05)
    }

    @Test
    fun noBlockRateZipper() {
        val x = render(Timbre.SINE, 1.5) { t -> 60.0 + 0.2 * sin(2 * PI * 0.5 * t) to rise(t, 0.0, 9.0) }
        val a = (0.4 * fs).toInt()
        val b = (1.4 * fs).toInt()
        val f0 = peak(x, a, b, 263.0, 4.0)
        val ref = mag(x, a, b, f0)
        val side = maxOf(mag(x, a, b, f0 + fs / block.toDouble()), mag(x, a, b, fs / block.toDouble()))
        assertTrue("sideband ${db(side, ref)}", db(side, ref) < -60)
    }

    @Test
    fun mutedIsSilent() {
        val core = SynthCore(fs)
        core.master = 0.0
        val out = FloatArray(fs)
        var i = 0
        while (i < out.size) {
            core.render(out, i, block, 60.0, 1.0)
            i += block
        }
        assertTrue(out.all { it == 0f })
    }

    /** Offline render for listening: the four timbres, a glide and the start of Ode to Joy. */
    @Test
    fun offlineRender() {
        val dir = File("build/render").apply { mkdirs() }
        for (t in Timbre.entries) writeWav(File(dir, "timbre_${t.name.lowercase()}_C4.wav"), render(t, 2.0) { tt -> 60.0 to rise(tt, 0.2, 1.6, 0.8) })
        writeWav(File(dir, "glide_classic_C4_C5.wav"), render(Timbre.CLASSIC, 3.2) { t -> 60 + 12 * ((t - 0.4) / 2.0).coerceIn(0.0, 1.0) to rise(t, 0.15, 2.8, 0.8) })
        val ode = listOf(4 to 1.0, 4 to 1.0, 5 to 1.0, 7 to 1.0, 7 to 1.0, 5 to 1.0, 4 to 1.0, 2 to 1.0, 0 to 1.0, 0 to 1.0, 2 to 1.0, 4 to 1.0, 4 to 1.5, 2 to 0.5, 2 to 2.0)
        val beat = 60.0 / 88
        val starts = ode.runningFold(0.6) { acc, n -> acc + n.second * beat }
        val end = starts.last()
        writeWav(File(dir, "song_ode_classic.wav"), render(Timbre.CLASSIC, end + 0.5) { t ->
            var m = 64.0
            for (k in ode.indices) if (t >= starts[k] - 0.06) m = 60.0 + ode[k].first
            m to rise(t, 0.4, end, 0.8)
        })
        assertTrue(File(dir, "song_ode_classic.wav").length() > 1000)
    }

    private fun writeWav(file: File, x: FloatArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val data = x.size * 2
            fun i32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.writeBytes("RIFF"); i32(36 + data); f.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(fs); i32(fs * 2); i16(2); i16(16)
            f.writeBytes("data"); i32(data)
            val buf = ByteArray(data)
            x.forEachIndexed { i, v ->
                val s = (v.coerceIn(-1f, 1f) * 32767).toInt()
                buf[2 * i] = s.toByte()
                buf[2 * i + 1] = (s shr 8).toByte()
            }
            f.write(buf)
        }
    }
}
