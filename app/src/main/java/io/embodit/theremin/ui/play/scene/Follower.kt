package io.embodit.theremin.ui.play.scene

import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.PlaceUpright
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.platform.tracking.HeadPose
import java.util.Locale
import kotlin.math.PI

/** A pose in Stage space: where a followed thing sits and its level turn (yaw about the vertical). */
data class FollowPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = PlaceUpright.rotation(yaw)
}

/**
 * World-fixed panel pose (taste D21, v0.1.3; was a lazy follow): the panel is set once where it first appears
 * through [PlaceUpright] ([distance] m from the eyes, [dropDeg] below the eye line, [bearingDeg] to the side,
 * upright, turned to the viewer; D9: pitch and roll never come from the head), and then stays put in the world:
 * no per-frame turning, no following. It moves only when the user drags it ([moveBy]) or asks for it ([park] =
 * "叫回面前", a 0.35 s ease-in-out slide to the designed pose of that moment). v0.1.5: no real-surface rule any more
 * (D24: real objects never push the content around).
 */
internal class Follower(
    private val name: String,
    private val distance: Float,
    private val dropDeg: Float,
    private val bearingDeg: Float,
) {
    private var started = false
    private var restYaw = 0f
    private var anchorPos = Vec3.ZERO
    private var slideStartMs = NEVER
    private var slideFromPos = Vec3.ZERO
    private var slideFromYaw = 0f
    private var slideToPos = Vec3.ZERO
    private var slideToYaw = 0f

    /** The current pose (centre and level turn). */
    var pose: FollowPose = FollowPose(Vec3.ZERO, 0f)
        private set

    /** Upright, turned to the viewer's heading only (D9). */
    val rotation: Quat4
        get() = pose.rotation

    val sliding: Boolean
        get() = slideStartMs != NEVER

    fun reset() {
        started = false
        slideStartMs = NEVER
    }

    private fun designedFor(head: HeadPose): Pair<Vec3, Float> {
        val p = PlaceUpright.place(head.position, head.forward, distance, dropDeg, bearingDeg, restYaw)
        return p.position to p.yaw
    }

    /** Once per frame. Returns true when the pose changed this frame. [head] must be a valid pose (see [PlaceUpright.validHead]). */
    fun follow(head: HeadPose, nowMs: Long): Boolean {
        if (!started) {
            val (pos, yaw) = designedFor(head)
            started = true
            restYaw = yaw
            anchorPos = pos
            write()
            ThLog.i("FOLLOW $name park reason=start pos=${ThLog.pos(pose.position)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(yaw.toDouble()))} tilt=${String.format(Locale.US, "%.2f", PlaceUpright.tiltDeg(rotation))}")
            return true
        }
        if (sliding) {
            slide(nowMs)
            return true
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
    fun park(head: HeadPose, nowMs: Long) {
        if (!started) return
        val (pos, yaw) = designedFor(head)
        slideFromPos = anchorPos
        slideFromYaw = restYaw
        slideToPos = pos
        slideToYaw = yaw
        slideStartMs = nowMs
        ThLog.i("FOLLOW $name slide reason=park to=${ThLog.pos(pos)}")
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
        write()
        if (q >= 1f) slideStartMs = NEVER
    }

    private fun write() {
        pose = FollowPose(anchorPos, restYaw)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val SLIDE_MS = 350f
    }
}
