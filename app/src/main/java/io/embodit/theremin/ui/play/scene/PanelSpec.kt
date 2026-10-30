package io.embodit.theremin.ui.play.scene

/** Ids and dp sizes of the attachment panels (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    /** Place panel before the instrument stands, fixed in the world where it appeared (D21): 400 x 250 (handle + recall, v0.1.3). */
    const val PLACE = "placePanel"

    /** Music stand panel above the instrument, facing the user (v0.1.4): 450 x 410 (380 + the grab bar row; design 320, QA q1-2). */
    const val STAND = "standPanel"

    val PLACE_SIZE = 400f to 250f
    val STAND_SIZE = 450f to 410f

    val pool: List<String> = listOf(PLACE, STAND)

    fun size(id: String): Pair<Float, Float> = if (id == PLACE) PLACE_SIZE else STAND_SIZE

    fun halfM(id: String, scale: Float = 1f): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
