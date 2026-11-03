package io.embodit.theremin.ui.play

import io.embodit.theremin.domain.model.Curve
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.domain.model.Quantize
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Timbre
import io.embodit.theremin.domain.play.LessonResult
import io.embodit.theremin.domain.play.PlayFrame
import io.embodit.theremin.domain.play.SongResult

/** Before the instrument stands: loading (S01), appearing beside the panel (S03); then playing. v0.1.5: no desk placing, no plane access state (D24). */
enum class Room { LOADING, FINDING, PLAYING }

/** The music stand's one page at a time (design `panels.standPanel.pages`). */
enum class Page { HOME, LESSONS, PRACTICE, RESULT, SETTINGS, MENU, ONBOARDING, SONG, SONG_RESULT }

enum class Library { BASICS, SONGS }

enum class LanguagePref { SYSTEM, ZH, EN }

/** The hint strip on top of the stand (design `stateModel.hintPriority`). */
/**
 * The hint strip on top of the stand (design `stateModel.hintPriority`). NO_MARKER and MARKER_READY stay until the
 * instrument has moved to a marker (v0.1.2, taste D1: content goes where the user marks, never in front of the head).
 */
enum class Hint { NONE, NO_HANDS, PITCH_LOST, VOLUME_LOST, CONTROLLERS, NO_MARKER, MARKER_READY, NEEDS_STANDARD }

data class Settings(
    val timbre: Timbre = Timbre.CLASSIC,
    val octave: Octave = Octave.MID,
    val scale: Scale = Scale.STANDARD,
    val quantize: Quantize = Quantize.OFF,
    val curve: Curve = Curve.SOFT,
    val handed: Handed = Handed.RIGHT_PITCH,
    val muted: Boolean = false,
    val language: LanguagePref = LanguagePref.SYSTEM,
)

/** Readout on the stand, refreshed at 10 Hz. */
data class Readout(
    val note: Int = 0,
    val cents: Float = 0f,
    val pitchKnown: Boolean = false,
    val sounding: Boolean = false,
    val volume: Float = 0f,
)

data class PracticeUi(val lessonId: String, val step: Int, val total: Int, val target: Int?, val next: Int?, val progress: Float, val cents: Float?)

data class SongUi(val songId: String, val slow: Boolean, val beat: Float, val current: Int?)

data class PlayUiState(
    val room: Room = Room.LOADING,
    val page: Page = Page.HOME,
    /** Page to return to from the settings / menu. */
    val pageBack: Page = Page.HOME,
    val library: Library = Library.BASICS,
    val settingsFocusTimbre: Boolean = false,
    val pose: InstrumentPose? = null,
    /** On the virtual stand (floor marker, or still waiting for a marker). */
    val onStand: Boolean = false,
    /** Placed at the waiting anchor beside the panel: no marker set yet. */
    val awaitingMarker: Boolean = false,
    /** A marker is set and the instrument is not on it yet. */
    val markerReady: Boolean = false,
    val dragging: Boolean = false,
    val settings: Settings = Settings(),
    val english: Boolean = false,
    val onboardStep: Int = 0,
    val onboarded: Boolean = false,
    val readout: Readout = Readout(),
    val hint: Hint = Hint.NONE,
    val controllerSource: Boolean = false,
    val handsLost: Boolean = false,
    val pitchLost: Boolean = false,
    val volumeLost: Boolean = false,
    val unavailable: Boolean = false,
    val practice: PracticeUi? = null,
    val lessonResult: LessonResult? = null,
    val lessonNewBest: Boolean = false,
    val song: SongUi? = null,
    val songResult: SongResult? = null,
    val songNewBest: Boolean = false,
    /** Best mean cents per lesson, best score per song. */
    val lessonBest: Map<String, Int> = emptyMap(),
    val songBest: Map<String, Int> = emptyMap(),
    val background: Boolean = false,
    val freeze: Boolean = false,
    /** Serial numbers the scene acts on once: move to the marker (or the waiting anchor), turn 15 degrees. */
    val frontSerial: Int = 0,
    val turnSerial: Int = 0,
    /** v0.1.8 (D9): the saved spot was not restored (behind / off to the side / too far): 「放回上次的位置」 is offered. */
    val lastSpot: Boolean = false,
    val lastSpotSerial: Int = 0,
    val headNear: Boolean = false,
    /** D26: "退出应用" pressed, the confirmation is showing. */
    val exitConfirm: Boolean = false,
) {
    val placed: Boolean
        get() = room == Room.PLAYING && pose != null

    /** Can be played: placed and not unavailable (settings and menu included). */
    val playable: Boolean
        get() = placed && !unavailable && !dragging

    /** The design's state id plus overlays, for logs and QA. */
    fun stateId(): String {
        val base =
            when (room) {
                Room.LOADING -> "S01"
                Room.FINDING -> "S03"
                Room.PLAYING ->
                    when {
                        unavailable -> "S19"
                        else ->
                            when (page) {
                                Page.ONBOARDING -> "S07"
                                Page.HOME -> if (readout.sounding) "S09" else "S08"
                                Page.LESSONS -> "S12"
                                Page.PRACTICE -> if ((practice?.total ?: 0) <= 1) "S13" else "S14"
                                Page.RESULT -> "S15"
                                Page.SETTINGS -> "S16"
                                Page.MENU -> "S17"
                                Page.SONG -> "S21"
                                Page.SONG_RESULT -> "S22"
                            }
                    }
            }
        val o = buildList {
            if (handsLost) add("S10")
            if (controllerSource) add("S11")
            if (english) add("S18")
            if (onStand && room == Room.PLAYING) add("S06")
        }
        return if (o.isEmpty()) base else "$base+" + o.joinToString("+")
    }
}

sealed interface PlayEvent {
    data object SceneReady : PlayEvent

    /** The scene asks to put the instrument down at [pose]; [front] = on the virtual stand. */
    /** The scene puts the instrument at [pose]; [onStand] = on the virtual stand; [waiting] = the no-marker anchor. */
    data class PlaceRequested(val pose: InstrumentPose, val onStand: Boolean, val waiting: Boolean = false) : PlayEvent

    /** The user set a marker on the floor or a desk (controller ray + trigger, or gaze + pinch on a surface). */
    data object MarkerSet : PlayEvent

    /** The scene was asked to move to a marker but none is set. */
    data object NoMarker : PlayEvent

    /** "移到标记处" on the place panel, the hint strip or the More page. */
    data object MoveToMarkerClicked : PlayEvent

    /** v0.1.8 (D9): the scene offers (or withdraws) the saved spot it did not restore; the user puts the instrument back there. */
    data class LastSpotOffered(val offered: Boolean) : PlayEvent

    data object LastSpotClicked : PlayEvent

    data object TurnClicked : PlayEvent

    data class ModeSelected(val practice: Boolean) : PlayEvent

    data class LibrarySelected(val library: Library) : PlayEvent

    data class LessonPicked(val id: String) : PlayEvent

    data class SongPicked(val id: String) : PlayEvent

    data object BackClicked : PlayEvent

    data object ListenClicked : PlayEvent

    data object RestartClicked : PlayEvent

    data object NextClicked : PlayEvent

    data object ToFreeClicked : PlayEvent

    data object ToListClicked : PlayEvent

    data class SettingsClicked(val focusTimbre: Boolean) : PlayEvent

    data object MoreClicked : PlayEvent

    data class SettingsChanged(val settings: Settings) : PlayEvent

    data object MuteToggled : PlayEvent


    data object ReplaceClicked : PlayEvent

    data object ReplayCoachClicked : PlayEvent

    /** D26: "退出应用" -> confirmation -> [ExitConfirmed] ends the process; [ExitCancelled] keeps playing. */
    data object ExitClicked : PlayEvent

    data object ExitConfirmed : PlayEvent

    data object ExitCancelled : PlayEvent

    data object CoachNextClicked : PlayEvent

    data object CoachSkipClicked : PlayEvent

    data object RetryClicked : PlayEvent

    data class SpeedSelected(val slow: Boolean) : PlayEvent

    data class DragChanged(val dragging: Boolean, val pose: InstrumentPose?) : PlayEvent

    data class HeadNear(val near: Boolean) : PlayEvent

    data object WentBackground : PlayEvent

    data object CameForeground : PlayEvent

    data class Debug(val key: String, val value: String) : PlayEvent
}

/** One-shot effects the screen hands to the scene / audio. */
sealed interface PlayEffect {
    data object ExitApp : PlayEffect

    data class ReferenceTone(val midi: Double) : PlayEffect

    data object Cue : PlayEffect

    data object Ripple : PlayEffect
}

/** Per-frame input of the session (from the frame system). */
data class Tick(val nowMs: Long, val dt: Float, val frame: PlayFrame, val sourcesOk: Boolean)
