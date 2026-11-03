package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.Vec3

/**
 * One drag of a world panel by its grab bar (taste D21, v0.1.7, headset v0.1.6: "面板会一致抖动，还会 orbit 运动").
 * The v0.1.6 bar used the Compose 2D drag detector: deltas in the panel's own 2D coordinates, so a moving panel
 * fed its own motion back into the next delta (jitter), and the stand's offset was kept along its turning right
 * axis (orbit). Here:
 * - the panel's turn is frozen for the whole drag ([yaw]); it turns to the user only after release;
 * - the result is a Stage (world) position: where it was grabbed plus the pointer's Stage motion since the grab,
 *   kept in the panel's own (frozen) plane, never a pose-relative offset;
 * - the pointer's motion comes from `detectSpatialDragGesture` on the world-fixed SpatialView, aimed at an invisible
 *   grab collider over the bar, and is converted to Stage space by the SDK. Measured on the emulator (v0.1.7,
 *   apps/theremin/build/ui-audit.md): the same detector on the attached panel itself reports the pointer RELATIVE TO
 *   THE MOVING PANEL, so any sum of those deltas fights the panel's own motion (half speed, swinging, runaway in
 *   depth); the SpatialView's frame does not move.
 * Pure; the scene feeds it and places the panel at [target] every frame while [active].
 */
class PanelDrag {
    /** debug `dragmode=` */
    enum class Mode {
        /** The pointer's motion within the panel's plane (the default). */
        PLANE,

        /** The pointer's full 3D motion (depth too). */
        FREE,

        /** Measure only: the panel stays where it is, the samples are logged. */
        FREEZE,
    }

    var mode = Mode.PLANE
    var active = false
        private set
    var yaw = 0f
        private set
    var start = Vec3.ZERO
        private set
    var events = 0
        private set

    /** The pointer's Stage motion since the grab (metres), as last reported. */
    var moved = Vec3.ZERO
        private set

    fun begin(at: Vec3, yaw: Float) {
        active = true
        this.yaw = yaw
        start = at
        moved = Vec3.ZERO
        events = 0
    }

    /** The pointer has moved by [stageMove] (Stage metres) since the grab. */
    fun moveTo(stageMove: Vec3) {
        if (!active || !stageMove.isFinite()) return
        events++
        moved = if (stageMove.length <= MAX_M) stageMove else stageMove * (MAX_M / stageMove.length)
    }

    /** The panel centre in Stage space for the motion so far. */
    fun target(): Vec3 =
        when (mode) {
            Mode.FREEZE -> start
            Mode.FREE -> start + moved
            Mode.PLANE -> start + moved.rejectFrom(normal())
        }

    /** The panel's facing direction (its +Z) at the frozen yaw. */
    fun normal(): Vec3 = PlaceUpright.rotation(yaw).rotate(Vec3(0f, 0f, 1f))

    /** A move of ([dxDp], [dyDp]) dp in the panel's own plane (y down, as on the panel), Stage metres: the debug drags. */
    fun inPlane(dxDp: Float, dyDp: Float): Vec3 = PlaceUpright.rotation(yaw).rotate(Vec3(dxDp, -dyDp, 0f) * M_PER_DP)

    fun end() {
        active = false
    }

    companion object {
        /** Panel pixels: 1 dp = 0.8 mm (PanelRig.DP_M). */
        const val M_PER_DP = 0.0008f

        /** One drag moves the panel at most this far from where it was grabbed. */
        const val MAX_M = 1.5f
    }
}
