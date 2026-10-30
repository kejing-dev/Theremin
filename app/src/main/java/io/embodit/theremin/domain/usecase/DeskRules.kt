package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.SurfaceKind
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** A desk top: a horizontal polygon (Stage metres, y = [top]). */
data class Desk(val id: String, val top: Float, val outline: List<Vec3>, val kind: SurfaceKind) {
    val centroid: Vec3 = outline.fold(Vec3.ZERO) { a, v -> a + v } / outline.size.toFloat()
}

/** Where the instrument snaps: centre and yaw (its +Z towards the player), plus the edge it lines up with. */
data class SnapFrame(val center: Vec3, val yaw: Float, val deskId: String, val edgeLength: Float, val depth: Float)

/**
 * Desk detection and the edge snap (design T6, `instrument.layoutCheck.shallowDesk`; desk rule taken from the
 * Trace Light): a horizontal plane (|normal.y| > 0.95, the SDK's planeOrientation is not trusted) 0.45 - 1.15 m
 * over the floor; TABLE first, CABINET / unknown horizontal planes also count. Without a known floor, a top
 * 0.15 - 1.0 m under the head counts.
 */
object DeskRules {
    const val MIN_EDGE_M = 0.70f

    /** A desk counts when its edge is within this level distance of the head (seated at it). */
    const val NEAR_M = 2.0f
    const val INSET_M = 0.45f
    const val SHALLOW_M = 0.55f
    const val SHALLOW_SHIFT_M = 0.20f
    const val SNAP_IN_M = 0.12f
    const val SNAP_OUT_M = 0.18f
    val SNAP_IN_RAD = Math.toRadians(25.0).toFloat()
    val SNAP_OUT_RAD = Math.toRadians(35.0).toFloat()

    /** A floor marker puts the instrument on its stand at desk height (the stand's legs are 0.70 m). */
    const val STAND_TOP_M = 0.75f

    /** Before any marker: the instrument waits this far beyond the panel and this far to its side (D20). */
    const val WAIT_BEYOND_M = 0.70f
    const val WAIT_SIDE_M = 0.55f
    const val WAIT_DROP_M = 0.25f

    fun floorY(planes: Collection<PlaneSurface>): Float? =
        planes.filter { it.kind == SurfaceKind.FLOOR }.minOfOrNull { it.minY }

    fun desks(planes: Collection<PlaneSurface>, headY: Float?): List<Desk> {
        val floor = floorY(planes)
        return planes
            .filter { abs(it.normal.y) > 0.95f && it.kind in ACCEPTED && it.vertices.size >= 3 }
            .filter { p ->
                val top = (p.minY + p.maxY) / 2f
                when {
                    floor != null -> top - floor in 0.45f..1.15f
                    headY != null -> headY - top in 0.15f..1.0f
                    else -> false
                }
            }
            .sortedBy { if (it.kind == SurfaceKind.TABLE) 0 else 1 }
            .map { p -> Desk(p.id, (p.minY + p.maxY) / 2f, hull(p.vertices), p.kind) }
    }

    private val ACCEPTED = setOf(SurfaceKind.TABLE, SurfaceKind.CABINET, SurfaceKind.OTHER)

    /** Level convex hull (x, z), counter-clockwise seen from above; y kept from the input. */
    fun hull(points: List<Vec3>): List<Vec3> {
        val pts = points.distinctBy { (it.x * 1000).toInt() to (it.z * 1000).toInt() }.sortedWith(compareBy({ it.x }, { it.z }))
        if (pts.size < 3) return pts
        fun cross(o: Vec3, a: Vec3, b: Vec3) = (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x)
        val lower = ArrayList<Vec3>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<Vec3>()
        for (p in pts.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    fun inside(desk: Desk, p: Vec3): Boolean {
        val o = desk.outline
        var c = false
        var j = o.size - 1
        for (i in o.indices) {
            val a = o[i]
            val b = o[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x) c = !c
            j = i
        }
        return c
    }

    /** Desks the user can sit at: an outline point within [maxM] of the head (level), nearest first. */
    fun near(desks: List<Desk>, head: Vec3, maxM: Float = NEAR_M): List<Desk> =
        desks.map { it to levelDistance(it, head) }.filter { it.second <= maxM }.sortedBy { it.second }.map { it.first }

    fun levelDistance(desk: Desk, head: Vec3): Float {
        if (inside(desk, head)) return 0f
        var best = Float.MAX_VALUE
        val o = desk.outline
        for (i in o.indices) {
            val a = o[i]
            val b = o[(i + 1) % o.size]
            val ex = b.x - a.x
            val ez = b.z - a.z
            val l2 = ex * ex + ez * ez
            val t = if (l2 < 1e-8f) 0f else (((head.x - a.x) * ex + (head.z - a.z) * ez) / l2).coerceIn(0f, 1f)
            val dx = a.x + ex * t - head.x
            val dz = a.z + ez * t - head.z
            best = minOf(best, kotlin.math.sqrt(dx * dx + dz * dz))
        }
        return best
    }

    /** Ray / desk top: the point on y = top inside the outline (two-sided), or null. */
    fun hit(desks: List<Desk>, origin: Vec3, dir: Vec3): Pair<Desk, Vec3>? {
        var best: Pair<Desk, Vec3>? = null
        var bestT = Float.MAX_VALUE
        for (d in desks) {
            if (abs(dir.y) < 1e-4f) continue
            val t = (d.top - origin.y) / dir.y
            if (t <= 0f || t > 6f || t >= bestT) continue
            val p = origin + dir * t
            if (inside(d, p)) {
                best = d to p
                bestT = t
            }
        }
        return best
    }

    /**
     * The snap frame of a desk: on its edge nearest to the head (at least 0.70 m long), the instrument centre
     * 0.45 m in from that edge, along the edge opposite the head, its front to the player. A desk shallower than
     * 0.55 m takes depth - 0.10 and slides 0.20 m along the edge to the pitch hand's side.
     */
    fun frame(desk: Desk, head: Vec3, handed: Handed): SnapFrame? {
        val o = desk.outline
        if (o.size < 3) return null
        var best: SnapFrame? = null
        var bestDist = Float.MAX_VALUE
        val h = Vec3(head.x, desk.top, head.z)
        for (i in o.indices) {
            val a = o[i].let { Vec3(it.x, desk.top, it.z) }
            val b = o[(i + 1) % o.size].let { Vec3(it.x, desk.top, it.z) }
            val e = b - a
            val len = e.length
            if (len < MIN_EDGE_M) continue
            val u = e / len
            val t = (h - a).dot(u).coerceIn(0f, len)
            val foot = a + u * t
            val dist = foot.distanceTo(h)
            if (dist >= bestDist) continue
            // inward normal: towards the centroid
            var n = Vec3(-u.z, 0f, u.x)
            if ((desk.centroid - foot).dot(n) < 0f) n = -n
            val depth = o.maxOf { (Vec3(it.x, desk.top, it.z) - a).dot(n) }
            val inset = if (depth < SHALLOW_M) (depth - 0.10f).coerceAtLeast(0.15f) else INSET_M
            val outward = -n
            val playerRight = (-outward).cross(Vec3.UP).normalized()
            val side = if (handed == Handed.RIGHT_PITCH) playerRight else -playerRight
            val shift = if (depth < SHALLOW_M) SHALLOW_SHIFT_M else 0f
            val margin = 0.32f
            val along = if (len > 2 * margin) (t + side.dot(u) * shift).coerceIn(margin, len - margin) else len / 2f
            val center = a + u * along + n * inset
            bestDist = dist
            best = SnapFrame(center, yawFacing(outward), desk.id, len, depth)
        }
        return best
    }

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

    /** Snap with hysteresis: in at 0.12 m / 25 degrees, out at 0.18 m / 35 degrees. */
    fun snap(center: Vec3, yaw: Float, frame: SnapFrame?, held: Boolean): Boolean {
        if (frame == null) return false
        val dist = Vec3(center.x - frame.center.x, 0f, center.z - frame.center.z).length
        val turn = abs(angle(yaw, frame.yaw))
        return if (held) dist <= SNAP_OUT_M && turn <= SNAP_OUT_RAD else dist <= SNAP_IN_M && turn <= SNAP_IN_RAD
    }

    /**
     * Where the instrument waits before the user has set a marker (taste D1 / D20, v0.1.2): never from the head.
     * It stands [WAIT_BEYOND_M] beyond the panel (along the panel's facing, away from the user) and [WAIT_SIDE_M]
     * to the panel's right, a little lower, its front turned to [user] (where the user stands; v0.1.3), or back to
     * the panel's side without one. So it never sits between the user and the panel; the user then points a marker
     * on the floor or a desk and moves it there.
     */
    fun besidePanel(panel: Vec3, panelFacing: Vec3, floorY: Float?, user: Vec3? = null): InstrumentPose {
        val toUser = Vec3(panelFacing.x, 0f, panelFacing.z).let { if (it.length < 1e-3f) Vec3(0f, 0f, 1f) else it.normalized() }
        val right = toUser.cross(Vec3.UP).normalized() * -1f
        val c = panel - toUser * WAIT_BEYOND_M + right * WAIT_SIDE_M
        val y = (panel.y - WAIT_DROP_M - 0.30f).let { if (floorY != null) maxOf(it, floorY + 0.3f) else it }
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

    /** A floor marker: the instrument on its stand at desk height, its front towards [face] (the controller, or the panel). */
    fun onFloor(point: Vec3, floorY: Float, face: Vec3): InstrumentPose {
        val center = Vec3(point.x, floorY + STAND_TOP_M, point.z)
        return InstrumentPose(center, yawTowards(center, face))
    }
}
