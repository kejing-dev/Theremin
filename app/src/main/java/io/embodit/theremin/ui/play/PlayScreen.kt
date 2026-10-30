package io.embodit.theremin.ui.play

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.theremin.platform.ThLog
import io.embodit.theremin.ui.play.components.LocalThResources
import io.embodit.theremin.ui.play.components.PlayStage
import io.embodit.theremin.ui.play.components.localizedResources
import io.embodit.theremin.ui.play.debug.ThProbe
import io.embodit.theremin.ui.play.scene.PlayFrameSystem
import io.embodit.theremin.ui.play.scene.PlayScene

/**
 * The one screen of the app, in a Mixed Stage. The ViewModel holds the state, [PlayScene] turns it into
 * entities and sound and reports placing, dragging and the per-frame mapping, [PlayStage] declares the panels.
 */
@Composable
fun PlayScreen() {
    val context = LocalContext.current
    val viewModel: PlayViewModel = viewModel(factory = PlayViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        ThProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { PlayScene(viewModel, viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<PlayFrameSystem>()
        ThProbe.host =
            object : ThProbe.Host {
                override fun dispatch(event: PlayEvent) = viewModel.onEvent(event)

                override fun uiState(): PlayUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): ThProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = ThProbe.click(name)

                override fun songNotes(id: String): List<Int> = viewModel.songs.firstOrNull { it.id == id }?.notes?.map { it.semitone } ?: emptyList()
            }
        ThLog.i("STAGE enter")
        onDispose {
            ThProbe.host = null
            unregisterSystem<PlayFrameSystem>()
            scene.detach()
            ThLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(PlayEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(PlayEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    // real hands drawn over the ripples while playing (design `container.upperLimbRenderMode`; device effect unverified)
    val limbs = LocalStageUpperLimbRenderModelManager.current
    LaunchedEffect(limbs) {
        val r = runCatching { limbs.setUpperLimbRenderMode(UpperLimbRenderMode.Visible) }
        ThLog.i("LIMBS visible result=${r.getOrNull() ?: r.exceptionOrNull()}")
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: PlayEffect ->
            when (effect) {
                PlayEffect.ExitApp -> {
                    // D26: release audio / tracking / anchor, remove the task from Recents, close the Stage, end the process
                    ThLog.i("EXIT release + finishAndRemoveTask + exitProcess")
                    runCatching { scene.detach() }.onFailure { ThLog.e("EXIT detach failed: $it", it) }
                    runCatching {
                        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
                    }.onFailure { ThLog.e("EXIT finishAndRemoveTask failed: $it", it) }
                    runCatching { navigator.closeStage() }.onFailure { ThLog.e("STAGE close failed: $it", it) }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ kotlin.system.exitProcess(0) }, 400)
                }
                is PlayEffect.ReferenceTone -> scene.audio()?.referenceTone(effect.midi)
                PlayEffect.Cue -> scene.audio()?.cue()
                PlayEffect.Ripple -> scene.wave()
            }
        }
    }

    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalThResources provides resources) {
        PlayStage(state = state, vm = viewModel, scene = scene, onEvent = viewModel::onEvent)
    }
}
