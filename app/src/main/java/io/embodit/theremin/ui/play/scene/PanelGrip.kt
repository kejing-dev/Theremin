package io.embodit.theremin.ui.play.scene

import io.embodit.theremin.domain.model.Vec3

/**
 * A world panel's grab bar (taste D21, v0.1.7): fed by `detectSpatialDragGesture` on the SpatialView aimed at the
 * bar's grab collider (PlayStage), and by the debug drags through the same calls. [moveTo] gets the pointer's Stage
 * motion since the grab (metres); [raw] is a log string with the SDK's own values.
 */
interface PanelGrip {
    fun start(raw: String)

    fun moveTo(stageMove: Vec3, raw: String)

    fun end(cancelled: Boolean)
}
