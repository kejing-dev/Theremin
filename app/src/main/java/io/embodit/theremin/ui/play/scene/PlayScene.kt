package io.embodit.theremin.ui.play.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.PointerRay
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.MapSettings
import io.embodit.theremin.domain.play.PlayFrame
import io.embodit.theremin.domain.play.RoleInputs
import io.embodit.theremin.domain.usecase.GrabRules
import io.embodit.theremin.domain.usecase.PlaceRules
import io.embodit.theremin.domain.usecase.PlaceUpright
import io.embodit.theremin.domain.usecase.PanelDrag
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.theremin.domain.usecase.StandPlacement
import io.embodit.theremin.platform.ThGraph
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.platform.anchor.InstrumentAnchor
import io.embodit.theremin.platform.audio.ThereminAudio
import io.embodit.theremin.platform.input.HandInput
import io.embodit.theremin.platform.tracking.HeadPose
import io.embodit.theremin.ui.play.Page
import io.embodit.theremin.ui.play.PlayEvent
import io.embodit.theremin.ui.play.PlayUiState
import io.embodit.theremin.ui.play.PlayViewModel
import io.embodit.theremin.ui.play.Room
import io.embodit.theremin.ui.play.Tick
import io.embodit.theremin.ui.play.debug.ThProbe
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: PlayScene? = null
}

/** The one per-frame ECS system of the app. */
class PlayFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side: the instrument, the marker, the panel pool, the sound. Compose state arrives through [render]
 * (idempotent); per-frame work — the marker, dragging, the bead / tag / column / ripple highlights drawn from the
 * mapper's last frame, the session tick — happens in [onFrame] only. The hand -> sound path does not pass through
 * here: the audio thread maps and plays by itself (design `audio.engine`). Everything started in [attach] is
 * stopped in [detach]. Main thread only.
 *
 * v0.1.5 (taste D24, headset: "琴和 UI 都被我桌上显示屏挡住了，还导致射线交互用不了"): the scene reads nothing of the real
 * room. No plane detection, no colliders on real surfaces, no "rest body" rule pushing panels off real surfaces, no
 * desk landing. The only colliders are the app's own: the instrument's tap box and, while a marker is wanted, a
 * virtual floor pad at the Stage floor. The content is drawn over the passthrough wherever it is.
 */
class PlayScene(private val vm: PlayViewModel, private val sink: (PlayEvent) -> Unit) : ThProbe.SceneProbe {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val headTracker = ThGraph.head
    private val controller = ThGraph.controller
    private val hands = ThGraph.hands
    private val mapper = ThGraph.mapper

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var shared: SharedResources? = null
    private var materials: SceneMaterials? = null
    private var rig: PanelRig? = null
    private var view: InstrumentView? = null
    private var markerView: MarkerView? = null
    private var floorPad: Entity? = null
    private var floorPadOn = false
    private var audio: ThereminAudio? = null
    private var attached = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null
    private var rebuildJob: Job? = null
    private val tagMaterials = HashMap<String, Material>()

    /** The place panel: 0.85 m ahead, 15 degrees below the eye line, 15 degrees to the left, upright (D9, design `panels.placePanel`). */
    private val follower = Follower("place", PlaceUpright.DISTANCE_M, PlaceUpright.DROP_DEG, PLACE_BEARING_DEG)

    private var ui = PlayUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var headSinceMs = NEVER
    private var lastFrameMs = NEVER
    private var frameGapMs = 0L
    private var frontSeen = 0
    private var placedAtMs = NEVER
    private var layoutLogged = 0
    private var frameNo = 0L
    private var placeVisibleMs = NEVER
    private var placeVisibleFrame = 0L
    private var placedFromDefaultHead = false

    // ---- the user's marker (v0.1.2, taste D1): where the instrument goes on "移到标记处"
    private class Marker(val pose: InstrumentPose, val point: Vec3)

    private var marker: Marker? = null
    private var markerTriggerDown = false

    // ---- drag of a standing instrument
    private var dragging = false
    private var dragPose: InstrumentPose? = null
    private var dragTriggerMs = NEVER
    private var dragByController = false
    private var grabDistance = 0.5f
    private var grabOffset = Vec3.ZERO
    private var grabHandRight = true
    private var grabStartPalm = Vec3.ZERO
    private var grabStartCenter = Vec3.ZERO
    private var pinchSinceMs = NEVER
    private var anchor: InstrumentAnchor? = null
    private var anchoredPose: InstrumentPose? = null
    private var saveJob: Job? = null

    /**
     * The stand's turn (null: face the user at once), the user's drag offset (instrument frame, metres; v0.1.7: never
     * along the panel's own turning axes) and its height correction (null: work it out from the eyes, D21).
     */
    private var standYaw: Float? = null
    private var standOffset = Vec3.ZERO
    private var standLift: Float? = null
    private var standLiftPose: InstrumentPose? = null

    // ---- grab bars (v0.1.7, D21): SDK spatial drag, turn frozen while held, Stage positions
    private val placeDrag = PanelDrag()
    private val standDrag = PanelDrag()
    private var dragLogUntilMs = NEVER

    // ---- look
    private var opacity = 1f
    private var near = false
    private var waveStartMs = NEVER

    /** The grab bar of panel [id] (PanelSpec.PLACE / STAND): the SDK drag on its grab collider ([onGrabStart] ...) and the debug drags. */
    fun grip(id: String): PanelGrip = if (id == PanelSpec.PLACE) placeGrip else standGrip

    private val placeGrip = Grip(PanelSpec.PLACE)
    private val standGrip = Grip(PanelSpec.STAND)

    /**
     * v0.1.7 (D21, headset v0.1.6 "面板会一致抖动，还会 orbit 运动"): the pointer's Stage motion moves the panel in the
     * world from where it was grabbed, in its own plane; its turn is frozen until release; on release the place panel
     * turns to the user (yaw only) and the stand keeps the result as an instrument-frame offset, then resumes turning.
     */
    private inner class Grip(val id: String) : PanelGrip {
        private val drag = if (id == PanelSpec.PLACE) placeDrag else standDrag

        override fun start(raw: String) {
            val slot = rig?.get(id)?.takeIf { it.shown } ?: return
            if (id == PanelSpec.PLACE) follower.grab()
            val yaw = if (id == PanelSpec.PLACE) follower.pose.yaw else standYaw ?: PlaceUpright.yawOf(slot.rotation.rotate(Vec3(0f, 0f, -1f)), 0f)
            drag.begin(slot.position, yaw)
            dragLogUntilMs = SystemClock.elapsedRealtime() + DRAG_LOG_MS
            ThLog.i("GRIP start panel=$id mode=${drag.mode} pos=${ThLog.pos(slot.position)} yaw=${ThLog.f(Math.toDegrees(yaw.toDouble()).toFloat(), 1)} $raw")
        }

        override fun moveTo(stageMove: Vec3, raw: String) {
            if (!drag.active) return
            drag.moveTo(stageMove)
            dragLogUntilMs = SystemClock.elapsedRealtime() + DRAG_LOG_MS
            ThLog.i("GRIP move panel=$id n=${drag.events} moved=${ThLog.pos(stageMove)} placed=${ThLog.pos(rig?.get(id)?.position)} target=${ThLog.pos(drag.target())} $raw")
        }

        override fun end(cancelled: Boolean) {
            if (!drag.active) return
            val at = drag.target()
            drag.end()
            val now = SystemClock.elapsedRealtime()
            dragLogUntilMs = now + DRAG_LOG_MS
            if (id == PanelSpec.PLACE) {
                follower.setAt(at)
                rig?.get(PanelSpec.PLACE)?.place(follower.pose.position, follower.rotation, 1f)
                if (hasHead) follower.faceTo(head.position, now)
            } else {
                val pose = ui.pose
                if (pose != null) standOffset = StandPlacement.offsetFor(pose, at, StandPlacement.baseLocal(ui.settings.handed == Handed.RIGHT_PITCH), standLift ?: 0f)
                rig?.get(PanelSpec.STAND)?.place(at, StandPlacement.rotation(drag.yaw), 1f)
            }
            ThLog.i("GRIP end panel=$id cancelled=$cancelled events=${drag.events} at=${ThLog.pos(at)} standOffset=${ThLog.pos(standOffset)}")
        }
    }

    /** Debug `paneldrag=dx,dy` / `standdrag=dx,dy` (dp, y down, in the panel's plane): the same grip path as the hand, in one step. */
    private fun debugGrip(id: String, dxDp: Float, dyDp: Float) {
        val g = grip(id)
        g.start("debug")
        g.moveTo((if (id == PanelSpec.PLACE) placeDrag else standDrag).inPlane(dxDp, dyDp), "debug")
        g.end(false)
    }

    // ---- the SDK drag on a grab collider (PlayStage): the SpatialView does not move, so its pointer positions are
    // world positions; the drag sum is converted to Stage by the SDK (same conversion as the taps)
    private var grabbing: Grip? = null
    private var grabSumPx = Vec3.ZERO

    /** True for the invisible grab colliders over the panels' bars (the drag detector's target filter). */
    fun isGrab(entity: com.pico.spatial.core.ecs.Entity): Boolean = runCatching { entity.getName() }.getOrNull()?.startsWith(GRAB_PREFIX) == true

    fun onGrabStart(value: SpatialDragValue, atPx: Offset3D) {
        val name = value.targetEntity?.let { runCatching { it.getName() }.getOrNull() } ?: return
        val g = if (name == GRAB_PREFIX + PanelSpec.PLACE) placeGrip else if (name == GRAB_PREFIX + PanelSpec.STAND) standGrip else return
        grabbing = g
        grabSumPx = Vec3.ZERO
        g.start("target=$name at_px=${fmt3(atPx)} ${rawOf(value)}")
    }

    fun onGrabDrag(value: SpatialDragValue) {
        val g = grabbing ?: return
        val a = value.dragAmount
        grabSumPx += Vec3(a.x, a.y, a.z)
        val zero = toStage(0f, 0f, 0f) ?: return
        val now = toStage(grabSumPx.x, grabSumPx.y, grabSumPx.z) ?: return
        g.moveTo(now - zero, "amount_px=${fmt3(a)} sum_px=${ThLog.pos(grabSumPx)} ${rawOf(value)}")
    }

    fun onGrabEnd(cancelled: Boolean) {
        grabbing?.end(cancelled)
        grabbing = null
    }

    private fun fmt3(o: Offset3D): String = String.format(Locale.US, "%.1f,%.1f,%.1f", o.x, o.y, o.z)

    private fun rawOf(v: SpatialDragValue): String = "dev=${fmt3(v.inputDevicePose.rawPosition)} kind=${v.interactionKind}"

    override fun dragMode(name: String): String {
        val m = when (name) {
            "free" -> PanelDrag.Mode.FREE
            "freeze" -> PanelDrag.Mode.FREEZE
            else -> PanelDrag.Mode.PLANE
        }
        placeDrag.mode = m
        standDrag.mode = m
        return "GRIP mode=$m"
    }

    /** "叫回面前": the place panel slides back to its designed place in front of the user (a UI recall, D21). */
    fun recallPanel() {
        ThLog.i("PANEL recall")
        if (hasHead) follower.park(head, SystemClock.elapsedRealtime())
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin: move the root once so that its local space is Stage space
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown ->
            panelShown[id] = shown
            if (shown && id == PanelSpec.PLACE && placeVisibleMs == NEVER) {
                placeVisibleMs = SystemClock.elapsedRealtime()
                placeVisibleFrame = frameNo
                ThLog.i("STARTUP place panel visible t=${ThLog.sinceStartMs()} room=${ui.room}")
            }
        }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            val slot = rig.world(id, w, h)
            slot.autoFace = false
            // v0.1.7 (D21): an invisible grab collider just in front of the panel's bar, riding with the panel; the SpatialView's
            // drag detector takes it (PlayStage), so the drag is measured in a frame that does not move with the panel
            val (_, hh) = PanelSpec.halfM(id)
            val grab = Entity().named(GRAB_PREFIX + id)
            slot.holder.addChild(grab)
            grab.setLocal(position = Vec3(0f, hh - GRAB_FROM_TOP_M, GRAB_FRONT_M))
            grab.tapBox(Vec3(GRAB_W_M, GRAB_H_M, 0.004f))
        }
        headTracker.start()
        controller.start()
        mapper.injectSource = ThProbe.enabled && HandInput.onEmulator
        ActiveScene.scene = this
        bindPanels(attachments)
        follower.reset()
        hasHead = false
        headSinceMs = NEVER
        placedFromDefaultHead = false
        placeVisibleMs = NEVER
        layoutLogged = 0
        ThLog.i("STARTUP scene attach t=${ThLog.sinceStartMs()}")
        buildJob =
            scope.launch {
                try {
                    build(root)
                } catch (t: Throwable) {
                    if (t !is CancellationException) ThLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        ThLog.i("STAGE root offset=${ThLog.pos(offset.domain())}")
        ThLog.i("REALITY planes=off mesh=off occlusion=none colliders=app-only (D24)")
    }

    /**
     * q3-1 (D1 "一打开就要看得见"): the instrument's meshes and materials keep the engine busy for seconds on a cold start
     * (3 - 13 s on the emulator) and the place panel was not drawn before they were done. So the build waits until the
     * loading panel ("正在调音…" + bar) has been on screen for [PANEL_LEAD_MS] / [PANEL_LEAD_FRAMES], or until
     * [PANEL_WAIT_MAX_MS] after the scene attached (not after the process start: on the emulator the Stage itself
     * opens 7 - 8 s after launch), whichever comes first.
     */
    private suspend fun awaitLoadingPanel() {
        val attachedAt = SystemClock.elapsedRealtime()
        while (stageRoot != null) {
            val age = ThLog.sinceStartMs()
            val shownFor = if (placeVisibleMs == NEVER) -1L else SystemClock.elapsedRealtime() - placeVisibleMs
            if (shownFor >= PANEL_LEAD_MS && frameNo - placeVisibleFrame >= PANEL_LEAD_FRAMES) {
                ThLog.i("STARTUP build starts t=$age after panel ${shownFor} ms")
                return
            }
            if (SystemClock.elapsedRealtime() - attachedAt >= PANEL_WAIT_MAX_MS) {
                ThLog.w("STARTUP build starts t=$age without the panel (visible=${placeVisibleMs != NEVER} head=$hasHead)")
                return
            }
            delay(FRAME_MS)
        }
    }

    private suspend fun build(root: Entity) {
        awaitLoadingPanel()
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        frame()
        markerView = MarkerView(root, m, sh)
        buildFloorPad(root)
        frame()
        buildInstrument(root, m, sh, layoutOf(ui))
        attached = true
        startAudio()
        ThLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0} t=${ThLog.sinceStartMs()}")
        render(ui, force = true)
        restoreAnchor()
        sink(PlayEvent.SceneReady)
    }

    /**
     * The app's own, invisible tap target on the Stage floor (y = 0): gaze + pinch on it sets the marker. It is the
     * only collider besides the instrument's, and it is switched on only while a marker is wanted, so it never takes
     * the pointer from the panels or the instrument. Real surfaces get no collider at all (D24).
     */
    private fun buildFloorPad(root: Entity) {
        val pad = Entity().named("floorPad")
        root.addChild(pad)
        pad.tapBox(Vec3(FLOOR_PAD_M, 0.01f, FLOOR_PAD_M))
        pad.setLocal(position = Vec3(0f, PlaceRules.FLOOR_Y - 0.005f, 0f))
        pad.enabled = false
        floorPad = pad
        floorPadOn = false
    }

    private fun setFloorPad(on: Boolean) {
        if (on == floorPadOn) return
        floorPadOn = on
        floorPad?.enabled = on
    }

    /**
     * The world anchor (v0.1.4): subscribe, then load the saved one; found, the instrument goes straight back there
     * (before SceneReady, so the place flow does not start). World tracking moving the anchor moves the instrument.
     */
    private suspend fun restoreAnchor() {
        val ctx = ThGraph.appContext ?: return
        val a = InstrumentAnchor(ctx)
        anchor = a
        a.onMoved = { p -> main.post { if (attached && !dragging && ui.placed) { anchoredPose = p; ThLog.i("ANCHOR moved -> ${ThLog.pos(p.center)}"); sink(PlayEvent.PlaceRequested(p, ui.onStand)) } } }
        a.start()
        val saved = a.load() ?: return
        if (ui.placed || !attached) return
        anchoredPose = saved.pose
        ThLog.i("ANCHOR restore at=${ThLog.pos(saved.pose.center)} yaw=${ThLog.f(Math.toDegrees(saved.pose.yaw.toDouble()).toFloat(), 1)} stand=${saved.onStand}")
        sink(PlayEvent.PlaceRequested(saved.pose, saved.onStand))
    }

    /** Every new resting pose (placed, dragged, moved to a marker) replaces the anchor, 0.4 s after it settles. */
    private fun syncAnchor(state: PlayUiState) {
        val pose = state.pose ?: return
        if (!state.placed || state.dragging || dragging || pose == anchoredPose) return
        anchoredPose = pose
        saveJob?.cancel()
        val stand = state.onStand
        saveJob = scope.launch {
            delay(ANCHOR_SETTLE_MS)
            anchor?.save(pose, stand)
        }
    }

    private suspend fun buildInstrument(root: Entity, m: SceneMaterials, sh: SharedResources, layout: Layout) {
        val v = InstrumentView(root, m, sh, layout)
        for (step in v.steps()) {
            step()
            frame()
        }
        val old = view
        view = v
        old?.destroy()
        tagMaterials.clear()
        ui.pose?.let { v.place(it) }
        v.setShown(ui.placed)
        v.setStand(ui.onStand)
        ThLog.i("INSTRUMENT built handed=${layout.handed} scale=${layout.scale} en=${layout.english} octave=${layout.octave}")
    }

    private fun startAudio() {
        val ctx = ThGraph.appContext ?: return
        if (audio == null) audio = ThereminAudio(ctx, mapper, hands, controller)
        audio?.start(view?.antenna)
        syncAudio(ui)
    }

    private fun layoutOf(s: PlayUiState) = Layout(s.settings.handed, s.settings.scale, s.english, s.settings.octave, labelTest)

    private suspend fun frame() {
        delay(if (frameGapMs > FAST_FRAME_MS) SLOW_STEP_MS else FRAME_MS)
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        rebuildJob?.cancel()
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        audio?.release()
        audio = null
        saveJob?.cancel()
        anchor?.stop()
        anchor = null
        anchoredPose = null
        standYaw = null
        standOffset = Vec3.ZERO
        standLift = null
        placeDrag.end()
        standDrag.end()
        mapper.pose = null
        mapper.armed = false
        headTracker.stop()
        controller.stop()
        hands.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        view?.destroy()
        markerView?.destroy()
        markerView = null
        marker = null
        runCatching { floorPad?.destroy(true) }
        floorPad = null
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        tagMaterials.clear()
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        rig = null
        view = null
        hasHead = false
        ThLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: PlayUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        syncAudio(state)
        mapper.settings = MapSettings(state.settings.scale, state.settings.octave, state.settings.curve, state.settings.handed, state.settings.quantize)
        if (!attached) return
        val v = view ?: return
        // rebuild on layout changes (scale, octave, mirror, language): ripples fade out and back in
        val layout = layoutOf(state)
        if (layout != v.layout && rebuildJob?.isActive != true) {
            val root = stageRoot
            val m = materials
            val sh = shared
            if (root != null && m != null && sh != null) rebuildJob = scope.launch { buildInstrument(root, m, sh, layoutOf(ui)) }
        }
        if (!dragging) {
            state.pose?.let { if (force || it != previous.pose) v.place(it) }
            mapper.pose = if (state.placed) state.pose else null
        }
        v.setShown(state.placed)
        v.setStand(state.onStand)
        mapper.armed = state.placed && !state.background
        syncAnchor(state)
        if (state.placed && !previous.placed) {
            placedAtMs = SystemClock.elapsedRealtime()
            mapper.resetTracking()
            if (!state.background) hands.start()
        }
        if (!state.placed && previous.placed) hands.stop()
        if (state.frontSerial != frontSeen) {
            frontSeen = state.frontSerial
            moveToMarker(force)
        }
        // background: sound fades and pauses, providers stop; back in front they start again
        if (state.background && !previous.background) {
            audio?.pause()
            hands.stop()
            controller.stop()
        } else if (!state.background && previous.background) {
            controller.start()
            if (state.placed) hands.start()
            audio?.resume()
        }
        syncPanels(state)
    }

    private fun syncAudio(state: PlayUiState) {
        val a = audio ?: return
        a.timbre = state.settings.timbre
        a.muted = state.settings.muted
        a.quantize = state.settings.quantize
        a.handed = state.settings.handed
    }

    /**
     * "移到标记处" (v0.1.2, taste D1 / D20): the instrument goes to the user's marker. With no marker yet and nothing
     * placed, it waits beside and a little beyond the place panel (never between the user and the panel, never from
     * the head pose) and the hint says it can be dragged anywhere. Placed and no marker: only the hint.
     */
    private fun moveToMarker(force: Boolean) {
        val mk = marker
        when {
            mk != null -> {
                marker = null
                markerView?.setAt(null)
                // v0.1.3: turned to where the user stands now, once, then locked (taste D1 / D10)
                val pose = PlaceRules.onFloor(mk.point, userAt() ?: mk.pose.center + Vec3(0f, 0f, 1f))
                ThLog.i("MARKER move to=${ThLog.pos(pose.center)} yaw=${ThLog.f(Math.toDegrees(pose.yaw.toDouble()).toFloat(), 1)} user=${userAt()?.let { ThLog.pos(it) }}")
                sink(PlayEvent.PlaceRequested(pose, onStand = true))
            }
            !ui.placed -> {
                val panel = rig?.get(PanelSpec.PLACE)
                if (panel == null || !panel.shown) {
                    if (!force) main.postDelayed({ moveToMarker(false) }, 200)
                    return
                }
                val pose = PlaceRules.besidePanel(panel.position, panel.rotation.rotate(Vec3(0f, 0f, 1f)), userAt())
                ThLog.i("MARKER none -> waiting anchor ${ThLog.pos(pose.center)} beside panel ${ThLog.pos(panel.position)}")
                sink(PlayEvent.PlaceRequested(pose, onStand = false, waiting = true))
            }
            else -> sink(PlayEvent.NoMarker)
        }
    }

    /**
     * Where the user is, for turning the instrument towards them, never for where it goes: the controller (ray
     * origin) when one is held, else the panel the user is reading carried 0.6 m towards the user.
     */
    private fun userRef(): Vec3? {
        controller.sample()?.let { return it.ray.origin }
        val rig = rig ?: return null
        val slot = if (ui.placed) rig[PanelSpec.STAND] else rig[PanelSpec.PLACE]
        return slot?.takeIf { it.shown }?.let { it.position + it.rotation.rotate(Vec3(0f, 0f, PANEL_TO_USER_M)) }
    }

    /**
     * Where the user stands, for turning the instrument only, never for where it goes (v0.1.3): the head position,
     * level; else the controller; else [userRef]. Read once when placing, then the pose is locked.
     */
    private fun userAt(): Vec3? {
        if (hasHead && head.position.length > 1e-3f) return head.position
        return controller.sample()?.ray?.origin ?: userRef()
    }

    /** Marker mode: before the instrument stands somewhere the user chose, and on the More page ("移到标记处"). */
    private fun markerMode(state: PlayUiState): Boolean =
        !state.placed || state.awaitingMarker || state.markerReady || state.page == Page.MENU

    private fun setMarker(point: Vec3, how: String) {
        val p = Vec3(point.x, PlaceRules.FLOOR_Y, point.z)
        val mk = Marker(PlaceRules.onFloor(p, userAt() ?: (p + Vec3(0f, 0f, 1f))), p)
        marker = mk
        markerView?.setAt(mk.point)
        ThLog.i("MARKER set by=$how at=${ThLog.pos(mk.point)} pose=${ThLog.pos(mk.pose.center)}")
        sink(PlayEvent.MarkerSet)
    }

    /**
     * Marker mode, per frame: the controller ray's point on the Stage floor (analytic, never a real surface) shows the
     * aim ring; the trigger sets the marker. The floor pad is on only in this mode (gaze + pinch).
     */
    private fun markerFrame(state: PlayUiState) {
        val mv = markerView ?: return
        val mode = markerMode(state)
        setFloorPad(mode)
        if (!mode) {
            mv.aimAt(null)
            markerTriggerDown = false
            return
        }
        val s = controller.sample()
        val ray = s?.ray
        val point = ray?.let { PlaceRules.floorHit(it.origin, it.direction) }
        mv.aimAt(point)
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (down && !markerTriggerDown && point != null && ray != null && !rayHitsStand(ray) && !(ui.pose?.let { rayHitsCabinet(it, ray) } ?: false)) {
            setMarker(point, "trigger")
        }
        markerTriggerDown = down
    }

    private fun syncPanels(state: PlayUiState) {
        val rig = rig ?: return
        if (state.placed) rig[PanelSpec.PLACE]?.hide() else rig[PanelSpec.STAND]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == NEVER) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.25f)
        if (lastFrameMs != NEVER) frameGapMs = now - lastFrameMs
        lastFrameMs = now
        frameNo++
        headTracker.pose()?.let {
            // the all-zero / identity pose before tracking is ready is not a head (D1 / D9, sign-hands lesson)
            if (PlaceUpright.validHead(it.position, it.rotation)) {
                head = it
                if (!hasHead) headSinceMs = now
                hasHead = true
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        if (!state.placed) {
            // D21 (v0.1.3): set once where it first appears, then fixed in the world; moved only by its handle or 叫回面前.
            // q3-1 (D1): no valid head HEAD_WAIT_MS after launch -> the Stage's default eye pose, so the loading panel shows
            // anyway; the first valid head then slides it to where it belongs (once).
            val startHead =
                when {
                    hasHead -> head
                    ThLog.sinceStartMs() >= HEAD_WAIT_MS -> DEFAULT_HEAD
                    else -> null
                }
            if (startHead != null) {
                if (follower.follow(startHead, now) && !hasHead && !placedFromDefaultHead) {
                    placedFromDefaultHead = true
                    ThLog.w("STARTUP no valid head at t=${ThLog.sinceStartMs()}: place panel from the default eye pose")
                }
                if (hasHead && placedFromDefaultHead) {
                    placedFromDefaultHead = false
                    follower.park(head, now)
                }
                if (placeDrag.active) follower.setAt(placeDrag.target())
                rig[PanelSpec.PLACE]?.place(follower.pose.position, follower.rotation, 1f)
            }
        }
        dragFrameLog(now)
        layoutLog(state)
        if (!attached) return
        val hs = hands.pollSupport()
        val cs = controller.pollSupport()
        markerFrame(state)
        if (state.placed) {
            placeStand(state, rig, dt)
            dragFrame(state, now, dt)
            // without audio the frame system drives the mapper itself (the draw still needs it)
            val f = if (audio?.running == true) mapper.last else mapper.step(java.lang.System.nanoTime(), fallbackInputs(state))
            drawFrame(state, f, now)
            val sourcesOk = hs == DataProvider.SupportState.SUPPORTED || cs == DataProvider.SupportState.SUPPORTED || (!hands.started && !controller.started)
            vm.tick(Tick(now, dt, f, sourcesOk))
            controller.sample()?.let { s -> mapper.triggerOnPanel = rayHitsStand(s.ray) } ?: run { mapper.triggerOnPanel = false }
            if (now % 1000L < 16L && ThProbe.enabled) pitchInfoLog(f, periodic = true)
        }
    }

    /**
     * D1 "一打开就要看得见" (q3-1: timed from the process start, not from the scene build): at 1 / 3 / 5 s after launch,
     * log every panel and the instrument: Stage position, shown (placed in view) / visible (drawn), distance and
     * direction from the head. Logged on the first frame past each mark; `age_ms` is the real time of the line.
     */
    private fun layoutLog(state: PlayUiState) {
        if (layoutLogged >= LAYOUT_MARKS_MS.size) return
        val age = ThLog.sinceStartMs()
        val markMs = LAYOUT_MARKS_MS[layoutLogged]
        if (age < markMs) return
        layoutLogged++
        val rig = rig ?: return
        val tag = "LAYOUT t+${markMs / 1000}s age_ms=$age"
        fun rel(p: Vec3): String {
            if (!hasHead) return "head=none"
            val d = p - head.position
            val flat = Vec3(d.x, 0f, d.z).length
            val yawDeg = Math.toDegrees(PlaceRules.angle(kotlin.math.atan2(-d.x, -d.z), PlaceUpright.yawOf(head.forward, 0f)).toDouble())
            val pitchDeg = Math.toDegrees(kotlin.math.atan2(d.y, flat).toDouble())
            return String.format(Locale.US, "dist=%.2f yaw=%+.0f pitch=%+.0f", d.length, yawDeg, pitchDeg)
        }
        ThLog.i("$tag head=${if (hasHead) ThLog.pos(head.position) else "none"} room=${state.room} built=$attached")
        for (slot in rig.all) ThLog.i("$tag panel=${slot.id} pos=${ThLog.pos(slot.position)} shown=${slot.shown} visible=${panelShown[slot.id] == true} tilt=${ThLog.f(PlaceUpright.tiltDeg(slot.rotation), 2)} ${rel(slot.position)}")
        val pose = state.pose
        if (pose == null) ThLog.i("$tag instrument pos=none shown=false built=$attached")
        else ThLog.i("$tag instrument pos=${ThLog.pos(pose.center)} shown=${state.placed && attached} built=$attached ${rel(pose.center)}")
    }

    private fun fallbackInputs(state: PlayUiState): RoleInputs {
        val (lh, rh) = hands.sample()
        val (lc, rc) = controller.hands()
        val right = state.settings.handed == Handed.RIGHT_PITCH
        return RoleInputs(if (right) rh else lh, if (right) lh else rh, if (right) rc else lc, if (right) lc else rc)
    }

    /**
     * The music stand (v0.1.4): above the instrument and riding with it, turned about the vertical towards the user
     * (smoothly; the user's position from [userAt], orientation only), upright (D9), moved by its handle, called back by 叫回.
     */
    private fun placeStand(state: PlayUiState, rig: PanelRig, dt: Float) {
        // held by its bar (v0.1.7): where the hand put it, the turn frozen
        if (standDrag.active) {
            rig[PanelSpec.STAND]?.place(standDrag.target(), StandPlacement.rotation(standDrag.yaw), 1f)
            return
        }
        val pose = (if (dragging) dragPose else null) ?: state.pose ?: return
        val (_, hh) = PanelSpec.halfM(PanelSpec.STAND)
        val local = StandPlacement.baseLocal(state.settings.handed == Handed.RIGHT_PITCH) + standOffset
        // height from the eyes once (D21): when it first shows, after 叫回, and when the instrument has moved (not while it is
        // being moved, not after the user placed the stand by hand); never per frame: the stand does not follow the head
        val relift = standLift == null || (!dragging && standOffset == Vec3.ZERO && standLiftPose != pose)
        if (relift && hasHead) {
            val lift = StandPlacement.liftFor(StandPlacement.position(pose, local, 0f), head.position, hh)
            standLift = lift
            standLiftPose = pose
            val c = StandPlacement.position(pose, local, lift)
            ThLog.i("STAND lift=${ThLog.f(lift)} centre=${ThLog.pos(c)} top=${ThLog.f(c.y + hh)} eye=${ThLog.f(head.position.y)} drop_deg=${ThLog.f(StandPlacement.dropDeg(c, head.position), 1)}")
        }
        val at = StandPlacement.position(pose, local, standLift ?: 0f)
        val target = userAt()?.let { StandPlacement.yawTo(at, it, pose.yaw) } ?: pose.yaw
        val yaw = standYaw?.let { StandPlacement.turn(it, target, dt) } ?: target
        standYaw = yaw
        rig[PanelSpec.STAND]?.place(at, StandPlacement.rotation(yaw), 1f)
    }

    /** Debug `standdrag=dx,dy` (dp, y down): the stand's grab bar, through the same grip as the hand. */
    override fun dragStand(dxDp: Float, dyDp: Float) = debugGrip(PanelSpec.STAND, dxDp, dyDp)

    /** "叫回": the stand back beside the instrument at its designed height, facing the user at once. */
    override fun recallStand() {
        ThLog.i("STAND recall from offset=${ThLog.pos(standOffset)}")
        standDrag.end()
        standOffset = Vec3.ZERO
        standYaw = null
        standLift = null
    }

    /** While a grab bar is held and [DRAG_LOG_MS] after: one line per frame with each panel's Stage position and yaw (the < 2 mm still-hand check). */
    private fun dragFrameLog(now: Long) {
        if (now > dragLogUntilMs) return
        val rig = rig ?: return
        for (id in PanelSpec.pool) {
            val slot = rig[id]?.takeIf { it.shown } ?: continue
            val held = (if (id == PanelSpec.PLACE) placeDrag else standDrag).active
            val yaw = Math.toDegrees(PlaceUpright.yawOf(slot.rotation.rotate(Vec3(0f, 0f, -1f)), 0f).toDouble()).toFloat()
            ThLog.i("DRAGF t=$now panel=$id held=$held pos=${ThLog.pos5(slot.position)} yaw=${ThLog.f(yaw, 2)}")
        }
    }

    /** Debug `standinfo`: where the stand is against the eyes (D21: top under the eyes, centre 10 - 20 degrees down). */
    override fun standInfo(): String {
        val slot = rig?.get(PanelSpec.STAND)?.takeIf { it.shown } ?: return "STANDINFO not shown"
        val (_, hh) = PanelSpec.halfM(PanelSpec.STAND)
        val c = slot.position
        if (!hasHead) return "STANDINFO centre=${ThLog.pos(c)} head=none"
        val eye = head.position
        val inst = ui.pose?.center
        return "STANDINFO centre=${ThLog.pos(c)} top=${ThLog.f(c.y + hh)} eye=${ThLog.f(eye.y)} top_below_eye_m=${ThLog.f(eye.y - c.y - hh)} drop_deg=${ThLog.f(StandPlacement.dropDeg(c, eye), 1)} " +
            "from_instrument=${inst?.let { ThLog.pos(c - it) } ?: "none"} offset=${ThLog.pos(standOffset)} lift=${ThLog.f(standLift ?: 0f)}"
    }

    private fun standPose(): Pair<Vec3, Quat4>? {
        val slot = rig?.get(PanelSpec.STAND)?.takeIf { it.shown } ?: return null
        return slot.position to slot.rotation
    }

    private fun rayHitsStand(ray: PointerRay): Boolean {
        val (c, rot) = standPose() ?: return false
        val n = rot.rotate(Vec3(0f, 0f, 1f))
        val denom = ray.direction.dot(n)
        if (abs(denom) < 1e-4f) return false
        val t = (c - ray.origin).dot(n) / denom
        if (t <= 0f) return false
        val local = rot.conjugate().rotate(ray.origin + ray.direction * t - c)
        val (hw, hh) = PanelSpec.halfM(PanelSpec.STAND)
        return abs(local.x) <= hw && abs(local.y) <= hh
    }

    /**
     * Moving the placed instrument at any time (v0.1.4): the controller trigger held 0.3 s with the ray on the cabinet,
     * or a pinch held 0.3 s with the palm on the cabinet ([GrabRules]: not where the hands play). It follows freely in
     * the air (the controller keeps its grab distance along the ray; the pinching hand carries it) and the release
     * sets the pose (and the anchor) exactly there: nothing snaps it to a surface (D1 "松手就停在原处", D24).
     */
    private fun dragFrame(state: PlayUiState, now: Long, dt: Float) {
        val s = controller.sample()
        val pose = state.pose ?: return
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (!dragging) {
            if (down && s != null && rayHitsCabinet(pose, s.ray) && !rayHitsStand(s.ray)) {
                if (dragTriggerMs == NEVER) dragTriggerMs = now
                if (now - dragTriggerMs >= GrabRules.HOLD_MS) {
                    grabDistance = (pose.center - s.ray.origin).dot(s.ray.direction).coerceIn(0.15f, 4f)
                    grabOffset = pose.center - (s.ray.origin + s.ray.direction * grabDistance)
                    startDrag(pose, byController = true)
                }
            } else dragTriggerMs = NEVER
            if (!dragging) pinchGrab(state, pose, now)
            return
        }
        var p = dragPose ?: pose
        if (dragByController) {
            if (!down || s == null) {
                endDrag()
                return
            }
            p = InstrumentPose(s.ray.origin + s.ray.direction * grabDistance + grabOffset, p.yaw)
            val x = controller.stickX()
            if (abs(x) > STICK_DEAD) p = InstrumentPose(p.center, p.yaw - (x - STICK_DEAD * kotlin.math.sign(x)) / (1f - STICK_DEAD) * STICK_RATE_RAD_S * dt)
        } else {
            val palm = pinchingPalm(grabHandRight)
            if (palm == null) {
                endDrag()
                return
            }
            p = InstrumentPose(grabStartCenter + (palm - grabStartPalm), p.yaw)
        }
        dragPose = p
        view?.place(p)
    }

    /** The palm of the [right] hand while it pinches, else null. */
    private fun pinchingPalm(right: Boolean): Vec3? {
        val (l, r) = hands.sample()
        val h = (if (right) r else l) ?: return null
        return if (h.pinchReady && h.pinch > PINCH_GRAB) h.palm else null
    }

    private fun pinchGrab(state: PlayUiState, pose: InstrumentPose, now: Long) {
        val rightPitch = state.settings.handed == Handed.RIGHT_PITCH
        val hand = listOf(true, false).firstOrNull { right -> pinchingPalm(right)?.let { GrabRules.grabbable(pose.toLocal(it), rightPitch) } == true }
        if (hand == null) {
            pinchSinceMs = NEVER
            return
        }
        if (pinchSinceMs == NEVER || hand != grabHandRight) {
            pinchSinceMs = now
            grabHandRight = hand
            return
        }
        if (now - pinchSinceMs < GrabRules.HOLD_MS) return
        pinchSinceMs = NEVER
        grabStartPalm = pinchingPalm(hand) ?: return
        grabStartCenter = pose.center
        startDrag(pose, byController = false)
    }

    /** Debug `move=dx,dy,dz`: a drag by that offset (metres), ending like a real one (QA without hands). */
    override fun debugMove(dx: Float, dy: Float, dz: Float): String {
        val pose = ui.pose ?: return "MOVE not placed"
        startDrag(pose, byController = false)
        dragPose = InstrumentPose(pose.center + Vec3(dx, dy, dz), pose.yaw)
        endDrag()
        return "MOVE to=${ThLog.pos(dragPose!!.center)}"
    }

    private fun startDrag(pose: InstrumentPose, byController: Boolean) {
        dragging = true
        dragByController = byController
        dragPose = pose
        mapper.suspended = true
        ThLog.i("DRAG start controller=$byController")
        sink(PlayEvent.DragChanged(true, null))
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        dragTriggerMs = NEVER
        mapper.suspended = false
        val final = dragPose ?: return
        ThLog.i("DRAG end at=${ThLog.pos(final.center)} yaw=${ThLog.f(Math.toDegrees(final.yaw.toDouble()).toFloat(), 1)}")
        sink(PlayEvent.DragChanged(false, final))
    }

    private fun rayHitsCabinet(pose: InstrumentPose, ray: PointerRay): Boolean {
        val o = pose.toLocal(ray.origin)
        val d = pose.rotation.conjugate().rotate(ray.direction)
        val c = Instrument.CABINET
        val center = Vec3(0f, Instrument.CABINET_BOTTOM_Y + c.y / 2f, 0f)
        val h = Vec3(c.x / 2 + 0.02f, c.y / 2 + 0.02f, c.z / 2 + 0.02f)
        var t0 = 0f
        var t1 = 10f
        val os = floatArrayOf(o.x - center.x, o.y - center.y, o.z - center.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        val hs = floatArrayOf(h.x, h.y, h.z)
        for (k in 0..2) {
            if (abs(ds[k]) < 1e-6f) {
                if (abs(os[k]) > hs[k]) return false
            } else {
                var a = (-hs[k] - os[k]) / ds[k]
                var b = (hs[k] - os[k]) / ds[k]
                if (a > b) a = b.also { b = a }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return false
            }
        }
        return true
    }

    /** Bead, hand tag, column, ripple highlights, target pillar, antenna glow, fade near the head. */
    private fun drawFrame(state: PlayUiState, f: PlayFrame, now: Long) {
        val v = view ?: return
        val pose = (if (dragging) dragPose else null) ?: state.pose ?: return
        val headLocal = if (hasHead) pose.toLocal(head.position) else null
        headLocal?.let { v.faceLabels(it) }
        val playing = state.playable
        val scale = state.settings.scale
        val inRange = { n: Int -> n in 0..scale.top }
        val nearest = Instrument.nearest(f.s).takeIf(inRange)
        val hold = vm.hold
        val scorer = vm.scorer
        var targetN: Int? = null
        var nextN: Int? = null
        var pillarLeft = 1f
        var holdShare: Float? = null
        var cents = Instrument.cents(f.s)
        when {
            state.page == Page.PRACTICE && hold != null -> {
                targetN = hold.target
                nextN = hold.next
                holdShare = hold.progress
                targetN?.let { cents = Instrument.centsFrom(f.s, it) }
            }
            state.page == Page.SONG && scorer != null -> {
                val i = scorer.current
                val song = scorer.song
                if (i != null) {
                    targetN = song.notes[i].semitone
                    val into = scorer.beat - song.starts[i]
                    pillarLeft = 1f - into / song.notes[i].beats
                    if (song.notes[i].beats - into <= 1f) nextN = song.notes.getOrNull(i + 1)?.semitone
                    cents = Instrument.centsFrom(f.s, targetN)
                } else if (scorer.beat < 0) {
                    nextN = song.notes.firstOrNull()?.semitone
                }
            }
            state.page == Page.ONBOARDING && state.onboardStep == 2 -> targetN = 0
        }
        val wave = if (waveStartMs != NEVER) ((now - waveStartMs) / WAVE_MS).let { if (it > 1f) { waveStartMs = NEVER; null } else it } else null
        val showHand = playing && f.pitchKnown
        v.highlight(if (showHand) nearest else null, targetN?.takeIf(inRange), nextN?.takeIf { inRange(it) && it != targetN }, wave)
        v.pillar(targetN, pillarLeft)
        v.dimRipples(if (state.handsLost || targetN != null) 0.4f / 0.7f else 1f)
        v.bead(if (showHand) f.point else null, f.d)
        v.tag(if (showHand) f.point else null, nearest ?: Instrument.nearest(f.s), cents, holdShare, headLocal) { n -> tagMaterial(n, state) }
        v.column(if (playing) f.amplitude else 0f, if (playing) f.h else null)
        v.glow(if (playing) f.amplitude * (if (state.settings.muted) 0f else 1f) else 0f)
        // the head inside 0.20 m of any part: the instrument fades to 30 % in 0.2 s (design C2)
        val isNear = headLocal != null && nearDistance(headLocal, state.settings.handed) < NEAR_M
        if (isNear != near) {
            near = isNear
            sink(PlayEvent.HeadNear(isNear))
        }
        // D20: the instrument never hides the stand's controls: inside the eye -> stand cone it fades too
        val inCone = hasHead && inPanelCone(state, pose)
        if (inCone != coneLogged) {
            coneLogged = inCone
            ThLog.i("CONE instrument in the view cone of the stand: $inCone")
        }
        val want = if (isNear || inCone) 0.3f else 1f
        if (abs(opacity - want) > 0.001f) {
            opacity += (want - opacity).coerceIn(-0.12f, 0.12f)
            v.root.setOpacity(opacity)
        }
    }

    private var coneLogged = false

    /**
     * Visibility only (taste D20): does a key point of the instrument (cabinet top, antenna top / middle, loop, column
     * top) lie between the eye and the music stand, inside the stand's rectangle as seen from the eye?
     */
    private fun inPanelCone(state: PlayUiState, pose: InstrumentPose): Boolean {
        val h = state.settings.handed
        val (c, rot) = standPose() ?: return false
        val (hw, hh) = PanelSpec.halfM(PanelSpec.STAND)
        val eye = head.position
        val n = rot.rotate(Vec3(0f, 0f, 1f))
        val ax = Instrument.antennaX(h)
        val points = listOf(
            Vec3(0f, Instrument.CABINET_BOTTOM_Y + Instrument.CABINET.y, 0f),
            Vec3(ax, Instrument.ANTENNA_TOP_Y, 0f),
            Vec3(ax, (Instrument.ANTENNA_BASE_Y + Instrument.ANTENNA_TOP_Y) / 2f, 0f),
            Instrument.loopCenter(h),
            Instrument.loopCenter(h).let { Vec3(it.x, Instrument.COLUMN_TOP_Y, it.z) },
        ).map { pose.toStage(it) }
        val toPanel = (c - eye).dot(n)
        if (abs(toPanel) < 1e-4f) return false
        return points.any { p ->
            val dir = p - eye
            val denom = dir.dot(n)
            if (abs(denom) < 1e-5f) return@any false
            // the eye -> point ray reaches the panel plane beyond the point (t > 1): the point is in front of the panel
            val t = toPanel / denom
            if (t <= 1f) return@any false
            val local = rot.conjugate().rotate(eye + dir * t - c)
            abs(local.x) <= hw && abs(local.y) <= hh
        }
    }

    private fun nearDistance(h: Vec3, handed: Handed): Float {
        val c = Instrument.CABINET
        val dx = (abs(h.x) - c.x / 2).coerceAtLeast(0f)
        val dy = (abs(h.y - 0.055f) - c.y / 2).coerceAtLeast(0f)
        val dz = (abs(h.z) - c.z / 2).coerceAtLeast(0f)
        val cab = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val ax = Instrument.antennaX(handed)
        val ay = h.y.coerceIn(Instrument.ANTENNA_BASE_Y, Instrument.ANTENNA_TOP_Y)
        val ant = Vec3(h.x - ax, h.y - ay, h.z).length
        val loop = h.distanceTo(Instrument.loopCenter(handed)) - Instrument.LOOP_RADIUS
        return minOf(cab, ant, loop)
    }

    private fun tagMaterial(n: Int, state: PlayUiState): Material? {
        val key = "$n|${state.english}|${state.settings.octave}|$labelTest"
        tagMaterials[key]?.let { return it }
        val m = materials ?: return null
        val sh = shared ?: return null
        val (main, sub) = LabelArt.noteText(n, state.english, state.settings.octave)
        val tex = runCatching { sh.keep(TextureResource.create(LabelArt.label(main, sub, labelTest))) }.getOrNull() ?: return null
        // kept for the scene's lifetime: the engine closes a material once no entity uses it, and the hand tag swaps
        // between these (QA q1-1 / q1-3: "closed UnlitMaterial" crash when a note came back)
        return sh.keep(m.textured(tex)).also { tagMaterials[key] = it }
    }

    /** Lesson / song done: the ripples light up from the inside out once (0.6 s). */
    fun wave() {
        waveStartMs = SystemClock.elapsedRealtime()
    }

    fun audio(): ThereminAudio? = audio

    // =========================================================================================
    // system pointer (gaze + pinch, emulator mouse)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        ThLog.i("TAP target=$name stage=${ThLog.pos(stage)}")
        if (stage == null) return
        // marker mode: gaze + pinch on the app's floor pad sets the marker (taste D1); nothing else is tappable here
        if (markerMode(ui) && name == "floorPad") setMarker(stage, "tap")
    }

    /** Debug `marker=floor` (and the old `marker=desk`): the marker 1 m ahead of the user's side, 0.4 m to the right (QA). */
    override fun debugMarker(kind: String): String {
        val ref = userRef() ?: return "MARKER no reference yet"
        val slot = rig?.get(if (ui.placed) PanelSpec.STAND else PanelSpec.PLACE)
        val toUser = (slot?.rotation?.rotate(Vec3(0f, 0f, 1f)) ?: Vec3(0f, 0f, 1f)).let { Vec3(it.x, 0f, it.z).normalized() }
        val point = Vec3(ref.x, PlaceRules.FLOOR_Y, ref.z) - toUser * 1.0f + Vec3(-toUser.z, 0f, toUser.x) * 0.4f
        setMarker(point, "debug")
        return "MARKER debug $kind at=${ThLog.pos(point)}"
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { ThLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    private var labelTest = false

    fun pitchInfoLog(f: PlayFrame = mapper.last, periodic: Boolean = false): String {
        val line =
            String.format(
                Locale.US,
                "PITCH d=%.4f s=%.3f note=%s cents=%+.1f f=%.2f v=%.3f h=%s src=%s/%s frozen=%b lost=%b/%b buffer_ms=%.1f hand_age_ms=%s state=%s",
                f.d, f.s, Instrument.letter(Instrument.nearest(f.s), ui.settings.octave), Instrument.cents(f.s), f.frequency, f.volume,
                f.h?.let { String.format(Locale.US, "%.3f", it) } ?: "null", f.pitchSrc, f.volumeSrc, f.frozen, f.pitchLost, f.volumeLost,
                audio?.lastBufferMs ?: -1f, hands.ageMs(java.lang.System.nanoTime())?.toString() ?: "na", ui.stateId(),
            )
        if (!periodic || f.amplitude > 0f) ThLog.i(line)
        return line
    }

    override fun poseLine(): String = "POSE hmd pos=${ThLog.pos5(head.position)} ${ThLog.quat5(head.rotation)} fwd=${ThLog.pos5(head.forward)} valid=$hasHead pose=${ui.pose?.let { ThLog.pos(it.center) }}"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s tilt=%.2f scale=%.3f size_dp=%.0fx%.0f", slot.id, ThLog.pos5(slot.position), ThLog.quat5(slot.rotation), PlaceUpright.tiltDeg(slot.rotation), slot.scale, slot.widthDp, slot.heightDp)
        }
    }

    /** D24 audit line: what of the real room the scene uses (nothing) and which colliders exist (the app's own). */
    override fun realitySummary(): List<String> =
        listOf(
            "REALITY planes=off mesh=off occlusion=none restRule=off deskLanding=off",
            "COLLIDERS floorPad(enabled=$floorPadOn, app floor y=${PlaceRules.FLOOR_Y}) instrumentTapBox=${view != null} realSurfaces=0",
            "MARKER ${marker?.let { "set at=${ThLog.pos(it.point)}" } ?: "none"} mode=${markerMode(ui)}",
        )

    override fun parkPanel(): String {
        if (!hasHead) return "FOLLOW no valid head yet"
        follower.park(head, SystemClock.elapsedRealtime())
        return "FOLLOW place park requested"
    }

    override fun dragPanelDebug(dxDp: Float, dyDp: Float): String {
        debugGrip(PanelSpec.PLACE, dxDp, dyDp)
        return "PANEL drag by ${ThLog.f(dxDp)},${ThLog.f(dyDp)} dp -> ${ThLog.pos(follower.pose.position)}"
    }

    override fun recenter(): String {
        follower.reset()
        return "RECENTER follower reset"
    }

    override fun pitchInfo(): String = pitchInfoLog()

    override fun record(seconds: Float): String {
        val a = audio ?: return "AUDIO none"
        a.record(seconds)
        return "AUDIO recording"
    }

    override fun restartAudio(spatial: Boolean): String {
        val ctx = ThGraph.appContext ?: return "AUDIO no context"
        audio?.release()
        audio = ThereminAudio(ctx, mapper, hands, controller).also { it.start(if (spatial) view?.antenna else null) }
        syncAudio(ui)
        return "AUDIO restarted spatial=$spatial"
    }

    override fun setLabelTest(on: Boolean) {
        labelTest = on
        render(ui)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val FRAME_MS = 20L
        private const val FAST_FRAME_MS = 40L
        private const val SLOW_STEP_MS = 600L
        private const val REBIND_DELAY_MS = 150L
        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE_RAD_S = 1.5708f
        private const val PINCH_GRAB = 0.8f
        private const val ANCHOR_SETTLE_MS = 400L
        /** The user sits about this far in front of the panel they read (reference point, not a head pose). */
        private const val PANEL_TO_USER_M = 0.6f
        private const val NEAR_M = 0.20f
        private const val WAVE_MS = 600f
        /** The app's own floor tap pad, metres across (centred on the Stage origin). */
        private const val FLOOR_PAD_M = 30f

        /** 15 degrees to the viewer's left (design `panels.placePanel`); distance and drop from [PlaceUpright] (D9). */
        private const val PLACE_BEARING_DEG = -15f

        /** q3-1 (D1): the cold-start timings, all from the process start. */
        private val LAYOUT_MARKS_MS = longArrayOf(1000L, 3000L, 5000L)
        private const val HEAD_WAIT_MS = 1200L
        private const val DRAG_LOG_MS = 1500L
        const val GRAB_PREFIX = "grab:"

        /** Grab collider: over the 48 dp bar row (its centre 16 + 24 dp under the panel's top edge), 0.20 x 0.04 m, 1 cm in front. */
        private const val GRAB_FROM_TOP_M = 40f * PanelRig.DP_M
        private const val GRAB_W_M = 0.20f
        private const val GRAB_H_M = 0.04f
        private const val GRAB_FRONT_M = 0.01f
        private const val PANEL_LEAD_MS = 700L
        private const val PANEL_LEAD_FRAMES = 4L
        private const val PANEL_WAIT_MAX_MS = 6000L

        /** The Stage's default eye pose (standing, looking down -Z): only until the first valid head arrives. */
        private val DEFAULT_HEAD = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    }
}
