package io.embodit.theremin.domain.play

import io.embodit.theremin.domain.model.Instrument
import kotlin.math.abs
import kotlin.math.roundToInt

/** A basics lesson (design `lessons.list`): targets as semitones above the low C, hold seconds each. */
data class Lesson(val id: String, val zh: String, val en: String, val targets: List<Int>, val holdS: Float)

object Lessons {
    val all: List<Lesson> =
        listOf(
            Lesson("L1", "长音 · do", "Long tone · Do", notes("C"), 3.0f),
            Lesson("L2", "长音 · sol", "Long tone · Sol", notes("G"), 3.0f),
            Lesson("L3", "do re mi", "Do Re Mi", notes("C", "D", "E", "D", "C"), 1.2f),
            Lesson("L4", "音阶 · 上行", "Scale · up", notes("C", "D", "E", "F", "G", "A", "B", "C'"), 1.0f),
            Lesson("L5", "音阶 · 下行", "Scale · down", notes("C'", "B", "A", "G", "F", "E", "D", "C"), 1.0f),
            Lesson("L6", "跳音 · do mi sol", "Leaps · Do Mi Sol", notes("C", "E", "G", "C'", "G", "E", "C"), 1.0f),
        )

    private fun notes(vararg names: String): List<Int> = names.map(Instrument::parseNote)

    fun byId(id: String): Lesson? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Fixed tolerance: +-40 cents, volume at least 0.15. */
    const val TOLERANCE_CENTS = 40f
    const val MIN_VOLUME = 0.15f
}

/** Result of a finished lesson: time taken, mean |cents| while holding (rounded). */
data class LessonResult(val lessonId: String, val seconds: Float, val meanCents: Int)

/**
 * The hold rule (design `lessons.rule`): the hold timer rises while |cents from the target| <= 40 and the
 * volume >= 0.15, falls at twice the speed otherwise (never below zero); full -> next target. The mean
 * deviation averages |cents| over the time spent inside the tolerance.
 */
class HoldTracker(val lesson: Lesson) {
    var step = 0
        private set
    var hold = 0f
        private set
    var elapsed = 0f
        private set
    private var centsSum = 0.0
    private var centsTime = 0.0

    val done: Boolean
        get() = step >= lesson.targets.size

    val target: Int?
        get() = lesson.targets.getOrNull(step)

    val next: Int?
        get() = lesson.targets.getOrNull(step + 1)

    val progress: Float
        get() = (hold / lesson.holdS).coerceIn(0f, 1f)

    fun restart() {
        step = 0
        hold = 0f
        elapsed = 0f
        centsSum = 0.0
        centsTime = 0.0
    }

    /** Jumps to [n] (debug `step=`). */
    fun jumpTo(n: Int) {
        step = n.coerceIn(0, lesson.targets.size)
        hold = 0f
    }

    /** Completes the current target (debug `completeTarget`). */
    fun completeTarget(): Boolean {
        if (done) return false
        step++
        hold = 0f
        return true
    }

    /** Advances by [dt] seconds; returns true when a target was completed in this step. */
    fun update(dt: Float, s: Float, volume: Float, sounding: Boolean): Boolean {
        val t = target ?: return false
        elapsed += dt
        val c = Instrument.centsFrom(s, t)
        val inside = sounding && abs(c) <= Lessons.TOLERANCE_CENTS && volume >= Lessons.MIN_VOLUME
        if (inside) {
            hold += dt
            centsSum += abs(c) * dt
            centsTime += dt
        } else {
            hold = (hold - 2f * dt).coerceAtLeast(0f)
        }
        if (hold >= lesson.holdS) {
            step++
            hold = 0f
            return true
        }
        return false
    }

    fun result(): LessonResult =
        LessonResult(lesson.id, elapsed, if (centsTime > 0) (centsSum / centsTime).roundToInt() else 0)
}
