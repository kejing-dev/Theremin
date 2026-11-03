package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.PlaceRules
import io.embodit.theremin.domain.usecase.GrabRules
import io.embodit.theremin.domain.usecase.StandPlacement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music stand facing the user from every side (this app turned its back once, v0.1.2 -> v0.1.3), never between
 * the user and the instrument, never mirrored; and the pinch-grab zone. v0.1.7 (D21, headset v0.1.6 "一开始显示的太高了"
 * / "orbit 运动"): beside the instrument, top edge under the eyes, centre 10 - 20 degrees below the eye line, and the
 * drag result kept in the instrument frame so the turn cannot carry the panel round.
 */
class StandTest {
    private val half = 0.224f

    /** Settles the smooth turn: 3 s of 60 Hz frames; the position does not depend on the turn any more. */
    private fun settle(pose: InstrumentPose, user: Vec3, start: Float, offset: Vec3 = Vec3.ZERO): Pair<Vec3, Float> {
        val local = StandPlacement.baseLocal(true) + offset
        val lift = StandPlacement.liftFor(StandPlacement.position(pose, local, 0f), user, half)
        val at = StandPlacement.position(pose, local, lift)
        var yaw = start
        repeat(180) { yaw = StandPlacement.turn(yaw, StandPlacement.yawTo(at, user, pose.yaw), 1f / 60f) }
        return at to yaw
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
            val err = PlaceRules.facingErrorDeg(n, user - at)
            assertTrue("$name normal off by $err", err < 10f)
            // upright, not mirrored: +X is the viewer's right, +Y up
            val view = Vec3(at.x - user.x, 0f, at.z - user.z).normalized()
            assertTrue("$name mirrored", rot.rotate(Vec3(1f, 0f, 0f)).dot(view.cross(Vec3.UP)) > 0.9f)
            assertTrue("$name upside down", rot.rotate(Vec3(0f, 1f, 0f)).y > 0.9f)
            // D21: the top edge under the eyes, the centre 10 - 20 degrees below the eye line
            assertTrue("$name top above the eyes", at.y + half <= user.y + 1e-4f)
            val drop = StandPlacement.dropDeg(at, user)
            assertTrue("$name drop $drop", drop >= 9.99f)
            assertTrue("$name drop $drop", drop <= 20.01f || kotlin.math.abs(at.y + half - user.y) < 1e-3f)
        }
    }

    @Test
    fun standIsBesideTheInstrumentNotOnTopOfIt() {
        val pose = InstrumentPose(Vec3(0f, 0.95f, -0.9f), 0f)
        val user = Vec3(0f, 1.62f, 0f)
        val (at, _) = settle(pose, user, 0f)
        val local = pose.toLocal(at)
        // on the volume-loop side, clear of the loop and its hand (x <= -0.39 - panel half width), a little back
        assertTrue("not beside: x=${local.x}", local.x + 0.18f <= Instrument.LOOP_CENTER.x - Instrument.LOOP_RADIUS)
        assertTrue("in front of the instrument: z=${local.z}", local.z <= 0f)
        // not stacked over the antenna top any more (v0.1.6 put its lower edge 5 cm over it)
        assertTrue("stacked: bottom=${local.y - half}", local.y - half < Instrument.ANTENNA_TOP_Y)
        // left-handed layout: the other side
        val mirrored = StandPlacement.baseLocal(false)
        assertTrue(mirrored.x > 0.5f)
    }

    @Test
    fun theOldDefaultWasAboveTheEyes() {
        // v0.1.6 for the record: lower edge 5 cm over the antenna top, centre + half the 560 dp panel -> top far over a standing eye
        val pose = InstrumentPose(Vec3(0f, 1.05f, -0.9f), 0f)
        val oldTop = pose.center.y + Instrument.ANTENNA_TOP_Y + 0.05f + 2 * half
        assertTrue(oldTop > 1.62f)
        val (at, _) = settle(pose, Vec3(0f, 1.62f, 0f), 0f)
        assertTrue(at.y + half <= 1.62f)
    }

    @Test
    fun standTurnsSmoothlyAndOnlyAboutTheVertical() {
        val pose = InstrumentPose(Vec3(0f, 0.75f, -1f), 0f)
        val at = StandPlacement.position(pose, StandPlacement.baseLocal(true), 0f)
        val user = Vec3(1.2f, 1.6f, -1f)
        val first = StandPlacement.turn(0f, StandPlacement.yawTo(at, user, 0f), 1f / 60f)
        assertTrue("jumped ${Math.toDegrees(first.toDouble())}", Math.toDegrees(first.toDouble()) < 10.0)
        val (_, yaw) = settle(pose, user, 0f)
        assertTrue(StandPlacement.rotation(yaw).rotate(Vec3.UP).y > 0.98f)
    }

    @Test
    fun draggedOffsetIsKeptInTheInstrumentFrameAndTheTurnDoesNotMoveIt() {
        val pose = InstrumentPose(Vec3(0f, 0.75f, -1f), 0.4f)
        val base = StandPlacement.baseLocal(true)
        val dropped = Vec3(-0.3f, 1.2f, -0.6f)
        val offset = StandPlacement.offsetFor(pose, dropped, base, 0.05f)
        val back = StandPlacement.position(pose, base + offset, 0.05f)
        assertTrue("released panel jumped ${back.distanceTo(dropped)}", back.distanceTo(dropped) < 1e-4f)
        // the user walks round: the stand turns, its centre stays (v0.1.6 orbited round its base)
        for (user in listOf(Vec3(1.5f, 1.6f, -1f), Vec3(-1.5f, 1.6f, -1f), Vec3(0f, 1.6f, 0.5f))) {
            val (at, _) = settle(pose, user, 0f, offset)
            val c = StandPlacement.position(pose, base + offset, StandPlacement.liftFor(StandPlacement.position(pose, base + offset, 0f), user, half))
            assertTrue(at.distanceTo(c) < 1e-5f)
        }
        val o = StandPlacement.clampOffset(Vec3(3f, -3f, 0f))
        assertTrue(o.x == StandPlacement.MAX_OFFSET_M && o.y == -StandPlacement.MAX_OFFSET_M)
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
