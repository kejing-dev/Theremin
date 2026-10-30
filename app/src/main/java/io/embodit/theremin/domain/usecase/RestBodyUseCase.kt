package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs

/**
 * Keeps the instrument body on the viewer's side of real surfaces: when its designed place would put
 * it into a table top, the floor or a wall, it rests on that surface instead of sinking into it.
 *
 * For every recognised plane that has the head at least 5 cm on one side, and whose quad grown by
 * 15 cm contains the foot point of the body centre: the centre must be at least `s + 3 cm` from the
 * plane on the head's side, where `s` is the half extent of the body's bounding box along the plane
 * normal. If it is not, the centre moves along the normal by the deficit (at most three passes over
 * the planes). If that brings the centre nearer than 0.35 m to the head, it slides along that plane,
 * straight away from the head's foot point, until it is 0.35 m away.
 */
class RestBodyUseCase(private val hits: HitSurfaceUseCase = HitSurfaceUseCase()) {

    /**
     * @param center designed place of the body centre, Stage metres
     * @param axes the body's three unit axes in Stage space
     * @param halfExtents half sizes of its bounding box along those axes (bumpers included)
     * @return where the centre has to be
     */
    fun rest(center: Vec3, axes: List<Vec3>, halfExtents: List<Float>, head: Vec3, planes: Collection<PlaneSurface>): Vec3 {
        var c = center
        var lastPlane: PlaneSurface? = null
        var lastNormal = Vec3.UP
        for (pass in 0 until MAX_PASSES) {
            var moved = false
            for (plane in planes) {
                if (plane.vertices.isEmpty()) continue
                var n = plane.normal
                var headSide = (head - plane.vertices[0]).dot(n)
                if (headSide < 0f) {
                    n = -n
                    headSide = -headSide
                }
                if (headSide < HEAD_CLEAR_M) continue
                if (!hits.contains(plane, c, margin = GROW_M)) continue
                var support = 0f
                for (i in axes.indices) support += abs(axes[i].dot(n)) * halfExtents[i]
                val depth = (c - plane.vertices[0]).dot(n)
                val deficit = support + GAP_M - depth
                if (deficit > 1e-5f) {
                    c += n * deficit
                    lastPlane = plane
                    lastNormal = n
                    moved = true
                }
            }
            if (!moved) break
        }
        val plane = lastPlane ?: return c
        val range = c.distanceTo(head)
        if (range >= MIN_RANGE_M) return c
        // slide along the plane, straight away from the point of the plane under (or beside) the head
        val height = (head - c).dot(lastNormal)
        val foot = head - lastNormal * height
        var away = (c - foot).rejectFrom(lastNormal)
        if (away.length < 1e-4f) away = (c - head).rejectFrom(lastNormal)
        if (away.length < 1e-4f) return c
        away = away.normalized()
        val along = (c - foot).dot(away)
        // distance to the head: sqrt(height^2 + along^2) must reach MIN_RANGE_M
        val needed = kotlin.math.sqrt((MIN_RANGE_M * MIN_RANGE_M - height * height).coerceAtLeast(0f))
        if (needed <= along) return c
        val slid = c + away * (needed - along)
        return if (hits.contains(plane, slid, margin = 1f)) slid else c
    }

    companion object {
        const val HEAD_CLEAR_M = 0.05f
        const val GROW_M = 0.15f
        const val GAP_M = 0.03f
        const val MIN_RANGE_M = 0.35f
        private const val MAX_PASSES = 3
    }
}
