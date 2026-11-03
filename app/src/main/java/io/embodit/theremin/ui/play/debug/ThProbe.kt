package io.embodit.theremin.ui.play.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.embodit.theremin.ui.play.PlayEvent
import io.embodit.theremin.ui.play.PlayUiState
import java.util.concurrent.ConcurrentHashMap

/**
 * Seams for QA's adb harness. Nothing here is product UI and nothing here does anything by itself:
 * the hooks are only driven by the debug-build receiver (`src/debug`). In a release build no receiver
 * exists, [ThProbe.enabled] stays false and the bounds modifiers are no-ops.
 */
object ThProbe {
    @Volatile var enabled: Boolean = false

    data class ControlBounds(val cx: Float, val cy: Float, val w: Float, val h: Float)

    private val controls = ConcurrentHashMap<String, ConcurrentHashMap<String, ControlBounds>>()
    private val panelSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun report(panel: String, name: String, bounds: ControlBounds, panelWidth: Int, panelHeight: Int) {
        controls.getOrPut(panel) { ConcurrentHashMap() }[name] = bounds
        panelSizes[panel] = panelWidth to panelHeight
    }

    fun forget(panel: String, name: String) {
        controls[panel]?.remove(name)
    }

    fun controlsOf(panel: String): Map<String, ControlBounds> = controls[panel]?.toSortedMap() ?: emptyMap()

    fun sizeOf(panel: String): Pair<Int, Int>? = panelSizes[panel]

    /** What the screen exposes to the debug receiver while it is composed. */
    interface Host {
        fun dispatch(event: PlayEvent)

        fun uiState(): PlayUiState

        fun dumpJson(): String

        fun scene(): SceneProbe?

        /** Semitones of a song's notes (debug hand paths). */
        fun songNotes(id: String): List<Int>

        /** Presses a panel control by its stable name; false when no such control is composed. */
        fun pressControl(name: String): Boolean
    }

    interface SceneProbe {
        fun poseLine(): String

        fun panelLines(): List<String>

        /** D24 audit: what of the real room is used (nothing) and the app's own colliders. */
        fun realitySummary(): List<String>

        fun parkPanel(): String


        /** Debug `paneldrag=dx,dy`: drags the place panel by its handle (dp, y down). */

        fun dragPanelDebug(dxDp: Float, dyDp: Float): String


        /** Debug `move=dx,dy,dz`: drags the instrument by that offset (m) and releases it. */

        fun debugMove(dx: Float, dy: Float, dz: Float): String


        /** Debug `standdrag=dx,dy` (dp) / `standrecall`. */

        fun dragStand(dxDp: Float, dyDp: Float)


        fun recallStand()

        /** Debug `standinfo` (v0.1.7, D21): the stand's centre / top edge against the eyes. */
        fun standInfo(): String

        /** Debug `dragmode=plane|free|freeze` (v0.1.7): how a grab bar turns dragAmount into a world move. */
        fun dragMode(name: String): String

        fun recenter(): String

        /** One `PITCH ...` line: d, s, f, cents, v, sources, buffer latency, state. */
        fun pitchInfo(): String

        /** Records the next [seconds] of the synth output into files/rec.wav. */
        fun record(seconds: Float): String

        /** Restarts the audio track with or without the SpatialAudioTrackExtension (latency check). */
        fun restartAudio(spatial: Boolean): String

        /** Sets the marker on the Stage floor beside the panel (`marker=floor`; `desk` is accepted and does the same). */
        fun debugMarker(kind: String): String

        /** Asymmetric "F↑" test texture on every 3D label. */
        fun setLabelTest(on: Boolean)
    }

    @Volatile var host: Host? = null

    /** Panel controls by name (registered by `Modifier.probe`): the debug command `ui <name>` presses one. */
    private val clicks = ConcurrentHashMap<String, () -> Unit>()

    fun registerClick(name: String, onClick: () -> Unit) {
        clicks[name] = onClick
    }

    fun unregisterClick(name: String) {
        clicks.remove(name)
    }

    fun click(name: String): Boolean {
        val c = clicks[name] ?: return false
        c()
        return true
    }
}

/** Id of the attachment panel whose content is being composed. */
val LocalProbePanel = compositionLocalOf { "" }

/** Registers the on-panel bounds of a control under a stable [name]. A no-op unless the app is debuggable. */
@Composable
fun Modifier.probe(name: String, onClick: (() -> Unit)? = null): Modifier {
    if (!ThProbe.enabled) return this
    val panel = LocalProbePanel.current
    DisposableEffect(panel, name, onClick) {
        if (onClick != null) ThProbe.registerClick(name, onClick)
        onDispose {
            ThProbe.forget(panel, name)
            if (onClick != null) ThProbe.unregisterClick(name)
        }
    }
    return this.onGloballyPositioned { c ->
        if (c.isAttached) {
            val root = c.findRootCoordinates()
            val topLeft = root.localPositionOf(c, Offset.Zero)
            val bottomRight = root.localPositionOf(c, Offset(c.size.width.toFloat(), c.size.height.toFloat()))
            ThProbe.report(
                panel,
                name,
                ThProbe.ControlBounds((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                root.size.width,
                root.size.height,
            )
        }
    }
}
