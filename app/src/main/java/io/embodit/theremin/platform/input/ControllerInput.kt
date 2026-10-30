package io.embodit.theremin.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.theremin.domain.model.PointerRay
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.ControllerHand
import io.embodit.theremin.platform.ThLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The aiming controller in one frame, Stage space. */
data class ControllerSample(
    val ray: PointerRay,
    val trigger: Float,
    val pressed: Boolean,
    /** Thumbstick x (-1 left .. 1 right) of the aiming controller. */
    val stickX: Float,
    val right: Boolean,
)

/**
 * Controllers (taken from the Chime Bells): pose from `ControllerTrackingProvider.latestData`, trigger and
 * thumbstick from `ControllerActionListener` (copied into atomics on the listener's thread). Two uses:
 * [sample] = the aiming ray (placement, drag; main thread), [hands] = both controllers' positions and
 * triggers as play sources (audio thread). The listener is added before the provider starts (design
 * `container.lifecycle`).
 */
class ControllerInput {
    @Volatile private var provider: ControllerTrackingProvider? = null
    private val rightValue = AtomicInteger(0)
    private val leftValue = AtomicInteger(0)
    private val rightPressed = AtomicBoolean(false)
    private val leftPressed = AtomicBoolean(false)
    private val rightStick = AtomicInteger(0)
    private val leftStick = AtomicInteger(0)
    private var useRight = true
    @Volatile var support: DataProvider.SupportState? = null
        private set

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            rightValue.set(java.lang.Float.floatToIntBits(action.right.triggerValue))
            rightPressed.set(action.right.triggerPressed)
            leftValue.set(java.lang.Float.floatToIntBits(action.left.triggerValue))
            leftPressed.set(action.left.triggerPressed)
            rightStick.set(java.lang.Float.floatToIntBits(runCatching { action.right.thumbstickValue.x }.getOrDefault(0f)))
            leftStick.set(java.lang.Float.floatToIntBits(runCatching { action.left.thumbstickValue.x }.getOrDefault(0f)))
        }

    val started: Boolean
        get() = provider != null

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val result = runCatching { p.start() }
        support = runCatching { p.supportState }.getOrNull()
        ThLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$support")
        provider = p
    }

    fun stop() {
        val p = provider ?: return
        provider = null
        runCatching { p.removeControllerActionListener(listener) }
        runCatching { p.stop() }
    }

    /** Main thread, once per frame: re-reads the support state. */
    fun pollSupport(): DataProvider.SupportState? {
        val p = provider ?: return null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != support) {
            support = s
            ThLog.i("TRACK controller support=$s")
        }
        return s
    }

    private fun f(a: AtomicInteger) = java.lang.Float.intBitsToFloat(a.get())

    /** The aiming controller (the one whose trigger was touched last). Null without a controller pose. */
    fun sample(): ControllerSample? {
        val p = provider ?: return null
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val rightV = f(rightValue)
        val leftV = f(leftValue)
        val rightDown = rightPressed.get()
        val leftDown = leftPressed.get()
        if (useRight && (leftV > SWITCH_AT || leftDown) && rightV < REST_BELOW && !rightDown) useRight = false
        if (!useRight && (rightV > SWITCH_AT || rightDown) && leftV < REST_BELOW && !leftDown) useRight = true
        val right = data.right
        val left = data.left
        val pickRight = if (useRight) right != null else left == null
        val pose = (if (pickRight) right else left) ?: return null
        val position = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rotation = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!position.isFinite()) return null
        val forward = rotation.rotate(Vec3.AHEAD)
        if (!forward.isFinite() || forward.length < 0.5f) return null
        return ControllerSample(
            ray = PointerRay(position, forward.normalized()),
            trigger = if (pickRight) rightV else leftV,
            pressed = if (pickRight) rightDown else leftDown,
            stickX = f(if (pickRight) rightStick else leftStick),
            right = pickRight,
        )
    }

    /** Thumbstick x of either controller, the larger push (rotation while placing / dragging). */
    fun stickX(): Float {
        val r = f(rightStick)
        val l = f(leftStick)
        return if (kotlin.math.abs(r) >= kotlin.math.abs(l)) r else l
    }

    /** Both controllers as play sources (left, right). Any thread. */
    fun hands(): Pair<ControllerHand?, ControllerHand?> {
        val p = provider ?: return NONE
        if (support != DataProvider.SupportState.SUPPORTED) return NONE
        val data = runCatching { p.latestData }.getOrNull() ?: return NONE
        fun one(pose: com.pico.spatial.tracking.controller.ControllerPose?, trigger: Float): ControllerHand? {
            if (pose == null) return null
            val v = Vec3(pose.position.x, pose.position.y, pose.position.z)
            return if (v.isFinite() && v.length > 0.001f) ControllerHand(v, trigger) else null
        }
        return runCatching { one(data.left, f(leftValue)) to one(data.right, f(rightValue)) }.getOrDefault(NONE)
    }

    companion object {
        private const val SWITCH_AT = 0.15f
        private const val REST_BELOW = 0.05f
        private val NONE: Pair<ControllerHand?, ControllerHand?> = null to null
    }
}
