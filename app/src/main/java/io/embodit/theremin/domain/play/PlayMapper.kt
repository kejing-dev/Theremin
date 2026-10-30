package io.embodit.theremin.domain.play

import io.embodit.theremin.domain.model.Curve
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.domain.model.Quantize
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Vec3

/** One tracked hand, Stage metres. */
data class HandSample(val palm: Vec3, val wrist: Vec3?, val pinch: Float = 0f, val pinchReady: Boolean = false)

/** One controller, Stage metres; trigger 0..1. */
data class ControllerHand(val position: Vec3, val trigger: Float)

/** Debug injection of one role, instrument frame. */
sealed interface Inject {
    /** Pitch: distance d to the antenna axis; volume: height h above the loop. */
    data class Direct(val value: Float) : Inject

    /** Raw joints (instrument frame): the full mapping runs on them. */
    data class Joints(val palm: Vec3, val wrist: Vec3?) : Inject
}

enum class Src { NONE, HAND, CONTROLLER }

/** What every sensor delivers at one instant. Real hands and controllers in Stage space. */
data class RoleInputs(
    val pitchHand: HandSample? = null,
    val volumeHand: HandSample? = null,
    val pitchController: ControllerHand? = null,
    val volumeController: ControllerHand? = null,
)

data class MapSettings(
    val scale: Scale = Scale.STANDARD,
    val octave: Octave = Octave.MID,
    val curve: Curve = Curve.SOFT,
    val handed: Handed = Handed.RIGHT_PITCH,
    val quantize: Quantize = Quantize.OFF,
)

/** The result of one mapping step: what the synth plays and what the scene draws. */
data class PlayFrame(
    val nowNs: Long = 0L,
    val placed: Boolean = false,
    val pitchSrc: Src = Src.NONE,
    val volumeSrc: Src = Src.NONE,
    val injected: Boolean = false,
    /** Filtered pitch point, instrument frame (bead and hand tag); null = no pitch hand. */
    val point: Vec3? = null,
    val d: Float = 0f,
    /** The hand's true continuous semitone (readout, scoring) and what the synth is asked to play. */
    val s: Float = 0f,
    val sPlayed: Float = 0f,
    val midiPlayed: Float = 60f,
    val frequency: Double = 261.63,
    val pitchKnown: Boolean = false,
    val frozen: Boolean = false,
    /** Volume hand: palm (instrument) for the column, height above the loop, volume 0..1. */
    val volumePalm: Vec3? = null,
    val h: Float? = null,
    val volume: Float = 0f,
    val triggerMuted: Boolean = false,
    val pitchLost: Boolean = false,
    val volumeLost: Boolean = false,
) {
    val bothLost: Boolean
        get() = pitchLost && volumeLost

    /** Target amplitude of the synth before master gain. */
    val amplitude: Float
        get() = if (!placed || !pitchKnown || pitchLost) 0f else volume
}

/**
 * Hands -> pitch and volume (design `instrument.pitchMapping`, `volumeMapping`, `input.twoHandRule`,
 * `input.lossRule`). Each role picks its own source every step: a tracked hand, else that side's
 * controller; an injection replaces both. A switch of source cross-fades the point over 60 ms. The pitch
 * hand's pinch freezes d until 150 ms after release, then d glides back over 60 ms.
 *
 * [step] is called by the audio thread every block (about 4 ms) and, while no audio runs, by the frame
 * system; the main thread only writes the @Volatile configuration. Not re-entrant: one caller at a time.
 */
class PlayMapper {
    @Volatile var pose: InstrumentPose? = null

    @Volatile var settings: MapSettings = MapSettings()

    /** Loss flags arm only once the instrument is placed (and a role has been seen). */
    @Volatile var armed: Boolean = false

    /** Injection per role (debug), and whether the injection stands in for controllers. */
    @Volatile var injectPitch: Inject? = null

    @Volatile var injectVolume: Inject? = null

    @Volatile var injectAsController: Boolean = false

    /** Debug: an injection source is present (counts as a supported source even with no values). */
    @Volatile var injectSource: Boolean = false

    /** Debug pinch on the pitch hand (freeze test). */
    @Volatile var injectPinch: Boolean = false

    /** The volume controller's trigger aims at a panel: it clicks, it does not mute. */
    @Volatile var triggerOnPanel: Boolean = false

    /** Pauses the readings (drag of the instrument: no pitch while moving it). */
    @Volatile var suspended: Boolean = false

    @Volatile var last: PlayFrame = PlayFrame()
        private set

    private val pitchFilter = OneEuro()
    private val volumeFilter = OneEuro()

    /** Repeated hand points skip the filters (v0.1.4); false only to render the v0.1.3 sound for comparison. */
    var filterSkipsRepeats: Boolean
        get() = pitchFilter.skipRepeats
        set(v) {
            pitchFilter.skipRepeats = v
            volumeFilter.skipRepeats = v
        }
    private var pitchSeenNs = NEVER
    private var volumeSeenNs = NEVER
    private var pitchEverSeen = false
    private var volumeEverSeen = false
    private var lastPitchSrc = Src.NONE
    private var lastVolumeSrc = Src.NONE
    private var pitchFade: Vec3 = Vec3.ZERO
    private var pitchFadeStartNs = NEVER
    private var volumeFade: Vec3 = Vec3.ZERO
    private var volumeFadeStartNs = NEVER
    private var lastPoint: Vec3? = null
    private var lastVolumePalm: Vec3? = null
    private var frozenD = Float.NaN
    private var pinchReleasedNs = NEVER
    private var thawStartNs = NEVER
    private var thawFrom = 0f
    private var lastD = 0f

    fun resetTracking() {
        pitchFilter.reset()
        volumeFilter.reset()
        pitchEverSeen = false
        volumeEverSeen = false
        pitchSeenNs = NEVER
        volumeSeenNs = NEVER
        frozenD = Float.NaN
        lastPoint = null
        lastVolumePalm = null
    }

    fun step(nowNs: Long, input: RoleInputs): PlayFrame {
        val pose = pose
        val cfg = settings
        if (pose == null || suspended) {
            val f = PlayFrame(nowNs = nowNs, placed = pose != null)
            last = f
            return f
        }
        val ip = injectPitch
        val iv = injectVolume
        val injected = injectSource || ip != null || iv != null

        // ---- pitch role: source and raw point (instrument frame)
        var pinch = injectPinch
        val (pSrc, rawP) =
            when {
                injected -> if (ip == null) Src.NONE to null else (if (injectAsController) Src.CONTROLLER else Src.HAND) to injectedPitchPoint(ip, cfg.handed)
                input.pitchHand != null -> {
                    val hnd = input.pitchHand
                    pinch = hnd.pinchReady && hnd.pinch > PINCH_FREEZE
                    Src.HAND to Instrument.pitchPoint(pose.toLocal(hnd.palm), hnd.wrist?.let { pose.toLocal(it) })
                }
                input.pitchController != null -> Src.CONTROLLER to pose.toLocal(input.pitchController.position)
                else -> Src.NONE to null
            }
        // ---- volume role
        var trigger = 0f
        val (vSrc, rawV) =
            when {
                injected -> if (iv == null) Src.NONE to null else (if (injectAsController) Src.CONTROLLER else Src.HAND) to injectedVolumePalm(iv, cfg.handed)
                input.volumeHand != null -> Src.HAND to pose.toLocal(input.volumeHand.palm)
                input.volumeController != null -> {
                    trigger = input.volumeController.trigger
                    Src.CONTROLLER to pose.toLocal(input.volumeController.position)
                }
                else -> Src.NONE to null
            }

        // ---- filtering and source cross-fade
        var point: Vec3? = null
        if (rawP != null) {
            val f = if (ip is Inject.Direct) rawP else pitchFilter.filter(rawP, nowNs)
            if (pSrc != lastPitchSrc && lastPoint != null) {
                pitchFade = lastPoint!! - f
                pitchFadeStartNs = nowNs
            }
            point = f + pitchFade * fadeLeft(pitchFadeStartNs, nowNs)
            pitchSeenNs = nowNs
            pitchEverSeen = true
            lastPoint = point
        } else {
            pitchFilter.reset()
        }
        lastPitchSrc = if (rawP != null) pSrc else lastPitchSrc
        var volumePalm: Vec3? = null
        if (rawV != null) {
            val f = if (iv is Inject.Direct) rawV else volumeFilter.filter(rawV, nowNs)
            if (vSrc != lastVolumeSrc && lastVolumePalm != null) {
                volumeFade = lastVolumePalm!! - f
                volumeFadeStartNs = nowNs
            }
            volumePalm = f + volumeFade * fadeLeft(volumeFadeStartNs, nowNs)
            volumeSeenNs = nowNs
            volumeEverSeen = true
            lastVolumePalm = volumePalm
        } else {
            volumeFilter.reset()
        }
        lastVolumeSrc = if (rawV != null) vSrc else lastVolumeSrc

        // ---- pitch: hold the last value up to 150 ms; pinch freezes d
        val held = point ?: lastPoint?.takeIf { pitchSeenNs != NEVER && nowNs - pitchSeenNs <= HOLD_NS }
        val pitchKnown = held != null
        var d = if (held != null) Instrument.antennaDistance(held, cfg.handed) else lastD
        if (ip is Inject.Direct) d = ip.value
        if (pinch && pitchKnown) {
            if (frozenD.isNaN()) frozenD = d
            pinchReleasedNs = NEVER
        } else if (!frozenD.isNaN()) {
            if (pinchReleasedNs == NEVER) pinchReleasedNs = nowNs
            if (nowNs - pinchReleasedNs > FREEZE_TAIL_NS) {
                thawFrom = frozenD
                thawStartNs = nowNs
                frozenD = Float.NaN
            }
        }
        val frozen = !frozenD.isNaN()
        if (frozen) d = frozenD
        else if (thawStartNs != NEVER) {
            val k = ((nowNs - thawStartNs).toDouble() / THAW_NS).coerceIn(0.0, 1.0).toFloat()
            d = thawFrom + (d - thawFrom) * k
            if (k >= 1f) thawStartNs = NEVER
        }
        lastD = d
        val s = Instrument.semitone(d, cfg.scale)
        val sPlayed = Instrument.quantized(s, cfg.quantize)
        val midi = Instrument.midi(sPlayed, cfg.octave)

        // ---- volume
        val h = when {
            iv is Inject.Direct -> iv.value
            volumePalm != null -> Instrument.loopHeight(volumePalm, cfg.handed)
            else -> null
        }
        val muted = vSrc == Src.CONTROLLER && trigger > TRIGGER_MUTE && !triggerOnPanel
        val volume = if (muted) 0f else Instrument.volume(h, cfg.curve)

        val armedNow = armed
        val pitchLost = armedNow && pitchEverSeen && !pitchKnown
        val volumeLost = armedNow && volumeEverSeen && volumePalm == null && iv !is Inject.Direct
        val frame =
            PlayFrame(
                nowNs = nowNs,
                placed = true,
                pitchSrc = if (rawP != null) pSrc else Src.NONE,
                volumeSrc = if (rawV != null) vSrc else Src.NONE,
                injected = injected,
                point = held,
                d = d,
                s = s,
                sPlayed = sPlayed,
                midiPlayed = midi,
                frequency = Instrument.frequency(midi),
                pitchKnown = pitchKnown,
                frozen = frozen,
                volumePalm = volumePalm,
                h = h,
                volume = volume,
                triggerMuted = muted,
                pitchLost = pitchLost,
                volumeLost = volumeLost,
            )
        last = frame
        return frame
    }

    private fun fadeLeft(startNs: Long, nowNs: Long): Float {
        if (startNs == NEVER) return 0f
        val k = 1f - ((nowNs - startNs).toDouble() / CROSSFADE_NS).toFloat()
        return k.coerceIn(0f, 1f)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        const val HOLD_NS = 150_000_000L
        const val FREEZE_TAIL_NS = 150_000_000L
        const val THAW_NS = 60_000_000.0
        const val CROSSFADE_NS = 60_000_000.0
        const val PINCH_FREEZE = 0.8f
        const val TRIGGER_MUTE = 0.5f

        /** Injected d: a point at that radius in the middle of the sector, 5 cm over the ripples. */
        fun injectedPitchPoint(i: Inject, handed: Handed): Vec3 =
            when (i) {
                is Inject.Direct -> Instrument.ripplePoint(i.value, Instrument.sectorDeg(-20f, handed), handed, Instrument.RIPPLE_Y + 0.05f)
                is Inject.Joints -> Instrument.pitchPoint(i.palm, i.wrist)
            }

        /** Injected h: the palm over the loop centre at that height (palm = h + 2 cm above the loop). */
        fun injectedVolumePalm(i: Inject, handed: Handed): Vec3 =
            when (i) {
                is Inject.Direct -> Instrument.loopCenter(handed).let { Vec3(it.x, it.y + i.value + Instrument.PALM_DROP_M, it.z) }
                is Inject.Joints -> i.palm
            }
    }
}
