package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.DeskRules
import io.embodit.theremin.domain.usecase.GrabRules
import io.embodit.theremin.domain.usecase.StandPlacement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.4: the music stand above the instrument, facing the user from every side (this app turned its back once,
 * v0.1.2 -> v0.1.3), never between the user and the instrument, never mirrored; and the pinch-grab zone.
 */
class StandTest {
    private val half = 0.164f

    /** Settles the smooth turn: 3 s of 60 Hz frames. */
    private fun settle(pose: InstrumentPose, user: Vec3, start: Float, offset: Pair<Float, Float> = 0f to 0f): Pair<Vec3, Float> {
        val base = StandPlacement.base(pose, half)
        var yaw = start
        repeat(180) {
            val at = StandPlacement.position(base, yaw, offset)
            yaw = StandPlacement.turn(yaw, StandPlacement.yawTo(at, user, pose.yaw), 1f / 60f)
        }
        return StandPlacement.position(base, yaw, offset) to yaw
    }

    @Test
    fun standFacesTheUserFromEastSouthWestNorth() {
        // the instrument turned arbitrarily (60 degrees), the user walks round it
        val pose = InstrumentPose(Vec3(0.2f, 0.75f, -1.0f), Math.toRadians(60.0).toFloat())
        val c = pose.center
        val stances = mapOf("east" to Vec3(1.4f, 1.6f, -1.0f), "south" to Vec3(0.2f, 1.6f, 0.2f), "west" to Vec3(-1.0f, 1.6f, -1.0f), "north" to Vec3(0.2f, 1.6f, -2.2f))
        for ((name, user) in stances) {
            val (at, yaw) = settle(pose, user, pose.yaw)
            val rot = StandPlacement.rotation(yaw)
            val n = rot.rotate(Vec3(0f, 0f, 1f))
            val err = DeskRules.facingErrorDeg(n, user - at)
            assertTrue("$name normal off by $err", err < 10f)
            // upright, not mirrored: +X is the viewer's right, +Y up
            val view = Vec3(at.x - user.x, 0f, at.z - user.z).normalized()
            assertTrue("$name mirrored", rot.rotate(Vec3(1f, 0f, 0f)).dot(view.cross(Vec3.UP)) > 0.9f)
            assertTrue("$name upside down", rot.rotate(Vec3(0f, 1f, 0f)).y > 0.9f)
            // over the antenna top (the playing hands stay under it), not between user and instrument
            assertTrue("$name too low", at.y - half >= c.y + Instrument.ANTENNA_TOP_Y)
            val flatUser = Vec3(user.x, 0f, user.z)
            assertTrue("$name between", Vec3(at.x, 0f, at.z).distanceTo(flatUser) >= Vec3(c.x, 0f, c.z).distanceTo(flatUser) - 0.08f)
        }
    }

    @Test
    fun standTurnsSmoothlyAndOnlyAboutTheVertical() {
        val pose = InstrumentPose(Vec3(0f, 0.75f, -1f), 0f)
        val base = StandPlacement.base(pose, half)
        val user = Vec3(1.2f, 1.6f, -1f)
        val first = StandPlacement.turn(0f, StandPlacement.yawTo(base, user, 0f), 1f / 60f)
        assertTrue("jumped ${Math.toDegrees(first.toDouble())}", Math.toDegrees(first.toDouble()) < 10.0)
        val (_, yaw) = settle(pose, user, 0f)
        assertTrue(StandPlacement.rotation(yaw).rotate(Vec3.UP).y > 0.98f)
    }

    @Test
    fun draggedOffsetIsKeptAndClamped() {
        val pose = InstrumentPose(Vec3(0f, 0.75f, -1f), 0f)
        val (at, _) = settle(pose, Vec3(0f, 1.6f, 0f), 0f, 0.3f to -0.1f)
        val base = StandPlacement.base(pose, half)
        assertTrue(at.distanceTo(base) > 0.3f)
        val o = StandPlacement.clampOffset(3f to -3f)
        assertTrue(o.first == StandPlacement.MAX_OFFSET_M && o.second == -StandPlacement.MAX_OFFSET_M)
    }

    @Test
    fun pinchGrabsTheCabinetNotThePlayingHands() {
        // on the cabinet: grabbable
        assertTrue(GrabRules.grabbable(Vec3(0f, 0.13f, 0.05f), true))
        assertTrue(GrabRules.grabbable(Vec3(-0.1f, 0.12f, 0.14f), true))
        // the pitch hand on its ripples, high and low notes
        for (n in 0..12) {
            val p = Instrument.ripplePoint(Instrument.radius(n, Scale.STANDARD), -20f, Handed.RIGHT_PITCH)
            assertFalse("pitch n=$n", GrabRules.grabbable(p - Vec3(0f, 0f, -0.04f), true))
        }
        // the volume hand over the loop, from silent to loud
        val c = Instrument.LOOP_CENTER
        for (h in listOf(0.02f, 0.06f, 0.12f, 0.2f)) assertFalse("volume h=$h", GrabRules.grabbable(Vec3(c.x + 0.06f, c.y + h, c.z), true))
        // mirrored layout
        assertTrue(GrabRules.grabbable(Vec3(0.1f, 0.12f, 0.1f), false))
        assertFalse(GrabRules.grabbable(Vec3(-c.x - 0.06f, c.y + 0.06f, c.z), false))
    }
}
