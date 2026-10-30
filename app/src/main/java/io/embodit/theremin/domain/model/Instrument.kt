package io.embodit.theremin.domain.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The instrument's geometry and its two mappings (design package `instrument`). Plain functions over the
 * instrument frame: origin = centre of the feet on the desk top, +Y up, +Z towards the player, +X the
 * player's right. Everything here runs on a plain JVM (unit tests) and on the synth thread.
 */
enum class Scale(val step: Float, val top: Int) {
    /** 2.2 cm per semitone, C .. D' (14 semitones above the low C). */
    STANDARD(0.022f, 14),

    /** 2.5 cm per semitone, C .. C'. */
    WIDE(0.025f, 12),
}

enum class Octave(val lowMidi: Int) {
    LOW(48),
    MID(60),
    HIGH(72),
}

enum class Curve { SOFT, LINEAR }

enum class Quantize { OFF, LIGHT, STRONG }

enum class Timbre { SINE, CLASSIC, WARM, BRIGHT }

/** Which hand holds pitch. LEFT mirrors the instrument (x negated part by part, never scale -1). */
enum class Handed { RIGHT_PITCH, LEFT_PITCH }

object Instrument {
    /** Horizontal plane of the pitch ripples (and of the bead), instrument y. */
    const val RIPPLE_Y = 0.20f

    /** Innermost ripple radius: fingers stretched out never touch the rod. */
    const val R_TOP = 0.09f

    /** Pitch antenna axis x (right-handed layout), z = 0; rod y 0.10 .. 0.53. */
    const val ANTENNA_X = 0.20f
    const val ANTENNA_BASE_Y = 0.10f
    const val ANTENNA_TOP_Y = 0.53f

    /** Volume loop centre (right-handed layout), outer diameter 0.16 m. */
    val LOOP_CENTER = Vec3(-0.31f, 0.12f, 0.12f)
    const val LOOP_RADIUS = 0.08f

    /** A volume hand counts as "on the loop" within this horizontal distance of its centre. */
    const val LOOP_REACH_M = 0.20f

    /** Volume column: y 0.12 .. 0.34; the mute band ends at 0.16. */
    const val COLUMN_TOP_Y = 0.34f

    /** Pitch point: the palm pushed 4 cm along the level wrist -> palm direction. */
    const val PALM_LEAD_M = 0.04f

    /** Palm height above the loop is measured 2 cm low (the palm joint sits above the skin). */
    const val PALM_DROP_M = 0.02f

    /** Ripple sector, degrees from +Z, positive towards -X (the volume side); labels at -50. */
    const val SECTOR_FROM_DEG = -60f
    const val SECTOR_TO_DEG = 25f
    const val LABEL_DEG = -50f

    /** Cabinet 0.46 x 0.09 x 0.15 on 1 cm feet; footprint with the reaching loop 0.62 x 0.275. */
    val CABINET = Vec3(0.46f, 0.09f, 0.15f)
    const val CABINET_BOTTOM_Y = 0.01f

    /** Music stand panel centre (right-handed), leaning back 12 degrees, facing the player. */
    val STAND_CENTER = Vec3(-0.03f, 0.42f, -0.13f)
    const val STAND_LEAN_DEG = 12f

    fun antennaX(h: Handed): Float = if (h == Handed.RIGHT_PITCH) ANTENNA_X else -ANTENNA_X

    fun loopCenter(h: Handed): Vec3 = if (h == Handed.RIGHT_PITCH) LOOP_CENTER else Vec3(-LOOP_CENTER.x, LOOP_CENTER.y, LOOP_CENTER.z)

    fun standCenter(h: Handed): Vec3 = if (h == Handed.RIGHT_PITCH) STAND_CENTER else Vec3(-STAND_CENTER.x, STAND_CENTER.y, STAND_CENTER.z)

    /** The music stand's turn in Stage space: the instrument's, leant back [STAND_LEAN_DEG]; its +Z faces the player. */
    fun standRotation(pose: InstrumentPose): Quat4 = pose.rotation * Quat4.axisAngle(Vec3.RIGHT, -Math.toRadians(STAND_LEAN_DEG.toDouble()).toFloat())

    /** Sector angle as laid out: mirrored layouts negate it. */
    fun sectorDeg(deg: Float, h: Handed): Float = if (h == Handed.RIGHT_PITCH) deg else -deg

    /** Radius of semitone [n] (0 = the low C) on [scale]. */
    fun radius(n: Float, scale: Scale): Float = R_TOP + (scale.top - n) * scale.step

    fun radius(n: Int, scale: Scale): Float = radius(n.toFloat(), scale)

    /** Outermost drawn radius. */
    fun maxRadius(scale: Scale): Float = radius(0, scale)

    /** Continuous semitone of a distance [d] to the antenna axis, clamped to [-2, top + 0.5]. */
    fun semitone(d: Float, scale: Scale): Float = (scale.top - (d - R_TOP) / scale.step).coerceIn(-2f, scale.top + 0.5f)

    fun midi(s: Float, octave: Octave): Float = octave.lowMidi + s

    /** Twelve-tone equal temperament, A4 = 440 Hz. */
    fun frequency(midi: Float): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    fun frequency(s: Float, octave: Octave): Double = frequency(midi(s, octave))

    /** Nearest semitone of [s] and its offset in cents (-50 .. +50). */
    fun nearest(s: Float): Int = floor(s + 0.5f).toInt()

    fun cents(s: Float): Float = (s - nearest(s)) * 100f

    /** Cents of [s] from a target semitone (not wrapped). */
    fun centsFrom(s: Float, target: Int): Float = (s - target) * 100f

    /** What the synth plays for a hand at [s] (the readout and scoring always use [s] itself). */
    fun quantized(s: Float, q: Quantize): Float =
        when (q) {
            Quantize.OFF -> s
            Quantize.LIGHT -> nearest(s).let { n -> n + 0.5f * (s - n) }
            Quantize.STRONG -> nearest(s).toFloat()
        }

    /**
     * Pitch point of a hand: the palm moved [PALM_LEAD_M] along the level part of wrist -> palm (finger
     * direction does not matter, only where the hand's front edge is).
     */
    fun pitchPoint(palm: Vec3, wrist: Vec3?): Vec3 {
        if (wrist == null) return palm
        val d = Vec3(palm.x - wrist.x, 0f, palm.z - wrist.z)
        val l = d.length
        return if (l < 1e-4f) palm else palm + d * (PALM_LEAD_M / l)
    }

    /** Horizontal distance of an instrument-frame point to the pitch antenna axis. */
    fun antennaDistance(p: Vec3, h: Handed): Float {
        val dx = p.x - antennaX(h)
        return sqrt(dx * dx + p.z * p.z)
    }

    /** Height h of the volume point above the loop plane, or null when the hand is not over the loop. */
    fun loopHeight(palm: Vec3, h: Handed): Float? {
        val c = loopCenter(h)
        val dx = palm.x - c.x
        val dz = palm.z - c.z
        if (sqrt(dx * dx + dz * dz) > LOOP_REACH_M) return null
        return palm.y - PALM_DROP_M - c.y
    }

    /** Volume 0..1 from the height above the loop: silent up to 2 cm, full at 20 cm. */
    fun volume(h: Float?, curve: Curve): Float {
        if (h == null || h <= 0.02f) return 0f
        if (h >= 0.20f) return 1f
        val x = (h - 0.02f) / 0.18f
        return if (curve == Curve.SOFT) x.toDouble().pow(1.8).toFloat() else x
    }

    /** Level angle (degrees) of an instrument point seen from the antenna axis, measured as the sector is. */
    fun sectorAngleOf(p: Vec3, h: Handed): Float {
        val dx = p.x - antennaX(h)
        return Math.toDegrees(atan2(-dx, p.z).toDouble()).toFloat()
    }

    /** Point on the ripple plane at radius [r] and sector angle [deg] (already mirrored). */
    fun ripplePoint(r: Float, deg: Float, h: Handed, y: Float = RIPPLE_Y): Vec3 {
        val a = Math.toRadians(deg.toDouble())
        return Vec3(antennaX(h) - (sin(a) * r).toFloat(), y, (cos(a) * r).toFloat())
    }

    /** Natural notes (white keys) as semitones above C. */
    private val NATURAL = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    fun isNatural(n: Int): Boolean = NATURAL.contains(((n % 12) + 12) % 12)

    /** Ripples drawn on [scale]: every semitone 0..top. */
    fun ripples(scale: Scale): IntRange = 0..scale.top

    /** Solfege name (do re mi ...) of a semitone, the sharp spelled with #. */
    fun solfege(n: Int): String = SOLFEGE[((n % 12) + 12) % 12]

    /** Letter name with octave: semitone 4 in the middle octave -> "E4". */
    fun letter(n: Int, octave: Octave): String {
        val midi = octave.lowMidi + n
        return LETTERS[((midi % 12) + 12) % 12] + (midi / 12 - 1)
    }

    fun letterOnly(n: Int): String = LETTERS[((n % 12) + 12) % 12]

    private val SOLFEGE = arrayOf("do", "do#", "re", "re#", "mi", "fa", "fa#", "sol", "sol#", "la", "la#", "si")
    private val LETTERS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Semitone of a lesson note name: C D E F G A B, a trailing ' adds an octave. */
    fun parseNote(name: String): Int {
        val base = LETTERS.indexOf(name.trimEnd('\'').uppercase())
        require(base >= 0) { "bad note $name" }
        return base + 12 * name.count { it == '\'' }
    }

    /** Distance [d] to the antenna that plays semitone [s] (inverse of [semitone]). */
    fun distanceFor(s: Float, scale: Scale): Float = radius(s, scale)

    /** Cents between two frequencies. */
    fun centsBetween(f: Double, ref: Double): Double = 1200.0 * ln(f / ref) / ln(2.0)

    fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f

    fun isClose(a: Float, b: Float, eps: Float): Boolean = abs(a - b) <= eps
}

/** Pose of the instrument in Stage space: feet centre on the desk and a level turn. */
data class InstrumentPose(val center: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    fun toStage(local: Vec3): Vec3 = center + rotation.rotate(local)

    fun toLocal(stage: Vec3): Vec3 = rotation.conjugate().rotate(stage - center)

    fun dirToStage(local: Vec3): Vec3 = rotation.rotate(local)
}
