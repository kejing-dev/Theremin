package io.embodit.theremin.ui.play.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.ui.theme.ThColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (same approach as the Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children (fades). */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f)
    else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity {
    runCatching { setName(name) }
    return this
}

/** A box collider of [size] (local metres) that the system pointer can tap. */
internal fun Entity.tapBox(size: Vec3, offset: Vec3 = Vec3.ZERO) {
    val shape = ShapeResource.createBox(size.sdk()).let { if (offset == Vec3.ZERO) it else it.offsetByTranslation(offset.sdk()) }
    components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = PhysicsMaterialResource()))
    components.set(InteractableComponent())
}

/**
 * Resources shared by entities that come and go (materials, meshes). The SDK releases a resource when
 * its last user entity is destroyed; shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials (design `palette.brandTokens`). Walnut and brass are physically based; everything
 * that signals (ripples, bead, fill, target, ghost, snap frame, labels) is unlit with tone mapping off so the
 * design colours are exact. Opacity of the sheer ones is per material; fades use the entity's opacity.
 */
internal class SceneMaterials(private val shared: SharedResources) {
    val walnut: Material = lit(ThColors.Walnut, 0f, 0.55f, 0.18f)
    val walnutDark: Material = lit(ThColors.WalnutDark, 0f, 0.6f, 0.15f)
    val brass: Material = lit(ThColors.Brass, 0.6f, 0.35f, 0.12f)
    val stand: Material = lit(ThColors.StandGrey, 0.3f, 0.5f, 0.1f)

    /** The antenna glows a little with the volume: 0 .. 30 % emission, in 8 steps. */
    val antennaGlow: List<Material> = (0..7).map { k ->
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(ThColors.Brass.color4())
                setMetallic(0.6f)
                setRoughness(0.35f)
                val g = 0.12f + 0.30f * k / 7f
                setEmissiveColor(Color4(ThColors.FilamentAmber.red * g, ThColors.FilamentAmber.green * g, ThColors.FilamentAmber.blue * g, 1f))
                setCullingMode(MaterialCullingMode.NONE)
            }
        )
    }

    val rippleNatural: Material = sheer(ThColors.RippleIvory, 0.7f)
    val rippleSharp: Material = sheer(ThColors.RippleIvory, 0.3f)
    val rippleCurrent: Material = sheer(ThColors.FilamentAmber, 1f)
    val rippleTarget: Material = sheer(ThColors.Verdigris, 1f)
    val rippleNext: Material = sheer(ThColors.Verdigris, 0.4f)
    val rippleFlash: Material = sheer(ThColors.RippleIvory, 1f)
    val bead: Material = sheer(ThColors.FilamentAmber, 1f)
    val pillar: Material = sheer(ThColors.Verdigris, 0.85f)
    val holdRing: Material = sheer(ThColors.Verdigris, 1f)
    val glass: Material = sheer(ThColors.Glass, 0.15f)
    val mute: Material = sheer(ThColors.MuteBand, 0.7f)
    val fill: Material = sheer(ThColors.FilamentAmber, 0.35f)
    val level: Material = sheer(ThColors.FilamentAmber, 1f)
    val ghost: Material = sheer(ThColors.Brass, 0.35f)
    val ghostBright: Material = sheer(ThColors.Brass, 0.6f)
    val snapDashed: Material = sheer(ThColors.FilamentAmber, 0.55f, depthTest = false)
    val snapSolid: Material = sheer(ThColors.FilamentAmber, 1f, depthTest = false)
    val deskDots: Material = sheer(ThColors.FilamentAmber, 0.08f, depthTest = false)

    private fun scaled(color: Color, k: Float): Color4 = Color4(color.red * k, color.green * k, color.blue * k, 1f)

    private fun lit(color: Color, metallic: Float, roughness: Float, glow: Float): Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(metallic)
                setRoughness(roughness)
                setEmissiveColor(scaled(color, glow))
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    fun sheer(color: Color, opacity: Float, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                if (!depthTest) setDepthTest(false)
            }
        )

    /** An unlit textured material with alpha (labels, hand tag). Not kept: the caller owns it. */
    fun textured(texture: TextureResource): UnlitMaterial =
        UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(Color4(1f, 1f, 1f, 1f))
            setBaseColorTexture(texture)
            runCatching { setApplyToneMapping(false) }
            setCullingMode(MaterialCullingMode.NONE)
            setDepthWrite(false)
        }
}

/** Programmatic meshes (`MeshModel`: positions, indices, normals, optional uv). */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)
    private val FRONT = Vector3(0f, 0f, 1f)

    /** Solid disc in the local XZ plane, facing +Y. */
    fun disc(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * radius).toFloat(), 0f, (sin(a) * radius).toFloat())
            normals += UP
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, 0.001f, radius)),
            name = name,
        )
    }

    /**
     * Disc in the local XY plane facing +Z with texture coordinates that map the unit square onto it:
     * the icon plates (the texture's circle fills the disc exactly, no transparency needed).
     */
    fun discUv(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val uvs = ArrayList<Vector2>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += FRONT
        uvs += Vector2(0.5f, 0.5f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            val x = cos(a).toFloat()
            val y = sin(a).toFloat()
            positions += Vector3(x * radius, y * radius, 0f)
            normals += FRONT
            uvs += Vector2(0.5f + x * 0.5f, 0.5f - y * 0.5f)
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % segments
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, radius, 0.001f)),
            name = name,
        )
    }

    /** Flat ring in the local XZ plane, facing +Y; with [dashes] > 0 only that many arcs are drawn. */
    fun ring(inner: Float, outer: Float, name: String, dashes: Int = 0, dashFill: Float = 0.6f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun arc(a0: Double, a1: Double, steps: Int) {
            for (i in 0 until steps) {
                val s = a0 + (a1 - a0) * i / steps
                val e = a0 + (a1 - a0) * (i + 1) / steps
                val base = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
                positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                repeat(4) { normals += UP }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
        }
        if (dashes <= 0) {
            arc(0.0, 2 * PI, segments)
        } else {
            val slot = 2 * PI / dashes
            for (d in 0 until dashes) arc(d * slot, d * slot + slot * dashFill, 3)
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(outer, 0.001f, outer)),
            name = name,
        )
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z: scaled to any length and width (needle, leaders). */
    fun unitBand(name: String): MeshResource {
        val positions = listOf(Vector3(0f, -0.5f, 0f), Vector3(1f, -0.5f, 0f), Vector3(1f, 0.5f, 0f), Vector3(0f, 0.5f, 0f))
        val normals = List(4) { FRONT }
        val indices = listOf(0, 1, 2, 0, 2, 3)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)),
            name = name,
        )
    }

    /** Closed cylinder of [radius] and [height] along +Y from y = 0. */
    fun cylinder(radius: Float, height: Float, name: String, segments: Int = 20): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        // side
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val n0 = Vector3(cos(a0).toFloat(), 0f, sin(a0).toFloat())
            val n1 = Vector3(cos(a1).toFloat(), 0f, sin(a1).toFloat())
            val base = positions.size
            positions += Vector3(n0.x * radius, 0f, n0.z * radius)
            positions += Vector3(n1.x * radius, 0f, n1.z * radius)
            positions += Vector3(n1.x * radius, height, n1.z * radius)
            positions += Vector3(n0.x * radius, height, n0.z * radius)
            normals += n0
            normals += n1
            normals += n1
            normals += n0
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        // caps
        fun cap(y: Float, up: Boolean) {
            val center = positions.size
            positions += Vector3(0f, y, 0f)
            normals += if (up) UP else Vector3(0f, -1f, 0f)
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * radius).toFloat(), y, (sin(a) * radius).toFloat())
                normals += if (up) UP else Vector3(0f, -1f, 0f)
            }
            for (i in 0 until segments) {
                val a = center + 1 + i
                val b = center + 1 + (i + 1) % segments
                if (up) {
                    indices += center
                    indices += b
                    indices += a
                } else {
                    indices += center
                    indices += a
                    indices += b
                }
            }
        }
        cap(height, true)
        cap(0f, false)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, height / 2f, 0f), halfExtent = Vector3(radius, height / 2f, radius)),
            name = name,
        )
    }

    /** A box mesh (corner radius 0). */
    fun box(size: Vec3, corner: Float = 0f): MeshResource = MeshResource.createBox(size.sdk(), corner)
}

/** A straight strip between two Stage points that turns its face to [faceNormal] (needle, leaders). */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var on = true

    init {
        entity.named(name)
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun setMaterial(value: Material) = entity.setMaterial(value)

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** Time-based easing helpers. */
internal object Ease {
    fun inOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    fun outCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }
}
