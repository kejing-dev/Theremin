package io.embodit.theremin.domain

import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.PanelDrag
import io.embodit.theremin.domain.usecase.PlaceUpright
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v0.1.7 (taste D21, headset v0.1.6 "面板会一致抖动，还会 orbit 运动"): a grab bar drag moves the panel in Stage space from
 * where it was grabbed, in its own plane, with its turn frozen; a still pointer leaves it still (< 2 mm); the move
 * never depends on where the panel is meanwhile (the pointer's motion is measured in a frame that does not move).
 */
class PanelDragTest {
    private val start = Vec3(0.3f, 1.3f, -0.8f)

    @Test
    fun stillPointerKeepsThePanelStill() {
        val d = PanelDrag()
        d.begin(start, 0.6f)
        d.moveTo(Vec3(0.12f, 0.03f, 0f))
        val held = d.target()
        val rnd = Random(7)
        var maxDev = 0f
        // 3 s at 60 Hz of a pointer that rests, with +-0.5 mm tracking noise
        repeat(180) {
            d.moveTo(Vec3(0.12f, 0.03f, 0f) + Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.001f)
            maxDev = maxOf(maxDev, d.target().distanceTo(held))
        }
        assertTrue("resting pointer moved the panel ${maxDev * 1000} mm", maxDev < 0.002f)
    }

    @Test
    fun movesInItsOwnPlaneWithTheTurnFrozen() {
        val yaw = Math.toRadians(40.0).toFloat()
        val d = PanelDrag()
        d.begin(start, yaw)
        // the pointer goes right, up and 0.3 m towards the user: the panel follows right and up, not in depth
        d.moveTo(Vec3(0.2f, 0.1f, 0.3f))
        val n = PlaceUpright.rotation(yaw).rotate(Vec3(0f, 0f, 1f))
        assertEquals(0f, (d.target() - start).dot(n), 1e-5f)
        assertEquals(yaw, d.yaw, 0f)
        assertTrue(d.target().y > start.y + 0.09f)
    }

    @Test
    fun debugDragIsInThePanelsPlane() {
        val d = PanelDrag()
        d.begin(start, Math.toRadians(90.0).toFloat())
        val m = d.inPlane(100f, -50f)
        val right = PlaceUpright.rotation(d.yaw).rotate(Vec3.RIGHT)
        assertTrue(m.distanceTo(right * 0.08f + Vec3.UP * 0.04f) < 1e-5f)
    }

    @Test
    fun rightThenLeftComesBackWithoutCircling() {
        val d = PanelDrag()
        d.begin(start, 0.3f)
        val right = PlaceUpright.rotation(0.3f).rotate(Vec3.RIGHT)
        var maxOff = 0f
        repeat(3) {
            for (i in 0..30) {
                d.moveTo(right * (i * 0.01f))
                maxOff = maxOf(maxOff, (d.target() - start).rejectFrom(right).length)
            }
            for (i in 30 downTo 0) d.moveTo(right * (i * 0.01f))
        }
        assertTrue("left the drag line by $maxOff m", maxOff < 1e-5f)
        assertTrue(d.target().distanceTo(start) < 1e-5f)
    }

    @Test
    fun freezeModeOnlyMeasures() {
        val d = PanelDrag()
        d.mode = PanelDrag.Mode.FREEZE
        d.begin(start, 0f)
        d.moveTo(Vec3(0.2f, 0f, 0f))
        assertTrue(d.target() == start)
        assertTrue(d.moved.x > 0.1f)
    }
}
