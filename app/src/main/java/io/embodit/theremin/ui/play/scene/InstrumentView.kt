package io.embodit.theremin.ui.play.scene

import android.graphics.Bitmap
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import io.embodit.theremin.domain.model.Handed
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.InstrumentPose
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.domain.model.Quat4
import io.embodit.theremin.domain.model.Scale
import io.embodit.theremin.domain.model.Vec3
import io.embodit.theremin.platform.ThLog
import kotlin.math.atan2
import kotlin.math.sqrt

/** What the instrument view is built for; a change rebuilds it. */
internal data class Layout(val handed: Handed, val scale: Scale, val english: Boolean, val octave: Octave, val labelTest: Boolean)

private fun Entity.model(mesh: MeshResource, material: Material, name: String): Entity {
    val e = ModelEntity(mesh, material).named(name)
    addChild(e)
    return e
}

private fun Entity.show(on: Boolean) {
    if (enabled != on) enabled = on
}

/**
 * The instrument (design `instrument`, `entities3d`), all procedural, in instrument coordinates below [root]
 * whose transform is the instrument pose: walnut cabinet with brass trim and name plate, the pitch antenna,
 * the volume loop and its column, the pitch ripples with their labels, the amber bead, the hand tag (note,
 * arrow, hold ring), the target pillar, the virtual stand. A mirrored layout is built part by part with x
 * negated (never scale -1: winding, normals and text would flip). [build] runs in paced steps.
 */
internal class InstrumentView(parent: Entity, private val m: SceneMaterials, private val shared: SharedResources, val layout: Layout) {
    val root: Entity = Entity().named("instrument")
    lateinit var antenna: Entity
        private set
    lateinit var cabinet: Entity
        private set
    private lateinit var stand: Entity
    private lateinit var naturals: Entity
    private lateinit var sharps: Entity
    private val current = arrayOfNulls<Entity>(15)
    private val target = arrayOfNulls<Entity>(15)
    private val labels = ArrayList<Pair<Int, Entity>>()
    private lateinit var bead: Entity
    private lateinit var tag: Entity
    private lateinit var tagNote: Entity
    private lateinit var arrowUp: Entity
    private lateinit var arrowDown: Entity
    private val holdRings = ArrayList<Entity>()
    private lateinit var pillar: Entity
    private lateinit var fill: Entity
    private lateinit var level: Entity
    private val tagTextures = HashMap<Int, Material>()
    private var tagNoteShown = Int.MIN_VALUE
    var complete = false
        private set

    init {
        parent.addChild(root)
        root.enabled = false
    }

    private val h get() = layout.handed
    private val ax get() = Instrument.antennaX(h)

    private fun mx(v: Vec3): Vec3 = if (h == Handed.RIGHT_PITCH) v else Vec3(-v.x, v.y, v.z)

    /** One group of entities per step (the idle emulator stalls on many meshes at once). */
    /**
     * One mesh (or texture) per step: on the idle emulator every mesh creation waits for the render thread, and a
     * step with fifteen of them held the main thread past the ANR limit (v0.1.0 self-test, language rebuild).
     */
    fun steps(): List<() -> Unit> = buildList {
        add(::buildCabinet)
        add(::buildCabinetTrim)
        add(::buildAntenna)
        add(::buildLoop)
        add(::buildColumn)
        add(::buildRipples)
        for (n in Instrument.ripples(layout.scale)) add { buildHighlight(n) }
        add(::buildPillar)
        add(::buildLabelQuad)
        for (n in Instrument.ripples(layout.scale)) if (Instrument.isNatural(n)) add { buildLabel(n) }
        add(::buildBead)
        add(::buildTag)
        for (k in 1..RING_STEPS) add { buildRing(k) }
        add(::buildStand)
        add { complete = true }
    }

    private fun keep(mesh: MeshResource): MeshResource = shared.keep(mesh)

    private fun buildCabinet() {
        val c = Instrument.CABINET
        val y0 = Instrument.CABINET_BOTTOM_Y
        cabinet = root.model(keep(MeshResource.createBox(c.sdk(), 0.01f)), m.walnut, "cabinet")
        cabinet.setLocal(position = Vec3(0f, y0 + c.y / 2f, 0f))
        // the drag handle: the cabinet box grown by 2 cm all round
        cabinet.tapBox(Vec3(c.x + 0.04f, c.y + 0.04f, c.z + 0.04f))
    }

    private fun buildCabinetTrim() {
        val c = Instrument.CABINET
        val y0 = Instrument.CABINET_BOTTOM_Y
        val feet = mesh("feet") {
            for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) box(Vec3(sx * (c.x / 2 - 0.04f), y0 / 2f, sz * (c.z / 2 - 0.03f)), Vec3(0.03f, y0, 0.03f))
        }
        root.model(keep(feet), m.walnutDark, "feet")
        val trim = mesh("trim") { box(Vec3(0f, y0 + c.y * 0.35f, c.z / 2f + 0.001f), Vec3(c.x - 0.02f, 0.008f, 0.002f)) }
        root.model(keep(trim), m.brass, "trim")
        val plate = keep(mesh("nameplate") { quadUv(0.09f, 0.0225f) })
        val tex = texture(LabelArt.namePlate())
        if (tex != null) {
            val e = root.model(plate, m.textured(tex), "nameplate")
            e.setLocal(position = mx(Vec3(-0.15f, y0 + c.y * 0.62f, c.z / 2f + 0.002f)))
        }
    }

    private fun buildAntenna() {
        val top = Instrument.ANTENNA_TOP_Y
        val base = Instrument.ANTENNA_BASE_Y
        val rod = mesh("antenna") {
            tube(Vec3(0f, base, 0f), Vec3(0f, top, 0f), 0.004f)
            sphere(Vec3(0f, top, 0f), 0.008f)
            tube(Vec3(0f, base - 0.005f, 0f), Vec3(0f, base + 0.02f, 0f), 0.015f)
        }
        antenna = root.model(keep(rod), m.antennaGlow[0], "antenna")
        antenna.setLocal(position = Vec3(ax, 0f, 0f))
    }

    private fun buildLoop() {
        val c = Instrument.loopCenter(h)
        val loop = mesh("loop") {
            torus(c, Instrument.LOOP_RADIUS - 0.004f, 0.004f)
            // the arm from the cabinet's end, under the loop's body side
            val cab = Instrument.CABINET
            val start = mx(Vec3(-cab.x / 2f + 0.01f, Instrument.CABINET_BOTTOM_Y + cab.y * 0.5f, 0.02f))
            val end = c + mx(Vec3(Instrument.LOOP_RADIUS - 0.004f, 0f, -0.03f))
            tube(start, end, 0.004f)
        }
        root.model(keep(loop), m.brass, "loop")
    }

    private fun buildColumn() {
        val c = Instrument.loopCenter(h)
        // the volume column: glass tube y 0.12 .. 0.34, mute band 0.12 .. 0.16, amber fill, level ring
        val glass = mesh("column") { tube(Vec3(c.x, c.y, c.z), Vec3(c.x, Instrument.COLUMN_TOP_Y, c.z), 0.025f, 24) }
        root.model(keep(glass), m.glass, "column")
        val mute = mesh("columnMute") { tube(Vec3(c.x, c.y, c.z), Vec3(c.x, c.y + 0.04f, c.z), 0.0255f, 24) }
        root.model(keep(mute), m.mute, "columnMute")
        val unit = mesh("columnFill") { tube(Vec3.ZERO, Vec3(0f, 1f, 0f), 0.022f, 24) }
        fill = root.model(keep(unit), m.fill, "columnFill")
        fill.setLocal(position = Vec3(c.x, c.y + 0.04f, c.z))
        fill.show(false)
        level = root.model(keep(mesh("columnLevel") { ringXZ(0.024f, 0.032f) }), m.level, "columnLevel")
        level.show(false)
    }

    private fun sector(): Pair<Float, Float> = Instrument.sectorDeg(Instrument.SECTOR_FROM_DEG, h) to Instrument.sectorDeg(Instrument.SECTOR_TO_DEG, h)

    private fun buildRipples() {
        val (a, b) = sector()
        val nat = mesh("ripplesNatural") { for (n in Instrument.ripples(layout.scale)) if (Instrument.isNatural(n)) arcBand(Instrument.radius(n, layout.scale), 0.003f, a, b, h) }
        val sh = mesh("ripplesSharp") { for (n in Instrument.ripples(layout.scale)) if (!Instrument.isNatural(n)) arcBand(Instrument.radius(n, layout.scale), 0.0015f, a, b, h) }
        naturals = root.model(keep(nat), m.rippleNatural, "ripplesNatural")
        sharps = root.model(keep(sh), m.rippleSharp, "ripplesSharp")
    }

    private fun buildHighlight(n: Int) {
        val (a, b) = sector()
        val arc = keep(mesh("ripple$n") { arcBand(Instrument.radius(n, layout.scale), if (Instrument.isNatural(n)) 0.004f else 0.0025f, a, b, h, Instrument.RIPPLE_Y + 0.0005f) })
        current[n] = root.model(arc, m.rippleCurrent, "rippleCur$n").also { it.show(false) }
        target[n] = root.model(arc, m.rippleTarget, "rippleTgt$n").also { it.show(false) }
    }

    private fun buildPillar() {
        pillar = root.model(keep(mesh("pillar") { tube(Vec3.ZERO, Vec3(0f, 0.10f, 0f), 0.01f) }), m.pillar, "pillar")
        pillar.show(false)
    }

    private var labelQuad: MeshResource? = null

    private fun buildLabelQuad() {
        labelQuad = keep(mesh("label") { quadUv(LABEL_W, LABEL_W / 2f) })
    }

    private fun buildLabel(n: Int) {
        val quad = labelQuad ?: return
        val deg = Instrument.sectorDeg(Instrument.LABEL_DEG, h)
        val (main, sub) = LabelArt.noteText(n, layout.english, layout.octave)
        val tex = texture(LabelArt.label(main, sub, layout.labelTest)) ?: return
        val e = root.model(quad, m.textured(tex), "label$n")
        e.setLocal(position = Instrument.ripplePoint(Instrument.radius(n, layout.scale), deg, h, Instrument.RIPPLE_Y + 0.016f))
        labels += n to e
    }

    private fun buildBead() {
        bead = root.model(keep(mesh("bead") { sphere(Vec3.ZERO, 0.007f) }), m.bead, "bead")
        bead.show(false)
    }

    private fun buildTag() {
        tag = Entity().named("tag")
        root.addChild(tag)
        tagNote = tag.model(keep(mesh("tagNote") { quadUv(0.04f, 0.02f) }), m.rippleFlash, "tagNote")
        val arrowTex = texture(LabelArt.arrow())
        val aq = keep(mesh("arrow") { quadUv(0.012f, 0.012f) })
        if (arrowTex != null) {
            val am = m.textured(arrowTex)
            arrowUp = tag.model(aq, am, "arrowUp")
            arrowUp.setLocal(position = Vec3(0.028f, 0f, 0f))
            arrowDown = tag.model(aq, am, "arrowDown")
            // turned 180 degrees about its normal: an arrow pointing down, not a mirror image
            arrowDown.setLocal(position = Vec3(0.028f, 0f, 0f), rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.PI.toFloat()))
        } else {
            arrowUp = Entity()
            arrowDown = Entity()
        }
        arrowUp.show(false)
        arrowDown.show(false)
        tag.enabled = false
    }

    private fun buildRing(k: Int) {
        val ring = tag.model(keep(mesh("hold$k") { ringXY(0.018f, 0.021f, k / RING_STEPS.toFloat()) }), m.holdRing, "hold$k")
        ring.setLocal(position = Vec3(0f, 0f, -0.001f))
        ring.show(false)
        holdRings += ring
    }

    private fun buildStand() {
        val c = Instrument.CABINET
        stand = mesh("stand") {
            box(Vec3(0f, -0.005f, 0f), Vec3(c.x, 0.01f, c.z))
            for (k in 0 until 3) {
                val a = Math.toRadians(90.0 + 120.0 * k)
                val foot = Vec3((kotlin.math.cos(a) * 0.30).toFloat(), -0.70f, (kotlin.math.sin(a) * 0.30).toFloat())
                tube(Vec3(0f, -0.01f, 0f), foot, 0.005f, 10)
            }
        }.let { root.model(keep(it), m.stand, "stand") }
        stand.show(false)
    }

    private fun texture(bmp: Bitmap): TextureResource? =
        runCatching { shared.keep(TextureResource.create(bmp)) }.onFailure { ThLog.e("TEXTURE failed: $it", it) }.getOrNull()

    // ---------------------------------------------------------------------------------------------
    // per frame
    // ---------------------------------------------------------------------------------------------

    fun place(pose: InstrumentPose) {
        root.setLocal(position = pose.center, rotation = pose.rotation)
    }

    fun setShown(on: Boolean) = root.show(on && complete)

    fun setStand(on: Boolean) {
        if (complete) stand.show(on)
    }

    /** Labels turn about the vertical only, towards the head (instrument frame): upright, never mirrored. */
    fun faceLabels(headLocal: Vec3) {
        if (!complete) return
        for ((_, e) in labels) {
            val p = e.transformOrNull?.position?.domain() ?: continue
            val yaw = atan2(headLocal.x - p.x, headLocal.z - p.z)
            e.setLocal(rotation = Quat4.axisAngle(Vec3.UP, yaw))
        }
    }

    /** Ripple brightness: 1 normally, 0.4 while lost or practising. */
    fun dimRipples(k: Float) {
        if (!complete) return
        naturals.setOpacity(k)
        sharps.setOpacity(k)
    }

    fun highlight(currentN: Int?, targetN: Int?, nextN: Int?, flashOuter: Float?) {
        if (!complete) return
        for (n in 0 until 15) {
            val c = current[n] ?: continue
            val t = target[n] ?: continue
            c.show(n == currentN && n != targetN)
            when (n) {
                targetN -> {
                    t.setMaterial(m.rippleTarget)
                    t.show(true)
                }
                nextN -> {
                    t.setMaterial(m.rippleNext)
                    t.show(true)
                }
                else -> t.show(false)
            }
            if (flashOuter != null) {
                // the done wave: rings light up from the inside out
                val r = Instrument.radius(n, layout.scale)
                val lit = kotlin.math.abs(r - (Instrument.R_TOP + flashOuter * (Instrument.maxRadius(layout.scale) - Instrument.R_TOP))) < 0.03f
                if (lit) {
                    t.setMaterial(m.rippleFlash)
                    t.show(true)
                }
            }
        }
    }

    /** Target pillar at the target ripple's label end, scaled by the remaining share [left] (songs). */
    fun pillar(targetN: Int?, left: Float = 1f) {
        if (!complete) return
        if (targetN == null || targetN > layout.scale.top) {
            pillar.show(false)
            return
        }
        val p = Instrument.ripplePoint(Instrument.radius(targetN, layout.scale), Instrument.sectorDeg(Instrument.LABEL_DEG + 4f, h), h)
        pillar.setLocal(position = p, scale = Vec3(1f, left.coerceIn(0.05f, 1f), 1f))
        pillar.show(true)
    }

    /** Bead at distance [d] along the antenna -> [point] radius, on the ripple plane. */
    fun bead(point: Vec3?, d: Float) {
        if (!complete) return
        if (point == null) {
            bead.show(false)
            return
        }
        val dx = point.x - ax
        val dz = point.z
        val l = sqrt(dx * dx + dz * dz)
        val dir = if (l < 1e-4f) Vec3(0f, 0f, 1f) else Vec3(dx / l, 0f, dz / l)
        bead.setLocal(position = Vec3(ax + dir.x * d, Instrument.RIPPLE_Y + 0.004f, dir.z * d))
        bead.show(true)
    }

    /**
     * The hand tag 0.10 m over the pitch point, facing the head: note plate, arrow when off by more than
     * 15 cents (bright beyond 40), the hold ring filled to [hold].
     */
    fun tag(point: Vec3?, note: Int, cents: Float, hold: Float?, headLocal: Vec3?, noteMaterial: (Int) -> Material?) {
        if (!complete) return
        if (point == null || headLocal == null) {
            tag.show(false)
            return
        }
        val at = point + Vec3(0f, 0.10f, 0f)
        val to = headLocal - at
        val yaw = atan2(to.x, to.z)
        val pitch = -atan2(to.y, sqrt(to.x * to.x + to.z * to.z))
        tag.setLocal(position = at, rotation = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, pitch))
        tag.show(true)
        if (note != tagNoteShown) {
            noteMaterial(note)?.let { tagNote.setMaterial(it) }
            tagNoteShown = note
        }
        val off = kotlin.math.abs(cents)
        // the arrow says where the hand is: flat (low) points down, sharp (high) points up (design S09 / S14)
        arrowUp.show(cents > 15f)
        arrowDown.show(cents < -15f)
        val op = if (off > 40f) 1f else 0.45f
        arrowUp.setOpacity(op)
        arrowDown.setOpacity(op)
        val k = if (hold == null) 0 else (hold * RING_STEPS).toInt().coerceIn(0, RING_STEPS)
        holdRings.forEachIndexed { i, e -> e.show(hold != null && i == k - 1) }
    }

    fun column(volume: Float, h: Float?) {
        if (!complete) return
        val c = Instrument.loopCenter(this.h)
        if (h == null || volume <= 0f) {
            fill.show(false)
            level.show(h != null)
            if (h != null) level.setLocal(position = Vec3(c.x, (c.y + h + 0.02f).coerceIn(c.y, Instrument.COLUMN_TOP_Y), c.z))
            return
        }
        val top = (c.y + 0.04f + volume * (Instrument.COLUMN_TOP_Y - c.y - 0.04f))
        fill.setLocal(scale = Vec3(1f, (top - c.y - 0.04f).coerceAtLeast(0.001f), 1f))
        fill.show(true)
        level.setLocal(position = Vec3(c.x, top, c.z))
        level.show(true)
    }

    fun glow(volume: Float) {
        if (!complete) return
        antenna.setMaterial(m.antennaGlow[(volume * 7).toInt().coerceIn(0, 7)])
    }

    fun destroy() {
        runCatching { root.destroy(true) }
        tagTextures.clear()
    }

    companion object {
        const val RING_STEPS = 10

        /** Ripple label plate width (2 : 1). */
        const val LABEL_W = 0.05f
    }
}

/** The 35 % ghost of cabinet, antenna and loop shown while placing or dragging. */
internal class GhostView(parent: Entity, private val m: SceneMaterials, shared: SharedResources) {
    val root: Entity = Entity().named("ghost")
    private val parts = ArrayList<Entity>()
    private var handed: Handed? = null
    private val shared = shared

    init {
        parent.addChild(root)
        root.enabled = false
    }

    fun build(h: Handed) {
        if (handed == h) return
        handed = h
        parts.forEach { runCatching { it.destroy(true) } }
        parts.clear()
        val c = Instrument.CABINET
        val ax = Instrument.antennaX(h)
        val loop = Instrument.loopCenter(h)
        val body = mesh("ghostBody") {
            box(Vec3(0f, Instrument.CABINET_BOTTOM_Y + c.y / 2f, 0f), c)
            tube(Vec3(ax, Instrument.ANTENNA_BASE_Y, 0f), Vec3(ax, Instrument.ANTENNA_TOP_Y, 0f), 0.005f)
            torus(loop, Instrument.LOOP_RADIUS - 0.004f, 0.005f)
        }
        parts += root.model(shared.keep(body), m.ghost, "ghostBody")
        root.setOpacity(GHOST_OPACITY)
    }

    fun place(pose: InstrumentPose) = root.setLocal(position = pose.center, rotation = pose.rotation)

    fun setShown(on: Boolean) = root.show(on)

    fun setBright(on: Boolean) = parts.forEach { it.setMaterial(if (on) m.ghostBright else m.ghost) }

    fun destroy() = runCatching { root.destroy(true) }

    private companion object {
        /** On top of the sheer material: the emulator showed the 35 % material nearly opaque. */
        const val GHOST_OPACITY = 0.45f
    }
}

/** The marker the user sets on the floor or a desk: a 0.24 m amber ring, dim while aiming, solid once set. */
internal class MarkerView(parent: Entity, m: SceneMaterials, shared: SharedResources) {
    private val aim: Entity = ModelEntity(shared.keep(mesh("markerAim") { ringXZ(0.10f, 0.12f) }), m.snapDashed).named("markerAim")
    private val set: Entity = ModelEntity(shared.keep(mesh("markerSet") { ringXZ(0.09f, 0.12f) }), m.snapSolid).named("markerSet")

    init {
        parent.addChild(aim)
        parent.addChild(set)
        aim.enabled = false
        set.enabled = false
    }

    fun aimAt(point: Vec3?) {
        if (point == null) {
            aim.show(false)
            return
        }
        aim.setLocal(position = point + Vec3(0f, 0.002f, 0f))
        aim.show(true)
    }

    fun setAt(point: Vec3?) {
        if (point == null) {
            set.show(false)
            return
        }
        set.setLocal(position = point + Vec3(0f, 0.003f, 0f))
        set.show(true)
    }

    fun destroy() {
        runCatching { aim.destroy(true) }
        runCatching { set.destroy(true) }
    }
}

/** The 0.64 x 0.28 m snap frame on the desk: dashed amber 55 %, solid when snapped. */
internal class SnapFrameView(parent: Entity, m: SceneMaterials, shared: SharedResources) {
    private val dashed: Entity = ModelEntity(shared.keep(mesh("snapDashed") { rectOutline(0.64f, 0.28f, 0.006f, 0.04f, 0.025f) }), m.snapDashed).named("snapDashed")
    private val solid: Entity = ModelEntity(shared.keep(mesh("snapSolid") { rectOutline(0.64f, 0.28f, 0.006f, 0f, 0f) }), m.snapSolid).named("snapSolid")

    init {
        parent.addChild(dashed)
        parent.addChild(solid)
        dashed.enabled = false
        solid.enabled = false
    }

    fun show(pose: InstrumentPose?, snapped: Boolean, handed: Handed) {
        if (pose == null) {
            dashed.show(false)
            solid.show(false)
            return
        }
        // the frame covers the instrument's footprint with the loop reaching out (centre 4 cm towards the player)
        val at = pose.toStage(Vec3(if (handed == Handed.RIGHT_PITCH) -0.08f else 0.08f, 0.001f, 0.035f))
        dashed.setLocal(position = at, rotation = pose.rotation)
        solid.setLocal(position = at, rotation = pose.rotation)
        dashed.show(!snapped)
        solid.show(snapped)
    }

    fun destroy() {
        runCatching { dashed.destroy(true) }
        runCatching { solid.destroy(true) }
    }
}
