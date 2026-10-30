package io.embodit.theremin.ui.play.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.theremin.ui.play.PlayEvent
import io.embodit.theremin.ui.play.PlayUiState
import io.embodit.theremin.ui.play.PlayViewModel
import io.embodit.theremin.ui.play.debug.LocalProbePanel
import io.embodit.theremin.ui.play.scene.PanelSpec
import io.embodit.theremin.ui.play.scene.PlayScene

/**
 * The Stage content: one full-size SpatialView carrying the 3D scene and the panel pool. Both attachment
 * panels are declared here once, with a fixed size, whether showing or not; the scene parents each under its
 * holder and shows or hides it. Window model: single panel with popup — the place panel before the instrument
 * stands, then the music stand on the instrument; settings, lists and menu swap the stand's page.
 */
@Composable
internal fun PlayStage(state: PlayUiState, vm: PlayViewModel, scene: PlayScene, onEvent: (PlayEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                },
        attachments = {
            AttachmentPanel(PanelSpec.PLACE) {
                PoolPanel(PanelSpec.PLACE, scene) { inView -> PlacePanel(state = state, filled = inView, onEvent = onEvent, onDrag = scene::dragPanel, onRecall = scene::recallPanel) }
            }
            AttachmentPanel(PanelSpec.STAND) {
                PoolPanel(PanelSpec.STAND, scene) { inView -> StandPanel(state = state, vm = vm, filled = inView, onEvent = onEvent, onDrag = scene::dragStand, onRecall = scene::recallStand) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/** Root of a pooled panel. [content] gets `inView`: the panel has been moved into view (what a pooled panel draws while parked stays broken on the emulator). */
@Composable
private fun PoolPanel(id: String, scene: PlayScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) { content(inView) }
}
