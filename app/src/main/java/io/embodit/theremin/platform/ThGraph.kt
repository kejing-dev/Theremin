package io.embodit.theremin.platform

import android.content.Context
import io.embodit.theremin.domain.play.PlayMapper
import io.embodit.theremin.platform.input.ControllerInput
import io.embodit.theremin.platform.input.HandInput
import io.embodit.theremin.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the tracking services and the
 * audio (every start has a stop: `PlayScene.attach` / `detach`). v0.1.5: no plane / mesh / scene understanding
 * service at all (taste D24).
 */
object ThGraph {
    val head = HeadTracker()
    val controller = ControllerInput()
    val hands = HandInput()
    val mapper = PlayMapper()

    @Volatile var appContext: Context? = null
}
