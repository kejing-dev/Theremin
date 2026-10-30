package io.embodit.theremin.domain.play

import io.embodit.theremin.domain.model.Instrument
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

/** One note of a song: semitone above the low C, length in beats. */
data class SongNote(val semitone: Int, val beats: Float)

data class Song(val id: String, val zh: String, val en: String, val bpm: Float, val notes: List<SongNote>, val source: String) {
    val beats: Float = notes.sumOf { it.beats.toDouble() }.toFloat()

    /** Start beat of every note (after the count-in). */
    val starts: FloatArray =
        FloatArray(notes.size).also { a ->
            var b = 0f
            notes.forEachIndexed { i, n ->
                a[i] = b
                b += n.beats
            }
        }

    /** Index of the note sounding at [beat] (0 = first note's start), or null outside the song. */
    fun noteAt(beat: Float): Int? {
        if (beat < 0f || beat >= beats) return null
        var i = starts.size - 1
        while (i > 0 && starts[i] > beat) i--
        return i
    }
}

/**
 * The six follow-along songs (design `songs`), read from `assets/songs.json`:
 * `{"songs":[{"id","zh","en","bpm","source","notes":[[semitone, beats], ...]}]}`.
 */
object SongBook {
    const val COUNT_IN_BEATS = 4

    /** Slow speed plays at 60 % of the tempo. */
    const val SLOW_FACTOR = 0.6f

    fun parse(json: String): List<Song> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("songs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ns = o.getJSONArray("notes")
            val notes = (0 until ns.length()).map { k -> ns.getJSONArray(k).let { SongNote(it.getInt(0), it.getDouble(1).toFloat()) } }
            Song(o.getString("id"), o.getString("zh"), o.getString("en"), o.getDouble("bpm").toFloat(), notes, o.optString("source"))
        }
    }
}

data class SongResult(val songId: String, val score: Int, val stars: Int, val good: Int, val total: Int)

/**
 * Follow-along scoring (design `songs.rule`): a note's score is the share of its time (less the first 120 ms
 * of grace) in which the hand sounds within +-40 cents of it; >= 60 % counts as "in tune". The song's score
 * is the duration-weighted mean x 100; one star from 60, two from 75, three from 90. The true hand pitch is
 * scored, never the snapped sound.
 */
class SongScorer(val song: Song, val slow: Boolean) {
    private val inside = DoubleArray(song.notes.size)
    private val counted = DoubleArray(song.notes.size)

    val beatS: Float = 60f / (song.bpm * if (slow) SongBook.SLOW_FACTOR else 1f)

    var timeS = 0f
        private set

    /** Beat position from the first note's start (negative during the count-in). */
    val beat: Float
        get() = timeS / beatS - SongBook.COUNT_IN_BEATS

    val done: Boolean
        get() = beat >= song.beats

    val current: Int?
        get() = song.noteAt(beat)

    fun restart() {
        timeS = 0f
        inside.fill(0.0)
        counted.fill(0.0)
    }

    /** Moves to [b] beats into the song (debug `beat=`). */
    fun seekBeat(b: Float) {
        timeS = (b + SongBook.COUNT_IN_BEATS) * beatS
    }

    fun update(dt: Float, s: Float, sounding: Boolean) {
        val i = song.noteAt(beat)
        timeS += dt
        if (i == null) return
        val since = (beat - song.starts[i]) * beatS
        if (since < GRACE_S) return
        counted[i] += dt.toDouble()
        if (sounding && abs(Instrument.centsFrom(s, song.notes[i].semitone)) <= Lessons.TOLERANCE_CENTS) inside[i] += dt.toDouble()
    }

    fun noteScore(i: Int): Double = if (counted[i] <= 0) 0.0 else (inside[i] / counted[i]).coerceIn(0.0, 1.0)

    fun result(): SongResult {
        var w = 0.0
        var sum = 0.0
        var good = 0
        song.notes.forEachIndexed { i, n ->
            val sc = noteScore(i)
            sum += sc * n.beats
            w += n.beats
            if (sc >= GOOD_SHARE) good++
        }
        val score = if (w > 0) (100 * sum / w).roundToInt() else 0
        return SongResult(song.id, score, stars(score), good, song.notes.size)
    }

    companion object {
        const val GRACE_S = 0.12f
        const val GOOD_SHARE = 0.6

        fun stars(score: Int): Int = when {
            score >= 90 -> 3
            score >= 75 -> 2
            score >= 60 -> 1
            else -> 0
        }
    }
}
