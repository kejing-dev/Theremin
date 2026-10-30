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
 * breaks the phase. Every control value is interpolated per sample. v0.1.4 (user: "像电波干扰"): pitch glides
 * through two one-pole stages in the semitone (log) domain, 14 ms each (about a 30 ms portamento, and the
 * 30 - 90 Hz steps of hand tracking come out -12 dB/octave smoother than with v0.1.3's single 6 ms stage);
 * volume through two stages, 18 ms up / 35 ms down each, plus the slope limit of 25 ms from silence to full /
 * 60 ms back. A light 5 Hz vibrato of a few cents is always there and deepens after the hand has held still for
 * 300 ms. The voice goes through a gentle low-pass and a small mono room (Schroeder: four combs, two all-passes,
 * 12 % wet). A 20 Hz DC blocker and a soft limit at -1 dBFS close the chain. Full volume at the default 70 % master peaks at -9 dBFS for the pure tone; the other timbres
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
            Timbre.SINE to Voice(listOf(1 to 1.0), 0.0, 5.0, 4.0),
            Timbre.CLASSIC to Voice(listOf(1 to 1.0, 2 to db(-18.0), 3 to db(-26.0), 4 to db(-38.0)), 3200.0, 5.0, 7.0),
            Timbre.WARM to Voice(listOf(1 to 1.0, 2 to db(-12.0)), 1200.0, 4.8, 6.0),
            Timbre.BRIGHT to Voice(listOf(1 to 1.0, 2 to db(-12.0), 3 to db(-17.0), 5 to db(-28.0)), 5000.0, 5.5, 7.0),
        )

    private val fs = sampleRate.toDouble()
    private val aUp = 1 - exp(-1 / (TAU_UP_S * fs))
    private val aDown = 1 - exp(-1 / (TAU_DOWN_S * fs))
    private val aUp2 = 1 - exp(-1 / (TAU_UP2_S * fs))
    private val aDown2 = 1 - exp(-1 / (TAU_DOWN2_S * fs))
    private val room = Room(sampleRate)
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

    /** Glide stages for pitch and volume (2; 1 renders the v0.1.3 single stage for the before / after check). */
    var glidePoles: Int = 2

    /** Room send 0..1 (0 for analysis renders). */
    var roomWet: Double = ROOM_WET

    private var midiS = Double.NaN
    private var midiA = Double.NaN
    private var volA = 0.0
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
        val two = glidePoles >= 2
        val aPitch = 1 - exp(-1 / ((if (two) pitchTauS / 2 else pitchTauS) * fs))
        val aU = if (two) aUp2 else aUp
        val aD = if (two) aDown2 else aDown
        if (midiS.isNaN()) {
            midiS = targetMidi
            midiA = targetMidi
        }
        // stillness for the vibrato: |change| below 0.15 semitone per 300 ms
        val blockS = n / fs
        if (!lastTarget.isNaN() && abs(targetMidi - lastTarget) < STILL_SEMITONES * blockS / STILL_WINDOW_S) stableS += blockS else stableS = 0.0
        lastTarget = targetMidi
        val vibTarget = if (!vibratoEnabled || v.vibCents <= 0) 0.0 else if (stableS > STILL_WINDOW_S) 1.0 else VIB_MOVING
        val lpA = if (v.lowPassHz > 0) 1 - exp(-2 * PI * v.lowPassHz / fs) else 0.0
        val wantGain = master.coerceIn(0.0, 1.0)
        for (i in 0 until n) {
            if (two) {
                midiA += aPitch * (targetMidi - midiA)
                midiS += aPitch * (midiA - midiS)
                volA += (if (targetVolume > volA) aU else aD) * (targetVolume - volA)
            } else {
                midiS += aPitch * (targetMidi - midiS)
                volA = targetVolume
            }
            var dv = (if (volA > vol) aU else aD) * (volA - vol)
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
            if (roomWet > 0) y = y * (1 - roomWet) + room.process(y) * roomWet
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
        /** Total pitch glide (two stages of half each): about a 30 ms portamento. */
        const val TAU_PITCH_S = 0.028
        const val TAU_PITCH_STRONG_S = 0.040
        const val TAU_UP2_S = 0.018
        const val TAU_DOWN2_S = 0.035
        const val VIB_MOVING = 0.45
        const val ROOM_WET = 0.12
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

/** A small mono Schroeder room: four parallel damped combs into two all-passes (about 0.6 s decay). */
private class Room(sampleRate: Int) {
    private val k = sampleRate / 44_100.0
    private val combs = intArrayOf(1116, 1188, 1277, 1356).map { DoubleArray((it * k).toInt()) }
    private val combPos = IntArray(4)
    private val combLp = DoubleArray(4)
    private val aps = intArrayOf(556, 341).map { DoubleArray((it * k).toInt()) }
    private val apPos = IntArray(2)

    fun process(x: Double): Double {
        var sum = 0.0
        for (i in 0..3) {
            val b = combs[i]
            val y = b[combPos[i]]
            combLp[i] = y * (1 - DAMP) + combLp[i] * DAMP
            b[combPos[i]] = x + combLp[i] * FEEDBACK
            combPos[i] = (combPos[i] + 1) % b.size
            sum += y
        }
        var y = sum * 0.25
        for (i in 0..1) {
            val b = aps[i]
            val d = b[apPos[i]]
            val o = -y + d
            b[apPos[i]] = y + d * 0.5
            apPos[i] = (apPos[i] + 1) % b.size
            y = o
        }
        return y
    }

    private companion object {
        const val FEEDBACK = 0.72
        const val DAMP = 0.35
    }
}

