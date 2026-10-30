package io.embodit.theremin.ui.play.scene

import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.FacePanelUseCase
import io.embodit.theremin.domain.usecase.RestBodyUseCase
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.platform.tracking.HeadPose
import java.util.Locale
import kotlin.math.PI

/** A pose in Stage space: where a followed thing sits and its level turn (yaw about the vertical). */
data class FollowPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * World-fixed panel pose (taste D21, v0.1.3; was a lazy follow): the panel is set once where it first
 * appears, [headOffset] in the head frame (x right, y up, -z ahead), level, turned to the head's heading,
 * and then stays put in the world: no per-frame turning, no following. It moves only when the user drags
 * it ([moveBy]) or asks for it ([park] = "叫回面前", a 0.35 s ease-in-out slide to the designed pose of that
 * moment). The surface rule keeps it out of real surfaces ([halfExtents] along its own axes).
 */
internal class Follower(
    private val name: String,
    private val headOffset: Vec3,
    private val halfExtents: List<Float>,
    private val lean: Quat4 = Quat4.IDENTITY,
) {
    private val restRule = RestBodyUseCase()
    private var started = false
    private var restYaw = 0f
    private var anchorPos = Vec3.ZERO
    private var applied = Vec3.ZERO
    private var slideStartMs = NEVER
    private var slideFromPos = Vec3.ZERO
    private var slideFromYaw = 0f
    private var slideFromApplied = Vec3.ZERO
    private var slideToPos = Vec3.ZERO
    private var slideToYaw = 0f
    private var slideToApplied = Vec3.ZERO
    private var lastPlaneCount = -1

    /** The current pose (centre and level turn). */
    var pose: FollowPose = FollowPose(Vec3.ZERO, 0f)
        private set

    /** Turned to the head's heading and tilted to face the head from its elevation (plus the designed lean). */
    val rotation: Quat4
        get() = pose.rotation * Quat4.axisAngle(Vec3.RIGHT, pitch * FACE_SHARE) * lean

    /** Elevation of the designed place below the horizontal, radians (negative = below). */
    private var pitch = 0f

    /**
     * Highest elevation the designed place may take, radians (set every frame by the scene from the rack:
     * `PanelClearance`, v0.1.1); never below the 40-degree floor.
     */
    var pitchCap = Float.POSITIVE_INFINITY

    val sliding: Boolean
        get() = slideStartMs != NEVER

    fun reset() {
        started = false
        slideStartMs = NEVER
    }

    /**
     * The designed place: [headOffset]'s distance, its own bearing beside the gaze heading, and its own
     * elevation added to the gaze elevation, clamped to 24 - 40 degrees below the horizontal (design
     * `panels.control.placement`: the panel stays low, under the lower tier's drums, and does not climb
     * when the user looks up at the small bells nor dive when they look down at the big ones).
     */
    private fun designedFor(head: HeadPose): Pair<Vec3, Float> {
        val yaw = FacePanelUseCase.yawOf(head.forward, restYaw)
        val gaze = kotlin.math.asin(head.forward.y.coerceIn(-1f, 1f))
        val flat = kotlin.math.sqrt(headOffset.x * headOffset.x + headOffset.z * headOffset.z)
        val own = kotlin.math.atan2(headOffset.y, flat)
        pitch = minOf((gaze + own).coerceIn(MIN_PITCH_RAD, MAX_PITCH_RAD), pitchCap).coerceAtLeast(MIN_PITCH_RAD)
        val bearing = kotlin.math.atan2(-headOffset.x, -headOffset.z)
        val range = headOffset.length
        val dir = Quat4.axisAngle(Vec3.UP, yaw + bearing).rotate(Vec3(0f, kotlin.math.sin(pitch), -kotlin.math.cos(pitch)))
        return (head.position + dir * range) to yaw
    }

    private fun correction(pos: Vec3, yaw: Float, headPos: Vec3, planes: Collection<PlaneSurface>): Vec3 {
        val turn = Quat4.axisAngle(Vec3.UP, yaw) * lean
        val axes = listOf(turn.rotate(Vec3.RIGHT), turn.rotate(Vec3.UP), turn.rotate(Vec3(0f, 0f, 1f)))
        return restRule.rest(pos, axes, halfExtents, headPos, planes) - pos
    }

    /** Once per frame. Returns true when the pose changed this frame. */
    fun follow(head: HeadPose, planes: Collection<PlaneSurface>, planeCount: Int, nowMs: Long): Boolean {
        if (!started) {
            val (pos, yaw) = designedFor(head)
            started = true
            restYaw = yaw
            anchorPos = pos
            applied = correction(pos, yaw, head.position, planes)
            lastPlaneCount = planeCount
            write()
            ThLog.i("FOLLOW $name park reason=start pos=${ThLog.pos(pose.position)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(yaw.toDouble()))}")
            return true
        }
        if (sliding) {
            slide(nowMs)
            return true
        }
        if (planeCount != lastPlaneCount) {
            lastPlaneCount = planeCount
            val wanted = correction(anchorPos, restYaw, head.position, planes)
            if ((wanted - applied).length > DEAD_BAND_M) {
                applied = wanted
                write()
                return true
            }
        }
        return false
    }

    /** Dragged by the handle: moves by [delta] (Stage metres) and stays there. */
    fun moveBy(delta: Vec3) {
        if (!started || sliding) return
        anchorPos += delta
        write()
    }

    /** Slides to the designed pose now ("叫回面前", debug `park`). */
    fun park(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long) {
        if (!started) return
        startSlide(head, planes, nowMs, "park")
    }

    private fun startSlide(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long, reason: String) {
        val (pos, yaw) = designedFor(head)
        slideFromPos = anchorPos
        slideFromYaw = restYaw
        slideFromApplied = applied
        slideToPos = pos
        slideToYaw = yaw
        slideToApplied = correction(pos, yaw, head.position, planes)
        slideStartMs = nowMs
        ThLog.i("FOLLOW $name slide reason=$reason to=${ThLog.pos(pos + slideToApplied)}")
        slide(nowMs)
    }

    private fun slide(nowMs: Long) {
        val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
        val t = Ease.inOutCubic(q)
        var dYaw = slideToYaw - slideFromYaw
        while (dYaw > PI.toFloat()) dYaw -= 2f * PI.toFloat()
        while (dYaw < -PI.toFloat()) dYaw += 2f * PI.toFloat()
        anchorPos = slideFromPos.lerp(slideToPos, t)
        restYaw = slideFromYaw + dYaw * t
        applied = slideFromApplied.lerp(slideToApplied, t)
        write()
        if (q >= 1f) slideStartMs = NEVER
    }

    private fun write() {
        pose = FollowPose(anchorPos + applied, restYaw)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val SLIDE_MS = 350f
        private const val DEAD_BAND_M = 0.01f
        private const val MIN_PITCH_RAD = -0.6981f
        private const val MAX_PITCH_RAD = -0.4189f
        private const val FACE_SHARE = 0.8f

        /** 18 degrees left and 24 degrees down, 0.6 m from the head (design `panels.control.placement`). */
        val MAIN_OFFSET = Vec3(-0.1694f, -0.2440f, -0.5213f)

        /** The control panel leans back 12 degrees. */
        val MAIN_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.20944f)
    }
}
