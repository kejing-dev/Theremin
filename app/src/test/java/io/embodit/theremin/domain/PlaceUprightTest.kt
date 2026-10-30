package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.PlaceRules
import io.embodit.theremin.domain.usecase.PlaceUpright
import io.embodit.theremin.domain.usecase.StandPlacement
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taste D9 (v0.1.5, X-ray hand "菜单一打开就斜着向下"): every panel pose goes through [PlaceUpright], which reads only
 * the head's horizontal heading. With the head pitched 30 degrees down, 30 degrees up, or rolled 20 degrees, the panel
 * stays upright (up within 1 degree of the world up), sits 10 - 20 degrees below the eye line, 0.8 - 1.2 m away, and
 * faces the viewer; the stand panel's rotation is yaw-only too.
 */
class PlaceUprightTest {
    private val eye = Vec3(0.3f, 1.55f, -0.2f)

    /** A head turned [yawDeg] about the vertical, then pitched [pitchDeg] (up positive) and rolled [rollDeg]. */
    private fun head(yawDeg: Float, pitchDeg: Float, rollDeg: Float): Quat4 =
        Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3.AHEAD, Math.toRadians(rollDeg.toDouble()).toFloat())

    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

    @Test
    fun panelStaysUprightAtEyeHeightForEveryHeadPose() {
        val poses = mapOf("level" to head(0f, 0f, 0f), "down30" to head(0f, -30f, 0f), "up30" to head(0f, 30f, 0f), "roll20" to head(0f, 0f, 20f), "turned+down" to head(135f, -30f, 10f))
        for ((name, rot) in poses) {
            val forward = rot.rotate(Vec3.AHEAD)
            val p = PlaceUpright.place(eye, forward, PlaceUpright.DISTANCE_M, PlaceUpright.DROP_DEG, -15f)
            val tilt = PlaceUpright.tiltDeg(p.rotation)
            assertTrue("$name tilt $tilt", tilt < 1f)
            val d = p.position - eye
            val dist = d.length
            assertTrue("$name distance $dist", dist in 0.8f..1.2f)
            val below = -deg(atan2(d.y, hypot(d.x, d.z)))
            assertTrue("$name below eye line $below", below in 10f..20f)
            // faces the viewer: +Z of the panel points back at the eye (level)
            val n = p.rotation.rotate(Vec3(0f, 0f, 1f))
            assertTrue("$name facing", PlaceRules.facingErrorDeg(n, Vec3(-d.x, 0f, -d.z)) < 1f)
            // sits in the viewer's horizontal heading, 15 degrees to the left
            val heading = PlaceUpright.heading(forward)!!
            val bearing = deg(PlaceRules.angle(atan2(d.x, d.z), atan2(heading.x, heading.z)))
            assertTrue("$name bearing $bearing", kotlin.math.abs(kotlin.math.abs(bearing) - 15f) < 1f)
        }
        // the same height whatever the pitch: down 30 and up 30 land within 1 cm of each other
        val a = PlaceUpright.place(eye, head(0f, -30f, 0f).rotate(Vec3.AHEAD)).position
        val b = PlaceUpright.place(eye, head(0f, 30f, 0f).rotate(Vec3.AHEAD)).position
        assertTrue(a.distanceTo(b) < 0.01f)
    }

    @Test
    fun standAndRoomRotationsAreYawOnly() {
        for (yaw in listOf(0f, 0.7f, 2.5f, -3f)) {
            assertTrue(PlaceUpright.tiltDeg(StandPlacement.rotation(yaw)) < 1e-3f)
            assertTrue(PlaceUpright.tiltDeg(PlaceUpright.rotation(yaw)) < 1e-3f)
        }
    }

    @Test
    fun startupPoseIsRejected() {
        assertFalse(PlaceUpright.validHead(Vec3.ZERO, Quat4.IDENTITY))
        assertFalse(PlaceUpright.validHead(Vec3(Float.NaN, 0f, 0f), Quat4.IDENTITY))
        assertTrue(PlaceUpright.validHead(eye, head(0f, -30f, 0f)))
        assertTrue(PlaceUpright.validHead(eye, Quat4.IDENTITY))
    }
}
