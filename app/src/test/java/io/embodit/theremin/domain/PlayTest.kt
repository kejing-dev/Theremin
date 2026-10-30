package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.ControllerHand
import io.embodit.theremin.domain.play.HandSample
import io.embodit.theremin.domain.play.HoldTracker
import io.embodit.theremin.domain.play.Inject
import io.embodit.theremin.domain.play.Lessons
import io.embodit.theremin.domain.play.MapSettings
import io.embodit.theremin.domain.play.PlayMapper
import io.embodit.theremin.domain.play.RoleInputs
import io.embodit.theremin.domain.play.SongBook
import io.embodit.theremin.domain.play.SongScorer
import io.embodit.theremin.domain.play.Src
import io.embodit.theremin.domain.usecase.PlaceRules
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTest {
    private val ms = 1_000_000L
    private val pose = InstrumentPose(Vec3(0.3f, 0.74f, -0.6f), 0.4f)

    private fun mapper() = PlayMapper().also { it.pose = pose; it.armed = true }

    /** Stage palm / wrist for an instrument-frame pitch point at distance [d], 5 cm over the ripples. */
    private fun pitchHand(d: Float): HandSample {
        val p = Instrument.ripplePoint(d, -20f, Handed.RIGHT_PITCH, 0.25f)
        val palm = p - Vec3(0f, 0f, -0.04f) // P = palm + 4 cm towards -z (fingers at the antenna side)
        return HandSample(pose.toStage(palm), pose.toStage(palm + Vec3(0f, -0.02f, 0.08f)))
    }

    private fun volumeHand(h: Float): HandSample {
        val c = Instrument.LOOP_CENTER
        return HandSample(pose.toStage(Vec3(c.x, c.y + h + Instrument.PALM_DROP_M, c.z)), null)
    }

    @Test
    fun jointsAndDirectInjectionAgree() {
        val a = mapper()
        a.injectPitch = Inject.Direct(0.31f)
        a.injectVolume = Inject.Direct(0.12f)
        val direct = a.step(0, RoleInputs())
        val b = mapper()
        val local = Instrument.ripplePoint(0.31f, -20f, Handed.RIGHT_PITCH, 0.25f)
        b.injectPitch = Inject.Joints(local + Vec3(0f, 0f, 0.04f), local + Vec3(0f, 0f, 0.12f))
        b.injectVolume = Inject.Joints(Instrument.LOOP_CENTER + Vec3(0f, 0.14f, 0f), null)
        val joints = b.step(0, RoleInputs())
        assertEquals(direct.s, joints.s, 1e-3f)
        assertEquals(direct.volume, joints.volume, 1e-4f)
        assertEquals(4, Instrument.nearest(joints.s))
    }

    @Test
    fun realHandsMapThroughThePose() {
        val m = mapper()
        var f = m.step(0, RoleInputs(pitchHand = pitchHand(0.31f), volumeHand = volumeHand(0.11f)))
        for (k in 1..20) f = m.step(k * 4 * ms, RoleInputs(pitchHand = pitchHand(0.31f), volumeHand = volumeHand(0.11f)))
        assertEquals(Src.HAND, f.pitchSrc)
        assertEquals(4f, f.s, 0.02f)
        assertEquals(0.287f, f.volume, 0.01f)
        assertTrue(f.amplitude > 0f)
    }

    @Test
    fun lossRulePerRole() {
        val m = mapper()
        val both = RoleInputs(pitchHand = pitchHand(0.25f), volumeHand = volumeHand(0.12f))
        m.step(0, both)
        // volume hand gone: silent at once, not "both lost"
        var f = m.step(4 * ms, RoleInputs(pitchHand = pitchHand(0.25f)))
        assertEquals(0f, f.volume, 0f)
        assertTrue(f.volumeLost)
        assertFalse(f.bothLost)
        // pitch hand gone for 100 ms: last value held; after 150 ms: lost
        m.step(8 * ms, both)
        f = m.step(108 * ms, RoleInputs(volumeHand = volumeHand(0.12f)))
        assertTrue(f.pitchKnown)
        assertFalse(f.pitchLost)
        f = m.step(170 * ms, RoleInputs(volumeHand = volumeHand(0.12f)))
        assertTrue(f.pitchLost)
        assertEquals(0f, f.amplitude, 0f)
        f = m.step(180 * ms, RoleInputs())
        assertTrue(f.bothLost)
    }

    @Test
    fun noLossBeforeSeenOrArmed() {
        val m = PlayMapper().also { it.pose = pose }
        val f = m.step(0, RoleInputs())
        assertFalse(f.pitchLost || f.volumeLost)
        m.armed = true
        assertFalse(m.step(500 * ms, RoleInputs()).pitchLost)
    }

    @Test
    fun pinchFreezesPitch() {
        val m = mapper()
        m.injectVolume = Inject.Direct(0.12f)
        m.injectPitch = Inject.Direct(0.31f)
        m.step(0, RoleInputs())
        m.injectPinch = true
        m.step(4 * ms, RoleInputs())
        m.injectPitch = Inject.Direct(0.25f)
        assertEquals(4f, m.step(50 * ms, RoleInputs()).s, 1e-3f)
        m.injectPinch = false
        assertEquals(4f, m.step(150 * ms, RoleInputs()).s, 1e-3f) // still inside the 150 ms tail
        m.step(330 * ms, RoleInputs()) // tail over: d glides back over 60 ms
        val f = m.step(400 * ms, RoleInputs())
        assertEquals(Instrument.semitone(0.25f, Scale.STANDARD), f.s, 1e-3f)
    }

    @Test
    fun sourceSwitchCrossFades() {
        val m = mapper()
        val hand = RoleInputs(pitchHand = pitchHand(0.31f), volumeHand = volumeHand(0.12f))
        var last = 0f
        for (k in 0..10) last = m.step(k * 4 * ms, hand).s
        // the controller sits 2 cm nearer the antenna: the semitone glides, no jump
        val ctrlPoint = pose.toStage(Instrument.ripplePoint(0.29f, -20f, Handed.RIGHT_PITCH, 0.25f))
        val ctrl = RoleInputs(pitchController = ControllerHand(ctrlPoint, 0f), volumeHand = volumeHand(0.12f))
        var maxJump = 0f
        // a held-still controller repeats one point: the filter carries on at its last cut-off (v0.1.4), ~0.5 s to settle
        for (k in 11..140) {
            val f = m.step(k * 4 * ms, ctrl)
            maxJump = maxOf(maxJump, abs(f.s - last))
            last = f.s
            assertEquals(Src.CONTROLLER, f.pitchSrc)
        }
        assertTrue("jump $maxJump", maxJump < 0.3f)
        assertEquals(Instrument.semitone(0.29f, Scale.STANDARD), last, 0.05f)
    }

    @Test
    fun controllerTriggerMutes() {
        val m = mapper()
        val c = Instrument.LOOP_CENTER
        val at = pose.toStage(Vec3(c.x, c.y + 0.14f, c.z))
        val input = RoleInputs(pitchHand = pitchHand(0.31f), volumeController = ControllerHand(at, 0.9f))
        assertEquals(0f, m.step(0, input).volume, 0f)
        m.triggerOnPanel = true
        assertTrue(m.step(4 * ms, input).volume > 0f)
    }

    @Test
    fun mirroredSettingsUseTheOtherSide() {
        val m = mapper()
        m.settings = MapSettings(handed = Handed.LEFT_PITCH)
        val c = Instrument.loopCenter(Handed.LEFT_PITCH)
        m.injectVolume = Inject.Joints(Vec3(c.x, c.y + 0.22f, c.z), null)
        m.injectPitch = Inject.Joints(Instrument.ripplePoint(0.31f, 20f, Handed.LEFT_PITCH, 0.25f), null)
        val f = m.step(0, RoleInputs())
        assertEquals(1f, f.volume, 1e-5f)
        assertEquals(4f, f.s, 1e-3f)
    }

    @Test
    fun holdTrackerRule() {
        val t = HoldTracker(Lessons.byId("L1")!!)
        repeat(150) { t.update(0.01f, 0f, 0.3f, true) } // 1.5 s on target
        assertEquals(0.5f, t.progress, 0.01f)
        repeat(25) { t.update(0.01f, 0.6f, 0.3f, true) } // 0.25 s at +60 cents: falls 0.5 s
        assertEquals(1.0f / 3f, t.progress, 0.01f)
        repeat(250) { t.update(0.01f, 0.1f, 0.3f, true) }
        assertTrue(t.done)
        // 1.5 s at 0 cents and 2.0 s at 10 cents inside the tolerance
        assertEquals(6, t.result().meanCents)
        val quiet = HoldTracker(Lessons.byId("L1")!!)
        repeat(400) { quiet.update(0.01f, 0f, 0.1f, true) } // volume under 0.15 never counts
        assertFalse(quiet.done)
    }

    @Test
    fun lessonsWalkThrough() {
        for (lesson in Lessons.all) {
            val t = HoldTracker(lesson)
            var guard = 0
            while (!t.done && guard++ < 100_000) t.update(0.01f, t.target!!.toFloat(), 0.5f, true)
            assertTrue(lesson.id, t.done)
            assertEquals(lesson.targets.size * lesson.holdS, t.result().seconds, 0.05f * lesson.targets.size)
        }
    }

    private val songs by lazy { SongBook.parse(File("src/main/assets/songs.json").readText()) }

    @Test
    fun sixSongsScoreIdealAndDetuned() {
        assertEquals(6, songs.size)
        for (song in songs) {
            for (offsetCents in listOf(0f, 60f)) {
                val sc = SongScorer(song, slow = false)
                var guard = 0
                while (!sc.done && guard++ < 1_000_000) {
                    val i = sc.current
                    val s = (i?.let { song.notes[it].semitone } ?: 0) + offsetCents / 100f
                    sc.update(0.005f, s, i != null)
                }
                val r = sc.result()
                if (offsetCents == 0f) assertTrue("${song.id} ideal ${r.score}", r.score >= 95) else assertTrue("${song.id} detuned ${r.score}", r.score < 10)
            }
            assertTrue(song.id, song.notes.all { it.semitone in 0..14 })
        }
        assertEquals(3, SongScorer.stars(90))
        assertEquals(2, SongScorer.stars(82))
        assertEquals(0, SongScorer.stars(59))
    }

    @Test
    fun waitingPoseIsBeyondAndBesideThePanel() {
        // panel at (0, 1.2, -0.7) facing the user at +z
        val panel = Vec3(0f, 1.2f, -0.7f)
        val p = PlaceRules.besidePanel(panel, Vec3(0f, 0f, 1f))
        assertEquals(-0.7f - PlaceRules.WAIT_BEYOND_M, p.center.z, 1e-4f) // beyond the panel, never between user and panel
        assertEquals(PlaceRules.WAIT_SIDE_M, p.center.x, 1e-4f) // to the panel's right
        assertTrue("within reach, not where a desk monitor stands", -p.center.z < 1.2f)
        assertEquals(1.2f - PlaceRules.WAIT_DROP_M, p.center.y, 1e-4f) // feet 0.35 m under the panel: hand height seated or standing
        val front = p.dirToStage(Vec3(0f, 0f, 1f))
        assertEquals(1f, front.z, 1e-4f) // turned back to the panel / user side
        // a floor marker: on the stand at desk height, facing the user
        val f = PlaceRules.onFloor(Vec3(1f, 0f, -1f), Vec3(1f, 1f, 0f))
        assertEquals(PlaceRules.STAND_TOP_M, f.center.y, 1e-4f)
        assertEquals(1f, f.dirToStage(Vec3(0f, 0f, 1f)).z, 1e-4f)
    }

    /** v0.1.5 (D24): the marker ray meets the app's own floor plane only, analytically; up / too far / too near give nothing. */
    @Test
    fun markerRayMeetsTheVirtualFloorOnly() {
        val eye = Vec3(0f, 1.6f, 0f)
        val hit = PlaceRules.floorHit(eye, Vec3(0f, -1.6f, -2f).normalized())
        assertNotNull(hit)
        assertEquals(0f, hit!!.y, 1e-4f)
        assertEquals(-2f, hit.z, 1e-3f)
        assertTrue(PlaceRules.floorHit(eye, Vec3(0f, 0.2f, -1f).normalized()) == null) // pointing up
        assertTrue(PlaceRules.floorHit(eye, Vec3(0f, -0.1f, -1f).normalized()) == null) // 16 m away: too far
        assertTrue(PlaceRules.floorHit(Vec3(0f, 0.1f, 0f), Vec3(0f, -1f, 0f)) == null) // 0.1 m: too near
    }

    /**
     * v0.1.3 regression (user: "朝向和 UI 又反了"): standing east, south, west or north of the marker, the placed
     * instrument's front and the music stand's normal point at the user within 10 degrees, and the stand is not mirrored.
     */
    @Test
    fun placedInstrumentFacesTheUserFromEverySide() {
        val marker = Vec3(0.4f, 0f, -1.1f)
        val stances = mapOf("east" to Vec3(1.6f, 1.6f, -1.1f), "south" to Vec3(0.4f, 1.6f, 0.2f), "west" to Vec3(-0.9f, 1.6f, -1.1f), "north" to Vec3(0.4f, 1.6f, -2.4f))
        for ((name, user) in stances) {
            val floor = PlaceRules.onFloor(marker, user)
            val wait = PlaceRules.besidePanel(Vec3(0.2f, 1.3f, -0.6f), Vec3(0f, 0f, 1f), user)
            for ((what, pose) in listOf("floor" to floor, "waiting" to wait)) {
                val toUser = user - pose.center
                assertTrue("$name $what front ${PlaceRules.facingErrorDeg(pose, user)}", PlaceRules.facingErrorDeg(pose, user) < 10f)
                val stand = Instrument.standRotation(pose)
                val n = stand.rotate(Vec3(0f, 0f, 1f))
                assertTrue("$name $what stand normal", PlaceRules.facingErrorDeg(n, toUser) < 10f)
                // readable, not mirrored: the stand's +X is the viewer's right, its +Y up
                val view = Vec3(-toUser.x, 0f, -toUser.z).normalized()
                val viewerRight = view.cross(Vec3.UP)
                assertTrue("$name $what mirrored", stand.rotate(Vec3(1f, 0f, 0f)).dot(viewerRight) > 0.9f)
                assertTrue("$name $what upside down", stand.rotate(Vec3(0f, 1f, 0f)).y > 0.9f)
                // text scale never negative (D10)
                assertTrue("$name $what scale", pose.rotation.rotate(Vec3(1f, 0f, 0f)).length > 0.99f)
            }
        }
    }
}
