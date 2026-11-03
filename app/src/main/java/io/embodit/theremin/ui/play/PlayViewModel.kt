package io.embodit.theremin.ui.play

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.theremin.domain.model.Curve
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.domain.model.Quantize
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Timbre
import io.embodit.theremin.domain.play.HoldTracker
import io.embodit.theremin.domain.play.Lessons
import io.embodit.theremin.domain.play.PlayFrame
import io.embodit.theremin.domain.play.Song
import io.embodit.theremin.domain.play.SongBook
import io.embodit.theremin.domain.play.SongScorer
import io.embodit.theremin.domain.play.Src
import io.embodit.theremin.platform.ThLog
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The whole state machine (design `states`, `stateModel`, `interactions`). Panels, scene callbacks and debug
 * hooks come in through [onEvent]; the frame system feeds [tick] once per frame with the latest mapping. The
 * lesson / song trackers live here and are ticked on the main thread; the panel readout is published at
 * 10 Hz. Settings, the onboarding flag and best results persist in SharedPreferences.
 */
class PlayViewModel(context: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayViewModel(context.applicationContext) as T
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("theremin", Context.MODE_PRIVATE)
    val songs: List<Song> =
        runCatching { SongBook.parse(appContext.assets.open("songs.json").bufferedReader().use { it.readText() }) }
            .onFailure { ThLog.e("SONGS load failed: $it", it) }
            .getOrDefault(emptyList())

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PlayUiState> = _state

    private val _effects = MutableSharedFlow<PlayEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<PlayEffect> = _effects

    /** Read by the scene every frame (target ring, lane). */
    var hold: HoldTracker? = null
        private set
    var scorer: SongScorer? = null
        private set
    /** The song lane's history: beat and true semitone per frame (ring buffer). */
    val lane = FloatArray(LANE_SAMPLES) { Float.NaN }
    val laneBeat = FloatArray(LANE_SAMPLES) { Float.NaN }
    private var laneHead = 0

    private var findJob: Job? = null
    private var hintJob: Job? = null
    private var lastReadoutMs = 0L
    private var lastStateId = ""
    private var unavailableSinceMs = NEVER
    private var coachHoldMs = 0f
    private var pendingReference = false
    private var lastControllerSource = false

    private fun load(): PlayUiState {
        fun <E : Enum<E>> e(key: String, def: E, values: Array<E>): E = values.firstOrNull { it.name == prefs.getString(key, null) } ?: def
        val s = Settings(
            timbre = e("timbre", Timbre.CLASSIC, Timbre.entries.toTypedArray()),
            octave = e("octave", Octave.MID, Octave.entries.toTypedArray()),
            scale = e("scale", Scale.STANDARD, Scale.entries.toTypedArray()),
            quantize = e("quantize", Quantize.OFF, Quantize.entries.toTypedArray()),
            curve = e("curve", Curve.SOFT, Curve.entries.toTypedArray()),
            handed = e("handed", Handed.RIGHT_PITCH, Handed.entries.toTypedArray()),
            muted = prefs.getBoolean("muted", false),
            language = e("language", LanguagePref.SYSTEM, LanguagePref.entries.toTypedArray()),
        )
        val lb = HashMap<String, Int>()
        val sb = HashMap<String, Int>()
        for (l in Lessons.all) if (prefs.contains("best_${l.id}")) lb[l.id] = prefs.getInt("best_${l.id}", 0)
        for (g in listOf("G1", "G2", "G3", "G4", "G5", "G6")) if (prefs.contains("song_$g")) sb[g] = prefs.getInt("song_$g", 0)
        return PlayUiState(settings = s, english = isEnglish(s.language), onboarded = prefs.getBoolean("onboarded", false), lessonBest = lb, songBest = sb)
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putString("timbre", s.timbre.name).putString("octave", s.octave.name).putString("scale", s.scale.name)
            .putString("quantize", s.quantize.name).putString("curve", s.curve.name).putString("handed", s.handed.name)
            .putBoolean("muted", s.muted).putString("language", s.language.name)
            .apply()
    }

    private fun isEnglish(pref: LanguagePref): Boolean =
        when (pref) {
            LanguagePref.EN -> true
            LanguagePref.ZH -> false
            LanguagePref.SYSTEM -> !appContext.resources.configuration.locales[0].language.startsWith("zh")
        }

    private fun set(block: (PlayUiState) -> PlayUiState) {
        _state.update(block)
        val id = _state.value.stateId()
        if (id != lastStateId) {
            lastStateId = id
            val st = _state.value
            ThLog.i("STATE $id page=${st.page} room=${st.room} hint=${st.hint}")
        }
    }

    private fun emit(e: PlayEffect) {
        _effects.tryEmit(e)
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: PlayEvent) {
        when (event) {
            PlayEvent.SceneReady -> if (_state.value.room == Room.LOADING) startFinding()
            is PlayEvent.PlaceRequested -> place(event)
            PlayEvent.MarkerSet -> set { it.copy(markerReady = true, hint = Hint.MARKER_READY) }
            PlayEvent.NoMarker -> set { it.copy(hint = Hint.NO_MARKER) }
            is PlayEvent.LastSpotOffered -> {
                ThLog.i("LASTSPOT offered=${event.offered}")
                set { it.copy(lastSpot = event.offered) }
            }
            PlayEvent.LastSpotClicked -> {
                ThLog.i("LASTSPOT clicked offered=${_state.value.lastSpot}")
                set { it.copy(lastSpot = false, lastSpotSerial = it.lastSpotSerial + 1, page = if (it.page == Page.MENU) Page.HOME else it.page) }
            }
            PlayEvent.MoveToMarkerClicked -> {
                findJob?.cancel()
                set { it.copy(frontSerial = it.frontSerial + 1, page = if (it.page == Page.MENU) Page.HOME else it.page) }
            }
            PlayEvent.TurnClicked -> set { it.copy(turnSerial = it.turnSerial + 1) }
            is PlayEvent.ModeSelected -> {
                stopPractice()
                set { it.copy(page = if (event.practice) Page.LESSONS else Page.HOME) }
            }
            is PlayEvent.LibrarySelected -> set { it.copy(library = event.library) }
            is PlayEvent.LessonPicked -> startLesson(event.id)
            is PlayEvent.SongPicked -> startSong(event.id, _state.value.song?.slow ?: false)
            PlayEvent.BackClicked -> back()
            PlayEvent.ListenClicked -> hold?.target?.let { t ->
                if (ThGraphMapper.last().volume <= 0f) emit(PlayEffect.ReferenceTone(Instrument.midi(t.toFloat(), _state.value.settings.octave).toDouble()))
            }
            PlayEvent.RestartClicked -> {
                val st = _state.value
                when (st.page) {
                    Page.PRACTICE, Page.RESULT -> st.practice?.lessonId?.let { startLesson(it) }
                    Page.SONG, Page.SONG_RESULT -> st.song?.let { startSong(it.songId, it.slow) }
                    else -> Unit
                }
            }
            PlayEvent.NextClicked -> {
                val st = _state.value
                if (st.page == Page.RESULT) {
                    val i = Lessons.all.indexOfFirst { it.id == st.practice?.lessonId }
                    Lessons.all.getOrNull(i + 1)?.let { startLesson(it.id) }
                } else if (st.page == Page.SONG_RESULT) {
                    val i = songs.indexOfFirst { it.id == st.song?.songId }
                    songs.getOrNull(i + 1)?.let { startSong(it.id, st.song?.slow ?: false) }
                }
            }
            PlayEvent.ToFreeClicked -> {
                stopPractice()
                set { it.copy(page = Page.HOME) }
            }
            PlayEvent.ToListClicked -> {
                stopPractice()
                set { it.copy(page = Page.LESSONS) }
            }
            is PlayEvent.SettingsClicked -> set { st -> st.copy(page = Page.SETTINGS, pageBack = backTarget(st), settingsFocusTimbre = event.focusTimbre) }
            PlayEvent.MoreClicked -> set { st -> st.copy(page = Page.MENU, pageBack = backTarget(st)) }
            is PlayEvent.SettingsChanged -> changeSettings(event.settings)
            PlayEvent.MuteToggled -> changeSettings(_state.value.settings.copy(muted = !_state.value.settings.muted))
            PlayEvent.ReplaceClicked -> {
                stopPractice()
                set { it.copy(room = Room.FINDING, pose = null, onStand = false, page = Page.HOME) }
                startFinding()
            }
            PlayEvent.ReplayCoachClicked -> set { it.copy(page = Page.ONBOARDING, onboardStep = 0) }
            // D26: exit asks once; the confirmed exit releases everything and ends the process (PlayScreen)
            PlayEvent.ExitClicked -> set { it.copy(exitConfirm = true) }
            PlayEvent.ExitCancelled -> set { it.copy(exitConfirm = false) }
            PlayEvent.ExitConfirmed -> {
                ThLog.i("EXIT confirmed")
                emit(PlayEffect.ExitApp)
            }
            PlayEvent.CoachNextClicked -> coachNext()
            PlayEvent.CoachSkipClicked -> finishCoach()
            PlayEvent.RetryClicked -> {
                unavailableSinceMs = NEVER
                set { it.copy(unavailable = false) }
            }
            is PlayEvent.SpeedSelected -> _state.value.song?.let { startSong(it.songId, event.slow) }
            is PlayEvent.DragChanged ->
                set {
                    if (event.pose == null) it.copy(dragging = event.dragging)
                    // released where the user let go: in the air (no virtual stand), the marker wait is over
                    else it.copy(dragging = event.dragging, pose = event.pose, onStand = false, awaitingMarker = false, hint = if (it.hint == Hint.NO_MARKER) Hint.NONE else it.hint)
                }
            is PlayEvent.HeadNear -> set { it.copy(headNear = event.near) }
            PlayEvent.WentBackground -> set { it.copy(background = true) }
            PlayEvent.CameForeground -> set { it.copy(background = false) }
            is PlayEvent.Debug -> debug(event.key, event.value)
        }
    }

    private fun backTarget(st: PlayUiState): Page = if (st.page == Page.SETTINGS || st.page == Page.MENU) st.pageBack else st.page

    private fun back() {
        val st = _state.value
        when (st.page) {
            Page.SETTINGS, Page.MENU -> set { it.copy(page = it.pageBack) }
            Page.PRACTICE, Page.RESULT, Page.SONG, Page.SONG_RESULT -> {
                stopPractice()
                set { it.copy(page = Page.LESSONS) }
            }
            else -> set { it.copy(page = Page.HOME) }
        }
    }

    /**
     * v0.1.4 (user: "不用落在桌子上"): no waiting for a desk. The instrument appears at once in the air beside the place
     * panel (the waiting anchor), facing the user; they drag it anywhere, or set a floor / desk marker and move it there.
     */
    private fun startFinding() {
        set { it.copy(room = Room.FINDING) }
        findJob?.cancel()
        ThLog.i("FIND -> waiting anchor beside the panel (in the air)")
        set { it.copy(frontSerial = it.frontSerial + 1) }
    }

    private fun place(e: PlayEvent.PlaceRequested) {
        findJob?.cancel()
        val first = !_state.value.onboarded
        set {
            it.copy(
                room = Room.PLAYING,
                pose = e.pose,
                onStand = e.onStand,
                awaitingMarker = e.waiting,
                markerReady = false,
                hint = if (e.waiting) Hint.NO_MARKER else if (it.hint == Hint.NO_MARKER || it.hint == Hint.MARKER_READY) Hint.NONE else it.hint,
                page = if (it.room != Room.PLAYING) (if (first) Page.ONBOARDING else Page.HOME) else it.page,
                onboardStep = if (first && it.room != Room.PLAYING) 0 else it.onboardStep,
            )
        }
        ThLog.i("PLACE center=${ThLog.pos(e.pose.center)} yaw=${ThLog.f(Math.toDegrees(e.pose.yaw.toDouble()).toFloat(), 1)} stand=${e.onStand} waiting=${e.waiting}")
    }

    private fun showHint(h: Hint) {
        set { it.copy(hint = h) }
        hintJob?.cancel()
        hintJob =
            viewModelScope.launch {
                delay(HINT_MS)
                if (_state.value.hint == h) set { it.copy(hint = Hint.NONE) }
            }
    }

    private fun changeSettings(s: Settings) {
        val old = _state.value.settings
        save(s)
        set { it.copy(settings = s, english = isEnglish(s.language)) }
        if ((old.scale != s.scale || old.octave != s.octave || old.handed != s.handed)) {
            hold?.restart()
            scorer?.restart()
        }
    }

    private fun coachNext() {
        val st = _state.value
        if (st.onboardStep >= 2) finishCoach() else set { it.copy(onboardStep = it.onboardStep + 1) }
        coachHoldMs = 0f
    }

    private fun finishCoach() {
        prefs.edit().putBoolean("onboarded", true).apply()
        set { it.copy(page = Page.HOME, onboarded = true, onboardStep = 0) }
    }

    private fun startLesson(id: String) {
        val lesson = Lessons.byId(id) ?: return
        scorer = null
        hold = HoldTracker(lesson)
        pendingReference = true
        set { it.copy(page = Page.PRACTICE, practice = practiceUi(), lessonResult = null, song = null, songResult = null) }
    }

    private fun startSong(id: String, slow: Boolean) {
        val song = songs.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return
        hold = null
        scorer = SongScorer(song, slow)
        lane.fill(Float.NaN)
        laneBeat.fill(Float.NaN)
        set {
            it.copy(page = Page.SONG, song = SongUi(song.id, slow, -SongBook.COUNT_IN_BEATS.toFloat(), null), songResult = null, practice = null, lessonResult = null)
        }
        if (_state.value.settings.scale == Scale.WIDE && song.notes.any { it.semitone > Scale.WIDE.top }) showHint(Hint.NEEDS_STANDARD)
    }

    private fun stopPractice() {
        hold = null
        scorer = null
        pendingReference = false
    }

    private fun practiceUi(): PracticeUi? {
        val h = hold ?: return null
        return PracticeUi(h.lesson.id, h.step, h.lesson.targets.size, h.target, h.next, h.progress, null)
    }

    private fun finishLesson() {
        val h = hold ?: return
        val r = h.result()
        val best = _state.value.lessonBest[r.lessonId]
        val newBest = best == null || r.meanCents < best
        if (newBest) prefs.edit().putInt("best_${r.lessonId}", r.meanCents).apply()
        emit(PlayEffect.Cue)
        emit(PlayEffect.Ripple)
        ThLog.i("LESSON done id=${r.lessonId} s=${ThLog.f(r.seconds, 1)} mean=${r.meanCents} best=$newBest")
        set { it.copy(page = Page.RESULT, lessonResult = r, lessonNewBest = newBest, practice = practiceUi(), lessonBest = if (newBest) it.lessonBest + (r.lessonId to r.meanCents) else it.lessonBest) }
    }

    private fun finishSong() {
        val sc = scorer ?: return
        val r = sc.result()
        val best = _state.value.songBest[r.songId]
        val newBest = best == null || r.score > best
        if (newBest) prefs.edit().putInt("song_${r.songId}", r.score).apply()
        emit(PlayEffect.Ripple)
        ThLog.i("SONG done id=${r.songId} score=${r.score} stars=${r.stars} good=${r.good}/${r.total} best=$newBest")
        set { it.copy(page = Page.SONG_RESULT, songResult = r, songNewBest = newBest, songBest = if (newBest) it.songBest + (r.songId to r.score) else it.songBest) }
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun tick(t: Tick) {
        val st = _state.value
        if (!st.placed || st.background) return
        val f = t.frame
        val sounding = f.amplitude > 0f
        val dt = if (st.freeze) 0f else t.dt

        // the pitch history of the song lane (one sample per frame)
        scorer?.let { sc ->
            lane[laneHead] = if (f.pitchKnown && f.amplitude > 0f) f.s else Float.NaN
            laneBeat[laneHead] = sc.beat
            laneHead = (laneHead + 1) % lane.size
        }
        hold?.let { h ->
            if (pendingReference && f.volume <= 0f) {
                pendingReference = false
                h.target?.let { emit(PlayEffect.ReferenceTone(Instrument.midi(it.toFloat(), st.settings.octave).toDouble())) }
            }
            if (st.page == Page.PRACTICE && !h.done && h.update(dt, f.s, f.volume, sounding && f.pitchKnown) && h.done) finishLesson()
        }
        scorer?.let { sc ->
            if (st.page == Page.SONG) {
                sc.update(dt, f.s, sounding && f.pitchKnown)
                if (sc.done) finishSong()
            }
        }
        // onboarding step 3: the bead held on the do ripple for 1 s
        if (st.page == Page.ONBOARDING && st.onboardStep == 2 && f.pitchKnown) {
            coachHoldMs = if (kotlin.math.abs(Instrument.centsFrom(f.s, 0)) <= Lessons.TOLERANCE_CENTS) coachHoldMs + dt * 1000f else 0f
            if (coachHoldMs >= 1000f) finishCoach()
        }

        // unavailable: started providers not supported and no injection, for 3 s
        if (!t.sourcesOk && !f.injected) {
            if (unavailableSinceMs == NEVER) unavailableSinceMs = t.nowMs
            if (!st.unavailable && t.nowMs - unavailableSinceMs >= UNAVAILABLE_MS) set { it.copy(unavailable = true) }
        } else {
            unavailableSinceMs = NEVER
            if (st.unavailable) set { it.copy(unavailable = false) }
        }

        val controller = f.pitchSrc == Src.CONTROLLER || f.volumeSrc == Src.CONTROLLER
        if (controller && !lastControllerSource) showHint(Hint.CONTROLLERS)
        lastControllerSource = controller

        if (t.nowMs - lastReadoutMs >= READOUT_MS || st.handsLost != f.bothLost) {
            lastReadoutMs = t.nowMs
            publish(f, controller)
        }
    }

    private fun publish(f: PlayFrame, controller: Boolean) {
        val n = Instrument.nearest(f.s)
        val readout = Readout(n, Instrument.cents(f.s), f.pitchKnown, f.amplitude > 0f, f.volume)
        val sc = scorer
        set { st ->
            val lossHint =
                when {
                    f.bothLost -> Hint.NO_HANDS
                    f.pitchLost -> Hint.PITCH_LOST
                    // a lost volume hand only silences (design input.lossRule): lowering it is how you stop
                    else -> null
                }
            val hint =
                lossHint ?: when {
                    st.hint != Hint.NO_HANDS && st.hint != Hint.PITCH_LOST && st.hint != Hint.VOLUME_LOST -> st.hint
                    st.markerReady -> Hint.MARKER_READY
                    st.awaitingMarker -> Hint.NO_MARKER
                    else -> Hint.NONE
                }
            st.copy(
                readout = readout,
                handsLost = f.bothLost,
                pitchLost = f.pitchLost,
                volumeLost = f.volumeLost,
                controllerSource = controller,
                hint = hint,
                practice = hold?.let { h -> PracticeUi(h.lesson.id, h.step, h.lesson.targets.size, h.target, h.next, h.progress, h.target?.let { t -> Instrument.centsFrom(f.s, t) }) } ?: st.practice,
                song = if (sc != null) SongUi(sc.song.id, sc.slow, sc.beat, sc.current) else st.song,
            )
        }
    }

    /** Ring buffer index of the newest lane sample. */
    val laneNewest: Int
        get() = (laneHead - 1 + lane.size) % lane.size

    // =========================================================================================
    // debug (design `debugHooks`; the injection keys are handled by DebugInject)
    // =========================================================================================

    private fun debug(key: String, value: String) {
        val st = _state.value
        val s = st.settings
        when (key) {
            "state" -> debugState(value.uppercase(Locale.US))
            // v0.1.5 (D24): no desks, no planes, no ghost; these keys stay accepted so old QA scripts do not break
            "desk", "plane" -> ThLog.i("CMD $key=$value ignored: real-surface detection was removed in v0.1.5 (taste D24)")
            // `tomarker` (DebugCommands) arrives as front=1: the same event as the 移到标记处 buttons
            "front", "tomarker" -> onEvent(PlayEvent.MoveToMarkerClicked)
            "place" -> when (value) {
                "front", "marker" -> onEvent(PlayEvent.MoveToMarkerClicked)
                else -> ThLog.i("CMD place=$value ignored: desk placing was removed in v0.1.5 (taste D24)")
            }
            "lesson" -> startLesson(value.uppercase(Locale.US))
            "step" -> {
                hold?.jumpTo(value.toIntOrNull() ?: 0)
                set { it.copy(practice = practiceUi()) }
            }
            "completetarget" -> {
                val h = hold
                if (h != null && h.completeTarget() && h.done) finishLesson() else set { it.copy(practice = practiceUi()) }
            }
            "done" -> when {
                hold != null -> {
                    while (hold?.completeTarget() == true) Unit
                    finishLesson()
                }
                scorer != null -> finishSong()
            }
            "song" -> startSong(value, st.song?.slow ?: false)
            "beat" -> scorer?.seekBeat(value.toFloatOrNull() ?: 0f)
            "slow" -> st.song?.let { startSong(it.songId, value == "1") }
            "score" -> {
                val sc = scorer ?: return
                val v = value.toIntOrNull() ?: 0
                val r = sc.result().copy(score = v, stars = SongScorer.stars(v), good = (sc.song.notes.size * v / 100))
                set { it.copy(page = Page.SONG_RESULT, songResult = r) }
            }
            "seed" -> if (value == "lessons") {
                prefs.edit().putInt("best_L1", 12).putInt("best_L2", 18).putInt("song_G1", 82).apply()
                set { it.copy(lessonBest = it.lessonBest + mapOf("L1" to 12, "L2" to 18), songBest = it.songBest + ("G1" to 82)) }
            }
            "coach" -> when (value) {
                "reset" -> {
                    prefs.edit().putBoolean("onboarded", false).apply()
                    set { it.copy(onboarded = false) }
                }
                else -> set { it.copy(page = Page.ONBOARDING, onboardStep = (value.toIntOrNull() ?: 1).coerceIn(1, 3) - 1) }
            }
            "timbre" -> Timbre.entries.firstOrNull { it.name.equals(value, true) }?.let { changeSettings(s.copy(timbre = it)) }
            "octave" -> Octave.entries.firstOrNull { it.name.equals(value, true) }?.let { changeSettings(s.copy(octave = it)) }
            "scale" -> Scale.entries.firstOrNull { it.name.equals(value, true) }?.let { changeSettings(s.copy(scale = it)) }
            "curve" -> changeSettings(s.copy(curve = if (value == "linear") Curve.LINEAR else Curve.SOFT))
            "quantize", "snap" -> Quantize.entries.firstOrNull { it.name.equals(value, true) }?.let { changeSettings(s.copy(quantize = it)) }
            "mirror" -> changeSettings(s.copy(handed = if (value == "1") Handed.LEFT_PITCH else Handed.RIGHT_PITCH))
            "mute" -> changeSettings(s.copy(muted = value == "1"))
            "lang" -> changeSettings(s.copy(language = when (value) { "en" -> LanguagePref.EN; "zh" -> LanguagePref.ZH; else -> LanguagePref.SYSTEM }))
            "freeze" -> set { it.copy(freeze = value != "0") }
            "page" -> Page.entries.firstOrNull { it.name.equals(value, true) }?.let { p -> set { it.copy(page = p) } }
            "library" -> set { it.copy(library = if (value == "songs") Library.SONGS else Library.BASICS) }
            "settings" -> onEvent(PlayEvent.SettingsClicked(false))
            "menu" -> onEvent(PlayEvent.MoreClicked)
            "back" -> back()
            "replace" -> onEvent(PlayEvent.ReplaceClicked)
            // v0.1.8: the same event as the 放回上次的位置 buttons
            "lastspot" -> onEvent(PlayEvent.LastSpotClicked)
            // the same path as the user's: `exit` opens the confirmation, `exit=confirm` presses 退出, `exit=cancel` 取消
            "exit" -> when (value) {
                "confirm" -> onEvent(PlayEvent.ExitConfirmed)
                "cancel" -> onEvent(PlayEvent.ExitCancelled)
                else -> onEvent(PlayEvent.ExitClicked)
            }
            else -> ThLog.i("CMD unknown $key=$value")
        }
        ThLog.i("CMD $key=$value -> ${_state.value.stateId()}")
    }

    private fun debugState(id: String) {
        when (id) {
            "S03" -> onEvent(PlayEvent.ReplaceClicked)
            "S04", "S05", "S20" -> ThLog.i("CMD state $id no longer exists (v0.1.5, D24: no desk placing / plane access)")
            "S06" -> onEvent(PlayEvent.MoveToMarkerClicked)
            "S07" -> set { it.copy(page = Page.ONBOARDING, onboardStep = 0) }
            "S08", "S09" -> onEvent(PlayEvent.ToFreeClicked)
            "S12" -> onEvent(PlayEvent.ToListClicked)
            "S13" -> startLesson("L1")
            "S14" -> startLesson("L4")
            "S15" -> {
                startLesson("L4")
                debug("done", "1")
            }
            "S16" -> onEvent(PlayEvent.SettingsClicked(false))
            "S17" -> onEvent(PlayEvent.MoreClicked)
            "S21" -> startSong("G1", false)
            "S22" -> {
                startSong("G1", false)
                debug("score", "82")
            }
            else -> ThLog.i("CMD state $id: reach it with its own keys (see AGENTS.md)")
        }
    }

    fun dumpJson(): String {
        val st = _state.value
        val f = ThGraphMapper.last()
        return JSONObject()
            .put("state", st.stateId())
            .put("room", st.room.name)
            .put("page", st.page.name)
            .put("placed", st.placed)
            .put("onStand", st.onStand)
            .put("awaitingMarker", st.awaitingMarker)
            .put("lastSpot", st.lastSpot)
            .put("markerReady", st.markerReady)
            .put("pose", st.pose?.let { "${ThLog.pos(it.center)} yaw=${ThLog.f(Math.toDegrees(it.yaw.toDouble()).toFloat(), 1)}" })
            .put("settings", st.settings.toString())
            .put("readout", st.readout.toString())
            .put("practice", st.practice?.toString())
            .put("song", st.song?.toString())
            .put("lessonResult", st.lessonResult?.toString())
            .put("songResult", st.songResult?.toString())
            .put("hint", st.hint.name)
            .put("exitConfirm", st.exitConfirm)
            .put("frame", "d=${ThLog.f(f.d, 4)} s=${ThLog.f(f.s, 3)} f=${ThLog.f(f.frequency.toFloat(), 2)} v=${ThLog.f(f.volume, 3)} src=${f.pitchSrc}/${f.volumeSrc}")
            .toString()
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        const val HINT_MS = 4000L
        const val READOUT_MS = 100L
        const val UNAVAILABLE_MS = 3000L
        const val LANE_SAMPLES = 360
    }
}

/** The mapper's last frame (indirection keeps the ViewModel free of the platform graph in tests). */
internal object ThGraphMapper {
    fun last(): PlayFrame = io.embodit.theremin.platform.ThGraph.mapper.last
}
