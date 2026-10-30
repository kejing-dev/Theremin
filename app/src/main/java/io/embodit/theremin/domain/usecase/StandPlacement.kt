package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

/**
 * Where the music stand panel goes (v0.1.4, user: "跟随物体的位置但是面朝用户"; taste D20 / D21). It rides with the
 * instrument, above it: its lower edge [CLEAR_M] over the antenna top, a little behind the cabinet, so it is never
 * between the user and the instrument and the playing hands (which stay under the antenna top) do not cover it.
 * It turns about the vertical only, towards the user, smoothly; its place never comes from the head. The user can
 * drag it off that place by [offset] (panel right, up; metres) and call it back (offset zero).
 */
object StandPlacement {
    const val CLEAR_M = 0.05f
    const val BACK_M = 0.06f
    const val TURN_TAU_S = 0.35f
    const val DEAD_DEG = 4f
    const val MAX_OFFSET_M = 0.6f

    /** Panel centre before the offset, Stage space, for a panel of half height [halfH]. */
    fun base(pose: InstrumentPose, halfH: Float): Vec3 = pose.toStage(Vec3(0f, Instrument.ANTENNA_TOP_Y + CLEAR_M + halfH, -BACK_M))

    /** Yaw that turns the panel's +Z from [at] to [user], level; [fallback] when the user is right above. */
    fun yawTo(at: Vec3, user: Vec3, fallback: Float): Float {
        val dx = user.x - at.x
        val dz = user.z - at.z
        return if (dx * dx + dz * dz < 1e-4f) fallback else atan2(dx, dz)
    }

    /** One smooth turning step from [current] towards [target] over [dt] seconds (within [DEAD_DEG] it rests). */
    fun turn(current: Float, target: Float, dt: Float): Float {
        val d = PlaceRules.angle(target, current)
        if (abs(Math.toDegrees(d.toDouble())) < DEAD_DEG && dt > 0f) return current
        val k = if (dt <= 0f) 1f else 1f - exp(-dt / TURN_TAU_S)
        return current + d * k
    }

    /** Upright, yaw only (D9, v0.1.5: the 10-degree lean is gone; pitch / roll are always zero). */
    fun rotation(yaw: Float): Quat4 = PlaceUpright.rotation(yaw)

    /** The panel centre: [base] moved by [offset] in the panel's own right / up at [yaw]. */
    fun position(base: Vec3, yaw: Float, offset: Pair<Float, Float>): Vec3 {
        val r = Quat4.axisAngle(Vec3.UP, yaw).rotate(Vec3.RIGHT)
        return base + r * offset.first + Vec3.UP * offset.second
    }

    fun clampOffset(o: Pair<Float, Float>): Pair<Float, Float> =
        o.first.coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M) to o.second.coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M)
}

/**
 * Where a pinch picks the instrument up (v0.1.4: "随时点击物体 drag 移动", playing must not start a drag): the palm, in
 * the instrument frame, over the cabinet itself — under the pitch ripples (y 0.20), off the volume loop (x -0.31)
 * and away from the antenna rod (x 0.20). Mirrored for the left-handed layout.
 */
object GrabRules {
    const val TOP_M = 0.16f
    const val X_LOOP_SIDE = -0.19f
    const val X_ANTENNA_SIDE = 0.12f
    const val Z_BACK = -0.12f
    const val Z_FRONT = 0.17f
    const val HOLD_MS = 300L

    fun grabbable(local: Vec3, rightPitch: Boolean): Boolean {
        val x = if (rightPitch) local.x else -local.x
        return local.y in -0.02f..TOP_M && x in X_LOOP_SIDE..X_ANTENNA_SIDE && local.z in Z_BACK..Z_FRONT
    }
}
