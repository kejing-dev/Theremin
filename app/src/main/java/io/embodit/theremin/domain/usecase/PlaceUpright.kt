package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** An upright pose: a position and a turn about the vertical only (pitch and roll are always zero). */
data class UprightPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = PlaceUpright.rotation(yaw)
}

/**
 * The one way a panel (or any content the app lays out from the viewer) gets its pose (taste D9, v0.1.5,
 * X-ray hand "菜单一打开就斜着向下"): only the head's **horizontal** heading is read; the panel is put [distance]
 * metres away, [dropDeg] below the eye line (the eyes' horizontal plane, not the gaze), turned [bearingDeg] to the
 * side, and faces the viewer upright. Pitch and roll never come from the head: a viewer looking 30 degrees down
 * or up, or with the head tilted, gets the same upright panel at the same height. Pure, tested on the JVM.
 */
object PlaceUpright {
    /** Default distance and drop of a panel placed in front of the viewer (D9: 0.8 - 1.2 m, 10 - 20 degrees below). */
    const val DISTANCE_M = 0.85f
    const val DROP_DEG = 15f

    /** Rotation that turns +Z to the yaw heading, upright. */
    fun rotation(yaw: Float): Quat4 = Quat4.axisAngle(Vec3.UP, yaw)

    /**
     * The yaw of the viewer's heading [forward] (the panel's +Z then points back at the viewer); [fallback] when the
     * viewer looks straight up or down and the heading is undefined.
     */
    fun yawOf(forward: Vec3, fallback: Float): Float =
        if (hypot(forward.x, forward.z) < 0.05f) fallback else atan2(-forward.x, -forward.z)

    /** The viewer's horizontal heading as a unit vector, or null when undefined. */
    fun heading(forward: Vec3): Vec3? {
        val h = Vec3(forward.x, 0f, forward.z)
        return if (h.length < 0.05f) null else h.normalized()
    }

    /**
     * True when a head pose can be used for placement: not the all-zero / identity pose the tracker reports before it
     * is ready (sign-hands lesson: that frame sits at the Stage origin, on the floor).
     */
    fun validHead(position: Vec3, rotation: Quat4): Boolean {
        if (!position.isFinite()) return false
        val zero = position.length < 1e-3f
        val identity = abs(rotation.w) > 0.9999f && abs(rotation.x) < 1e-3f && abs(rotation.y) < 1e-3f && abs(rotation.z) < 1e-3f
        return !(zero && identity) && position.length > 1e-3f
    }

    /**
     * Places content from the viewer: [distance] m along the heading turned by [bearingDeg] (positive = to the viewer's
     * right), dropped so that its centre lies [dropDeg] below the eye line, upright and turned back to the viewer.
     */
    fun place(eye: Vec3, forward: Vec3, distance: Float = DISTANCE_M, dropDeg: Float = DROP_DEG, bearingDeg: Float = 0f, fallbackYaw: Float = 0f): UprightPose {
        val yaw = yawOf(forward, fallbackYaw)
        val bearing = Math.toRadians(bearingDeg.toDouble()).toFloat()
        val drop = Math.toRadians(dropDeg.toDouble()).toFloat()
        // heading of the content from the eye: yaw turns -Z (ahead) ... +Z of the content points back, so ahead = -(+Z)
        val aheadYaw = yaw + Math.PI.toFloat() - bearing
        val ahead = Vec3(sin(aheadYaw), 0f, cos(aheadYaw))
        val position = eye + ahead * (distance * cos(drop)) - Vec3.UP * (distance * sin(drop))
        // face the eye: +Z towards the eye's level position
        val toEye = Vec3(eye.x - position.x, 0f, eye.z - position.z)
        val faceYaw = if (toEye.length < 1e-4f) yaw else atan2(toEye.x, toEye.z)
        return UprightPose(position, faceYaw)
    }

    /** Angle in degrees between a rotation's up axis and the world up. */
    fun tiltDeg(rotation: Quat4): Float {
        val up = rotation.rotate(Vec3.UP)
        return Math.toDegrees(kotlin.math.acos(up.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
