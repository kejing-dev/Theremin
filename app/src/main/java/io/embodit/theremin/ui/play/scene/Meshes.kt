package io.embodit.theremin.ui.play.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Collects triangles; [build] turns them into a mesh resource with fitted bounds. */
internal class MeshBuilder {
    val positions = ArrayList<Vector3>()
    val normals = ArrayList<Vector3>()
    val uvs = ArrayList<Vector2>()
    val indices = ArrayList<Int>()

    fun vertex(p: Vec3, n: Vec3, u: Float = 0f, v: Float = 0f): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += Vector2(u, v)
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    val empty: Boolean
        get() = indices.isEmpty()

    /** Axis-aligned box with flat normals. */
    fun box(center: Vec3, size: Vec3) {
        val h = size / 2f
        val faces =
            listOf(
                Vec3(1f, 0f, 0f) to listOf(Vec3(1f, -1f, -1f), Vec3(1f, 1f, -1f), Vec3(1f, 1f, 1f), Vec3(1f, -1f, 1f)),
                Vec3(-1f, 0f, 0f) to listOf(Vec3(-1f, -1f, 1f), Vec3(-1f, 1f, 1f), Vec3(-1f, 1f, -1f), Vec3(-1f, -1f, -1f)),
                Vec3(0f, 1f, 0f) to listOf(Vec3(-1f, 1f, -1f), Vec3(-1f, 1f, 1f), Vec3(1f, 1f, 1f), Vec3(1f, 1f, -1f)),
                Vec3(0f, -1f, 0f) to listOf(Vec3(-1f, -1f, 1f), Vec3(-1f, -1f, -1f), Vec3(1f, -1f, -1f), Vec3(1f, -1f, 1f)),
                Vec3(0f, 0f, 1f) to listOf(Vec3(-1f, -1f, 1f), Vec3(1f, -1f, 1f), Vec3(1f, 1f, 1f), Vec3(-1f, 1f, 1f)),
                Vec3(0f, 0f, -1f) to listOf(Vec3(1f, -1f, -1f), Vec3(-1f, -1f, -1f), Vec3(-1f, 1f, -1f), Vec3(1f, 1f, -1f)),
            )
        for ((n, corners) in faces) {
            val ids = corners.map { c -> vertex(center + Vec3(c.x * h.x, c.y * h.y, c.z * h.z), n) }
            quad(ids[0], ids[1], ids[2], ids[3])
        }
    }

    /** A round tube from [a] to [b] of [radius], capped. */
    fun tube(a: Vec3, b: Vec3, radius: Float, segments: Int = 16) {
        val axis = (b - a).normalized()
        var u = Vec3.UP.cross(axis)
        if (u.length < 1e-3f) u = Vec3.RIGHT.cross(axis)
        u = u.normalized()
        val w = axis.cross(u).normalized()
        val ring = (0 until segments).map { i ->
            val t = 2 * PI * i / segments
            (u * cos(t).toFloat() + w * sin(t).toFloat())
        }
        for (i in 0 until segments) {
            val n0 = ring[i]
            val n1 = ring[(i + 1) % segments]
            val i0 = vertex(a + n0 * radius, n0)
            val i1 = vertex(a + n1 * radius, n1)
            val i2 = vertex(b + n1 * radius, n1)
            val i3 = vertex(b + n0 * radius, n0)
            quad(i0, i1, i2, i3)
        }
        for ((c, n) in listOf(a to -axis, b to axis)) {
            val center = vertex(c, n)
            val ids = ring.map { vertex(c + it * radius, n) }
            for (i in 0 until segments) tri(center, ids[i], ids[(i + 1) % segments])
        }
    }

    fun sphere(c: Vec3, r: Float, rings: Int = 10, segments: Int = 16) {
        val grid = Array(rings + 1) { j ->
            val phi = PI * j / rings
            IntArray(segments + 1) { i ->
                val th = 2 * PI * i / segments
                val n = Vec3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
                vertex(c + n * r, n)
            }
        }
        for (j in 0 until rings) for (i in 0 until segments) quad(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i])
    }

    /** Horizontal torus (the volume loop) around [c]: ring radius [big], tube radius [small]. */
    fun torus(c: Vec3, big: Float, small: Float, segments: Int = 48, sides: Int = 10) {
        val grid = Array(segments + 1) { i ->
            val a = 2 * PI * i / segments
            val dir = Vec3(cos(a).toFloat(), 0f, sin(a).toFloat())
            IntArray(sides + 1) { k ->
                val b = 2 * PI * k / sides
                val n = dir * cos(b).toFloat() + Vec3.UP * sin(b).toFloat()
                vertex(c + dir * big + n * small, n)
            }
        }
        for (i in 0 until segments) for (k in 0 until sides) quad(grid[i][k], grid[i + 1][k], grid[i + 1][k + 1], grid[i][k + 1])
    }

    /** A flat band on the ripple plane: radius [r] around the antenna, sector [fromDeg] .. [toDeg] (already mirrored). */
    fun arcBand(r: Float, width: Float, fromDeg: Float, toDeg: Float, handed: Handed, y: Float = Instrument.RIPPLE_Y, segments: Int = 32) {
        val lo = minOf(fromDeg, toDeg)
        val hi = maxOf(fromDeg, toDeg)
        var prevIn = -1
        var prevOut = -1
        for (i in 0..segments) {
            val deg = lo + (hi - lo) * i / segments
            val pin = vertex(Instrument.ripplePoint(r - width / 2f, deg, handed, y), Vec3.UP)
            val pout = vertex(Instrument.ripplePoint(r + width / 2f, deg, handed, y), Vec3.UP)
            if (prevIn >= 0) quad(prevIn, prevOut, pout, pin)
            prevIn = pin
            prevOut = pout
        }
    }

    /** Vertical ring facing +Z (hold ring), from 12 o'clock clockwise over [fraction] of the turn. */
    fun ringXY(inner: Float, outer: Float, fraction: Float, segments: Int = 48) {
        val n = Vec3(0f, 0f, 1f)
        val steps = maxOf(1, (segments * fraction).toInt())
        for (i in 0 until steps) {
            val a0 = PI / 2 - 2 * PI * fraction * i / steps
            val a1 = PI / 2 - 2 * PI * fraction * (i + 1) / steps
            val i0 = vertex(Vec3((cos(a0) * inner).toFloat(), (sin(a0) * inner).toFloat(), 0f), n)
            val i1 = vertex(Vec3((cos(a0) * outer).toFloat(), (sin(a0) * outer).toFloat(), 0f), n)
            val i2 = vertex(Vec3((cos(a1) * outer).toFloat(), (sin(a1) * outer).toFloat(), 0f), n)
            val i3 = vertex(Vec3((cos(a1) * inner).toFloat(), (sin(a1) * inner).toFloat(), 0f), n)
            quad(i0, i1, i2, i3)
        }
    }

    /** Flat horizontal ring around the origin (the level marker of the column). */
    fun ringXZ(inner: Float, outer: Float, segments: Int = 32) {
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val i0 = vertex(Vec3((cos(a0) * inner).toFloat(), 0f, (sin(a0) * inner).toFloat()), Vec3.UP)
            val i1 = vertex(Vec3((cos(a0) * outer).toFloat(), 0f, (sin(a0) * outer).toFloat()), Vec3.UP)
            val i2 = vertex(Vec3((cos(a1) * outer).toFloat(), 0f, (sin(a1) * outer).toFloat()), Vec3.UP)
            val i3 = vertex(Vec3((cos(a1) * inner).toFloat(), 0f, (sin(a1) * inner).toFloat()), Vec3.UP)
            quad(i0, i1, i2, i3)
        }
    }

    /** A [w] x [h] quad centred at the origin facing +Z; v = 1 on the top edge (the engine samples v = 0 at a bitmap's bottom row). */
    fun quadUv(w: Float, h: Float) {
        val n = Vec3(0f, 0f, 1f)
        val a = vertex(Vec3(-w / 2, -h / 2, 0f), n, 0f, 0f)
        val b = vertex(Vec3(w / 2, -h / 2, 0f), n, 1f, 0f)
        val c = vertex(Vec3(w / 2, h / 2, 0f), n, 1f, 1f)
        val d = vertex(Vec3(-w / 2, h / 2, 0f), n, 0f, 1f)
        quad(a, b, c, d)
    }

    /** A rectangle outline on the desk (XZ, facing +Y), centred; with [dash] > 0 broken into dashes. */
    fun rectOutline(w: Float, d: Float, band: Float, dash: Float, gap: Float) {
        fun strip(a: Vec3, b: Vec3) {
            val dir = (b - a).normalized()
            val side = Vec3.UP.cross(dir).normalized() * (band / 2f)
            quad(vertex(a - side, Vec3.UP), vertex(a + side, Vec3.UP), vertex(b + side, Vec3.UP), vertex(b - side, Vec3.UP))
        }
        val c = listOf(Vec3(-w / 2, 0f, -d / 2), Vec3(w / 2, 0f, -d / 2), Vec3(w / 2, 0f, d / 2), Vec3(-w / 2, 0f, d / 2))
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val len = a.distanceTo(b)
            if (dash <= 0f) {
                strip(a, b)
                continue
            }
            val dir = (b - a) / len
            var s = 0f
            while (s < len) {
                strip(a + dir * s, a + dir * minOf(s + dash, len))
                s += dash + gap
            }
        }
    }

    fun build(name: String): MeshResource {
        var min = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var max = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in positions) {
            min = Vec3(minOf(min.x, p.x), minOf(min.y, p.y), minOf(min.z, p.z))
            max = Vec3(maxOf(max.x, p.x), maxOf(max.y, p.y), maxOf(max.z, p.z))
        }
        if (positions.isEmpty()) {
            min = Vec3.ZERO
            max = Vec3.ZERO
        }
        val c = (min + max) / 2f
        val h = ((max - min) / 2f).let { Vec3(maxOf(it.x, 0.001f), maxOf(it.y, 0.001f), maxOf(it.z, 0.001f)) }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = c.sdk(), halfExtent = h.sdk()),
            name = name,
        )
    }
}

internal fun mesh(name: String, block: MeshBuilder.() -> Unit): MeshResource = MeshBuilder().apply(block).build(name)
