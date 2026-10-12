package com.infinite8looper.compass.ui

import androidx.compose.ui.graphics.Color

// The colors the screens share, each named for what it is used for. A screen takes a color from here by its
// role, so a role's color is changed in this one place.

/** The light color of text, heavy ticks and outlines. */
internal val Ink = Color(0xFFE8EAED)
/** Secondary text and prompts. */
internal val Dim = Color(0xFF9E9E9E)

/** The heading pointer, and anything that is on or can be acted on. */
val AccentColor = Color(0xFF2F8BFF)
/** North on the dial. */
val NorthColor = Color(0xFFFF3B30)
/** A failure, and deleting. It has the same value as [NorthColor] but is a separate role, so the two are kept as two values. */
val ErrorColor = Color(0xFFFF3B30)

/** A waypoint's own color: its line and arrow on the dial, and its mark wherever it is listed. */
fun waypointColor(hue: Float): Color = Color.hsv(hue, 0.65f, 1f)

/** The pale, partly transparent version of a waypoint's color that its line has while another target is focused. */
fun softTint(hue: Float): Color = Color.hsv(hue, SOFT_TINT_SATURATION, SOFT_TINT_VALUE, SOFT_TINT_ALPHA)

private const val SOFT_TINT_SATURATION = 0.40f
private const val SOFT_TINT_VALUE = 1f
private const val SOFT_TINT_ALPHA = 0.6f
