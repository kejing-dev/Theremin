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
import io.embodit.theremin.domain.model.PlaneSurface
import io.embodit.theremin.domain.model.PointerRay
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.SurfaceKind
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.domain.play.MapSettings
import io.embodit.theremin.domain.play.PlayFrame
import io.embodit.theremin.domain.play.RoleInputs
import io.embodit.theremin.domain.usecase.Desk
import io.embodit.theremin.domain.usecase.DeskRules
import io.embodit.theremin.domain.usecase.SnapFrame
import io.embodit.theremin.platform.ThGraph
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.platform.audio.ThereminAudio
import io.embodit.theremin.platform.input.HandInput
import io.embodit.theremin.platform.sense.PlaneTracker
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
import kotlin.math.exp
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
 * The 3D side: the instrument, the placing ghost and snap frame, the panel pool, the sound. Compose state
 * arrives through [render] (idempotent); per-frame work — placing, dragging, the bead / tag / column / ripple
 * highlights drawn from the mapper's last frame, the session tick — happens in [onFrame] only. The hand ->
 * sound path does not pass through here: the audio thread maps and plays by itself (design
 * `audio.engine`). Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class PlayScene(private val vm: PlayViewModel, private val sink: (PlayEvent) -> Unit) : ThProbe.SceneProbe {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planes = ThGraph.planes
    private val headTracker = ThGraph.head
    private val controller = ThGraph.controller
    private val hands = ThGraph.hands
    private val mapper = ThGraph.mapper

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var shared: SharedResources? = null
    private var materials: SceneMaterials? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var view: InstrumentView? = null
    private var ghost: GhostView? = null
    private var snapView: SnapFrameView? = null
    private var markerView: MarkerView? = null
    private var audio: ThereminAudio? = null
    private var attached = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null
    private var rebuildJob: Job? = null
    private val tagMaterials = HashMap<String, Material>()

    private val follower = Follower("place", PLACE_OFFSET, listOf(0.17f, 0.10f, 0.01f), Follower.MAIN_LEAN)

    private var ui = PlayUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastFrameMs = NEVER
    private var frameGapMs = 0L

    // ---- desks and placing
    private var desks: List<Desk> = emptyList()
    private var desksKey = ""
    private var seededDesk: PlaneSurface? = null
    private var frame: SnapFrame? = null
    private var ghostPose: InstrumentPose? = null
    private var ghostYaw = 0f
    private var userTurned = false
    private var held = false
    private var reportedSnap = false
    private var stickRate = 0f
    private var turnSeen = 0
    private var frontSeen = 0
    private var turnFrom = 0f
    private var turnTo = 0f
    private var turnStartMs = NEVER
    private var triggerDown = false
    private var planesFailed = false
    private var reportedDesks: Boolean? = null
    private var placedAtMs = NEVER

    // ---- the user's marker (v0.1.2, taste D1): where the instrument goes on "移到标记处"
    private class Marker(val pose: InstrumentPose, val onStand: Boolean, val point: Vec3)

    private var marker: Marker? = null
    private var markerTriggerDown = false

    // ---- drag of a standing instrument
    private var dragging = false
    private var dragPose: InstrumentPose? = null
    private var dragTriggerMs = NEVER
    private var dragByController = false

    // ---- look
    private var opacity = 1f
    private var near = false
    private var waveStartMs = NEVER

    /** The place panel's handle dragged by ([dxDp], [dyDp]) in the panel's own plane (y down, Compose). */
    fun dragPanel(dxDp: Float, dyDp: Float) {
        val slot = rig?.get(PanelSpec.PLACE) ?: return
        val r = slot.rotation.rotate(Vec3.RIGHT)
        val u = slot.rotation.rotate(Vec3.UP)
        follower.moveBy(r * (dxDp * PanelRig.DP_M) - u * (dyDp * PanelRig.DP_M))
    }

    /** "叫回面前": the place panel slides back to its designed place in front of the user (a UI recall, D21). */
    fun recallPanel() {
        ThLog.i("PANEL recall")
        follower.park(head, planes.all, SystemClock.elapsedRealtime())
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
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h).autoFace = false
        }
        headTracker.start()
        // the listener goes on before the provider starts: the stick turns the ghost from the first moment
        controller.start()
        mapper.injectSource = ThProbe.enabled && HandInput.onEmulator
        ActiveScene.scene = this
        bindPanels(attachments)
        follower.reset()
        syncPlanes(ui)
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
    }

    private suspend fun build(root: Entity) {
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        frame()
        colliders = PlaneColliders(root).also { c -> planes.all.forEach(c::upsert) }
        frame()
        ghost = GhostView(root, m, sh).also { it.build(ui.settings.handed) }
        snapView = SnapFrameView(root, m, sh)
        markerView = MarkerView(root, m, sh)
        frame()
        buildInstrument(root, m, sh, layoutOf(ui))
        attached = true
        startAudio()
        ThLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(PlayEvent.SceneReady)
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
        mapper.pose = null
        mapper.armed = false
        planes.stop()
        headTracker.stop()
        controller.stop()
        hands.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        view?.destroy()
        ghost?.destroy()
        snapView?.destroy()
        markerView?.destroy()
        markerView = null
        marker = null
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        tagMaterials.clear()
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        view = null
        ghost = null
        snapView = null
        hasHead = false
        ThLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: PlayUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        syncPlanes(state)
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
            ghost?.build(state.settings.handed)
        }
        if (!dragging) {
            state.pose?.let { if (force || it != previous.pose) v.place(it) }
            mapper.pose = if (state.placed) state.pose else null
        }
        v.setShown(state.placed)
        v.setStand(state.onStand)
        mapper.armed = state.placed && !state.background
        if (state.placed && !previous.placed) {
            placedAtMs = SystemClock.elapsedRealtime()
            mapper.resetTracking()
            if (!state.background) hands.start()
        }
        if (!state.placed && previous.placed) hands.stop()
        if (state.room == Room.PLACING && previous.room != Room.PLACING) resetGhost()
        if (state.turnSerial != turnSeen) {
            turnSeen = state.turnSerial
            if (state.room == Room.PLACING) {
                userTurned = true
                turnFrom = ghostYaw
                turnTo = ghostYaw - Math.toRadians(15.0).toFloat()
                turnStartMs = SystemClock.elapsedRealtime()
            }
        }
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
     * "移到标记处" / the 5 s no-desk timeout (v0.1.2, taste D1 / D20): the instrument goes to the user's marker. With no
     * marker yet and nothing placed, it waits beside and beyond the place panel (never between the user and the panel,
     * never from the head pose) and the hint asks for a marker. Placed and no marker: only the hint.
     */
    private fun moveToMarker(force: Boolean) {
        val mk = marker
        when {
            mk != null -> {
                marker = null
                markerView?.setAt(null)
                // v0.1.3: turned to where the user stands now, once, then locked (taste D1 / D10)
                val pose = markerFor(mk.point, userAt())?.pose ?: mk.pose
                ThLog.i("MARKER move to=${ThLog.pos(pose.center)} yaw=${ThLog.f(Math.toDegrees(pose.yaw.toDouble()).toFloat(), 1)} user=${userAt()?.let { ThLog.pos(it) }} stand=${mk.onStand}")
                sink(PlayEvent.PlaceRequested(pose, mk.onStand))
            }
            !ui.placed -> {
                val panel = rig?.get(PanelSpec.PLACE)
                if (panel == null || !panel.shown) {
                    if (!force) main.postDelayed({ moveToMarker(false) }, 200)
                    return
                }
                val pose = DeskRules.besidePanel(panel.position, panel.rotation.rotate(Vec3(0f, 0f, 1f)), DeskRules.floorY(planes.all), userAt())
                ThLog.i("MARKER none -> waiting anchor ${ThLog.pos(pose.center)} beside panel ${ThLog.pos(panel.position)}")
                sink(PlayEvent.PlaceRequested(pose, onStand = true, waiting = true))
            }
            else -> sink(PlayEvent.NoMarker)
        }
    }

    /**
     * Where the user is, for choosing a desk edge and turning the instrument towards them, never the head pose: the
     * controller (ray origin) when one is held, else the panel the user is reading (place panel before placing, the
     * music stand afterwards), carried 0.6 m towards the user.
     */
    private fun userRef(): Vec3? {
        controller.sample()?.let { return it.ray.origin }
        val rig = rig ?: return null
        val slot = if (ui.placed) rig[PanelSpec.STAND] else rig[PanelSpec.PLACE]
        return slot?.takeIf { it.shown }?.let { it.position + it.rotation.rotate(Vec3(0f, 0f, PANEL_TO_USER_M)) }
    }

    /**
     * Where the user stands, for turning the instrument (and choosing the desk edge) only, never for where it goes
     * (v0.1.3): the head position, level; else the controller; else [userRef]. Read once when placing, then the
     * pose is locked. [userRef] sits beside the panel, so a marker nearer than the panel turned the front away.
     */
    private fun userAt(): Vec3? {
        if (hasHead && head.position.length > 1e-3f) return head.position
        return controller.sample()?.ray?.origin ?: userRef()
    }

    /** Marker mode: before the instrument stands somewhere the user chose, and on the More page ("移到标记处"). */
    private fun markerMode(state: PlayUiState): Boolean =
        (!state.placed && state.room != Room.PLACING) || state.awaitingMarker || state.markerReady || state.page == Page.MENU

    /** A surface point -> where the instrument would stand: on a desk (snapped to its frame if near), or on the floor stand. */
    private fun markerFor(point: Vec3, face: Vec3?): Marker? {
        val handed = ui.settings.handed
        desks.firstOrNull { abs(it.top - point.y) < 0.05f && DeskRules.inside(it, point) }?.let { d ->
            val p = Vec3(point.x, d.top, point.z)
            val ref = face ?: p
            val f = DeskRules.frame(d, ref, handed)
            val yaw = f?.yaw ?: DeskRules.yawTowards(p, ref)
            val pose = if (f != null && DeskRules.snap(p, yaw, f, false)) InstrumentPose(f.center, f.yaw) else InstrumentPose(p, yaw)
            return Marker(pose, false, p)
        }
        val fy = DeskRules.floorY(planes.all) ?: return null
        if (abs(point.y - fy) > 0.08f) return null
        val p = Vec3(point.x, fy, point.z)
        return Marker(DeskRules.onFloor(p, fy, face ?: (p + Vec3(0f, 0f, 1f))), true, p)
    }

    private fun setMarker(mk: Marker, how: String) {
        marker = mk
        markerView?.setAt(mk.point)
        ThLog.i("MARKER set by=$how at=${ThLog.pos(mk.point)} pose=${ThLog.pos(mk.pose.center)} stand=${mk.onStand}")
        sink(PlayEvent.MarkerSet)
    }

    /** Marker mode, per frame: the controller ray's floor / desk point shows the aim ring; the trigger sets the marker. */
    private fun markerFrame(state: PlayUiState) {
        val mv = markerView ?: return
        if (!markerMode(state)) {
            mv.aimAt(null)
            markerTriggerDown = false
            return
        }
        val s = controller.sample()
        val ray = s?.ray
        var point: Vec3? = null
        if (ray != null) {
            point = DeskRules.hit(desks, ray.origin, ray.direction)?.second
            val fy = DeskRules.floorY(planes.all)
            if (point == null && fy != null && ray.direction.y < -1e-3f) {
                val t = (fy - ray.origin.y) / ray.direction.y
                if (t in 0.2f..6f) point = ray.origin + ray.direction * t
            }
        }
        mv.aimAt(point)
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (down && !markerTriggerDown && point != null && ray != null && !rayHitsStand(state, ray)) {
            markerFor(point, userAt())?.let { setMarker(it, "trigger") }
        }
        markerTriggerDown = down
    }

    private fun syncPlanes(state: PlayUiState) {
        if (stageRoot == null) return
        // plane detection runs while placing (and 10 s after), never in the background
        val now = SystemClock.elapsedRealtime()
        val want = !state.background && (!state.placed || state.dragging || (placedAtMs != NEVER && now - placedAtMs < PLANES_AFTER_MS))
        if (want && !planes.running) {
            val ok = planes.start(PlaneTracker.Listener { id, surface, _ -> if (surface != null) colliders?.upsert(surface) else colliders?.remove(id) })
            if (!ok && !planesFailed) {
                planesFailed = true
                sink(PlayEvent.PlaneTrackingFailed)
            }
        } else if (!want && planes.running) {
            planes.pause()
        }
    }

    private var downSent = false

    private fun resetGhost() {
        downSent = false
        ghostPose = null
        userTurned = false
        held = false
        turnStartMs = NEVER
        stickRate = 0f
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
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        if (!state.placed && hasHead && head.position.length > 1e-3f) {
            // D21 (v0.1.3): set once where it first appears, then fixed in the world; moved only by its handle or 叫回面前
            follower.follow(head, planes.all, planes.count, now)
            rig[PanelSpec.PLACE]?.place(follower.pose.position, follower.rotation, 1f)
        }
        if (!attached) return
        val hs = hands.pollSupport()
        val cs = controller.pollSupport()
        syncPlanes(state)
        refreshDesks(state)
        markerFrame(state)
        when (state.room) {
            Room.PLACING -> placingFrame(state, now, dt)
            else -> {
                ghost?.setShown(false)
                snapView?.show(null, false, state.settings.handed)
            }
        }
        if (state.placed) {
            placeStand(state, rig)
            dragFrame(state, now, dt)
            // without audio the frame system drives the mapper itself (the draw still needs it)
            val f = if (audio?.running == true) mapper.last else mapper.step(java.lang.System.nanoTime(), fallbackInputs(state))
            drawFrame(state, f, now)
            val sourcesOk = hs == DataProvider.SupportState.SUPPORTED || cs == DataProvider.SupportState.SUPPORTED || (!hands.started && !controller.started)
            vm.tick(Tick(now, dt, f, sourcesOk))
            controller.sample()?.let { s -> mapper.triggerOnPanel = rayHitsStand(state, s.ray) } ?: run { mapper.triggerOnPanel = false }
            if (now % 1000L < 16L && ThProbe.enabled) pitchInfoLog(f, periodic = true)
        }
    }

    private fun fallbackInputs(state: PlayUiState): RoleInputs {
        val (lh, rh) = hands.sample()
        val (lc, rc) = controller.hands()
        val right = state.settings.handed == Handed.RIGHT_PITCH
        return RoleInputs(if (right) rh else lh, if (right) lh else rh, if (right) rc else lc, if (right) lc else rc)
    }

    /** Desks from the planes (a seeded debug desk joins them; `desk=none` hides every desk). */
    private fun refreshDesks(state: PlayUiState) {
        val ref = userRef()
        val cell = if (ref != null) "${(ref.x * 2).toInt()},${(ref.z * 2).toInt()}" else "-"
        val key = "${planes.count}|${state.deskMode}|${seededDesk != null}|$cell"
        if (key == desksKey) return
        desksKey = key
        if (state.deskMode == "seed" && seededDesk == null && ref != null) seedDesk()
        val all = planes.all.toMutableList().also { l -> seededDesk?.let { l += it } }
        desks =
            if (state.deskMode == "none" || ref == null) emptyList()
            // the panel sits ~0.25 m under the eyes: the no-floor desk rule (top 0.15 - 1.0 m under the head) shifts by that
            else DeskRules.near(DeskRules.desks(all, ref.y + 0.25f), ref)
        ThLog.i("DESKS n=${desks.size} planes=${planes.count} mode=${state.deskMode} ${desks.joinToString { "${it.kind}@${ThLog.f(it.top)}" }}")
        val found = desks.isNotEmpty()
        if (found != reportedDesks) {
            reportedDesks = found
            sink(PlayEvent.DesksChanged(found))
        }
    }

    /** Debug `desk=seed`: a 1.2 x 0.7 m table top 0.35 m under the place panel, reaching from just behind it away from the user. */
    private fun seedDesk() {
        val panel = rig?.get(PanelSpec.PLACE)?.takeIf { it.shown } ?: return
        val toUser = panel.rotation.rotate(Vec3(0f, 0f, 1f)).let { Vec3(it.x, 0f, it.z).normalized() }
        val f = toUser * -1f
        val r = Vec3(-f.z, 0f, f.x)
        val y = panel.position.y - 0.35f
        val c = Vec3(panel.position.x, y, panel.position.z) + f * 0.10f
        val v = listOf(c - r * 0.6f - f * 0.35f, c + r * 0.6f - f * 0.35f, c + r * 0.6f + f * 0.35f, c - r * 0.6f + f * 0.35f)
        val p = PlaneSurface("seed-desk", SurfaceKind.TABLE, "TABLE", v, listOf(0, 1, 2, 0, 2, 3))
        seededDesk = p
        colliders?.upsert(p)
        ThLog.i("DESK seeded top=${ThLog.f(y)} center=${ThLog.pos(c)}")
    }

    private fun pointerRay(): PointerRay? = controller.sample()?.ray

    /**
     * The ghost (S04 / S05): follows the controller ray's desk point (aiming never uses the head's direction,
     * taste D1; with gaze + pinch the tap itself places it). Without a pointer it waits on the snap frame. It
     * faces the user until turned (stick dead zone 0.15, 90 degrees/s, smoothed 0.15 s; [turn 15]); it snaps to
     * the desk-edge frame with hysteresis.
     */
    private fun placingFrame(state: PlayUiState, now: Long, dt: Float) {
        val g = ghost ?: return
        val desk = desks.firstOrNull()
        val ref = userAt()
        if (desk == null || ref == null) {
            g.setShown(false)
            snapView?.show(null, false, state.settings.handed)
            return
        }
        frame = desks.mapNotNull { DeskRules.frame(it, ref, state.settings.handed) }.firstOrNull()
        val f = frame
        val ray = pointerRay()
        val hit = ray?.let { DeskRules.hit(desks, it.origin, it.direction) }
        val target =
            when {
                state.placeMode == "ghost" && f != null -> f.center + Vec3(-0.30f, 0f, 0f).let { f.let { fr -> InstrumentPose(fr.center, fr.yaw).dirToStage(Vec3(0.30f, 0f, 0.0f)) } }
                hit != null -> hit.second
                ghostPose != null -> ghostPose!!.center
                f != null -> f.center
                else -> desk.centroid
            }
        val c0 = ghostPose?.center
        val center = if (c0 == null || dt <= 0f) target else c0.lerp(target, 1f - exp(-dt / FOLLOW_TAU_S))
        // stick and turn 15
        val x = controller.stickX()
        val want = if (abs(x) > STICK_DEAD) -(x - STICK_DEAD * kotlin.math.sign(x)) / (1f - STICK_DEAD) * STICK_RATE_RAD_S else 0f
        stickRate += (want - stickRate) * (if (dt > 0f) 1f - exp(-dt / STICK_TAU_S) else 0f)
        if (abs(stickRate) > 0.01f) {
            if (!userTurned) ThLog.i("STICK start")
            userTurned = true
            ghostYaw += stickRate * dt
        } else if (want == 0f) stickRate = 0f
        if (turnStartMs != NEVER) {
            val q = ((now - turnStartMs) / TURN_MS).coerceIn(0f, 1f)
            ghostYaw = turnFrom + (turnTo - turnFrom) * Ease.inOutCubic(q)
            if (q >= 1f) turnStartMs = NEVER
        }
        if (!userTurned) ghostYaw = f?.yaw ?: DeskRules.yawTowards(center, ref)
        val snapNow = state.placeMode == "snap" || DeskRules.snap(center, ghostYaw, f, held)
        if (snapNow != held) ThLog.i("SNAP ${if (snapNow) "on" else "off"}")
        held = snapNow
        val wanted = if (snapNow && f != null) InstrumentPose(f.center, f.yaw) else InstrumentPose(Vec3(center.x, desk.top, center.z), ghostYaw)
        val prev = ghostPose
        ghostPose =
            if (prev == null || dt <= 0f || state.freeze) wanted
            else {
                val k = 1f - exp(-dt / SNAP_TAU_S)
                InstrumentPose(prev.center.lerp(wanted.center, k), prev.yaw + DeskRules.angle(wanted.yaw, prev.yaw) * k)
            }
        g.place(ghostPose!!)
        g.setShown(true)
        g.setBright(snapNow)
        snapView?.show(f?.let { InstrumentPose(it.center, it.yaw) }, snapNow, state.settings.handed)
        if (snapNow != reportedSnap) {
            reportedSnap = snapNow
            sink(PlayEvent.GhostChanged(snapNow))
        }
        // debug `place=down`: put the ghost down where it is
        if (state.placeMode == "down" && !downSent) {
            downSent = true
            ThLog.i("PLACE debug down")
            sink(PlayEvent.PlaceRequested(ghostPose!!, false))
            return
        }
        // the trigger's press edge puts it down (controller ray on a desk)
        val s = controller.sample()
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (down && !triggerDown && hit != null) {
            ThLog.i("TAP trigger place")
            sink(PlayEvent.PlaceRequested(ghostPose!!, false))
        }
        triggerDown = down
    }

    /** The stand panel rides on the instrument: (-0.03, 0.42, -0.13), leaning back 12 degrees, facing the player. */
    private fun placeStand(state: PlayUiState, rig: PanelRig) {
        val pose = (if (dragging) dragPose else null) ?: state.pose ?: return
        val c = Instrument.standCenter(state.settings.handed)
        rig[PanelSpec.STAND]?.place(pose.toStage(c), Instrument.standRotation(pose), 1f)
    }

    private fun rayHitsStand(state: PlayUiState, ray: PointerRay): Boolean {
        val pose = state.pose ?: return false
        val c = pose.toStage(Instrument.standCenter(state.settings.handed))
        val rot = Instrument.standRotation(pose)
        val n = rot.rotate(Vec3(0f, 0f, 1f))
        val denom = ray.direction.dot(n)
        if (abs(denom) < 1e-4f) return false
        val t = (c - ray.origin).dot(n) / denom
        if (t <= 0f) return false
        val local = rot.conjugate().rotate(ray.origin + ray.direction * t - c)
        val (hw, hh) = PanelSpec.halfM(PanelSpec.STAND)
        return abs(local.x) <= hw && abs(local.y) <= hh
    }

    /** Controller drag: trigger held 0.5 s with the ray on the cabinet; the instrument follows the ray on its desk height. */
    private fun dragFrame(state: PlayUiState, now: Long, dt: Float) {
        val s = controller.sample()
        val pose = state.pose ?: return
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (!dragging) {
            if (down && s != null && rayHitsCabinet(pose, s.ray)) {
                if (dragTriggerMs == NEVER) dragTriggerMs = now
                if (now - dragTriggerMs >= DRAG_HOLD_MS) startDrag(pose, byController = true)
            } else dragTriggerMs = NEVER
            return
        }
        var p = dragPose ?: pose
        if (dragByController) {
            if (!down || s == null) {
                endDrag()
                return
            }
            val y = p.center.y
            if (abs(s.ray.direction.y) > 1e-3f) {
                val t = (y - s.ray.origin.y) / s.ray.direction.y
                if (t in 0.1f..4f) p = InstrumentPose((s.ray.origin + s.ray.direction * t).let { Vec3(it.x, y, it.z) }, p.yaw)
            }
        }
        val x = controller.stickX()
        if (abs(x) > STICK_DEAD) p = InstrumentPose(p.center, p.yaw - (x - STICK_DEAD * kotlin.math.sign(x)) / (1f - STICK_DEAD) * STICK_RATE_RAD_S * dt)
        // the desk edge still snaps
        val f = userAt()?.let { ref -> desks.mapNotNull { DeskRules.frame(it, ref, state.settings.handed) }.firstOrNull() }
        val snapped = f != null && abs(f.center.y - p.center.y) < 0.05f && DeskRules.snap(p.center, p.yaw, f, held)
        held = snapped
        dragPose = p
        val shown = if (snapped && f != null) InstrumentPose(f.center, f.yaw) else p
        view?.place(shown)
        ghost?.place(shown)
        ghost?.setShown(true)
        ghost?.setBright(snapped)
    }

    private fun startDrag(pose: InstrumentPose, byController: Boolean) {
        dragging = true
        dragByController = byController
        dragPose = pose
        held = false
        mapper.suspended = true
        ThLog.i("DRAG start controller=$byController")
        sink(PlayEvent.DragChanged(true, null))
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        dragTriggerMs = NEVER
        mapper.suspended = false
        ghost?.setShown(false)
        val f = userAt()?.let { ref -> desks.mapNotNull { DeskRules.frame(it, ref, ui.settings.handed) }.firstOrNull() }
        val p = dragPose ?: return
        val final = if (held && f != null) InstrumentPose(f.center, f.yaw) else p
        ThLog.i("DRAG end at=${ThLog.pos(final.center)} snapped=$held")
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
        val c = pose.toStage(Instrument.standCenter(h))
        val rot = Instrument.standRotation(pose)
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
        // marker mode: gaze + pinch on the floor or a desk sets the marker (taste D1)
        if (ui.room != Room.PLACING) {
            if (markerMode(ui)) markerFor(stage, userAt())?.let { setMarker(it, "tap") }
            return
        }
        // placing on a desk: the ghost is the marker; a tap on the desk puts the instrument down there (snapped if near)
        val hit = desks.firstOrNull { abs(it.top - stage.y) < 0.05f && DeskRules.inside(it, stage) } ?: return
        val f = frame
        val yaw = if (userTurned) ghostYaw else f?.yaw ?: DeskRules.yawTowards(stage, userAt() ?: stage)
        val p = Vec3(stage.x, hit.top, stage.z)
        val pose = if (f != null && DeskRules.snap(p, yaw, f, false)) InstrumentPose(f.center, f.yaw) else InstrumentPose(p, yaw)
        sink(PlayEvent.PlaceRequested(pose, false))
    }

    /** Debug `marker=desk|floor`: the marker as if aimed with a controller from the user's side (QA). */
    override fun debugMarker(kind: String): String {
        val ref = userRef() ?: return "MARKER no reference yet"
        val slot = rig?.get(if (ui.placed) PanelSpec.STAND else PanelSpec.PLACE)
        val toUser = (slot?.rotation?.rotate(Vec3(0f, 0f, 1f)) ?: Vec3(0f, 0f, 1f)).let { Vec3(it.x, 0f, it.z).normalized() }
        val point =
            when (kind) {
                "desk" -> desks.firstOrNull()?.let { d -> DeskRules.frame(d, ref, ui.settings.handed)?.center ?: d.centroid }
                else -> DeskRules.floorY(planes.all)?.let { fy -> Vec3(ref.x, fy, ref.z) - toUser * 1.0f + Vec3(-toUser.z, 0f, toUser.x) * 0.4f }
            } ?: return "MARKER no $kind surface"
        val mk = markerFor(point, userAt()) ?: return "MARKER not on a surface ${ThLog.pos(point)}"
        setMarker(mk, "debug")
        return "MARKER debug $kind at=${ThLog.pos(mk.point)}"
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
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f", slot.id, ThLog.pos5(slot.position), ThLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp)
        }
    }

    override fun planesSummary(): List<String> =
        planes.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${ThLog.pos(p.centroid)} normal=${ThLog.pos(p.normal)} top=${ThLog.f(p.maxY)}" } +
            desks.map { d -> "DESK ${d.id.take(8)} ${d.kind} top=${ThLog.f(d.top)} n=${d.outline.size}" } +
            listOfNotNull(frame?.let { "FRAME center=${ThLog.pos(it.center)} yaw=${ThLog.f(Math.toDegrees(it.yaw.toDouble()).toFloat(), 1)} edge=${ThLog.f(it.edgeLength)} depth=${ThLog.f(it.depth)}" })

    override fun parkPanel(): String {
        follower.park(head, planes.all, SystemClock.elapsedRealtime())
        return "FOLLOW place park requested"
    }

    override fun dragPanelDebug(dxDp: Float, dyDp: Float): String {
        dragPanel(dxDp, dyDp)
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
        private const val PLANES_AFTER_MS = 10_000L
        private const val FOLLOW_TAU_S = 0.12f
        private const val SNAP_TAU_S = 0.05f
        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE_RAD_S = 1.5708f
        private const val STICK_TAU_S = 0.15f
        private const val TURN_MS = 150f
        private const val DRAG_HOLD_MS = 500L
        /** The user sits about this far in front of the panel they read (reference point, not a head pose). */
        private const val PANEL_TO_USER_M = 0.6f
        private const val NEAR_M = 0.20f
        private const val WAVE_MS = 600f

        /** 0.75 m from the eyes, 20 degrees down, 15 degrees left (design `panels.placePanel`). */
        private val PLACE_OFFSET = Vec3(-0.1824f, -0.2565f, -0.6807f)
    }
}
