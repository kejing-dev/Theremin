package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

/**
 * Where the music stand panel goes (v0.1.4, user: "跟随物体的位置但是面朝用户"; taste D20 / D21). It rides with the
 * instrument and turns about the vertical only, towards the user, smoothly; its place never comes from the head.
 *
 * v0.1.7 (headset v0.1.6: "UI 面板原来一开始显示的太高了"; "面板会一致抖动，还会 orbit 运动"): it no longer sits on top of
 * the antenna (450 x 560 dp stacked over a 0.53 m antenna put its top edge far above the eyes). It stands BESIDE the
 * instrument on the volume-loop side ([SIDE_X], a little back, so it is never between the user and the instrument
 * and the playing hands do not cover it), its centre [CENTRE_Y] over the instrument's feet, then lifted or lowered
 * once ([liftFor]) so its top edge is under the eyes and its centre 10 - 20 degrees below the eye line. The user's
 * drag is kept in the instrument's frame ([offsetFor]), never along the panel's own turning right axis: the turn
 * cannot carry the panel round in a circle any more.
 */
object StandPlacement {
    const val SIDE_X = 0.64f
    const val CENTRE_Y = 0.30f
    const val BACK_Z = -0.08f
    const val TURN_TAU_S = 0.35f
    const val DEAD_DEG = 4f
    const val MAX_OFFSET_M = 1.2f

    /** Below the eye line: the centre at least [MIN_DROP_DEG] and at most [MAX_DROP_DEG] (D9 / D21). */
    const val MIN_DROP_DEG = 10f
    const val MAX_DROP_DEG = 20f

    /** Panel centre in the instrument frame before the user's drag: beside the volume loop, a little back. */
    fun baseLocal(rightPitch: Boolean): Vec3 = Vec3(if (rightPitch) -SIDE_X else SIDE_X, CENTRE_Y, BACK_Z)

    /** The panel centre in Stage space: [local] (base + the user's offset, instrument frame) plus the height [lift]. */
    fun position(pose: InstrumentPose, local: Vec3, lift: Float): Vec3 = pose.toStage(local) + Vec3.UP * lift

    /** The user's drag result (a Stage position) as an offset from [base] in the instrument frame, clamped. */
    fun offsetFor(pose: InstrumentPose, world: Vec3, base: Vec3, lift: Float): Vec3 =
        clampOffset(pose.toLocal(world - Vec3.UP * lift) - base)

    /**
     * The height change that puts a panel centred at [centre] (half height [halfH]) with its top edge under the [eye]
     * and its centre [MIN_DROP_DEG] - [MAX_DROP_DEG] below the eye line; 0 when it already is.
     */
    fun liftFor(centre: Vec3, eye: Vec3, halfH: Float): Float {
        val flat = kotlin.math.hypot(centre.x - eye.x, centre.z - eye.z).coerceAtLeast(0.3f)
        val highest = minOf(eye.y - flat * tanDeg(MIN_DROP_DEG), eye.y - halfH)
        val lowest = minOf(eye.y - flat * tanDeg(MAX_DROP_DEG), highest)
        return centre.y.coerceIn(lowest, highest) - centre.y
    }

    /** Degrees the panel centre lies below the eye line (positive = below). */
    fun dropDeg(centre: Vec3, eye: Vec3): Float {
        val flat = kotlin.math.hypot(centre.x - eye.x, centre.z - eye.z)
        return Math.toDegrees(atan2((eye.y - centre.y).toDouble(), flat.toDouble())).toFloat()
    }

    private fun tanDeg(d: Float): Float = kotlin.math.tan(Math.toRadians(d.toDouble())).toFloat()

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

    fun clampOffset(o: Vec3): Vec3 =
        Vec3(o.x.coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M), o.y.coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M), o.z.coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M))
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
