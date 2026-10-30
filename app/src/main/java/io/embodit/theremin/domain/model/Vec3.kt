package io.embodit.theremin.domain.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vector in Stage metres (right-handed: +X right, +Y up, -Z ahead of the user at Stage start).
 * The domain layer owns this type so that it compiles and tests on a plain JVM, without the SDK.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Float
        get() = sqrt(dot(this))

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-12f) this else this / l
    }

    fun distanceTo(o: Vec3): Float = (this - o).length

    fun lerp(o: Vec3, t: Float): Vec3 = this + (o - this) * t

    /** Removes the component along [unitAxis]. */
    fun rejectFrom(unitAxis: Vec3): Vec3 = this - unitAxis * dot(unitAxis)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val RIGHT = Vec3(1f, 0f, 0f)

        /** "Ahead" in Stage space. (The SDK's own `Vector3.FORWARD` is +Z, so never use that for aiming.) */
        val AHEAD = Vec3(0f, 0f, -1f)
    }
}

/** Unit quaternion, Hamilton convention: `(a * b).rotate(v) == a.rotate(b.rotate(v))`. */
data class Quat4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }

    fun conjugate() = Quat4(-x, -y, -z, w)

    operator fun times(o: Quat4) =
        Quat4(
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
            w * o.w - x * o.x - y * o.y - z * o.z,
        )

    fun normalized(): Quat4 {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-12f) IDENTITY else Quat4(x / l, y / l, z / l, w / l)
    }

    companion object {
        val IDENTITY = Quat4(0f, 0f, 0f, 1f)

        fun axisAngle(unitAxis: Vec3, radians: Float): Quat4 {
            val s = sin(radians / 2f)
            return Quat4(unitAxis.x * s, unitAxis.y * s, unitAxis.z * s, cos(radians / 2f))
        }

        /** Shortest rotation that takes unit vector [a] onto unit vector [b]. */
        fun fromTo(a: Vec3, b: Vec3): Quat4 {
            val d = a.dot(b)
            if (d > 0.99999f) return IDENTITY
            if (d < -0.99999f) {
                val helper = if (abs(a.x) < 0.9f) Vec3.RIGHT else Vec3.UP
                val axis = a.cross(helper).normalized()
                return Quat4(axis.x, axis.y, axis.z, 0f)
            }
            val c = a.cross(b)
            return Quat4(c.x, c.y, c.z, 1f + d).normalized()
        }

        /** Rotation whose local X / Y / Z axes are the given orthonormal, right-handed Stage vectors. */
        fun fromBasis(xAxis: Vec3, yAxis: Vec3, zAxis: Vec3): Quat4 {
            val m00 = xAxis.x; val m01 = yAxis.x; val m02 = zAxis.x
            val m10 = xAxis.y; val m11 = yAxis.y; val m12 = zAxis.y
            val m20 = xAxis.z; val m21 = yAxis.z; val m22 = zAxis.z
            val trace = m00 + m11 + m22
            val q =
                if (trace > 0f) {
                    val s = sqrt(trace + 1f) * 2f
                    Quat4((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
                } else if (m00 > m11 && m00 > m22) {
                    val s = sqrt(1f + m00 - m11 - m22) * 2f
                    Quat4(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                } else if (m11 > m22) {
                    val s = sqrt(1f + m11 - m00 - m22) * 2f
                    Quat4((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
                } else {
                    val s = sqrt(1f + m22 - m00 - m11) * 2f
                    Quat4((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
                }
            return q.normalized()
        }
    }
}
