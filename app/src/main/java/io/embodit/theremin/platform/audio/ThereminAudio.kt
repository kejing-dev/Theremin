package io.embodit.theremin.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.pico.spatial.audio.SpatialAudioTrackExtension
import com.pico.spatial.core.ecs.Entity
import io.embodit.theremin.domain.audio.SynthCore
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Quantize
import io.embodit.theremin.domain.model.Timbre
import io.embodit.theremin.domain.play.PlayFrame
import io.embodit.theremin.domain.play.PlayMapper
import io.embodit.theremin.domain.play.RoleInputs
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.platform.input.ControllerInput
import io.embodit.theremin.platform.input.HandInput
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The instrument's sound (design `audio.engine`): one low-latency float AudioTrack, 48 kHz mono, buffer two
 * bursts, always playing (silence is written while quiet, so there is no start-up delay). The audio thread
 * (URGENT_AUDIO) does the whole hand -> sound path itself every block of one burst (about 4 ms): it reads the
 * hand / controller providers' `latestData`, runs [PlayMapper.step] and renders [SynthCore]. The frame system
 * only reads [PlayMapper.last] to draw. With the SpatialAudioTrackExtension (OBJECT) attached to the antenna the
 * sound comes from the instrument.
 *
 * [start] / [pause] / [release] from the main thread; every start has its release (scene detach).
 */
class ThereminAudio(
    private val context: Context,
    private val mapper: PlayMapper,
    private val hands: HandInput,
    private val controllers: ControllerInput,
) {
    @Volatile var timbre: Timbre = Timbre.CLASSIC

    @Volatile var muted: Boolean = false

    @Volatile var quantize: Quantize = Quantize.OFF

    @Volatile var handed: Handed = Handed.RIGHT_PITCH

    /** Reference tone (lessons): midi and remaining samples; plays only while the volume role is silent. */
    @Volatile private var refMidi = 60.0
    @Volatile private var refRequestMs = 0L
    @Volatile private var cueRequested = false

    /** Debug recordWav: samples still to copy. */
    @Volatile private var recordLeft = 0
    private var recordBuf: FloatArray? = null
    private var recordPos = 0

    @Volatile var lastBufferMs: Float = 0f
        private set

    @Volatile var running = false
        private set

    private var track: AudioTrack? = null
    private var extension: SpatialAudioTrackExtension? = null
    private var thread: Thread? = null
    @Volatile private var quit = false
    @Volatile private var paused = false
    private var burst = 192
    private var sampleRate = 48_000

    fun start(antenna: Entity?) {
        if (track != null) {
            resume()
            return
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..2048 } ?: 192
        val builder =
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(burst * 4 * 4)
        val ext =
            if (antenna != null) {
                runCatching { SpatialAudioTrackExtension().also { it.spatialAudioTrackExtensionConfig(SpatialAudioTrackExtension.SpatialAudioMode.OBJECT, false, builder) } }
                    .onFailure { ThLog.w("AUDIO spatial config failed: $it") }
                    .getOrNull()
            } else null
        val t = runCatching { builder.build() }.onFailure { ThLog.e("AUDIO track failed: $it", it) }.getOrNull() ?: return
        // v0.1.4: three bursts to start (two underran into crackle on the headset), grown by one burst per underrun up to six
        runCatching { t.setBufferSizeInFrames(burst * 3) }
        if (ext != null && antenna != null) {
            runCatching { ext.attachToEntityWithAudioTrack(antenna, t) }.onFailure { ThLog.w("AUDIO spatial attach failed: $it") }
        }
        extension = ext
        track = t
        ThLog.i("AUDIO track rate=$sampleRate burst=$burst buffer=${t.bufferSizeInFrames} perf=${t.performanceMode} spatial=${ext != null}")
        quit = false
        paused = false
        runCatching { t.play() }
        val th = Thread({ loop(t) }, "theremin-synth")
        thread = th
        running = true
        th.start()
    }

    fun pause() {
        paused = true
        runCatching { track?.pause() }
        ThLog.i("AUDIO paused")
    }

    fun resume() {
        if (!paused) return
        paused = false
        runCatching { track?.play() }
        ThLog.i("AUDIO resumed")
    }

    fun release() {
        quit = true
        thread?.let { runCatching { it.join(200) } }
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { extension?.release() }
        track = null
        extension = null
        running = false
        ThLog.i("AUDIO released")
    }

    /** Lessons: plays [midi] for 1 s at 0.6 if the volume role is silent. */
    fun referenceTone(midi: Double) {
        refMidi = midi
        refRequestMs = SystemClock.elapsedRealtime()
    }

    fun cue() {
        cueRequested = true
    }

    /** Debug: records the next [seconds] of output into files/rec.wav. */
    fun record(seconds: Float) {
        val n = (seconds * sampleRate).toInt().coerceIn(1, sampleRate * 20)
        recordBuf = FloatArray(n)
        recordPos = 0
        recordLeft = n
        ThLog.i("AUDIO record ${ThLog.f(seconds)} s")
    }

    private fun inputs(): RoleInputs {
        val (lh, rh) = hands.sample()
        val (lc, rc) = controllers.hands()
        val right = handed == Handed.RIGHT_PITCH
        return RoleInputs(
            pitchHand = if (right) rh else lh,
            volumeHand = if (right) lh else rh,
            pitchController = if (right) rc else lc,
            volumeController = if (right) lc else rc,
        )
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val core = SynthCore(sampleRate)
        val buf = FloatArray(burst)
        var refStartMs = 0L
        var lastLatLogMs = 0L
        val ts = AudioTimestamp()
        var written = 0L
        var lastUnderruns = 0
        while (!quit) {
            if (paused) {
                SystemClock.sleep(10)
                continue
            }
            val now = System.nanoTime()
            val frame: PlayFrame = runCatching { mapper.step(now, inputs()) }.getOrElse { mapper.last }
            core.timbre = timbre
            core.master = if (muted) 0.0 else SynthCore.DEFAULT_MASTER
            core.pitchTauS = if (quantize == Quantize.STRONG) SynthCore.TAU_PITCH_STRONG_S else SynthCore.TAU_PITCH_S
            if (cueRequested) {
                cueRequested = false
                core.cue()
            }
            var midi = frame.midiPlayed.toDouble()
            var vol = frame.amplitude.toDouble()
            // reference tone: 1 s at 0.6 with 80 ms edges, only while the player is silent
            val nowMs = SystemClock.elapsedRealtime()
            if (refRequestMs != 0L) {
                refStartMs = refRequestMs
                refRequestMs = 0L
            }
            if (refStartMs != 0L) {
                val e = nowMs - refStartMs
                if (e > REF_MS + 200 || frame.amplitude > 0f) refStartMs = 0L
                else if (e < REF_MS) {
                    midi = refMidi
                    vol = REF_LEVEL
                }
            }
            core.render(buf, 0, burst, midi, vol)
            val w = t.write(buf, 0, burst, AudioTrack.WRITE_BLOCKING)
            if (w < 0) {
                ThLog.w("AUDIO write error $w")
                SystemClock.sleep(5)
            } else written += w
            val rec = recordBuf
            if (recordLeft > 0 && rec != null) {
                val k = minOf(burst, recordLeft)
                System.arraycopy(buf, 0, rec, recordPos, k)
                recordPos += k
                recordLeft -= k
                if (recordLeft == 0) saveRecording(rec)
            }
            if (nowMs - lastLatLogMs > 1000) {
                lastLatLogMs = nowMs
                val under = runCatching { t.underrunCount }.getOrDefault(0)
                if (under > lastUnderruns) {
                    lastUnderruns = under
                    val grown = (t.bufferSizeInFrames + burst).coerceAtMost(burst * 6)
                    runCatching { t.setBufferSizeInFrames(grown) }
                    ThLog.w("AUDIO underrun total=$under buffer=${t.bufferSizeInFrames}")
                }
                if (runCatching { t.getTimestamp(ts) }.getOrDefault(false)) {
                    lastBufferMs = ((written - ts.framePosition) * 1000f / sampleRate)
                }
            }
        }
    }

    private fun saveRecording(x: FloatArray) {
        val file = File(context.filesDir, "rec.wav")
        runCatching {
            RandomAccessFile(file, "rw").use { f ->
                f.setLength(0)
                val data = x.size * 2
                fun i32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
                fun i16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                f.writeBytes("RIFF"); i32(36 + data); f.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
                f.writeBytes("data"); i32(data)
                val b = ByteArray(data)
                x.forEachIndexed { i, v ->
                    val s = (v.coerceIn(-1f, 1f) * 32767).toInt()
                    b[2 * i] = s.toByte()
                    b[2 * i + 1] = (s shr 8).toByte()
                }
                f.write(b)
            }
            val peak = x.maxOf { kotlin.math.abs(it) }
            ThLog.i(String.format(Locale.US, "AUDIO recorded %s samples=%d peak=%.4f", file.absolutePath, x.size, peak))
        }.onFailure { ThLog.e("AUDIO record save failed: $it", it) }
    }

    companion object {
        private const val REF_MS = 1000L
        private const val REF_LEVEL = 0.6
    }
}
