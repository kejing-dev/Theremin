package io.embodit.theremin.ui.play.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.theremin.ui.play.LanguagePref
import java.util.Locale

/**
 * Localised resources for the language chosen in the app ("System default / 中文 / English").
 *
 * The language switches at run time without restarting the Stage: the screen provides a Resources
 * object for the chosen locale through this local (provided outside the SpatialView; the feasibility
 * spike verified that such a local reaches attachment panels) and every panel reads its copy from it.
 * `LocalContext` is deliberately not overridden, so SpatialUI popups keep the real window context.
 */
val LocalThResources = staticCompositionLocalOf<Resources> { error("LocalThResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

/** String for the language chosen in the app. */
@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalThResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalThResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun rq(@PluralsRes id: Int, count: Int): String = LocalThResources.current.getQuantityString(id, count, count)
