package io.embodit.theremin.domain

import io.embodit.theremin.domain.audio.SynthCore
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Timbre
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.HandSample
import io.embodit.theremin.domain.play.PlayMapper
import io.embodit.theremin.domain.play.RoleInputs
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.4 sound check (user on the headset: "像电波干扰"): 10 s of Ode to Joy played by a simulated hand, tracked at
 * 45 Hz with 3 mm jitter, read by the audio loop every 4 ms exactly as on the device (mapper -> synth). Rendered
 * once as v0.1.3 (filter fed repeats, one 6 ms glide stage, no room) and once as v0.1.4. Writes both to
 * `build/render/` and checks pitch jitter on held notes, clicks and energy above 6 kHz.
 */
class PerformanceRenderTest {
    private val fs = 48_000
    private val burst = 192
    private val pose = InstrumentPose(Vec3(0.3f, 0.74f, -0.6f), 0.4f)
    private val notes = listOf(4, 4, 5, 7, 7, 5, 4, 2, 0, 0, 2, 4, 4, 2, 2)
    private val beats = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 0.5, 2.0)
    private val beat = 60.0 / 120
    private val starts = beats.runningFold(0.8) { a, b -> a + b * beat }

    private class Result(val wav: FloatArray, val jitterCents: Double, val maxStep: Double, val hfDb: Double)

    /** The hand's semitone at [t]: held on each note, a 120 ms eased move into the next one. */
    private fun noteAt(t: Double): Double {
        var k = 0
        while (k + 1 < notes.size && t >= starts[k + 1]) k++
        if (k + 1 < notes.size) {
            val into = t - (starts[k + 1] - 0.12)
            if (into > 0) {
                val q = (into / 0.12).coerceIn(0.0, 1.0)
                return notes[k] + (notes[k + 1] - notes[k]) * (0.5 - 0.5 * cos(PI * q))
            }
        }
        return notes[k].toDouble()
    }

    private fun steady(t: Double): Boolean {
        if (t < starts[0] + 0.3 || t > starts.last() - 0.2) return false
        return starts.none { t > it - 0.2 && t < it + 0.15 }
    }

    private fun run(v014: Boolean): Result {
        val m = PlayMapper().also { it.pose = pose; it.armed = true; it.filterSkipsRepeats = v014 }
        val core = SynthCore(fs).also { it.timbre = Timbre.CLASSIC }
        if (!v014) {
            core.glidePoles = 1
            core.pitchTauS = 0.006
            core.roomWet = 0.0
        }
        val rnd = Random(7)
        fun j() = (rnd.nextGaussian() * 0.003).toFloat()
        val out = FloatArray(10 * fs)
        var hp: HandSample? = null
        var hv: HandSample? = null
        var lastTick = -1L
        val midis = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < out.size) {
            val t = i.toDouble() / fs
            val tick = (t * 45).toLong()
            if (tick != lastTick) {
                lastTick = tick
                val s = noteAt(t).toFloat()
                val p = Instrument.ripplePoint(Instrument.radius(s, Scale.STANDARD), -20f, Handed.RIGHT_PITCH, 0.25f)
                val palm = p - Vec3(0f, 0f, -0.04f) + Vec3(j(), j(), j())
                hp = HandSample(pose.toStage(palm), pose.toStage(palm + Vec3(0f, -0.02f, 0.08f)))
                val h =
                    when {
                        t < 0.5 -> 0.0
                        t < 0.8 -> 0.16 * (t - 0.5) / 0.3
                        t < 9.2 -> 0.16
                        t < 9.6 -> 0.16 * (1 - (t - 9.2) / 0.4)
                        else -> 0.0
                    }.toFloat()
                val c = Instrument.LOOP_CENTER
                hv = HandSample(pose.toStage(Vec3(c.x + j(), c.y + h + Instrument.PALM_DROP_M + j(), c.z + j())), null)
            }
            val f = m.step((t * 1e9).toLong(), RoleInputs(pitchHand = hp, volumeHand = hv))
            val len = minOf(burst, out.size - i)
            core.render(out, i, len, f.midiPlayed.toDouble(), f.amplitude.toDouble())
            midis += t to core.currentMidi
            i += len
        }
        // pitch jitter on held notes: the smoothed pitch minus its 60 ms moving average, in cents
        val w = (0.03 * fs / burst).toInt()
        var sq = 0.0
        var cnt = 0
        for (k in w until midis.size - w) {
            if (!steady(midis[k].first)) continue
            var avg = 0.0
            for (q in -w..w) avg += midis[k + q].second
            avg /= (2 * w + 1)
            val d = (midis[k].second - avg) * 100
            sq += d * d
            cnt++
        }
        var maxStep = 0.0
        for (k in 1 until out.size) maxStep = maxOf(maxStep, abs((out[k] - out[k - 1]).toDouble()))
        return Result(out, sqrt(sq / cnt), maxStep, highShareDb(out))
    }

    /** Energy above 6 kHz relative to the whole, dB (DFT over 4096-sample frames, Hann window). */
    private fun highShareDb(x: FloatArray): Double {
        val n = 4096
        var hi = 0.0
        var all = 0.0
        var start = 0
        val bins = n / 2
        val k6 = (6000.0 * n / fs).toInt()
        while (start + n <= x.size) {
            // Goertzel-free: direct DFT on a sparse set of bins is too slow; use a radix-2 FFT
            val re = DoubleArray(n) { k -> x[start + k] * (0.5 - 0.5 * cos(2 * PI * k / (n - 1))) }
            val im = DoubleArray(n)
            fft(re, im)
            for (b in 1 until bins) {
                val p = re[b] * re[b] + im[b] * im[b]
                all += p
                if (b >= k6) hi += p
            }
            start += n
        }
        return 10 * log10(maxOf(hi, 1e-30) / all)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k)
                    val wi = sin(ang * k)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }

    @Test
    fun handPlayedOdeIsSmoothAndClean() {
        val before = run(v014 = false)
        val after = run(v014 = true)
        val dir = File("build/render").apply { mkdirs() }
        writeWav(File(dir, "performance_v013_before.wav"), before.wav)
        writeWav(File(dir, "performance_v014.wav"), after.wav)
        File(dir, "performance_metrics.txt").writeText(
            String.format(
                Locale.US,
                "v0.1.3 jitter=%.2f cents maxStep=%.4f hf6k=%.1f dB%nv0.1.4 jitter=%.2f cents maxStep=%.4f hf6k=%.1f dB%n",
                before.jitterCents, before.maxStep, before.hfDb, after.jitterCents, after.maxStep, after.hfDb,
            )
        )
        assertTrue("jitter ${after.jitterCents} vs ${before.jitterCents}", after.jitterCents < 3.0 && after.jitterCents < before.jitterCents / 2)
        assertTrue("click ${after.maxStep}", after.maxStep < 0.06)
        assertTrue("hf ${after.hfDb}", after.hfDb < -45.0)
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
