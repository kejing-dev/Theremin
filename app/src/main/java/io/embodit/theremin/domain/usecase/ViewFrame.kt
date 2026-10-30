package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pose of the viewer's head in Stage metres: -Z of [rotation] is the viewing direction. */
data class ViewPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)
}

/** A point as the viewer sees it: [yaw] to the right and [pitch] upwards of the viewing direction, degrees. */
data class ViewPoint(val yaw: Float, val pitch: Float, val range: Float)

/**
 * A rectangle in the view, in degrees: centre and half sizes. Layout decisions (tags, plates) are made
 * on these, on the flat chart (yaw, pitch); inside the comfortable part of the view the chart is close
 * enough to what the eye sees.
 */
data class ViewRect(val yaw: Float, val pitch: Float, val halfYaw: Float, val halfPitch: Float) {
    val area: Float
        get() = 4f * halfYaw * halfPitch

    fun inflated(degrees: Float): ViewRect = ViewRect(yaw, pitch, halfYaw + degrees, halfPitch + degrees)

    fun movedBy(dYaw: Float, dPitch: Float): ViewRect = ViewRect(yaw + dYaw, pitch + dPitch, halfYaw, halfPitch)

    /** Overlapping area with [other], square degrees; 0 when they only touch. */
    fun overlap(other: ViewRect): Float {
        val w = min(yaw + halfYaw, other.yaw + other.halfYaw) - max(yaw - halfYaw, other.yaw - other.halfYaw)
        val h = min(pitch + halfPitch, other.pitch + other.halfPitch) - max(pitch - halfPitch, other.pitch - other.halfPitch)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    fun intersects(other: ViewRect): Boolean = overlap(other) > 0f

    /**
     * True when the segment from ([y0], [p0]) to ([y1], [p1]), made [halfWidth] thick, meets the
     * rectangle. The same test as [clipSegment], written without allocations: the plate layout asks it
     * for every piece of every blade in every frame.
     */
    fun meetsSegment(y0: Float, p0: Float, y1: Float, p1: Float, halfWidth: Float): Boolean {
        val xmin = yaw - halfYaw - halfWidth
        val xmax = yaw + halfYaw + halfWidth
        val ymin = pitch - halfPitch - halfWidth
        val ymax = pitch + halfPitch + halfWidth
        val dx = y1 - y0
        val dy = p1 - p0
        var t0 = 0f
        var t1 = 1f
        if (abs(dx) < 1e-9f) {
            if (y0 < xmin || y0 > xmax) return false
        } else {
            val ta = (xmin - y0) / dx
            val tb = (xmax - y0) / dx
            t0 = max(t0, min(ta, tb))
            t1 = min(t1, max(ta, tb))
            if (t0 > t1) return false
        }
        if (abs(dy) < 1e-9f) {
            if (p0 < ymin || p0 > ymax) return false
        } else {
            val ta = (ymin - p0) / dy
            val tb = (ymax - p0) / dy
            t0 = max(t0, min(ta, tb))
            t1 = min(t1, max(ta, tb))
            if (t0 > t1) return false
        }
        return true
    }

    /**
     * The part of the segment from ([y0], [p0]) to ([y1], [p1]), made [halfWidth] thick, that lies over
     * the rectangle: its start and end as shares of the way along the segment, or null when they do not meet.
     */
    fun clipSegment(y0: Float, p0: Float, y1: Float, p1: Float, halfWidth: Float): Pair<Float, Float>? {
        // Liang-Barsky clip of the segment against the rectangle grown by the half width
        val xmin = yaw - halfYaw - halfWidth
        val xmax = yaw + halfYaw + halfWidth
        val ymin = pitch - halfPitch - halfWidth
        val ymax = pitch + halfPitch + halfWidth
        val dx = y1 - y0
        val dy = p1 - p0
        var t0 = 0f
        var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(y0 - xmin, xmax - y0, p0 - ymin, ymax - p0)
        for (i in 0 until 4) {
            if (abs(p[i]) < 1e-9f) {
                if (q[i] < 0f) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0f) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        return if (t0 <= t1) t0 to t1 else null
    }
}

/** A straight thing in the view (a blade): its two ends with their ranges, and half its width, degrees. */
data class ViewSegment(
    val yaw0: Float,
    val pitch0: Float,
    val range0: Float,
    val yaw1: Float,
    val pitch1: Float,
    val range1: Float,
    val halfWidth: Float,
)

/**
 * The viewer's frame of reference. Everything "as seen from the head" is computed here: where a Stage
 * point lies in the view, how big a thing looks, and the Stage direction of a place in the view.
 */
class ViewFrame(val pose: ViewPose) {
    private val inverse = pose.rotation.conjugate()

    /** Where [point] lies in the view, or null when it is behind the viewer or at the eye. */
    fun see(point: Vec3): ViewPoint? {
        val d = inverse.rotate(point - pose.position)
        val range = d.length
        if (range < MIN_RANGE_M || d.z > -1e-3f) return null
        return ViewPoint(degrees(atan2(d.x, -d.z)), degrees(atan2(d.y, hypot(d.x, d.z))), range)
    }

    /** Stage direction (unit vector) of the place ([yaw], [pitch]) in the view. */
    fun direction(yaw: Float, pitch: Float): Vec3 {
        val y = radians(yaw)
        val p = radians(pitch)
        return pose.rotation.rotate(Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p)))
    }

    /** Stage point at [range] metres in the direction ([yaw], [pitch]). */
    fun point(yaw: Float, pitch: Float, range: Float): Vec3 = pose.position + direction(yaw, pitch) * range

    /** A square of ±[halfDegrees] around [point], or null when the point is not in front of the viewer. */
    fun box(point: Vec3, halfDegrees: Float): ViewRect? {
        val v = see(point) ?: return null
        return ViewRect(v.yaw, v.pitch, halfDegrees, halfDegrees)
    }

    /** A viewer-facing rectangle of [halfWidthM] x [halfHeightM] centred on [center]. */
    fun rect(center: Vec3, halfWidthM: Float, halfHeightM: Float): ViewRect? {
        val v = see(center) ?: return null
        return ViewRect(v.yaw, v.pitch, angle(halfWidthM, v.range), angle(halfHeightM, v.range))
    }

    /** Bounding rectangle of the segment [a] - [b], grown by [halfDegrees]; null when an end is behind. */
    fun bounds(a: Vec3, b: Vec3, halfDegrees: Float): ViewRect? {
        val va = see(a) ?: return null
        val vb = see(b) ?: return null
        return ViewRect(
            (va.yaw + vb.yaw) / 2f,
            (va.pitch + vb.pitch) / 2f,
            abs(va.yaw - vb.yaw) / 2f + halfDegrees,
            abs(va.pitch - vb.pitch) / 2f + halfDegrees,
        )
    }

    companion object {
        private const val MIN_RANGE_M = 0.05f

        fun degrees(radians: Float): Float = Math.toDegrees(radians.toDouble()).toFloat()

        fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

        /** Visual angle, degrees, of a length [sizeM] seen square on from [rangeM]. */
        fun angle(sizeM: Float, rangeM: Float): Float = degrees(atan2(sizeM, rangeM))
    }
}
