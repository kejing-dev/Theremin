package io.embodit.theremin.ui.play.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LinearProgressDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.theremin.R
import io.embodit.theremin.domain.model.Curve
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.domain.model.Quantize
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Timbre
import io.embodit.theremin.domain.play.Lessons
import io.embodit.theremin.ui.play.Hint
import io.embodit.theremin.ui.play.LanguagePref
import io.embodit.theremin.ui.play.Library
import io.embodit.theremin.ui.play.Page
import io.embodit.theremin.ui.play.PlayEvent
import io.embodit.theremin.ui.play.PlayUiState
import io.embodit.theremin.ui.play.PlayViewModel
import io.embodit.theremin.ui.play.Room
import io.embodit.theremin.ui.play.debug.probe
import io.embodit.theremin.ui.play.scene.PanelSpec
import kotlin.math.abs
import kotlin.math.roundToInt

// =============================================================================================
// place panel (S01, S03 - S05, S20)
// =============================================================================================

/** The place panel, 400 x 220 dp, lazily following low left of the view until the instrument stands. */
@Composable
internal fun PlacePanel(state: PlayUiState, filled: Boolean, onEvent: (PlayEvent) -> Unit, onDrag: (Float, Float) -> Unit, onRecall: () -> Unit) {
    val (w, h) = PanelSpec.PLACE_SIZE
    Glass(w.dp, h.dp, refreshKey = listOf(filled, state.room, state.snapped, state.deskFound, state.language())) {
        if (!filled) return@Glass
        // D21: the panel stays where it is in the world; the handle moves it, 叫回面前 brings it back
        DragHandle(onDrag)
        TitleRow(
            title = rs(if (state.room == Room.NO_PLANE) R.string.place_title_noplane else R.string.place_title),
            trailing = { WordButton("place_recall", rs(R.string.action_recall), onRecall, minWidth = 112.dp, height = 40.dp) },
        )
        when (state.room) {
            Room.LOADING -> {
                Body(rs(R.string.place_loading))
                val sweep = loadingProgress()
                LinearProgressIndicator(progress = { sweep }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = LinearProgressDefaults.linearProgressColors(indicatorColor = PicoTheme.colorScheme.interaction))
            }
            Room.NO_PLANE -> {
                Body(rs(R.string.place_noplane))
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WordButton("place_front", rs(R.string.action_front), { onEvent(PlayEvent.MoveToMarkerClicked) }, primary = true, minWidth = 160.dp)
                    WordButton("place_exit", rs(R.string.action_exit), { onEvent(PlayEvent.ExitClicked) }, minWidth = 120.dp)
                }
            }
            else -> {
                val line =
                    when {
                        state.room == Room.FINDING -> rs(R.string.place_finding)
                        state.snapped -> rs(R.string.place_snapped)
                        else -> rs(R.string.place_placing)
                    }
                Body(line)
                if (state.room == Room.PLACING) Body(rs(R.string.place_stick), secondary = true)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.room == Room.PLACING) WordButton("turn15", rs(R.string.action_turn15), { onEvent(PlayEvent.TurnClicked) }, minWidth = 140.dp, iconRes = R.drawable.ic_rot_cw)
                    WordButton("place_front", rs(R.string.action_front), { onEvent(PlayEvent.MoveToMarkerClicked) }, primary = state.room == Room.FINDING, minWidth = 160.dp)
                }
            }
        }
    }
}

// =============================================================================================
// music stand (S06 - S22)
// =============================================================================================

/** The music stand, 450 x 320 dp on the instrument: one page at a time, the hint strip on top. */
@Composable
internal fun StandPanel(state: PlayUiState, vm: PlayViewModel, filled: Boolean, onEvent: (PlayEvent) -> Unit) {
    val (w, h) = PanelSpec.STAND_SIZE
    Glass(w.dp, h.dp, padding = 14.dp, spacing = 6.dp, refreshKey = listOf(filled, state.page, state.room, state.unavailable, state.library, state.language(), state.settings.handed)) {
        if (!filled) return@Glass
        HintStrip(state, onEvent)
        when {
            state.unavailable -> UnavailablePage(onEvent)
            else -> when (state.page) {
                Page.HOME -> HomePage(state, onEvent)
                Page.LESSONS -> LessonsPage(state, vm, onEvent)
                Page.PRACTICE -> PracticePage(state, onEvent)
                Page.RESULT -> ResultPage(state, onEvent)
                Page.SETTINGS -> SettingsPage(state, onEvent)
                Page.MENU -> MenuPage(onEvent)
                Page.ONBOARDING -> OnboardingPage(state, onEvent)
                Page.SONG -> SongPage(state, vm, onEvent)
                Page.SONG_RESULT -> SongResultPage(state, vm, onEvent)
            }
        }
    }
}

/** A slow sweep for the loading bar (there is no indeterminate linear indicator). */
@Composable
private fun loadingProgress(): Float {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
    val v = t.animateFloat(initialValue = 0.05f, targetValue = 1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(1600)), label = "loading")
    return v.value
}

private fun PlayUiState.language(): Any = english to settings.language

@Composable
private fun Body(text: String, secondary: Boolean = false, center: Boolean = false) {
    Text(
        text = text,
        style = ThType.body,
        color = if (secondary) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = if (center) Modifier.fillMaxWidth() else Modifier,
    )
}

@Composable
private fun volumeHandWord(state: PlayUiState): String = rs(if (state.settings.handed == Handed.RIGHT_PITCH) R.string.hand_left else R.string.hand_right)

@Composable
private fun pitchHandWord(state: PlayUiState): String = rs(if (state.settings.handed == Handed.RIGHT_PITCH) R.string.hand_right else R.string.hand_left)

/** One inline hint line (Stage has no snackbar): loss hints stay until the hand is back, the rest 4 s. */
@Composable
private fun HintStrip(state: PlayUiState, onEvent: (PlayEvent) -> Unit) {
    val text =
        when (state.hint) {
            Hint.NONE -> null
            Hint.NO_HANDS -> rs(R.string.hint_no_hands)
            Hint.PITCH_LOST -> rs(R.string.hint_hand_lost, pitchHandWord(state))
            Hint.VOLUME_LOST -> rs(R.string.hint_hand_lost, volumeHandWord(state))
            Hint.CONTROLLERS -> rs(R.string.hint_controllers)
            Hint.NO_MARKER -> rs(R.string.hint_no_marker)
            Hint.MARKER_READY -> rs(R.string.hint_marker_ready)
            Hint.NEEDS_STANDARD -> rs(R.string.hint_needs_standard)
        } ?: return
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(14.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.interaction)
        Text(text = text, style = ThType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // the marker is set: one press brings the instrument there (taste D1 "移到标记处")
        if (state.hint == Hint.MARKER_READY) WordButton("strip_to_marker", rs(R.string.action_front), { onEvent(PlayEvent.MoveToMarkerClicked) }, primary = true, minWidth = 120.dp, height = 32.dp)
    }
}

@Composable
private fun ModeRow(state: PlayUiState, onEvent: (PlayEvent) -> Unit, itemWidth: Dp = 150.dp, trailing: @Composable () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Segments(
            items = listOf("mode_free" to rs(R.string.mode_free), "mode_practice" to rs(R.string.mode_practice)),
            selected = if (state.page == Page.HOME) 0 else 1,
            onSelect = { onEvent(PlayEvent.ModeSelected(it == 1)) },
            itemWidth = itemWidth,
            height = 40.dp,
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** "mi · E4" (Chinese UI) or "E4" (English). */
@Composable
private fun noteName(n: Int, state: PlayUiState): String {
    val letter = Instrument.letter(n, state.settings.octave)
    return if (state.english) letter else "${Instrument.solfege(n)} · $letter"
}

@Composable
private fun shortNote(n: Int, state: PlayUiState): String = if (state.english) Instrument.letter(n, state.settings.octave) else Instrument.solfege(n)

@Composable
private fun ColumnScope.HomePage(state: PlayUiState, onEvent: (PlayEvent) -> Unit) {
    ModeRow(state, onEvent)
    val r = state.readout
    val active = r.pitchKnown && r.sounding
    Text(
        text = if (r.pitchKnown) noteName(r.note, state) else "—",
        style = PicoTheme.typography.displaySmall,
        color = if (active) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelTertiary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().probe("note_readout"),
    )
    PitchNeedle(cents = if (r.pitchKnown) r.cents else null, modifier = Modifier.fillMaxWidth().height(30.dp))
    Body(
        when {
            !r.sounding -> rs(R.string.home_raise, volumeHandWord(state))
            else -> rs(R.string.cents, signed(r.cents))
        },
        secondary = !r.sounding,
        center = true,
    )
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.controllerSource) Icon(painter = painterResource(R.drawable.ic_controller), contentDescription = rs(R.string.controller_source), modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Chip(label = { Text(text = timbreName(state.settings.timbre), maxLines = 1, softWrap = false) }, onClick = { onEvent(PlayEvent.SettingsClicked(true)) }, modifier = Modifier.probe("timbre_chip") { onEvent(PlayEvent.SettingsClicked(true)) })
        Spacer(Modifier.weight(1f))
        Key("mute", if (state.settings.muted) R.drawable.ic_mute else R.drawable.ic_volume, rs(if (state.settings.muted) R.string.action_unmute else R.string.action_mute), selected = state.settings.muted) { onEvent(PlayEvent.MuteToggled) }
        Key("settings", R.drawable.ic_settings, rs(R.string.settings_title)) { onEvent(PlayEvent.SettingsClicked(false)) }
        Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(PlayEvent.MoreClicked) }
    }
}

private fun signed(c: Float): String {
    val v = c.roundToInt()
    return if (v > 0) "+$v" else "$v"
}

@Composable
internal fun timbreName(t: Timbre): String =
    rs(
        when (t) {
            Timbre.SINE -> R.string.timbre_sine
            Timbre.CLASSIC -> R.string.timbre_classic
            Timbre.WARM -> R.string.timbre_warm
            Timbre.BRIGHT -> R.string.timbre_bright
        }
    )

/**
 * The pitch needle (domain content, no SpatialUI equivalent): -100 .. +100 cents, the +-40 band in passable
 * at 40 %, the +-15 band in passable, the needle in the primary label colour.
 */
@Composable
private fun PitchNeedle(cents: Float?, modifier: Modifier) {
    val track = PicoTheme.colorScheme.fillSecondary
    val pass = PicoTheme.colorScheme.passable
    val ink = PicoTheme.colorScheme.labelPrimary
    val tick = PicoTheme.colorScheme.labelTertiary
    Canvas(modifier = modifier) {
        val w = size.width
        val cy = size.height / 2f
        val x = { c: Float -> w / 2f + c / 100f * (w / 2f - 6f) }
        drawRoundRect(track, Offset(0f, cy - 4f), Size(w, 8f), CornerRadius(4f, 4f))
        drawRoundRect(pass.copy(alpha = 0.4f), Offset(x(-40f), cy - 4f), Size(x(40f) - x(-40f), 8f), CornerRadius(4f, 4f))
        drawRoundRect(pass, Offset(x(-15f), cy - 4f), Size(x(15f) - x(-15f), 8f), CornerRadius(4f, 4f))
        for (c in listOf(-100f, -50f, 0f, 50f, 100f)) drawLine(tick, Offset(x(c), cy - 10f), Offset(x(c), cy - 6f), strokeWidth = 2f)
        if (cents != null) {
            val nx = x(cents.coerceIn(-100f, 100f))
            drawRoundRect(ink, Offset(nx - 3f, cy - 13f), Size(6f, 26f), CornerRadius(3f, 3f))
        }
    }
}

@Composable
private fun ColumnScope.LessonsPage(state: PlayUiState, vm: PlayViewModel, onEvent: (PlayEvent) -> Unit) {
    ModeRow(state, onEvent, itemWidth = 96.dp) {
        Segments(
            items = listOf("lib_basics" to rs(R.string.lib_basics), "lib_songs" to rs(R.string.lib_songs)),
            selected = if (state.library == Library.BASICS) 0 else 1,
            onSelect = { onEvent(PlayEvent.LibrarySelected(if (it == 0) Library.BASICS else Library.SONGS)) },
            itemWidth = 96.dp,
            height = 40.dp,
        )
    }
    data class Tile(val id: String, val name: String, val sub: String, val suggest: Boolean, val onClick: () -> Unit)
    val tiles =
        if (state.library == Library.BASICS) {
            val firstOpen = Lessons.all.firstOrNull { it.id !in state.lessonBest }?.id
            Lessons.all.map { l ->
                val best = state.lessonBest[l.id]
                Tile(l.id, if (state.english) l.en else l.zh, if (best != null) rs(R.string.lesson_best, best) else rs(R.string.lesson_targets, l.targets.size), l.id == firstOpen) { onEvent(PlayEvent.LessonPicked(l.id)) }
            }
        } else {
            val firstOpen = vm.songs.firstOrNull { it.id !in state.songBest }?.id
            vm.songs.map { s ->
                val best = state.songBest[s.id]
                val stars = best?.let { "★".repeat(io.embodit.theremin.domain.play.SongScorer.stars(it)).ifEmpty { "☆" } + " $it" }
                Tile(s.id, if (state.english) s.en else s.zh, stars ?: rs(R.string.song_notes, s.notes.size), s.id == firstOpen) { onEvent(PlayEvent.SongPicked(s.id)) }
            }
        }
    // two-line tiles (name + best / note count): the ListItem clipped its supporting line at this height
    for (row in tiles.chunked(2)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in row) {
                Column(
                    modifier = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary)
                        .clickable(onClick = t.onClick).probe("item_${t.id}", t.onClick).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = t.name, style = ThType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (t.suggest) Text(text = rs(R.string.suggest), style = ThType.figure, color = PicoTheme.colorScheme.interaction, maxLines = 1)
                        Text(text = t.sub, style = ThType.figure, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackTitle(title: String, onEvent: (PlayEvent) -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(PlayEvent.BackClicked) }
        Text(text = title, style = ThType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ProgressDots(done: Int, total: Int) {
    val on = PicoTheme.colorScheme.passable
    val off = PicoTheme.colorScheme.fillSecondary
    Canvas(modifier = Modifier.width((total * 12).dp).height(12.dp)) {
        val step = size.width / total
        for (i in 0 until total) drawCircle(if (i < done) on else off, radius = 4.5f * density, center = Offset(step * (i + 0.5f), size.height / 2f))
    }
}

@Composable
private fun ColumnScope.PracticePage(state: PlayUiState, onEvent: (PlayEvent) -> Unit) {
    val p = state.practice ?: return
    val lesson = Lessons.byId(p.lessonId) ?: return
    BackTitle(if (state.english) lesson.en else lesson.zh, onEvent) { ProgressDots(p.step, p.total) }
    val target = p.target
    Text(
        text = when {
            target == null -> rs(R.string.practice_done_short)
            p.total == 1 -> rs(R.string.practice_hold, shortNote(target, state), lesson.holdS.roundToInt())
            else -> rs(R.string.practice_next, shortNote(target, state))
        },
        style = PicoTheme.typography.displaySmall,
        color = PicoTheme.colorScheme.labelPrimary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth(), colors = LinearProgressDefaults.linearProgressColors(indicatorColor = PicoTheme.colorScheme.passable))
    PitchNeedle(cents = if (state.readout.pitchKnown) p.cents else null, modifier = Modifier.fillMaxWidth().height(30.dp))
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val quiet = state.readout.volume <= 0f
        WordButton("listen", rs(R.string.action_listen), { onEvent(PlayEvent.ListenClicked) }, enabled = quiet, minWidth = 128.dp, iconRes = R.drawable.ic_play)
        WordButton("restart", rs(R.string.action_restart), { onEvent(PlayEvent.RestartClicked) }, minWidth = 112.dp, iconRes = R.drawable.ic_redo)
        if (!quiet) Body(rs(R.string.listen_lower, volumeHandWord(state)), secondary = true)
    }
}

@Composable
private fun ColumnScope.ResultPage(state: PlayUiState, onEvent: (PlayEvent) -> Unit) {
    val r = state.lessonResult ?: return
    val lesson = Lessons.byId(r.lessonId) ?: return
    Text(text = rs(R.string.result_done), style = PicoTheme.typography.displaySmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    Body(if (state.english) lesson.en else lesson.zh, secondary = true)
    Body(rs(R.string.result_line, r.seconds.roundToInt(), r.meanCents))
    if (state.lessonNewBest) Text(text = rs(R.string.result_best), style = ThType.figure, color = PicoTheme.colorScheme.passable, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WordButton("again", rs(R.string.action_again), { onEvent(PlayEvent.RestartClicked) }, primary = true, minWidth = 120.dp)
        if (r.lessonId != Lessons.all.last().id) WordButton("next", rs(R.string.action_next_lesson), { onEvent(PlayEvent.NextClicked) }, minWidth = 112.dp)
        WordButton("to_free", rs(R.string.action_to_free), { onEvent(PlayEvent.ToFreeClicked) }, minWidth = 120.dp)
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = ThType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(96.dp))
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
private fun ColumnScope.SettingsPage(state: PlayUiState, onEvent: (PlayEvent) -> Unit) {
    val s = state.settings
    fun set(n: io.embodit.theremin.ui.play.Settings) = onEvent(PlayEvent.SettingsChanged(n))
    BackTitle(rs(R.string.settings_title), onEvent)
    SettingRow(rs(R.string.set_timbre)) {
        Segments(Timbre.entries.map { "timbre_${it.name.lowercase()}" to timbreName(it) }, s.timbre.ordinal, { set(s.copy(timbre = Timbre.entries[it])) }, itemWidth = 72.dp, height = 34.dp)
    }
    SettingRow(rs(R.string.set_octave)) {
        Segments(listOf("octave_low" to rs(R.string.octave_low), "octave_mid" to rs(R.string.octave_mid), "octave_high" to rs(R.string.octave_high)), s.octave.ordinal, { set(s.copy(octave = Octave.entries[it])) }, itemWidth = 72.dp, height = 34.dp)
    }
    SettingRow(rs(R.string.set_scale)) {
        Segments(listOf("scale_standard" to rs(R.string.scale_standard), "scale_wide" to rs(R.string.scale_wide)), s.scale.ordinal, { set(s.copy(scale = Scale.entries[it])) }, itemWidth = 96.dp, height = 34.dp)
    }
    SettingRow(rs(R.string.set_snap)) {
        Segments(listOf("snap_off" to rs(R.string.snap_off), "snap_light" to rs(R.string.snap_light), "snap_strong" to rs(R.string.snap_strong)), s.quantize.ordinal, { set(s.copy(quantize = Quantize.entries[it])) }, itemWidth = 72.dp, height = 34.dp)
    }
    SettingRow(rs(R.string.set_curve)) {
        Segments(listOf("curve_soft" to rs(R.string.curve_soft), "curve_linear" to rs(R.string.curve_linear)), s.curve.ordinal, { set(s.copy(curve = Curve.entries[it])) }, itemWidth = 96.dp, height = 34.dp)
    }
    SettingRow(rs(R.string.set_hand)) {
        Segments(listOf("hand_right" to rs(R.string.handed_right), "hand_left" to rs(R.string.handed_left)), s.handed.ordinal, { set(s.copy(handed = Handed.entries[it])) }, itemWidth = 120.dp, height = 34.dp)
    }
}

@Composable
private fun MenuRow(name: String, icon: Int, title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text = title, style = ThType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1) },
        leadingContent = { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary) },
        modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).probe(name, onClick),
    )
}

@Composable
private fun ColumnScope.MenuPage(onEvent: (PlayEvent) -> Unit) {
    BackTitle(rs(R.string.action_more), onEvent)
    MenuRow("menu_front", R.drawable.ic_move, rs(R.string.menu_front)) { onEvent(PlayEvent.MoveToMarkerClicked) }
    MenuRow("menu_replace", R.drawable.ic_retry, rs(R.string.menu_replace)) { onEvent(PlayEvent.ReplaceClicked) }
    MenuRow("menu_coach", R.drawable.ic_hand, rs(R.string.menu_coach)) { onEvent(PlayEvent.ReplayCoachClicked) }
    MenuRow("menu_exit", R.drawable.ic_exit, rs(R.string.action_exit)) { onEvent(PlayEvent.ExitClicked) }
}

@Composable
private fun ColumnScope.OnboardingPage(state: PlayUiState, onEvent: (PlayEvent) -> Unit) {
    val step = state.onboardStep
    Text(text = rs(R.string.coach_step, step + 1), style = ThType.figure, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    Text(
        text = when (step) {
            0 -> rs(R.string.coach_1, pitchHandWord(state))
            1 -> rs(R.string.coach_2, volumeHandWord(state))
            else -> rs(R.string.coach_3)
        },
        style = PicoTheme.typography.titleLarge,
        color = PicoTheme.colorScheme.labelPrimary,
        maxLines = 2,
    )
    CoachSketch(step, state.settings.handed, Modifier.fillMaxWidth().height(96.dp))
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step < 2) WordButton("coach_next", rs(R.string.action_next), { onEvent(PlayEvent.CoachNextClicked) }, primary = true, minWidth = 128.dp)
        WordButton("coach_skip", rs(R.string.action_skip), { onEvent(PlayEvent.CoachSkipClicked) }, minWidth = 112.dp)
    }
}

/** The coach sketch: antenna, loop, the two hands as outlines (pitch hand amber, volume hand white), mirrored with the layout. */
@Composable
private fun CoachSketch(step: Int, handed: Handed, modifier: Modifier) {
    val amber = PicoTheme.colorScheme.interaction
    val white = PicoTheme.colorScheme.labelPrimary
    val dim = PicoTheme.colorScheme.labelTertiary
    val pass = PicoTheme.colorScheme.passable
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val right = handed == Handed.RIGHT_PITCH
        fun mx(x: Float) = if (right) x else w - x
        // antenna and loop
        drawLine(if (step == 0) amber else dim, Offset(mx(w * 0.72f), h * 0.1f), Offset(mx(w * 0.72f), h * 0.9f), strokeWidth = 4f)
        drawOval(if (step == 1) white else dim, Offset(mx(w * 0.22f) - 40f, h * 0.78f), Size(80f, 16f), style = Stroke(3f))
        // ripples
        for (k in 1..3) drawArc(if (step == 2 && k == 3) pass else dim, 120f, 120f, false, Offset(mx(w * 0.72f) - 30f * k, h * 0.5f - 30f * k), Size(60f * k, 60f * k), style = Stroke(2f))
        // hands
        fun hand(cx: Float, cy: Float, color: Color) {
            val p = Path().apply {
                moveTo(cx - 16f, cy + 18f)
                lineTo(cx - 18f, cy - 10f)
                lineTo(cx - 8f, cy - 24f)
                lineTo(cx + 8f, cy - 24f)
                lineTo(cx + 18f, cy - 8f)
                lineTo(cx + 14f, cy + 18f)
                close()
            }
            drawPath(p, color, style = Stroke(3f))
        }
        hand(mx(w * (if (step == 0) 0.6f else 0.52f)), h * 0.45f, amber)
        hand(mx(w * 0.22f), h * (if (step == 1) 0.35f else 0.55f), white)
    }
}

@Composable
private fun ColumnScope.SongPage(state: PlayUiState, vm: PlayViewModel, onEvent: (PlayEvent) -> Unit) {
    val ui = state.song ?: return
    val song = vm.songs.firstOrNull { it.id == ui.songId } ?: return
    BackTitle(if (state.english) song.en else song.zh, onEvent) {
        Segments(listOf("speed_slow" to rs(R.string.speed_slow), "speed_full" to rs(R.string.speed_full)), if (ui.slow) 0 else 1, { onEvent(PlayEvent.SpeedSelected(it == 0)) }, itemWidth = 72.dp, height = 40.dp)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SongLane(state, vm, Modifier.width(300.dp).height(110.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            val cur = ui.current?.let { song.notes[it].semitone }
            Text(text = when {
                ui.beat < 0 -> "${(-ui.beat).toInt() + 1}"
                cur != null -> shortNote(cur, state)
                else -> "—"
            }, style = PicoTheme.typography.displaySmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            val r = state.readout
            Text(text = if (r.sounding && cur != null) rs(R.string.cents, signed(Instrument.centsFrom(r.note + r.cents / 100f, cur))) else " ", style = ThType.figure, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(progress = { (ui.beat / song.beats).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f), colors = LinearProgressDefaults.linearProgressColors(indicatorColor = PicoTheme.colorScheme.interaction))
        WordButton("restart", rs(R.string.action_restart), { onEvent(PlayEvent.RestartClicked) }, minWidth = 112.dp, iconRes = R.drawable.ic_redo)
    }
}

/**
 * The target lane (domain content): time across (the now line at 25 %, four beats ahead), pitch up (C .. D');
 * the notes are bars (the current one in passable), the hand's true pitch a line in the interaction colour.
 */
@Composable
private fun SongLane(state: PlayUiState, vm: PlayViewModel, modifier: Modifier) {
    val ui = state.song ?: return
    val song = vm.songs.firstOrNull { it.id == ui.songId } ?: return
    val bar = PicoTheme.colorScheme.fillSecondary
    val pass = PicoTheme.colorScheme.passable
    val line = PicoTheme.colorScheme.interaction
    val now = PicoTheme.colorScheme.labelSecondary
    val bg = PicoTheme.colorScheme.fillTertiary
    Canvas(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        drawRect(bg)
        val ahead = 4f
        val back = ahead / 3f
        val bx = { b: Float -> (b - (ui.beat - back)) / (ahead + back) * size.width }
        val sy = { s: Float -> size.height - 6f - (s / 14f) * (size.height - 12f) }
        song.notes.forEachIndexed { i, n ->
            val x0 = bx(song.starts[i])
            val x1 = bx(song.starts[i] + n.beats)
            if (x1 < 0 || x0 > size.width) return@forEachIndexed
            drawRoundRect(if (i == ui.current) pass else bar, Offset(x0 + 1f, sy(n.semitone.toFloat()) - 4f), Size((x1 - x0 - 2f).coerceAtLeast(2f), 8f), CornerRadius(4f, 4f))
        }
        drawLine(now, Offset(size.width * 0.25f, 0f), Offset(size.width * 0.25f, size.height), strokeWidth = 2f)
        var prev: Offset? = null
        val n = vm.lane.size
        for (k in 0 until n) {
            val i = (vm.laneNewest + 1 + k) % n
            val b = vm.laneBeat[i]
            val s = vm.lane[i]
            if (b.isNaN() || s.isNaN()) {
                prev = null
                continue
            }
            val p = Offset(bx(b), sy(s))
            if (p.x < 0f) {
                prev = null
                continue
            }
            prev?.let { if (abs(it.y - p.y) < size.height / 3) drawLine(line, it, p, strokeWidth = 3f) }
            prev = p
        }
    }
}

@Composable
private fun ColumnScope.SongResultPage(state: PlayUiState, vm: PlayViewModel, onEvent: (PlayEvent) -> Unit) {
    val r = state.songResult ?: return
    val song = vm.songs.firstOrNull { it.id == r.songId } ?: return
    Text(text = if (state.english) song.en else song.zh, style = ThType.title, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "★".repeat(r.stars) + "☆".repeat(3 - r.stars), style = PicoTheme.typography.displaySmall, color = PicoTheme.colorScheme.interaction, maxLines = 1)
        Text(text = "${r.score}", style = PicoTheme.typography.displaySmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
    Body(rs(R.string.song_good, r.good, r.total))
    if (state.songNewBest) Text(text = rs(R.string.result_best), style = ThType.figure, color = PicoTheme.colorScheme.passable, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WordButton("again", rs(R.string.action_again), { onEvent(PlayEvent.RestartClicked) }, primary = true, minWidth = 120.dp)
        if (song.id != vm.songs.lastOrNull()?.id) WordButton("next", rs(R.string.action_next_song), { onEvent(PlayEvent.NextClicked) }, minWidth = 112.dp)
        WordButton("to_list", rs(R.string.action_to_list), { onEvent(PlayEvent.ToListClicked) }, minWidth = 112.dp)
    }
}

@Composable
private fun ColumnScope.UnavailablePage(onEvent: (PlayEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Text(text = rs(R.string.unavail_title), style = ThType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
    }
    Body(rs(R.string.unavail_reason), secondary = true)
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WordButton("retry", rs(R.string.action_retry), { onEvent(PlayEvent.RetryClicked) }, primary = true, minWidth = 140.dp)
        WordButton("unavail_exit", rs(R.string.action_exit), { onEvent(PlayEvent.ExitClicked) }, minWidth = 112.dp)
    }
}

/** A SegmentControl of [itemWidth] x [height] items. */
@Composable
internal fun Segments(items: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: Dp, height: Dp) {
    SegmentControl(modifier = Modifier.width(itemWidth * items.size).height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(height).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction,
                        selectedItemContentColor = io.embodit.theremin.ui.theme.ThColors.InkOnFill,
                    ),
                // design-style: inherited-content-color SegmentItem
                title = { Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
            )
        }
    }
}

/** Grab bar on top of a world-fixed panel: dragging it (gaze + pinch, or the controller ray) moves the panel in its own plane. */
@Composable
private fun DragHandle(onDrag: (Float, Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); onDrag(d.x / density, d.y / density) } },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(96.dp, 6.dp).clip(CircleShape).background(PicoTheme.colorScheme.labelTertiary))
    }
}

