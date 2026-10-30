package io.embodit.theremin.domain.audio

import io.embodit.theremin.domain.model.Timbre
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The theremin voice (design `audio.synthSpec`), plain Kotlin so that the JVM tests and the offline render
 * run the very code the AudioTrack thread runs.
 *
 * One phase accumulator (double); harmonics are sin(k * phase) of that same phase, so a pitch change never
 * breaks the phase. Every control value is interpolated per sample: pitch is smoothed in the semitone
 * (log) domain with tau 6 ms, volume with tau 12 ms up / 30 ms down and a slope limit of 25 ms from silence
 * to full / 60 ms back. The auto vibrato only touches the sound: it fades in 400 ms after the hand has held
 * still for 300 ms and leaves at once when it moves. A 20 Hz DC blocker and a soft limit at -1 dBFS close
 * the chain. Full volume at the default 70 % master peaks at -9 dBFS for the pure tone; the other timbres
 * are RMS-matched to it.
 *
 * Not thread safe: one instance per audio thread.
 */
class SynthCore(val sampleRate: Int = 48_000) {

    private class Voice(val harmonics: List<Pair<Int, Double>>, val lowPassHz: Double, val vibHz: Double, val vibCents: Double) {
        /** RMS of the summed partials: dividing by it matches the loudness of the four timbres. */
        val norm: Double = sqrt(harmonics.sumOf { it.second * it.second })
    }

    private val voices =
        mapOf(
            Timbre.SINE to Voice(listOf(1 to 1.0), 0.0, 0.0, 0.0),
            Timbre.CLASSIC to Voice(listOf(1 to 1.0, 2 to db(-16.0), 3 to db(-24.0)), 0.0, 5.5, 6.0),
            Timbre.WARM to Voice(listOf(1 to 1.0, 2 to db(-10.0)), 1200.0, 5.0, 5.0),
            Timbre.BRIGHT to Voice(listOf(1 to 1.0, 2 to db(-12.0), 3 to db(-16.0), 5 to db(-26.0)), 0.0, 6.0, 7.0),
        )

    private val fs = sampleRate.toDouble()
    private val aUp = 1 - exp(-1 / (TAU_UP_S * fs))
    private val aDown = 1 - exp(-1 / (TAU_DOWN_S * fs))
    private val attackStep = 1 / (MIN_ATTACK_S * fs)
    private val releaseStep = 1 / (MIN_RELEASE_S * fs)
    private val dcR = 1 - 2 * PI * 20 / fs
    private val vibIn = 1 / (VIB_IN_S * fs)
    private val vibOut = 1 / (VIB_OUT_S * fs)

    var timbre: Timbre = Timbre.CLASSIC

    /** Master gain 0..1 (default 0.7); 0 = muted. Ramped like the volume. */
    var master: Double = 0.7

    /** Pitch smoothing time constant; the strong snap uses a longer one (about a 40 ms glide). */
    var pitchTauS: Double = TAU_PITCH_S

    /** The auto vibrato (off only for analysis renders). */
    var vibratoEnabled: Boolean = true

    private var midiS = Double.NaN
    private var vol = 0.0
    private var gain = 0.0
    private var phase = 0.0
    private var vibPhase = 0.0
    private var vibAmount = 0.0
    private var stableS = 0.0
    private var lastTarget = Double.NaN
    private var lp = 0.0
    private var dcX = 0.0
    private var dcY = 0.0
    private var cueLeft = 0
    private var cuePhaseA = 0.0
    private var cuePhaseB = 0.0

    /** Smoothed values after the last block (pitchInfo, tests). */
    val currentMidi: Double
        get() = midiS

    val currentVolume: Double
        get() = vol

    /** Queues the lesson-done cue: 120 ms of 1.5 kHz + 2 kHz at -18 dB. */
    fun cue() {
        cueLeft = (CUE_S * fs).toInt()
        cuePhaseA = 0.0
        cuePhaseB = 0.0
    }

    /**
     * Renders [n] samples into [out] from [offset] towards [targetMidi] (continuous) and [targetVolume] (0..1).
     * Targets are held for the block; the per-sample smoothing turns them into a continuous curve.
     */
    fun render(out: FloatArray, offset: Int, n: Int, targetMidi: Double, targetVolume: Double) {
        val v = voices.getValue(timbre)
        val aPitch = 1 - exp(-1 / (pitchTauS * fs))
        if (midiS.isNaN()) midiS = targetMidi
        // stillness for the vibrato: |change| below 0.15 semitone per 300 ms
        val blockS = n / fs
        if (!lastTarget.isNaN() && abs(targetMidi - lastTarget) < STILL_SEMITONES * blockS / STILL_WINDOW_S) stableS += blockS else stableS = 0.0
        lastTarget = targetMidi
        val vibTarget = if (vibratoEnabled && v.vibCents > 0 && stableS > STILL_WINDOW_S) 1.0 else 0.0
        val lpA = if (v.lowPassHz > 0) 1 - exp(-2 * PI * v.lowPassHz / fs) else 0.0
        val wantGain = master.coerceIn(0.0, 1.0)
        for (i in 0 until n) {
            midiS += aPitch * (targetMidi - midiS)
            var dv = (if (targetVolume > vol) aUp else aDown) * (targetVolume - vol)
            dv = dv.coerceIn(-releaseStep, attackStep)
            vol += dv
            var dg = (if (wantGain > gain) aUp else aDown) * (wantGain - gain)
            dg = dg.coerceIn(-releaseStep, attackStep)
            gain += dg
            vibAmount += (vibTarget - vibAmount).coerceIn(-vibOut, vibIn)
            vibPhase += v.vibHz / fs
            if (vibPhase >= 1.0) vibPhase -= 1.0
            val m = midiS + vibAmount * (v.vibCents / 100.0) * sin(2 * PI * vibPhase)
            phase += 440.0 * 2.0.pow((m - 69.0) / 12.0) / fs
            phase -= floor(phase)
            var x = 0.0
            for ((k, a) in v.harmonics) x += a * sin(2 * PI * k * phase)
            x /= v.norm
            if (lpA > 0) {
                lp += lpA * (x - lp)
                x = lp
            }
            var y = x * vol * gain * FULL_SCALE
            if (cueLeft > 0) {
                val t = 1.0 - cueLeft / (CUE_S * fs)
                val env = sin(PI * t)
                cuePhaseA += 1500.0 / fs
                cuePhaseB += 2000.0 / fs
                y += gain / DEFAULT_MASTER * CUE_GAIN * env * 0.5 * (sin(2 * PI * cuePhaseA) + sin(2 * PI * cuePhaseB))
                cueLeft--
            }
            val dc = y - dcX + dcR * dcY
            dcX = y
            dcY = dc
            out[offset + i] = softLimit(dc).toFloat()
        }
    }

    private fun softLimit(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (LIMIT - KNEE)
        val y = KNEE + (LIMIT - KNEE) * (over / (1 + over))
        return if (x < 0) -y else y
    }

    companion object {
        const val TAU_PITCH_S = 0.006
        const val TAU_PITCH_STRONG_S = 0.013
        const val TAU_UP_S = 0.012
        const val TAU_DOWN_S = 0.030
        const val MIN_ATTACK_S = 0.025
        const val MIN_RELEASE_S = 0.060
        const val VIB_IN_S = 0.4
        const val VIB_OUT_S = 0.08
        const val STILL_SEMITONES = 0.15
        const val STILL_WINDOW_S = 0.3
        const val DEFAULT_MASTER = 0.7
        const val CUE_S = 0.12

        /** Peak of a full-volume pure tone at master 0.7 is -9 dBFS: 10^(-9/20) / 0.7. */
        val FULL_SCALE: Double = 10.0.pow(-9.0 / 20.0) / DEFAULT_MASTER
        val CUE_GAIN: Double = 10.0.pow(-18.0 / 20.0)
        val LIMIT: Double = 10.0.pow(-1.0 / 20.0)
        val KNEE: Double = 0.7

        fun db(v: Double): Double = 10.0.pow(v / 20.0)
    }
}
