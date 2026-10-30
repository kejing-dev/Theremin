package io.embodit.theremin.platform.input

import android.os.Build
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPinch
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.HandSample
import io.embodit.theremin.platform.ThLog

/**
 * Hand joints from `HandTrackingProvider` (design `input.modes`): PALM and WRIST of both hands plus the pinch,
 * Stage metres. [sample] may be called from the audio thread (it only reads `latestData`).
 *
 * Not started on the emulator (Spatial Ruler finding: once hand tracking ran there, gaze + pinch stopped
 * reaching every app until the emulator restarted; design `input.emulatorRule`). A joint at the Stage origin
 * that is not ready is the provider's placeholder before any hand was seen and counts as no hand.
 */
class HandInput {
    @Volatile private var provider: HandTrackingProvider? = null
    @Volatile var support: DataProvider.SupportState? = null
        private set

    val wanted: Boolean = !onEmulator

    val started: Boolean
        get() = provider != null

    fun start() {
        if (provider != null || !wanted) return
        val p = HandTrackingProvider()
        val r = runCatching { p.start() }
        support = runCatching { p.supportState }.getOrNull()
        ThLog.i("TRACK hand start=${r.getOrNull() ?: r.exceptionOrNull()} support=$support")
        provider = p
    }

    fun stop() {
        val p = provider ?: return
        provider = null
        runCatching { p.stop() }
        ThLog.i("TRACK hand stopped")
    }

    /** Re-reads the support state (main thread, once per frame); logs changes. */
    fun pollSupport(): DataProvider.SupportState? {
        val p = provider ?: return null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != support) {
            support = s
            ThLog.i("TRACK hand support=$s")
        }
        return s
    }

    /** Left and right hand, or null where none is tracked. */
    fun sample(): Pair<HandSample?, HandSample?> {
        val p = provider ?: return NONE
        if (support != DataProvider.SupportState.SUPPORTED) return NONE
        val data = runCatching { p.latestData }.getOrNull() ?: return NONE
        return hand(data.left, data.leftPinch) to hand(data.right, data.rightPinch)
    }

    /** Data age is not public on HandTrackingData in SDK 6.1.9 (timestamp is internal): not measured. */
    fun ageMs(@Suppress("UNUSED_PARAMETER") nowNs: Long): Long? = null

    private fun hand(pose: HandPose?, pinch: HandPinch?): HandSample? {
        if (pose == null) return null
        val palm = runCatching { pose.joint(HandJoint.Index.PALM).position }.getOrNull() ?: return null
        val wrist = runCatching { pose.joint(HandJoint.Index.WRIST).position }.getOrNull()
        val pv = Vec3(palm.x, palm.y, palm.z)
        if (!pv.isFinite() || pv.length < PLACEHOLDER_M) return null
        val wv = wrist?.let { Vec3(it.x, it.y, it.z) }?.takeIf { it.isFinite() && it.length > PLACEHOLDER_M }
        return HandSample(pv, wv, pinch?.pinchValue ?: 0f, pinch?.isReady ?: false)
    }

    companion object {
        private const val PLACEHOLDER_M = 0.001f
        private val NONE: Pair<HandSample?, HandSample?> = null to null

        val onEmulator: Boolean =
            Build.HARDWARE == "ranchu" || Build.DEVICE.contains("emulator") || Build.FINGERPRINT.contains("emulator")
    }
}
