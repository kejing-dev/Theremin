package io.embodit.theremin.platform

import android.content.Context
import io.embodit.theremin.domain.play.PlayMapper
import io.embodit.theremin.platform.input.ControllerInput
import io.embodit.theremin.platform.input.HandInput
import io.embodit.theremin.platform.sense.PlaneTracker
import io.embodit.theremin.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services and the
 * audio (every start has a stop: `PlayScene.attach` / `detach`).
 */
object ThGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()
    val hands = HandInput()
    val mapper = PlayMapper()

    @Volatile var appContext: Context? = null
}
