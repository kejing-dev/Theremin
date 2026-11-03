package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.PlaceRules
import io.embodit.theremin.domain.usecase.PlaceUpright
import io.embodit.theremin.domain.usecase.RestoreRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taste D9 "恢复上次锚点也不能落在身后" (v0.1.8, headset v0.1.7: "琴一开始出现的位置是我的右后方" / "面板直接没了"): a saved
 * world anchor brings the instrument back on launch only when the instrument and its stand are both within 45 degrees
 * of the head's heading and 0.5 - 2.5 m from the eyes. Otherwise the launch puts it in front of the user (beside the
 * place panel) and offers 「放回上次的位置」.
 */
class RestoreTest {
    /** The stand panel's half height (450 x 560 dp at 1 m / 1000 dp, as PanelSpec). */
    private val standHalfH = 0.28f

    private fun yawed(deg: Float): Quat4 = Quat4.axisAngle(Vec3.UP, Math.toRadians(deg.toDouble()).toFloat())

    /** The head's forward after turning [deg] (positive = to the left, about +Y) from facing -Z. */
    private fun forward(deg: Float): Vec3 = yawed(deg).rotate(Vec3.AHEAD)

    /** Where the first-run flow puts the instrument: beside the place panel, which sits 0.85 m ahead, 15 deg left (Follower). */
    private fun frontPose(eye: Vec3, fwd: Vec3): InstrumentPose {
        val panel = PlaceUpright.place(eye, fwd, PlaceUpright.DISTANCE_M, PlaceUpright.DROP_DEG, -15f)
        return PlaceRules.besidePanel(panel.position, panel.rotation.rotate(Vec3(0f, 0f, 1f)), eye)
    }

    @Test
    fun headsetV017CaseIsNotRestored() {
        // the headset log of v0.1.7 (2026-11-02): head at the origin facing -Z, the anchor 0.39 m behind and to the right
        val d = RestoreRules.decide(InstrumentPose(Vec3(0.1732f, 0.8082f, 0.3942f), Math.toRadians(-171.3).toFloat()), Vec3(0.001f, 1.1138f, 0.0025f), Vec3.AHEAD, true, standHalfH)
        assertFalse(d.toString(), d.restore)
        assertEquals("behind", d.instrument.why)
        assertTrue("right-behind: ${d.instrument.bearingDeg}", d.instrument.bearingDeg > 135f)
    }

    @Test
    fun savedInFrontIsRestored() {
        val eye = Vec3(0.2f, 1.6f, -0.1f)
        for (turn in listOf(0f, 90f, 180f, -120f)) {
            val fwd = forward(turn)
            // saved last time 1.2 m ahead of where the user faces now, turned to them
            val c = eye + PlaceUpright.heading(fwd)!! * 1.2f + Vec3(0f, -0.5f, 0f)
            val d = RestoreRules.decide(InstrumentPose(c, PlaceRules.yawTowards(c, eye)), eye, fwd, true, standHalfH)
            assertTrue("turn $turn: $d", d.restore)
            assertTrue(d.instrument.inView && d.stand.inView)
        }
    }

    @Test
    fun turnedRoundBeforeColdStartIsNotRestoredAndStartsInFront() {
        // placed last session 1.2 m ahead of the user facing -Z; this launch the user faces the other way (180 deg)
        val eye = Vec3(0f, 1.6f, 0f)
        val saved = InstrumentPose(Vec3(0f, 1.1f, -1.2f), 0f)
        assertTrue(RestoreRules.decide(saved, eye, forward(0f), true, standHalfH).restore)
        for (turn in listOf(180f, 135f, -135f, 90f, -90f, 60f)) {
            val fwd = forward(turn)
            val d = RestoreRules.decide(saved, eye, fwd, true, standHalfH)
            assertFalse("turn $turn: $d", d.restore)
            // instead: in front of the user now, instrument and stand both within +-45 deg and 0.5 - 2.5 m
            val front = frontPose(eye, fwd)
            for (rightPitch in listOf(true, false)) {
                val f = RestoreRules.decide(front, eye, fwd, rightPitch, standHalfH)
                assertTrue("front after turn $turn rightPitch=$rightPitch: $f", f.restore)
                // and it faces the user
                assertTrue(PlaceRules.facingErrorDeg(front, eye) < 2f)
            }
        }
    }

    @Test
    fun seatedAndStandingFrontPosesPass() {
        for (eyeY in listOf(1.1f, 1.65f)) {
            val eye = Vec3(0.5f, eyeY, 0.3f)
            for (turn in listOf(0f, 37f, 180f, -100f)) {
                val f = RestoreRules.decide(frontPose(eye, forward(turn)), eye, forward(turn), true, standHalfH)
                assertTrue("eye $eyeY turn $turn: $f", f.restore)
            }
        }
    }

    @Test
    fun outOfViewTooFarOrTooNearIsNotRestored() {
        val eye = Vec3(0f, 1.6f, 0f)
        val fwd = Vec3.AHEAD
        fun at(x: Float, z: Float) = InstrumentPose(Vec3(x, 1.1f, z), PlaceRules.yawTowards(Vec3(x, 1.1f, z), eye))
        // 60 deg to the right, 1.2 m: off view
        val side = RestoreRules.decide(at(1.2f * kotlin.math.sin(Math.toRadians(60.0)).toFloat(), -1.2f * kotlin.math.cos(Math.toRadians(60.0)).toFloat()), eye, fwd, true, standHalfH)
        assertFalse(side.toString(), side.restore)
        assertEquals("off_view", side.instrument.why)
        // straight ahead but 3.2 m away
        val far = RestoreRules.decide(at(0f, -3.2f), eye, fwd, true, standHalfH)
        assertFalse(far.toString(), far.restore)
        assertEquals("too_far", far.instrument.why)
        // in the user's lap: 0.3 m
        val near = RestoreRules.decide(InstrumentPose(Vec3(0f, 1.45f, -0.25f), 0f), eye, fwd, true, standHalfH)
        assertFalse(near.toString(), near.restore)
        // the instrument in view at 35 deg right, but its stand (volume-loop side) pushed past 45 deg: not restored either
        val edge = RestoreRules.decide(InstrumentPose(Vec3(0.55f, 1.1f, -0.8f), PlaceRules.yawTowards(Vec3(0.55f, 1.1f, -0.8f), eye)), eye, fwd, false, standHalfH)
        assertTrue(edge.toString(), edge.instrument.inView)
        assertFalse(edge.toString(), edge.restore)
    }

    @Test
    fun noValidHeadNeverRestores() {
        assertFalse(RestoreRules.decide(InstrumentPose(Vec3(0f, 1.1f, -1f), 0f), null, null, true, standHalfH).restore)
    }

    @Test
    fun bearingSign() {
        val eye = Vec3(0f, 1.6f, 0f)
        assertTrue(RestoreRules.seen("r", Vec3(1f, 1.6f, -1f), eye, Vec3.AHEAD).bearingDeg in 44f..46f)
        assertTrue(RestoreRules.seen("l", Vec3(-1f, 1.6f, -1f), eye, Vec3.AHEAD).bearingDeg in -46f..-44f)
        assertTrue(kotlin.math.abs(RestoreRules.seen("b", Vec3(0f, 1.6f, 1f), eye, Vec3.AHEAD).bearingDeg) > 179f)
    }
}
