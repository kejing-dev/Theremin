package io.embodit.theremin.domain.usecase

import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** How a room panel is turned: yaw about the vertical and pitch towards the viewer, radians; no roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector along the panel's width (always horizontal). */
    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    /** Unit vector along the panel's height. */
    val up: Vec3
        get() = Vec3(-sin(yaw) * sin(pitch), cos(pitch), -cos(yaw) * sin(pitch))

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
}

/**
 * Facing rule for panels that stand in the room: turn about the vertical towards the head only (D9, v0.1.5:
 * no pitch, no roll). When the head is almost straight above or below the panel the yaw is undefined: the
 * last yaw is kept.
 */
class FacePanelUseCase {

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        // D9 (v0.1.5): panels stay upright; the pitch towards the head is no longer applied
        return PanelFacing(yaw, 0f)
    }

    companion object {
        /** 60 degrees. */
        const val MAX_PITCH_RAD = 1.0471976f
        const val KEEP_YAW_WITHIN_M = 0.1f

        /** Yaw of a horizontal heading [forward] (the yaw that turns +Z back towards the viewer of it). */
        fun yawOf(forward: Vec3, fallback: Float): Float =
            if (hypot(forward.x, forward.z) < 0.05f) fallback else atan2(-forward.x, -forward.z)
    }
}
