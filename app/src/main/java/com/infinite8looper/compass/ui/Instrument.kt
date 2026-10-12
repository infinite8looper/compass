package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import com.infinite8looper.compass.nav.Pane

// The colors the screens share, each named for what it is used for. A screen takes a color from here by its
// role, so a role's color is changed in this one place.

/** The light color of text, heavy ticks and outlines. */
internal val Ink = Color(0xFFE8EAED)
/** Secondary text and prompts. */
internal val Dim = Color(0xFF9E9E9E)
/** Fine ticks, and anything that is switched off or not the current one. */
internal val Faint = Color(0xFF6E6E6E)

/** The heading pointer, and anything that is on or can be acted on. */
val AccentColor = Color(0xFF2F8BFF)
/** North on the dial. */
val NorthColor = Color(0xFFFF3B30)
/** A failure, and deleting. It has the same value as [NorthColor] but is a separate role, so the two are kept as two values. */
val ErrorColor = Color(0xFFFF3B30)

/** A confirmation that something was done, such as a waypoint saved: a soft green. */
val ConfirmColor = Color(0xFF7FD99A)

/** A waypoint's own color: its line and arrow on the dial, and its mark wherever it is listed. */
fun waypointColor(hue: Float): Color = Color.hsv(hue, 0.65f, 1f)

/** The pale, partly transparent version of a waypoint's color that its line has while another target is focused. */
fun softTint(hue: Float): Color = Color.hsv(hue, SOFT_TINT_SATURATION, SOFT_TINT_VALUE, SOFT_TINT_ALPHA)

private const val SOFT_TINT_SATURATION = 0.40f
private const val SOFT_TINT_VALUE = 1f
private const val SOFT_TINT_ALPHA = 0.6f

// The parts the screens share, drawn in fractions of the screen's radius.

/** Where the outer ends of the pane ticks are on a screen that keeps its bottom edge for them. */
internal const val PANE_TICKS_AT_EDGE = 0.93f
private const val PANE_TICK_LENGTH = 0.055f
private const val PANE_TICK_LENGTH_CURRENT = 0.085f
private const val PANE_TICK_WIDTH = 0.018f
private const val PANE_TICK_SPACING = 0.085f

/**
 * Three ticks at 6 o'clock that say which of the three side-by-side screens this is: one tick per screen in the
 * order they sit in, left to right, with the tick of screen number [index] (a [Pane]'s place in that order) in the
 * accent color and a little longer, and the other two faint.
 *
 * The ticks fill the box they are given and are placed by its radius: their outer ends are [radius] of it from the
 * center. [scale] makes the ticks and the gaps between them smaller or larger, for a place with less room than the
 * edge. [alpha] is read while drawing, so fading the ticks recomposes nothing.
 */
@Composable
fun PanePosition(
    index: Int, modifier: Modifier = Modifier, radius: Float = PANE_TICKS_AT_EDGE, scale: Float = 1f, alpha: () -> Float = { 1f },
) {
    Spacer(modifier.fillMaxSize().drawBehind {
        val shown = alpha()
        if (shown <= 0f) return@drawBehind
        val r = size.minDimension / 2f
        // The same distance apart wherever on the radius they sit, as an angle about the center.
        val step = Math.toDegrees((PANE_TICK_SPACING * scale / radius).toDouble()).toFloat()
        for (i in Pane.entries.indices) {
            val current = i == index
            val length = scale * if (current) PANE_TICK_LENGTH_CURRENT else PANE_TICK_LENGTH
            // A turn clockwise carries the bottom of the screen to the left, where the first screen's tick is.
            rotate((1 - i) * step, center) {
                drawLine(
                    if (current) AccentColor else Faint, Offset(center.x, center.y + r * radius),
                    Offset(center.x, center.y + r * (radius - length)), r * PANE_TICK_WIDTH * scale, StrokeCap.Round, alpha = shown,
                )
            }
        }
    })
}
