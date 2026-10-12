package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.infinite8looper.compass.nav.TargetUi

val HeadingBlue = Color(0xFF2F8BFF)
val NorthRed = Color(0xFFFF3B30)
private val TickGray = Color(0xFF6E6E6E)
private val LabelGray = Color(0xFF7C7C7C)
/** The light color of text, heavy ticks and outlines. */
internal val Ink = Color(0xFFE8EAED)

// Fractions of the dial radius, shared with the screen so its readout can stay inside the letters.
private const val RING_OUTER = 0.84f
private const val RING_INNER = 0.72f
private const val LETTER_RADIUS = 0.555f
private const val LETTER_SIZE = 0.146f
/** Nothing but the readout is drawn inside this fraction of the radius: the letters' inner edge is at about 0.50. */
internal const val READOUT_RADIUS = 0.47f

fun waypointColor(hue: Float): Color = Color.hsv(hue, 0.65f, 1f)

/**
 * The dial is three stacked layers. The face (ticks, degree labels, letters) and the target markers are each
 * drawn once and then turned by their layer's rotation, which is read from [headingProvider] when the layer is
 * updated: a new heading neither recomposes nor redraws them. The pointer on top does not move.
 */
@Composable
fun CompassDial(headingProvider: () -> Float, targets: List<TargetUi>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    // Unfocused markers first so the focused one is drawn on top.
    val markers = remember(targets) { targets.filter { it.dialAngleDeg != null }.sortedBy { it.focused } }
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
                            color = if (d == 0) NorthRed else if (heavy) Ink else TickGray,
                            start = Offset(c.x, c.y - r * RING_OUTER), end = Offset(c.x, c.y - r * RING_INNER),
                            strokeWidth = if (heavy) r * 0.022f else r * 0.01f, cap = StrokeCap.Round,
                        )
                    }
                }
                for ((angle, layout) in degrees) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * 0.93f) }
                for ((angle, layout) in cardinals) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * LETTER_RADIUS) }
            }
        })

        Spacer(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn() }.drawWithCache {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val at = Offset(c.x, c.y - r * (RING_OUTER + RING_INNER) / 2f)
            val ring = Stroke(r * 0.012f)
            onDrawBehind {
                for (t in markers) {
                    rotate(t.dialAngleDeg ?: continue, c) {
                        val radius = if (t.focused) r * 0.075f else r * 0.048f
                        drawCircle(Color.Black, radius + r * 0.012f, at)
                        drawCircle(waypointColor(t.hue), radius, at)
                        if (t.focused) drawCircle(Color.Black, radius * 0.38f, at, style = ring)
                    }
                }
            }
        })

        Spacer(Modifier.fillMaxSize().drawWithCache {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            // The triangle's tip meets the ring, which leaves room for a letter to pass under its base.
            val triangle = Path().apply {
                moveTo(c.x, c.y - r * 0.71f); lineTo(c.x - r * 0.052f, c.y - r * 0.625f); lineTo(c.x + r * 0.052f, c.y - r * 0.625f); close()
            }
            onDrawBehind {
                drawLine(HeadingBlue, Offset(c.x, c.y - r * RING_OUTER), Offset(c.x, c.y - r * RING_INNER), r * 0.022f, StrokeCap.Round)
                drawPath(triangle, HeadingBlue)
            }
        })
    }
}

private fun DrawScope.centeredText(layout: TextLayoutResult, cx: Float, cy: Float) {
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}
