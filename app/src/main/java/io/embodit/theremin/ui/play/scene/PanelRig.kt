package io.embodit.theremin.ui.play.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.usecase.FacePanelUseCase
import io.embodit.theremin.domain.usecase.PanelFacing
import io.embodit.theremin.domain.usecase.ViewPose
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.ui.play.debug.ThProbe
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * The pre-built panel pool, taken from the Spatial Ruler (its findings on SDK 6.1.9 are kept as they
 * are; see that project's `PanelRig` for the measurements):
 * 1. a panel parented under a disabled holder never appears: a slot stays enabled, parked out of
 *    view at 1/1000 size, until its panel is bound and has had 6 frames / 0.6 s to come up;
 * 2. a parked panel below a head anchor takes the pointer away from every panel on the line of sight
 *    through that anchor: hidden = holder disabled and stowed 30 m behind the viewer (room panels)
 *    or 40 m below the head (head-locked ones);
 * 3. what a panel draws while out of place stays broken: a pooled panel composes its content only
 *    two engine frames after it was moved into place ([onVisibility]);
 * 4. a panel takes the pointer over its whole rectangle: narrow content squeezes the holder
 *    ([Slot.widthFactor]) and draws itself stretched by the inverse.
 *
 * 1 dp of an attachment panel is 0.8 mm at scale 1. A room panel is turned towards the viewer once per
 * frame ([faceViewer]) by writing its yaw into its holder (upright, D9) (no `LookAtComponent`: with it the
 * pointer hits the unturned plane). Panels that the app places itself (the lazy-follow main panel)
 * are [Slot.autoFace] = false and get their pose through [Slot.place].
 */
internal class PanelRig(private val stageRoot: Entity, private val onVisibility: (id: String, shown: Boolean) -> Unit) {

    enum class Mount { WORLD, HEAD }

    enum class Rest { SHOWN, PARKED, STOWED }

    private val facingRule = FacePanelUseCase()
    private var frame = 0L
    private var viewer: Vec3? = null
    private var stow: Vec3 = Vec3(0f, -40f, 0f)
    private var stowSet = false

    inner class Slot(val id: String, val holder: Entity, val mount: Mount, val widthDp: Float, val heightDp: Float) {
        var panel: Entity? = null
            private set
        var shown = false
            private set
        var position: Vec3 = Vec3.ZERO
            private set
        var rotation: Quat4 = Quat4.IDENTITY
            private set
        var scale = 1f
            private set

        /** Room panels turn towards the viewer by themselves unless the scene places them. */
        var autoFace = true

        var widthFactor = 1f
            set(value) {
                val f = value.coerceIn(MIN_WIDTH_FACTOR, 1f)
                if (f == field) return
                field = f
                apply()
            }

        var headOffset: Vec3? = null
        var headRotation: Quat4 = Quat4.IDENTITY
        var switch: Entity = holder
        var facing: PanelFacing = PanelFacing(0f, 0f)
            private set
        private var faced = false
        var settled = false
            private set
        var rest = Rest.PARKED
            private set
        private var boundFrame = 0L
        private var boundMs = 0L
        private var writtenPosition: Vec3? = null
        private var writtenScale = -1f
        private var writtenFactor = -1f
        private var switchOn = true
        private var reported: Boolean? = null
        private var shownFrame = 0L

        val lastYaw: Float?
            get() = if (faced) facing.yaw else null

        internal fun bindPanel(entity: Entity, nowMs: Long) {
            settled = false
            apply()
            holder.addChild(entity)
            panel = entity
            boundFrame = frame
            boundMs = nowMs
            runCatching { entity.setName("panel:$id") }
        }

        internal fun forgetPanel() {
            panel = null
            settled = false
        }

        internal fun settle(nowMs: Long) {
            if (reported != shown) report()
            if (settled || panel == null) return
            if (frame - boundFrame < SETTLE_FRAMES || nowMs - boundMs < SETTLE_MS) return
            settled = true
            apply()
        }

        private fun report() {
            val visible = shown && frame - shownFrame >= SHOW_FRAMES
            if (reported != visible) {
                reported = visible
                onVisibility(id, visible)
            }
        }

        internal fun face(viewer: Vec3) {
            val f = facingRule.facing(position, viewer, lastYaw)
            if (faced && angle(f.yaw, facing.yaw) < FACE_STEP_RAD && abs(f.pitch - facing.pitch) < FACE_STEP_RAD) return
            facing = f
            faced = true
            rotation = f.rotation
            holder.setLocal(rotation = f.rotation)
        }

        /** Shows the panel; [at] and [k] move a WORLD slot, null keeps the last values. */
        fun show(at: Vec3? = null, k: Float? = null) {
            if (at != null) position = at
            if (k != null) scale = k
            shown = true
            if (mount == Mount.WORLD && autoFace) viewer?.let { face(it) }
            apply()
        }

        /** Places a WORLD slot with an explicit pose (lazy-follow panels); shows it. */
        fun place(at: Vec3, rotation: Quat4, k: Float) {
            autoFace = false
            position = at
            scale = k
            this.rotation = rotation
            holder.setLocal(rotation = rotation)
            shown = true
            apply()
        }

        fun hide() {
            if (!shown) return
            shown = false
            apply()
        }

        internal fun home(at: Vec3, k: Float, visible: Boolean) {
            position = at
            scale = k
            shown = visible
            apply()
        }

        internal fun apply() {
            val wanted =
                when {
                    shown -> Rest.SHOWN
                    !settled -> Rest.PARKED
                    else -> Rest.STOWED
                }
            val away = wanted != Rest.SHOWN
            val p = if (away) awayPosition() else position
            val k = if (away) PARK_SCALE else scale
            val wantSwitch = wanted != Rest.STOWED
            if (!wantSwitch && switchOn) {
                switch.enabled = false
                switchOn = false
            }
            if (p != writtenPosition) {
                holder.setLocal(position = p)
                writtenPosition = p
            }
            val factor = if (away) 1f else widthFactor
            if (k != writtenScale || factor != writtenFactor) {
                if (factor == 1f) holder.setUniformScale(k) else holder.setLocal(scale = Vec3(k * factor, k, k))
                writtenScale = k
                writtenFactor = factor
            }
            if (wantSwitch && !switchOn) {
                switch.enabled = true
                switchOn = true
            }
            if (wanted != rest) {
                if (wanted == Rest.SHOWN) shownFrame = frame
                rest = wanted
                if (ThProbe.enabled && panel != null) ThLog.i("RIG slot id=$id ${wanted.name.lowercase(Locale.US)}")
            }
            report()
        }

        private fun awayPosition(): Vec3 = if (mount == Mount.WORLD) stow else BELOW_HEAD

        val placed: Vec3
            get() = writtenPosition ?: position

        fun enginePosition(): Vec3? =
            runCatching { holder.convertPositionTo(Vector3.ZERO, stageRoot).domain() }.getOrNull()?.takeIf { it.isFinite() }

        fun engineRotation(): Quat4? = runCatching { holder.convertRotationTo(Quat.identity(), stageRoot).domain() }.getOrNull()
    }

    private val slots = LinkedHashMap<String, Slot>()

    val all: Collection<Slot>
        get() = slots.values

    operator fun get(id: String): Slot? = slots[id]

    fun onFrame(nowMs: Long) {
        frame++
        for (slot in slots.values) slot.settle(nowMs)
    }

    /** Keeps the stowing place of hidden room panels 30 m behind the viewer. */
    fun stowBehind(view: ViewPose) {
        val to = stow - view.position
        val range = to.length
        val ahead = if (range < 1e-3f) 1f else to.dot(view.forward) / range
        if (stowSet && range in STOW_MIN_M..STOW_MAX_M && ahead < STOW_AHEAD_COS) return
        stowSet = true
        val back = Vec3(-view.forward.x, 0f, -view.forward.z).let { if (it.length < 0.1f) Vec3(0f, 0f, 1f) else it.normalized() }
        stow = view.position + back * STOW_DISTANCE_M
        for (slot in slots.values) if (slot.mount == Mount.WORLD && !slot.shown) slot.apply()
    }

    /** Turns every shown, auto-facing room panel towards [viewer]. */
    fun faceViewer(viewer: Vec3) {
        this.viewer = viewer
        for (slot in slots.values) if (slot.mount == Mount.WORLD && slot.shown && slot.autoFace) slot.face(viewer)
    }

    /** A panel that stands in the room. */
    fun world(id: String, widthDp: Float, heightDp: Float): Slot {
        val holder = Entity().named("holder:$id")
        stageRoot.addChild(holder)
        return Slot(id, holder, Mount.WORLD, widthDp, heightDp).also {
            it.home(Vec3.ZERO, 1f, visible = false)
            slots[id] = it
        }
    }

    /** Parents freshly composed panel entities under their holders. Returns true when none is missing. */
    fun bind(attachments: SpatialViewAttachments, ids: Collection<String>, nowMs: Long): Boolean {
        var complete = true
        for (id in ids) {
            val slot = slots[id] ?: continue
            if (slot.panel != null) continue
            val panel = attachments.entity(id)
            if (panel == null) {
                complete = false
                continue
            }
            slot.bindPanel(panel, nowMs)
            ThLog.i("PANEL bound id=$id")
        }
        return complete
    }

    /** The framework owns the panel entities: let go of them before the holders are destroyed. */
    fun release() {
        for (slot in slots.values) {
            runCatching { slot.holder.removeAllChildren() }
            slot.forgetPanel()
        }
        slots.clear()
    }

    companion object {
        /** Metres per dp of an attachment panel at scale 1. */
        const val DP_M = 0.0008f
        private const val MIN_WIDTH_FACTOR = 0.25f
        private const val PARK_SCALE = 0.001f
        private val BELOW_HEAD = Vec3(0f, -40f, 0f)
        private const val STOW_DISTANCE_M = 30f
        private const val STOW_MIN_M = 20f
        private const val STOW_MAX_M = 45f
        private const val STOW_AHEAD_COS = 0.26f
        private const val SHOW_FRAMES = 2L
        private const val SETTLE_FRAMES = 6L
        private const val SETTLE_MS = 600L
        private const val FACE_STEP_RAD = 0.0044f

        private fun angle(a: Float, b: Float): Float {
            var d = abs(a - b)
            if (d > PI.toFloat()) d = 2f * PI.toFloat() - d
            return d
        }
    }
}
