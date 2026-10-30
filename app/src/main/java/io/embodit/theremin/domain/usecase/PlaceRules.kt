package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Where the instrument goes (v0.1.5; was `DeskRules`). Nothing here reads the real room any more (taste D24: real
 * objects never block, collide with or occlude the content): the "floor" is the Stage's own horizontal plane
 * y = [FLOOR_Y] (the Stage origin sits on the floor), a marker on it puts the instrument on its virtual stand, and
 * before any marker the instrument waits beside the place panel. Desk detection, edge snapping and plane colliders
 * were deleted after the headset feedback "琴和 UI 都被我桌上显示屏挡住了，还导致射线交互用不了".
 */
object PlaceRules {
    /** The Stage's floor plane (its origin is at floor height). */
    const val FLOOR_Y = 0f

    /** A floor marker puts the instrument on its stand at desk height (the stand's legs are 0.70 m). */
    const val STAND_TOP_M = 0.75f

    /**
     * Before any marker: the instrument waits this far beyond the panel and this far to its side (D20), a little lower.
     * v0.1.5: nearer than before (0.70 m beyond put it where a desk monitor stands, 1.4 m from the eyes).
     */
    const val WAIT_BEYOND_M = 0.35f
    const val WAIT_SIDE_M = 0.55f

    /**
     * The waiting instrument's feet sit this far under the place panel's centre (the panel is 15 degrees under the eyes):
     * a seated player (eyes 1.1 m) gets the ripples at about 0.75 m, a standing one (1.65 m) at about 1.3 m, both at
     * hand height. v0.1.5: was 0.55 m, which put the cabinet 0.33 m over the floor for a seated player (headset log).
     */
    const val WAIT_DROP_M = 0.35f

    /** The farthest a marker ray is followed, metres. */
    const val MARKER_MAX_M = 6f
    const val MARKER_MIN_M = 0.2f

    /** Yaw that turns the instrument's +Z onto the level direction [dir]. */
    fun yawFacing(dir: Vec3): Float = atan2(dir.x, dir.z)

    /** Yaw that turns +Z from [from] towards [to]. */
    fun yawTowards(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (dx * dx + dz * dz < 1e-6f) 0f else atan2(dx, dz)
    }

    /** Signed smallest difference a - b, radians. */
    fun angle(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    /**
     * Where the instrument waits before the user has set a marker (taste D1 / D20, v0.1.2): never from the head.
     * It stands [WAIT_BEYOND_M] beyond the panel (along the panel's facing, away from the user) and [WAIT_SIDE_M]
     * to the panel's right, a little lower, its front turned to [user] (where the user stands; v0.1.3), or back to
     * the panel's side without one. So it never sits between the user and the panel.
     */
    fun besidePanel(panel: Vec3, panelFacing: Vec3, user: Vec3? = null): InstrumentPose {
        val toUser = Vec3(panelFacing.x, 0f, panelFacing.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, 1f) else it.normalized() }
        val right = toUser.cross(Vec3.UP).normalized() * -1f
        val c = panel - toUser * WAIT_BEYOND_M + right * WAIT_SIDE_M
        val y = maxOf(panel.y - WAIT_DROP_M, FLOOR_Y + 0.3f)
        val center = Vec3(c.x, y, c.z)
        return InstrumentPose(center, if (user != null) yawTowards(center, user) else yawFacing(toUser))
    }

    /** Level angle in degrees between the instrument's front (+Z of [pose]) and the direction from it to [user]. */
    fun facingErrorDeg(pose: InstrumentPose, user: Vec3): Float {
        val front = pose.rotation.rotate(Vec3(0f, 0f, 1f))
        return facingErrorDeg(front, user - pose.center)
    }

    /** Level angle in degrees between two directions. */
    fun facingErrorDeg(a: Vec3, b: Vec3): Float {
        val d = angle(atan2(a.x, a.z), atan2(b.x, b.z))
        return abs(Math.toDegrees(d.toDouble()).toFloat())
    }

    /** A floor marker: the instrument on its stand at desk height, its front towards [face] (the user). */
    fun onFloor(point: Vec3, face: Vec3, floorY: Float = FLOOR_Y): InstrumentPose {
        val center = Vec3(point.x, floorY + STAND_TOP_M, point.z)
        return InstrumentPose(center, yawTowards(center, face))
    }

    /**
     * Where a pointing ray meets the floor plane, or null when it points up / too steep / too far. Analytic: the ray
     * only ever meets the app's own virtual floor, never a real surface (D24).
     */
    fun floorHit(origin: Vec3, direction: Vec3, floorY: Float = FLOOR_Y): Vec3? {
        if (direction.y > -1e-3f) return null
        val t = (floorY - origin.y) / direction.y
        if (t < MARKER_MIN_M || t > MARKER_MAX_M) return null
        return origin + direction * t
    }
}
