package io.embodit.theremin.ui.play.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.theremin.ui.play.debug.probe
import io.embodit.theremin.ui.theme.ThColors

/** Panel padding and the corner radius of the glass (design: Material.Regular root, 32 dp corners). */
internal val PanelPadding = 16.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp
private val REBUILD_AFTER_MS = longArrayOf(1500L, 2500L, 4000L)

/**
 * Glass root of a panel: clipped shape, Material.Regular, padding.
 *
 * [refreshKey]: whenever it changes (the panel came into view, its rows changed), the rows are rebuilt
 * three more times, 1.5 / 4 / 8 s later. On the idle emulator (1 - 2 fps) a pooled panel whose rows changed
 * sometimes stayed on a half-recorded frame: black below the first rows until the next content change
 * (a root alpha / recomposition did not clear it, new rows did).
 */
@Composable
internal fun Glass(
    width: Dp,
    height: Dp,
    corner: Dp = 32.dp,
    material: Material = Material.Regular,
    padding: Dp = PanelPadding,
    spacing: Dp = 8.dp,
    refreshKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey) {
        for (wait in REBUILD_AFTER_MS) {
            delay(wait)
            generation++
        }
    }
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        key(generation) { content() }
    }
}

/** Type roles of the design that differ from the stock PicoTheme roles (design `typography.roles`). */
internal object ThType {
    /** Name badge, chip values: titleMedium 16 / 20 at 600, tabular figures. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Chip labels, tile names, plates: labelLarge 14 / 18 at 600, tabular figures. */
    val figure: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Hint line, current target, dialog body: bodyMedium 14 / 18 at 500 (Chinese at 17 sp or less is Medium). */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")
}

/** A 56 x 48 icon key (back, mute, settings, more); [selected] = toggled on (amber fill). */
@Composable
internal fun Key(name: String, iconRes: Int, description: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val content = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (selected) ThColors.InkOnFill else PicoTheme.colorScheme.labelPrimaryLight
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillPrimary, contentColor = content),
        size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/**
 * A worded button: capsule, 48 dp high. [primary] = interaction fill with ink text (the leading action),
 * [danger] = error fill with ink text; otherwise the tertiary fill with glass text.
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 104.dp,
    height: Dp = 48.dp,
    iconRes: Int? = null,
) {
    val container =
        when {
            danger -> PicoTheme.colorScheme.error
            primary -> PicoTheme.colorScheme.interaction
            else -> PicoTheme.colorScheme.fillTertiary
        }
    val content = if (primary || danger) ThColors.InkOnFill else if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ThType.title)
    }
}

/** Title row of a panel: an optional leading key, the title, an optional trailing key. */
@Composable
internal fun TitleRow(title: String, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) leading()
        Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = ThType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}
