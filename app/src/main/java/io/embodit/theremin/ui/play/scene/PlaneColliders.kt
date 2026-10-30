package io.embodit.theremin.ui.play.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.platform.ThLog
import kotlin.math.abs

/**
 * Tap targets for the system pointer (gaze + pinch, emulator mouse). The app never sees where that
 * pointer hovers; it only gets a spatial tap on an entity that has a collider and is interactable.
 *
 * Each detected surface gets one thin **box** collider fitted to its polygon. A box is hit from both
 * sides; the static-mesh collider of a plane anchor is one-sided and wall quads face outwards, so a
 * tap from inside the room would miss it (feasibility spike, row 5). The tap point is put back onto
 * the mathematical plane afterwards (`ResolvePlacementUseCase.onTappedSurface`).
 *
 * A backstop shell around the room catches taps that hit no surface, so that the app can say
 * "No surface detected here" instead of staying silent.
 */
internal class PlaneColliders(private val root: Entity) {

    private class Collider(val entity: Entity, var center: Vec3, var size: Vec3, var rotation: Quat4)

    private val colliders = HashMap<String, Collider>()
    private val idByEntity = HashMap<Long, String>()
    private var backstop: Entity? = null

    val count: Int
        get() = colliders.size

    /** Surface id for a tapped entity, or null when the entity is not a surface collider. */
    fun planeIdOf(entity: Entity): String? = idByEntity[entity.id]

    fun isBackstop(entity: Entity): Boolean = runCatching { entity.getName() }.getOrNull()?.startsWith(BACKSTOP) == true

    fun upsert(surface: PlaneSurface) {
        val fit = fit(surface) ?: return
        val existing = colliders[surface.id]
        if (existing == null) {
            val entity = Entity()
            // SDK entity names must not contain hyphens; the first eight UUID characters have none
            runCatching { entity.setName("plane:${surface.label.replace('-', '_')}") }
            entity.components.set(collision(fit.size))
            entity.components.set(InteractableComponent())
            root.addChild(entity)
            entity.setLocal(position = fit.center, rotation = fit.rotation)
            colliders[surface.id] = Collider(entity, fit.center, fit.size, fit.rotation)
            idByEntity[entity.id] = surface.id
            return
        }
        if (existing.size.distanceTo(fit.size) > 0.001f) {
            existing.entity.components.set(collision(fit.size))
            existing.size = fit.size
        }
        if (existing.center.distanceTo(fit.center) > 0.0005f || rotationChanged(existing.rotation, fit.rotation)) {
            existing.entity.setLocal(position = fit.center, rotation = fit.rotation)
            existing.center = fit.center
            existing.rotation = fit.rotation
        }
    }

    fun remove(id: String) {
        val c = colliders.remove(id) ?: return
        idByEntity.remove(c.entity.id)
        runCatching { c.entity.destroy(true) }
    }

    /** Six slabs far outside any room: floor, ceiling and four walls of a shell around the Stage origin. */
    fun buildBackstop() {
        if (backstop != null) return
        val shell = Entity()
        runCatching { shell.setName(BACKSTOP) }
        root.addChild(shell)
        val h = SHELL_HALF_M
        val t = SHELL_THICKNESS_M
        val slabs =
            listOf(
                Triple("px", Vec3(h, SHELL_CENTER_Y, 0f), Vec3(t, 2 * h, 2 * h)),
                Triple("nx", Vec3(-h, SHELL_CENTER_Y, 0f), Vec3(t, 2 * h, 2 * h)),
                Triple("pz", Vec3(0f, SHELL_CENTER_Y, h), Vec3(2 * h, 2 * h, t)),
                Triple("nz", Vec3(0f, SHELL_CENTER_Y, -h), Vec3(2 * h, 2 * h, t)),
                Triple("py", Vec3(0f, SHELL_CENTER_Y + h, 0f), Vec3(2 * h, t, 2 * h)),
                Triple("ny", Vec3(0f, SHELL_CENTER_Y - h, 0f), Vec3(2 * h, t, 2 * h)),
            )
        for ((tag, center, size) in slabs) {
            val slab = Entity()
            runCatching { slab.setName("$BACKSTOP:$tag") }
            slab.components.set(collision(size))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = center)
        }
        backstop = shell
    }

    fun clear() {
        colliders.values.forEach { runCatching { it.entity.destroy(true) } }
        colliders.clear()
        idByEntity.clear()
        runCatching { backstop?.destroy(true) }
        backstop = null
    }

    private fun collision(size: Vec3): CollisionComponent =
        CollisionComponent(
            collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))),
            physicsMaterial = PhysicsMaterialResource(),
        )

    private class Fit(val center: Vec3, val size: Vec3, val rotation: Quat4)

    /** Oriented bounding box of the polygon in its own plane: X along its first edge, Z along the normal. */
    private fun fit(surface: PlaneSurface): Fit? {
        val v = surface.vertices
        if (v.size < 3) return null
        val n = surface.normal
        var u = Vec3.ZERO
        for (i in v.indices) {
            val e = (v[(i + 1) % v.size] - v[i]).rejectFrom(n)
            if (e.length > 0.01f) {
                u = e.normalized()
                break
            }
        }
        if (u.length < 0.5f) return null
        val w = n.cross(u).normalized()
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minW = Float.MAX_VALUE
        var maxW = -Float.MAX_VALUE
        val origin = v[0]
        for (p in v) {
            val d = p - origin
            val du = d.dot(u)
            val dw = d.dot(w)
            if (du < minU) minU = du
            if (du > maxU) maxU = du
            if (dw < minW) minW = dw
            if (dw > maxW) maxW = dw
        }
        val sizeU = maxU - minU
        val sizeW = maxW - minW
        if (sizeU < 0.01f || sizeW < 0.01f || !sizeU.isFinite() || !sizeW.isFinite()) {
            ThLog.w("PLANES skip degenerate ${surface.label} size=($sizeU, $sizeW)")
            return null
        }
        val center = origin + u * ((minU + maxU) / 2f) + w * ((minW + maxW) / 2f)
        return Fit(center, Vec3(sizeU, sizeW, THICKNESS_M), Quat4.fromBasis(u, w, n))
    }

    private fun rotationChanged(a: Quat4, b: Quat4): Boolean {
        val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)
        return dot < 0.99999f
    }

    companion object {
        const val BACKSTOP = "backstop"

        /** Thin, so that the tap point is within a few millimetres of the surface. */
        private const val THICKNESS_M = 0.006f
        private const val SHELL_HALF_M = 9f
        private const val SHELL_THICKNESS_M = 0.2f
        private const val SHELL_CENTER_Y = 1.5f
    }
}
