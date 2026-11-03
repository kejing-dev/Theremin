package io.embodit.theremin.debug

import android.content.Context
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.Inject
import io.embodit.theremin.platform.ThGraph
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.ui.play.PlayEvent
import io.embodit.theremin.ui.play.debug.ThProbe
import io.embodit.theremin.ui.play.scene.PanelSpec
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * QA hooks of the debug build (design `debugHooks`). `key=value` words in one string (or separate extras),
 * applied in a fixed order so that combos read naturally:
 *
 *   adb shell "am broadcast -a io.embodit.theremin.DEBUG -p io.embodit.theremin --es cmd 'desk=seed place=snap freeze=1'"
 *   adb shell "am broadcast -a io.embodit.theremin.DEBUG -p io.embodit.theremin --es cmd 'scale=standard octave=mid hand=0.319,0.12'"
 *
 * Injection (instrument frame): `hand=<d|off>,<h|off>` (d = pitch point to antenna axis, h = volume point over
 * the loop; `hand=idle` = 0.25,off; `hand=off`), `joints=rightWrist:x,y,z;rightPalm:x,y,z;leftPalm:x,y,z` (full
 * mapping), `pinch=0|1`, `handpath=scale|glide|vibrato|song|song60|off` (72 Hz), `source=hands|controllers|none`.
 * Audio: `recordwav=<s>` (files/rec.wav), `pitchinfo`. Labels: `labeltest=1` (F-arrow test texture).
 * Everything else goes to the ViewModel: `state=Sxx`, `place=front` (= 移到标记处; `desk=` / `plane=` / `place=ghost|snap|down` are
 * accepted and ignored since v0.1.5, D24), `marker=floor` (set the marker on the Stage floor), `tomarker` (移到标记处), `lesson=L1..L6`, `step=n`, `completetarget`, `done=1`, `song=G1..G6`, `beat=n`,
 * `slow=0|1`, `score=n`, `seed=lessons`, `coach=1|2|3|reset`, `timbre=sine|classic|warm|bright`,
 * `octave=low|mid|high`, `scale=standard|wide`, `curve=soft|linear`, `quantize=off|light|strong`, `mirror=0|1`,
 * `mute=0|1`, `lang=zh|en|system`, `freeze=0|1`, `page=<Page>`, `library=basics|songs`, `settings`, `menu`,
 * `back`, `replace`, `exit` / `exit=confirm` / `exit=cancel` (D26, the user's own path); queries `dump`, `hmd`, `bounds`, `reality`
 * (= `planes`: D24 audit line), `park`, `ui=<control>`; v0.1.7 `standinfo`, `dragmode=plane|free|freeze`.
 * Every state change logs `STATE Sxx...` under the tag `Theremin`.
 */
internal object DebugCommands {

    private val ORDER =
        listOf(
            "lang", "mirror", "scale", "octave", "curve", "quantize", "snap", "timbre", "mute", "coach_reset", "freeze_off", "freeze",
            "source", "desk", "plane", "state", "place", "seed", "library", "lesson", "song", "slow", "step", "beat", "completetarget", "done", "score",
            "coach", "page", "settings", "menu", "back", "replace", "marker", "tomarker", "hand", "joints", "pinch", "handpath", "labeltest", "ui",
            "spatialaudio", "recordwav", "pitchinfo", "dump", "hmd", "bounds", "planes", "reality", "park", "paneldrag", "move", "standdrag", "standrecall", "standinfo", "dragmode", "exit",
        )

    private var pathThread: Thread? = null

    fun run(context: Context, extras: Map<String, String>) {
        val host = ThProbe.host
        if (host == null) {
            ThLog.i("CMD ignored: the Stage is not running")
            return
        }
        val pairs = LinkedHashMap<String, String>()
        extras["cmd"]?.let { raw -> parse(raw).forEach { (k, v) -> pairs[k] = v } }
        for ((k, v) in extras) if (k != "cmd") pairs[k.lowercase(Locale.US)] = v
        pairs["freeze"]?.let { if (it == "0" || it == "off") { pairs.remove("freeze"); pairs["freeze_off"] = "0" } }
        pairs["coach"]?.let { if (it == "reset") { pairs.remove("coach"); pairs["coach_reset"] = "reset" } }
        val keys = pairs.keys.sortedBy { k -> ORDER.indexOf(k).let { if (it < 0) ORDER.size else it } }
        for (k in keys) {
            val v = pairs[k] ?: continue
            try {
                apply(context, host, when (k) { "freeze_off" -> "freeze"; "coach_reset" -> "coach"; else -> k }, v)
            } catch (t: Throwable) {
                ThLog.e("CMD $k=$v failed: $t", t)
            }
        }
    }

    /** `a=1,b=2 c=3` -> pairs; a comma separates pairs only when a `key=` follows, so `hand=0.31,0.12` keeps its value. */
    fun parse(raw: String): List<Pair<String, String>> =
        raw.trim().split(Regex("\\s+"))
            .flatMap { chunk -> chunk.split(Regex(",(?=[A-Za-z_]+=)")) }
            .map { it.trim().trim(',') }
            .filter { it.isNotEmpty() }
            .map { token ->
                val i = token.indexOf('=')
                if (i < 0) {
                    if (token.uppercase(Locale.US).matches(Regex("S\\d+"))) "state" to token else token.lowercase(Locale.US) to "1"
                } else token.substring(0, i).lowercase(Locale.US) to token.substring(i + 1)
            }

    private fun apply(context: Context, host: ThProbe.Host, key: String, value: String) {
        val scene = host.scene()
        val m = ThGraph.mapper
        when (key) {
            "hand" -> {
                stopPath()
                m.injectSource = true
                when (value) {
                    "off" -> { m.injectPitch = null; m.injectVolume = null }
                    "idle" -> { m.injectPitch = Inject.Direct(0.25f); m.injectVolume = null }
                    else -> {
                        val parts = value.split(',', ' ', ';').filter { it.isNotBlank() }
                        m.injectPitch = parts.getOrNull(0)?.toFloatOrNull()?.let { Inject.Direct(it) }
                        m.injectVolume = parts.getOrNull(1)?.toFloatOrNull()?.let { Inject.Direct(it) }
                    }
                }
                ThLog.i("CMD hand pitch=${m.injectPitch} volume=${m.injectVolume}")
            }
            "joints" -> {
                stopPath()
                m.injectSource = true
                val map = value.split(';').mapNotNull { e ->
                    val i = e.indexOf(':')
                    if (i < 0) null else e.substring(0, i).lowercase(Locale.US) to e.substring(i + 1).split(',').mapNotNull { it.trim().toFloatOrNull() }
                }.toMap()
                fun v(k: String) = map[k]?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
                val pitchSide = if (host.uiState().settings.handed == io.embodit.theremin.domain.model.Handed.RIGHT_PITCH) "right" else "left"
                val volumeSide = if (pitchSide == "right") "left" else "right"
                m.injectPitch = v("${pitchSide}palm")?.let { Inject.Joints(it, v("${pitchSide}wrist")) }
                m.injectVolume = v("${volumeSide}palm")?.let { Inject.Joints(it, v("${volumeSide}wrist")) }
                ThLog.i("CMD joints pitch=${m.injectPitch} volume=${m.injectVolume}")
            }
            "pinch" -> m.injectPinch = value == "1"
            "source" -> {
                when (value) {
                    "none" -> { stopPath(); m.injectSource = false; m.injectPitch = null; m.injectVolume = null }
                    "controllers" -> { m.injectSource = true; m.injectAsController = true }
                    else -> { m.injectSource = true; m.injectAsController = false }
                }
                ThLog.i("SRC=${if (value == "none") "none" else "inject"} as=${if (m.injectAsController) "controllers" else "hands"}")
            }
            "handpath" -> startPath(host, value)
            "recordwav" -> ThLog.i(scene?.record(value.toFloatOrNull() ?: 2f) ?: NO_SCENE)
            "pitchinfo" -> scene?.pitchInfo() ?: ThLog.i(NO_SCENE)
            "labeltest" -> scene?.setLabelTest(value == "1")
            "marker" -> ThLog.i(scene?.debugMarker(value) ?: NO_SCENE)
            "tomarker" -> host.dispatch(PlayEvent.Debug("front", "1"))
            "spatialaudio" -> ThLog.i(scene?.restartAudio(value != "0") ?: NO_SCENE)
            "ui" -> ThLog.i("UI $value pressed=${host.pressControl(value)}")
            "dump" -> {
                val json = host.dumpJson()
                ThLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "hmd" -> ThLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { ThLog.i(it) } ?: ThLog.i(NO_SCENE)
                for (panel in PanelSpec.pool) for ((ctrl, b) in ThProbe.controlsOf(panel)) {
                    ThLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                }
            }
            "planes", "reality" -> scene?.realitySummary()?.forEach { ThLog.i(it) } ?: ThLog.i(NO_SCENE)
            "park" -> ThLog.i(scene?.parkPanel() ?: NO_SCENE)
            "paneldrag" -> value.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 2 }?.let { (x, y) -> ThLog.i(scene?.dragPanelDebug(x, y) ?: NO_SCENE) }
            "move" -> value.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 3 }?.let { (x, y, z) -> ThLog.i(scene?.debugMove(x, y, z) ?: NO_SCENE) }
            "standdrag" -> value.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 2 }?.let { (x, y) -> scene?.dragStand(x, y); ThLog.i("STAND drag $x,$y") }
            "standrecall" -> scene?.recallStand()
            "standinfo" -> ThLog.i(scene?.standInfo() ?: NO_SCENE)
            "dragmode" -> ThLog.i(scene?.dragMode(value) ?: NO_SCENE)
            else -> host.dispatch(PlayEvent.Debug(key, value))
        }
    }

    private fun stopPath() {
        pathThread?.interrupt()
        pathThread = null
    }

    /**
     * Preset hand paths at 72 Hz through the same mapping: `scale` (the eight naturals C .. C', 0.6 s each),
     * `glide` (C -> C' in 4 s and back), `vibrato` (E4 with 6 Hz +-3 mm), `song` / `song60` (the open song's ideal
     * path, or 60 cents sharp). The volume hand stays at h = 0.12.
     */
    private fun startPath(host: ThProbe.Host, name: String) {
        stopPath()
        if (name == "off") return
        val m = ThGraph.mapper
        m.injectSource = true
        val st = host.uiState()
        val scale = st.settings.scale
        val naturals = listOf(0, 2, 4, 5, 7, 9, 11, 12)
        val t = Thread({
            val t0 = System.nanoTime()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val s = (System.nanoTime() - t0) / 1e9
                    val semitone: Float? =
                        when (name) {
                            "scale" -> naturals.getOrNull((s / 0.6).toInt())?.toFloat()
                            "glide" -> ((s % 8.0).let { if (it < 4) it / 4 else (8 - it) / 4 } * 12).toFloat()
                            "vibrato" -> 4f
                            "song", "song60" -> {
                                host.uiState().song?.current?.let { i -> songNote(host, i) }?.let { it + if (name == "song60") 0.6f else 0f }
                            }
                            else -> null
                        }
                    if (semitone == null && name == "scale") break
                    var d = semitone?.let { Instrument.radius(it, scale) }
                    if (name == "vibrato" && d != null) d += (0.003 * sin(2 * PI * 6.0 * s)).toFloat()
                    m.injectPitch = d?.let { Inject.Direct(it) }
                    m.injectVolume = if (semitone != null) Inject.Direct(0.12f) else Inject.Direct(0f)
                    Thread.sleep(14)
                }
            } catch (_: InterruptedException) {
            }
            ThLog.i("CMD handpath $name ended")
        }, "handpath")
        pathThread = t
        t.start()
        ThLog.i("CMD handpath $name started")
    }

    private fun songNote(host: ThProbe.Host, index: Int): Float? {
        val id = host.uiState().song?.songId ?: return null
        return songCache.getOrPut(id) { host.songNotes(id) }.getOrNull(index)?.toFloat()
    }

    private val songCache = HashMap<String, List<Int>>()

    private const val NO_SCENE = "CMD ignored: no scene"
}
