package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Whether the saved world anchor may bring the instrument back on launch (v0.1.8, taste D9 "恢复上次锚点也不能落在
 * 身后"; headset v0.1.7: "琴一开始出现的位置是我的右后方" and "面板直接没了"). The anchor is a real-room position, so after
 * the user turned round or moved, it can be behind them or across the room, and the music stand rides with it. Both
 * the instrument and the stand beside it must lie within [MAX_OFF_DEG] of the head's horizontal heading and
 * [MIN_DIST_M] - [MAX_DIST_M] from the eyes; otherwise the instrument starts in front of the user and the saved spot
 * is offered as 「放回上次的位置」. Pure, tested on the JVM.
 */
object RestoreRules {
    const val MAX_OFF_DEG = 45f
    const val MIN_DIST_M = 0.5f
    const val MAX_DIST_M = 2.5f

    /** Where one thing lies from the eyes: distance, level bearing (positive = to the right) and whether it passes. */
    data class Seen(val name: String, val dist: Float, val bearingDeg: Float) {
        val inView: Boolean
            get() = abs(bearingDeg) <= MAX_OFF_DEG
        val inRange: Boolean
            get() = dist in MIN_DIST_M..MAX_DIST_M
        val ok: Boolean
            get() = inView && inRange

        /** The first rule it breaks, for the log; null when it passes. */
        val why: String?
            get() = when {
                abs(bearingDeg) > 90f -> "behind"
                !inView -> "off_view"
                dist > MAX_DIST_M -> "too_far"
                dist < MIN_DIST_M -> "too_near"
                else -> null
            }

        override fun toString(): String = String.format(java.util.Locale.US, "%s dist=%.2f bearing=%+.0f%s", name, dist, bearingDeg, why?.let { " $it" } ?: "")
    }

    data class Decision(val restore: Boolean, val instrument: Seen, val stand: Seen) {
        override fun toString(): String = "restore=$restore $instrument $stand"
    }

    /** [point] seen from [eye] looking along [forward] (only its horizontal heading counts). */
    fun seen(name: String, point: Vec3, eye: Vec3, forward: Vec3): Seen {
        val d = point - eye
        val heading = PlaceUpright.heading(forward) ?: Vec3(0f, 0f, -1f)
        // level bearing from the heading to the point, positive to the viewer's right
        val bearing = PlaceRules.angle(atan2(d.x, -d.z), atan2(heading.x, -heading.z))
        return Seen(name, d.length, Math.toDegrees(bearing.toDouble()).toFloat())
    }

    /**
     * The decision for a saved [pose]: the instrument centre and the stand centre (beside the volume loop, at the height
     * [StandPlacement.liftFor] gives it) seen from the head. [eye] null (no valid head yet) never restores.
     */
    fun decide(pose: InstrumentPose, eye: Vec3?, forward: Vec3?, rightPitch: Boolean, standHalfH: Float): Decision {
        if (eye == null || forward == null) {
            val none = Seen("none", 0f, 180f)
            return Decision(false, none.copy(name = "instrument"), none.copy(name = "stand"))
        }
        val base = StandPlacement.position(pose, StandPlacement.baseLocal(rightPitch), 0f)
        val stand = base + Vec3.UP * StandPlacement.liftFor(base, eye, standHalfH)
        val i = seen("instrument", pose.center, eye, forward)
        val s = seen("stand", stand, eye, forward)
        return Decision(i.ok && s.ok, i, s)
    }
}
