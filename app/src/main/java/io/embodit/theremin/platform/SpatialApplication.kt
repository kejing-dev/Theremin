package io.embodit.theremin.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.theremin.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
