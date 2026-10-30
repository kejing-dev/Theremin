package io.embodit.theremin.platform.anchor

import android.content.Context
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.world.WorldAnchor
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.platform.ThLog
import java.util.UUID
import kotlin.math.atan2
import kotlinx.coroutines.delay

/**
 * The instrument's world anchor (v0.1.4, user: "摆放应该是用 spatial anchor"): one `WorldTrackingManager` anchor
 * (Full Space only) at the instrument's centre, persisted by the system; its id, the instrument's yaw relative to
 * the anchor and the stand flag go in prefs. The next launch loads it and the instrument comes back where it was.
 * Taken from Memory Palace: subscribe first (else creation answers -6), retry -6 / -5 for a few seconds after the
 * Stage opens, loading an empty store is -7. Main thread only.
 */
class InstrumentAnchor(context: Context) {
    data class Saved(val pose: InstrumentPose, val onStand: Boolean)

    private val prefs = context.getSharedPreferences("anchor", Context.MODE_PRIVATE)
    private var subscription: Cancellable? = null

    /** World tracking moved our anchor (recalibration): the new instrument pose. */
    var onMoved: ((InstrumentPose) -> Unit)? = null

    fun start() {
        if (subscription != null) return
        subscription =
            runCatching {
                    WorldTrackingManager.subscribeAnchorUpdate { u ->
                        if (u.event == AnchorUpdate.Event.UPDATED && u.anchor.anchorUUID.toString() == prefs.getString(KEY_ID, null)) {
                            runCatching { onMoved?.invoke(poseOf(u.anchor)) }
                        }
                    }
                }
                .onFailure { ThLog.w("ANCHOR subscribe failed: $it") }
                .getOrNull()
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
    }

    /** The saved instrument, or null (none saved, or the system no longer has the anchor). */
    suspend fun load(): Saved? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        var tries = 0
        while (tries < TRIES) {
            tries++
            when (val r = runCatching { WorldTrackingManager.loadAnchor() }.getOrElse { ThLog.w("ANCHOR load threw $it"); return null }) {
                is WorldTrackingResult.Success -> {
                    val a = (r.data ?: emptyArray()).firstOrNull { it.anchorUUID.toString() == id }
                    ThLog.i("ANCHOR load tries=$tries found=${a != null} of ${r.data?.size ?: 0}")
                    return a?.let { Saved(poseOf(it), prefs.getBoolean(KEY_STAND, false)) }
                }
                is WorldTrackingResult.Error -> {
                    if (r.errorCode == NOT_FOUND) {
                        ThLog.i("ANCHOR load: store empty")
                        return null
                    }
                    if (r.errorCode != NOT_READY && r.errorCode != NO_PERMISSION) {
                        ThLog.w("ANCHOR load failed ${r.errorCode} ${r.errorMessage}")
                        return null
                    }
                    delay(RETRY_MS)
                }
            }
        }
        ThLog.w("ANCHOR load gave up after $tries tries")
        return null
    }

    /** Replaces the saved anchor with one at [pose]. */
    suspend fun save(pose: InstrumentPose, onStand: Boolean): Boolean {
        val old = prefs.getString(KEY_ID, null)
        var tries = 0
        while (tries < TRIES) {
            tries++
            val p = pose.center
            when (val r = runCatching { WorldTrackingManager.createAnchor(Vector3(p.x, p.y, p.z), EulerAngles(0f, 0f, 0f), "theremin") }.getOrElse { ThLog.w("ANCHOR create threw $it"); return false }) {
                is WorldTrackingResult.Success -> {
                    val a = r.data ?: return false
                    // the yaw is kept relative to the anchor's own turn, so a recalibration that turns the anchor turns the instrument too
                    val rel = pose.yaw - yawOf(a)
                    prefs.edit().putString(KEY_ID, a.anchorUUID.toString()).putFloat(KEY_YAW, rel).putBoolean(KEY_STAND, onStand).apply()
                    ThLog.i("ANCHOR saved id=${a.anchorUUID} at=${ThLog.pos(p)} tries=$tries")
                    if (old != null) runCatching { WorldTrackingManager.removeAnchor(UUID.fromString(old)) }
                    return true
                }
                is WorldTrackingResult.Error -> {
                    if (r.errorCode != NOT_READY && r.errorCode != NO_PERMISSION) {
                        ThLog.w("ANCHOR create failed ${r.errorCode} ${r.errorMessage}")
                        return false
                    }
                    delay(RETRY_MS)
                }
            }
        }
        return false
    }

    private fun yawOf(a: WorldAnchor): Float {
        val q = a.transform.quaternion
        val f = Quat4(q.x, q.y, q.z, q.w).normalized().rotate(Vec3(0f, 0f, 1f))
        return atan2(f.x, f.z)
    }

    private fun poseOf(a: WorldAnchor): InstrumentPose {
        val p = a.transform.position
        return InstrumentPose(Vec3(p.x, p.y, p.z), yawOf(a) + prefs.getFloat(KEY_YAW, 0f))
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_YAW = "yaw"
        const val KEY_STAND = "stand"
        const val NOT_FOUND = -7
        const val NOT_READY = -6
        const val NO_PERMISSION = -5
        const val TRIES = 8
        const val RETRY_MS = 750L
    }
}
