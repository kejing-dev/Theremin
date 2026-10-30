package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.Curve
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.domain.model.Quantize
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentTest {
    @Test
    fun a4IsExactly440() {
        assertEquals(440.0, Instrument.frequency(9f, Octave.MID), 0.01)
        assertEquals(261.626, Instrument.frequency(0f, Octave.MID), 0.01)
        assertEquals(130.813, Instrument.frequency(0f, Octave.LOW), 0.01)
        assertEquals(523.251, Instrument.frequency(0f, Octave.HIGH), 0.01)
    }

    @Test
    fun ripplesAndSemitonesAreInverse() {
        for (scale in Scale.entries) for (n in 0..scale.top) {
            assertEquals(n.toFloat(), Instrument.semitone(Instrument.radius(n, scale), scale), 1e-4f)
        }
        assertEquals(0.398f, Instrument.maxRadius(Scale.STANDARD), 1e-4f)
        assertEquals(0.39f, Instrument.maxRadius(Scale.WIDE), 1e-4f)
        assertEquals(0.31f, Instrument.radius(4, Scale.STANDARD), 1e-4f)
        assertEquals(0.244f, Instrument.radius(7, Scale.STANDARD), 1e-4f)
    }

    /** The QA values of design S09 / S14. */
    @Test
    fun qaCaptureValues() {
        val s = Instrument.semitone(0.319f, Scale.STANDARD)
        assertEquals(4, Instrument.nearest(s))
        assertEquals(-40.9f, Instrument.cents(s), 0.1f)
        assertEquals(0f, Instrument.cents(Instrument.semitone(0.310f, Scale.STANDARD)), 0.01f)
        val high = Instrument.semitone(0.234f, Scale.STANDARD)
        assertEquals(7, Instrument.nearest(high))
        assertEquals(45.5f, Instrument.centsFrom(high, 7), 0.1f)
        assertEquals(0f, Instrument.centsFrom(Instrument.semitone(0.398f, Scale.STANDARD), 0), 0.01f)
    }

    @Test
    fun volumeCurve() {
        assertEquals(0f, Instrument.volume(0.01f, Curve.SOFT), 0f)
        assertEquals(0.287f, Instrument.volume(0.11f, Curve.SOFT), 0.005f)
        assertEquals(1f, Instrument.volume(0.20f, Curve.SOFT), 0f)
        assertEquals(0.5f, Instrument.volume(0.11f, Curve.LINEAR), 1e-4f)
        assertEquals(0f, Instrument.volume(null, Curve.SOFT), 0f)
    }

    @Test
    fun loopReachAndPalmDrop() {
        val c = Instrument.LOOP_CENTER
        assertEquals(0.10f, Instrument.loopHeight(Vec3(c.x, c.y + 0.12f, c.z), Handed.RIGHT_PITCH)!!, 1e-5f)
        assertNull(Instrument.loopHeight(Vec3(c.x + 0.21f, c.y + 0.12f, c.z), Handed.RIGHT_PITCH))
        // mirrored: the loop moves to +x
        assertNull(Instrument.loopHeight(Vec3(c.x, c.y + 0.12f, c.z), Handed.LEFT_PITCH))
        assertEquals(0.10f, Instrument.loopHeight(Vec3(-c.x, c.y + 0.12f, c.z), Handed.LEFT_PITCH)!!, 1e-5f)
    }

    @Test
    fun pitchPointLeadsTheLevelPalm() {
        val palm = Vec3(0.2f, 0.25f, 0.3f)
        val wrist = Vec3(0.2f, 0.20f, 0.40f) // fingers point at -z and upwards
        val p = Instrument.pitchPoint(palm, wrist)
        assertEquals(0.26f, p.z, 1e-5f)
        assertEquals(palm.y, p.y, 0f)
        assertEquals(palm, Instrument.pitchPoint(palm, null))
    }

    @Test
    fun quantize() {
        assertEquals(4.3f, Instrument.quantized(4.3f, Quantize.OFF), 0f)
        assertEquals(4.15f, Instrument.quantized(4.3f, Quantize.LIGHT), 1e-5f)
        assertEquals(329.63, Instrument.frequency(Instrument.quantized(4.3f, Quantize.STRONG), Octave.MID), 0.01)
    }

    @Test
    fun mirrorKeepsGeometry() {
        for (deg in listOf(-60f, -50f, 0f, 25f)) {
            val r = Instrument.ripplePoint(0.3f, Instrument.sectorDeg(deg, Handed.RIGHT_PITCH), Handed.RIGHT_PITCH)
            val l = Instrument.ripplePoint(0.3f, Instrument.sectorDeg(deg, Handed.LEFT_PITCH), Handed.LEFT_PITCH)
            assertEquals(r.x, -l.x, 1e-5f)
            assertEquals(r.z, l.z, 1e-5f)
            assertEquals(0.3f, Instrument.antennaDistance(r, Handed.RIGHT_PITCH), 1e-5f)
        }
        // labels at -50 degrees lie on the outer (+x) side, away from the volume loop
        assertTrue(Instrument.ripplePoint(0.3f, -50f, Handed.RIGHT_PITCH).x > Instrument.ANTENNA_X)
    }

    @Test
    fun noteNames() {
        assertEquals("E4", Instrument.letter(4, Octave.MID))
        assertEquals("C5", Instrument.letter(12, Octave.MID))
        assertEquals("C3", Instrument.letter(0, Octave.LOW))
        assertEquals("mi", Instrument.solfege(4))
        assertEquals(12, Instrument.parseNote("C'"))
    }

    @Test
    fun poseRoundTrip() {
        val pose = InstrumentPose(Vec3(0.5f, 0.75f, -1.2f), 0.7f)
        val p = Vec3(0.1f, 0.2f, 0.3f)
        val back = pose.toLocal(pose.toStage(p))
        assertTrue(abs(back.x - p.x) < 1e-5f && abs(back.y - p.y) < 1e-5f && abs(back.z - p.z) < 1e-5f)
    }

    /** Layout check of the design (`instrument.layoutCheck`): every lesson target reachable from the right shoulder. */
    @Test
    fun lessonTargetsWithinReach() {
        val shoulder = Vec3(0.18f, 0.30f, 0.57f)
        for (scale in Scale.entries) for (n in 0..12) {
            val r = Instrument.radius(n, scale)
            // nearest point of that arc in the sector to the shoulder
            var best = Float.MAX_VALUE
            var deg = Instrument.SECTOR_FROM_DEG
            while (deg <= Instrument.SECTOR_TO_DEG) {
                val p = Instrument.ripplePoint(r, deg, Handed.RIGHT_PITCH)
                best = minOf(best, Vec3(p.x - shoulder.x, 0f, p.z - shoulder.z).length)
                deg += 1f
            }
            assertTrue("n=$n $scale reach=$best", best in 0.15f..0.52f)
        }
    }
}
