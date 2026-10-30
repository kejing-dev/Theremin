package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.SurfaceHit
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs

/**
 * Analytic, two-sided ray / plane-polygon intersection in Stage metres.
 *
 * Physics ray casts cannot be used for placement: colliders built from plane anchors are one-sided and
 * wall quads face outwards, so a ray from inside the room misses them (feasibility spike, row 5).
 */
class HitSurfaceUseCase {

    /** Nearest surface hit by the ray, or null. [direction] must be a unit vector. */
    fun nearest(
        planes: Collection<PlaneSurface>,
        origin: Vec3,
        direction: Vec3,
        maxDistance: Float = MAX_DISTANCE,
    ): SurfaceHit? {
        var best: SurfaceHit? = null
        for (plane in planes) {
            val hit = onSurface(plane, origin, direction, maxDistance) ?: continue
            val current = best
            if (current == null || hit.distance < current.distance) best = hit
        }
        return best
    }

    /** Hit of the ray with one surface, limited to its polygon. */
    fun onSurface(
        plane: PlaneSurface,
        origin: Vec3,
        direction: Vec3,
        maxDistance: Float = MAX_DISTANCE,
    ): SurfaceHit? {
        val v = plane.vertices
        val idx = plane.indices
        var best: SurfaceHit? = null
        var i = 0
        while (i + 2 < idx.size) {
            val v0 = v[idx[i]]
            val v1 = v[idx[i + 1]]
            val v2 = v[idx[i + 2]]
            i += 3
            // Moeller-Trumbore without the back-face test
            val e1 = v1 - v0
            val e2 = v2 - v0
            val pv = direction.cross(e2)
            val det = e1.dot(pv)
            if (abs(det) < 1e-9f) continue
            val inv = 1f / det
            val tv = origin - v0
            val u = tv.dot(pv) * inv
            if (u < -EDGE_EPS || u > 1f + EDGE_EPS) continue
            val qv = tv.cross(e1)
            val w = direction.dot(qv) * inv
            if (w < -EDGE_EPS || u + w > 1f + EDGE_EPS) continue
            val t = e2.dot(qv) * inv
            if (t <= MIN_DISTANCE || t > maxDistance) continue
            val current = best
            if (current == null || t < current.distance) {
                var n = e1.cross(e2).normalized()
                if (n.dot(direction) > 0f) n = -n
                best = SurfaceHit(origin + direction * t, n, t, plane)
            }
        }
        return best
    }

    /**
     * Hit of the ray with the unbounded plane that carries the surface. Used to put a system tap,
     * which lands on the surface's thin box collider, back onto the mathematical plane.
     */
    fun onInfinitePlane(plane: PlaneSurface, origin: Vec3, direction: Vec3): SurfaceHit? {
        if (plane.vertices.isEmpty()) return null
        var n = plane.normal
        val denom = direction.dot(n)
        if (abs(denom) < 1e-6f) return null
        val t = (plane.vertices[0] - origin).dot(n) / denom
        if (t <= MIN_DISTANCE) return null
        if (denom > 0f) n = -n
        return SurfaceHit(origin + direction * t, n, t, plane)
    }

    /** Orthogonal projection of [point] onto the surface's plane. */
    fun project(plane: PlaneSurface, point: Vec3): Vec3 {
        if (plane.vertices.isEmpty()) return point
        val n = plane.normal
        return point - n * (point - plane.vertices[0]).dot(n)
    }

    /** Distance of [point] from the surface's plane, metres. */
    fun distanceToPlane(plane: PlaneSurface, point: Vec3): Float =
        if (plane.vertices.isEmpty()) Float.MAX_VALUE
        else abs((point - plane.vertices[0]).dot(plane.normal))

    /** True when the projection of [point] lies inside the polygon (within [margin] metres). */
    fun contains(plane: PlaneSurface, point: Vec3, margin: Float = 0.002f): Boolean {
        val p = project(plane, point)
        val n = plane.normal
        val v = plane.vertices
        val idx = plane.indices
        var i = 0
        while (i + 2 < idx.size) {
            val a = v[idx[i]]
            val b = v[idx[i + 1]]
            val c = v[idx[i + 2]]
            i += 3
            if (insideTriangle(p, a, b, c, n, margin)) return true
        }
        return false
    }

    private fun insideTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3, n: Vec3, margin: Float): Boolean {
        val sign = if ((b - a).cross(c - a).dot(n) >= 0f) 1f else -1f
        return edgeSide(p, a, b, n) * sign >= -margin &&
            edgeSide(p, b, c, n) * sign >= -margin &&
            edgeSide(p, c, a, n) * sign >= -margin
    }

    /** Signed distance of [p] from the edge a→b inside the plane with normal [n]. */
    private fun edgeSide(p: Vec3, a: Vec3, b: Vec3, n: Vec3): Float {
        val e = b - a
        val len = e.length
        if (len < 1e-9f) return 0f
        return e.cross(p - a).dot(n) / len
    }

    companion object {
        const val MAX_DISTANCE = 20f
        const val MIN_DISTANCE = 1e-4f
        private const val EDGE_EPS = 1e-5f
    }
}
