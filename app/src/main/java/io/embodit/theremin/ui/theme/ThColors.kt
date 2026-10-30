package io.embodit.theremin.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's brand tokens (design package `palette.brandTokens`). This is the only file with colour
 * literals. They colour domain content only (instrument, ripples, bead, column, target, ghost, snap frame)
 * and the pitch needle; every panel colour comes from the SpatialUI scheme (interaction and passable
 * overridden in [ThereminTheme]).
 */
object ThColors {
    val Walnut = Color(0xFF5A3A24) // design-style: fixed-figma-color walnut: cabinet
    val WalnutDark = Color(0xFF3A2416) // design-style: fixed-figma-color walnutDark: feet, sides
    val Brass = Color(0xFFC9A35A) // design-style: fixed-figma-color brass: antenna, loop, trim
    val BrassHighlight = Color(0xFFE8CB8A) // design-style: fixed-figma-color brassHighlight
    val FilamentAmber = Color(0xFFFFB547) // design-style: fixed-figma-color filamentAmber: bead, fill, current ripple
    val RippleIvory = Color(0xFFF4ECDC) // design-style: fixed-figma-color rippleIvory: ripples, labels
    val Verdigris = Color(0xFF4FB39A) // design-style: fixed-figma-color verdigris: target, tolerance
    val Glass = Color(0xFFDDE6EE) // design-style: fixed-figma-color glass: volume column tube
    val MuteBand = Color(0xFF55585E) // design-style: fixed-figma-color muteBand: silent part of the column
    val StandGrey = Color(0xFF8A8D93) // design-style: fixed-figma-color standGrey: the virtual stand
    val LabelInk = Color(0xFF241A10) // design-style: fixed-figma-color labelInk: label plate ground
    val InkOnFill = Color(0xFF16181C) // design-style: fixed-figma-color ink: text on the amber fill
}
