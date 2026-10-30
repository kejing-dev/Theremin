package io.embodit.theremin.platform.sense

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.SurfaceKind
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.platform.ThLog

/**
 * Plane detection. Surfaces arrive progressively (on the emulator the first after about 20 s, all of
 * them after about 45 s) and keep being updated; nothing waits for them.
 *
 * Vertices are converted to Stage metres here. `planeOrientation` and `boundingBoxSize` of the SDK
 * anchor are not used (unreliable on the emulator); normals come from the vertices.
 * All state is touched on the main thread only.
 */
class PlaneTracker {

    /** Called on the main thread. [surface] is null when the plane was removed. */
    fun interface Listener {
        fun onPlane(id: String, surface: PlaneSurface?, added: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null
    private var listener: Listener? = null

    /** `elapsedRealtime` of the last newly reported plane; drives the "scanning" hint. */
    var lastAddedAtMs: Long = 0L
        private set

    var startedAtMs: Long = 0L
        private set

    val all: Collection<PlaneSurface>
        get() = surfaces.values

    val count: Int
        get() = surfaces.size

    fun get(id: String): PlaneSurface? = surfaces[id]

    /** Starts (or resumes) detection; false when the platform refused to start it. */
    fun start(listener: Listener): Boolean {
        if (subscription != null) return true
        this.listener = listener
        startedAtMs = SystemClock.elapsedRealtime()
        lastAddedAtMs = startedAtMs
        // subscribe first, then start: no event is lost
        subscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
            }
        val result = runCatching { PlaneTrackingManager.start() }
            .onFailure { ThLog.e("PLANES start failed: $it", it) }
        ThLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
        if (result.isFailure) {
            runCatching { subscription?.cancel() }
            subscription = null
        }
        return result.isSuccess
    }

    /**
     * Stops detection but keeps the surfaces seen so far (the room does not move): the rack is placed
     * and needs no more planes (design: stop 15 s after placing, and in the background). [start] resumes.
     */
    fun pause() {
        if (subscription == null) return
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        ThLog.i("PLANES tracking paused kept=${surfaces.size}")
    }

    val running: Boolean
        get() = subscription != null

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        listener = null
        surfaces.clear()
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val surface = runCatching { anchor.toSurface(id) }.getOrNull() ?: return
                if (surface.vertices.size < 3 || surface.indices.size < 3) return
                val previous = surfaces[id]
                val isNew = previous == null
                if (!isNew && sameGeometry(previous!!, surface)) return
                surfaces[id] = surface
                if (isNew) lastAddedAtMs = SystemClock.elapsedRealtime()
                listener?.onPlane(id, surface, isNew)
            }
            AnchorUpdate.Event.REMOVED -> {
                if (surfaces.remove(id) != null) listener?.onPlane(id, null, false)
            }
            else -> Unit
        }
    }

    private fun PlaneAnchor.toSurface(id: String): PlaneSurface {
        val q = transform.quaternion
        val p = transform.position
        val stage =
            vertices.map { v ->
                val w = q.rotateVector(v) + p
                Vec3(w.x, w.y, w.z)
            }
        val name = semantics.name
        return PlaneSurface(id, SurfaceKind.fromSemanticName(name), name, stage, indices.toList())
    }

    /** The emulator re-reports unchanged planes all the time; those updates are dropped. */
    private fun sameGeometry(a: PlaneSurface, b: PlaneSurface): Boolean {
        if (a.vertices.size != b.vertices.size || a.semanticName != b.semanticName) return false
        for (i in a.vertices.indices) {
            if (a.vertices[i].distanceTo(b.vertices[i]) > GEOMETRY_EPS_M) return false
        }
        return true
    }

    private companion object {
        const val GEOMETRY_EPS_M = 0.0005f
    }
}
