package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.infinite8looper.compass.nav.TargetUi

private val LabelGray = Color(0xFF7C7C7C)

// Fractions of the dial radius, shared with the screen so its readout can stay inside the letters.
private const val RING_OUTER = 0.84f
private const val RING_INNER = 0.72f
private const val LETTER_RADIUS = 0.555f
private const val LETTER_SIZE = 0.146f
/** Nothing but the readout is drawn inside this fraction of the radius: the letters' inner edge is at about 0.50. */
internal const val READOUT_RADIUS = 0.47f

private const val HEAVY_TICK = 0.022f
// A target's line runs a little past both edges of the ring and is heavier than a heavy tick.
private const val MARKER_OUTER = 0.86f
private const val MARKER_INNER = 0.70f
private const val MARKER_WIDTH = 0.036f
private const val MARKER_WIDTH_OTHER = 0.030f
private const val MARKER_CASING = 0.012f
// While the wearer faces the focused target its line is twice as heavy and a little longer.
private const val FACING_WIDTH = 2f * MARKER_WIDTH
private const val FACING_EXTRA_LENGTH = 0.012f
// A crumb's tick: short, between the letters and the ring.
private const val CRUMB_TICK_OUTER = 0.70f
private const val CRUMB_TICK_INNER = 0.648f
private const val CRUMB_TICK_WIDTH = 0.02f
// The heading pointer's triangle. The focused target's arrow is the same triangle, turned to the target's bearing.
private const val ARROW_TIP = 0.71f
private const val ARROW_BASE = 0.625f
private const val ARROW_HALF_WIDTH = 0.052f

/**
 * The dial is stacked layers. The face (ticks, degree labels, letters), the lines of the targets that are not
 * focused, and the focused target's line and arrow are each drawn once and then turned by their layer's rotation,
 * which is read from [headingProvider] when the layer is updated: a new heading neither recomposes nor redraws
 * them. The pointer does not move.
 *
 * [facing] is read only where the focused target's layer is drawn, so a change of it redraws that one layer, once.
 *
 * [focused] is what the wearer walks to: the focused waypoint, or, while a breadcrumb trail is retraced, the crumb
 * walked to, which is drawn the same way in the breadcrumb color. [crumbTicks] are the dial angles of the trail's
 * other crumbs, each a short tick inside the ring, on a layer of their own that is drawn when the list changes
 * (with a position fix) and turned like the others. The list holds at most one tick per degree, however long the
 * trail.
 *
 * Order, from the bottom: face, crumb ticks, other targets, the pointer's line, the focused target, the pointer's triangle.
 * The focused target is above every other target's line, so nothing at the same bearing hides it. When the wearer
 * faces it, its arrow lies exactly under the pointer's triangle and its heavy line covers the pointer's line:
 * the triangle keeps the pointer's color and the line above it has the target's.
 */
@Composable
fun CompassDial(
    headingProvider: () -> Float, targets: List<TargetUi>, modifier: Modifier = Modifier, facing: () -> Boolean = { false },
    focused: TargetUi? = targets.firstOrNull { it.focused }, crumbTicks: List<Float> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val others = remember(targets) { targets.filter { it.dialAngleDeg != null && !it.focused } }
    @Suppress("NAME_SHADOWING") val focused = focused?.takeIf { it.dialAngleDeg != null }
    val turn: () -> Float = { headingProvider().let { if (it.isNaN()) 0f else -it } }

    Box(modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn() }.drawWithCache {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val degStyle = TextStyle(color = LabelGray, fontSize = (r * 0.09f).toSp())
            val cardStyle = TextStyle(color = Ink, fontSize = (r * LETTER_SIZE).toSp())
            val degrees = (0 until 360 step 30).map { it to measurer.measure(it.toString(), degStyle) }
            val cardinals = listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").map { (a, s) -> a to measurer.measure(s, cardStyle) }
            onDrawBehind {
                for (d in 0 until 360 step 2) {
                    val heavy = d % 30 == 0
                    rotate(d.toFloat(), c) {
                        drawLine(
                            color = if (d == 0) NorthColor else if (heavy) Ink else Faint,
                            start = Offset(c.x, c.y - r * RING_OUTER), end = Offset(c.x, c.y - r * RING_INNER),
                            strokeWidth = if (heavy) r * HEAVY_TICK else r * 0.01f, cap = StrokeCap.Round,
                        )
                    }
                }
                for ((angle, layout) in degrees) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * 0.93f) }
                for ((angle, layout) in cardinals) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * LETTER_RADIUS) }
            }
        })

        if (crumbTicks.isNotEmpty()) {
            Spacer(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn() }.drawBehind {
                val r = size.minDimension / 2f
                for (i in crumbTicks.indices) {
                    rotate(crumbTicks[i], center) {
                        drawLine(
                            CrumbColor, Offset(center.x, center.y - r * CRUMB_TICK_OUTER), Offset(center.x, center.y - r * CRUMB_TICK_INNER),
                            r * CRUMB_TICK_WIDTH, StrokeCap.Round,
                        )
                    }
                }
            })
        }

        if (others.isNotEmpty()) {
            Spacer(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn() }.drawWithCache {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                onDrawBehind {
                    for (t in others) {
                        rotate(t.dialAngleDeg ?: continue, c) {
                            drawLine(softTint(t.hue), Offset(c.x, c.y - r * MARKER_OUTER), Offset(c.x, c.y - r * MARKER_INNER), r * MARKER_WIDTH_OTHER)
                        }
                    }
                }
            })
        }

        Spacer(Modifier.fillMaxSize().drawBehind {
            val r = size.minDimension / 2f
            drawLine(
                AccentColor, Offset(center.x, center.y - r * RING_OUTER), Offset(center.x, center.y - r * RING_INNER),
                r * HEAVY_TICK, StrokeCap.Round,
            )
        })

        if (focused != null) {
            Spacer(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn() }.drawWithCache {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                val arrow = pointerTriangle(c, r)
                val color = if (focused.isCrumb) CrumbColor else waypointColor(focused.hue)
                val angle = focused.dialAngleDeg ?: 0f
                onDrawBehind {
                    val heavy = facing()
                    val width = r * if (heavy) FACING_WIDTH else MARKER_WIDTH
                    val extra = if (heavy) r * FACING_EXTRA_LENGTH else 0f
                    val outer = c.y - r * MARKER_OUTER - extra
                    val inner = c.y - r * MARKER_INNER + extra
                    val casing = r * MARKER_CASING
                    rotate(angle, c) {
                        drawLine(Color.Black, Offset(c.x, outer - casing), Offset(c.x, inner + casing), width + 2f * casing)
                        drawPath(arrow, color)
                        drawLine(color, Offset(c.x, outer), Offset(c.x, inner), width)
                    }
                }
            })
        }

        Spacer(Modifier.fillMaxSize().drawWithCache {
            val triangle = pointerTriangle(Offset(size.width / 2f, size.height / 2f), size.minDimension / 2f)
            onDrawBehind { drawPath(triangle, AccentColor) }
        })
    }
}

/** A triangle at 12 o'clock whose tip meets the ring, which leaves room for a letter to pass under its base. */
private fun pointerTriangle(c: Offset, r: Float) = Path().apply {
    moveTo(c.x, c.y - r * ARROW_TIP)
    lineTo(c.x - r * ARROW_HALF_WIDTH, c.y - r * ARROW_BASE)
    lineTo(c.x + r * ARROW_HALF_WIDTH, c.y - r * ARROW_BASE)
    close()
}

private fun DrawScope.centeredText(layout: TextLayoutResult, cx: Float, cy: Float) {
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}
