package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.infinite8looper.compass.nav.TargetUi

val HeadingBlue = Color(0xFF2F8BFF)
val NorthRed = Color(0xFFFF3B30)
private val TickGray = Color(0xFF6E6E6E)
private val LabelGray = Color(0xFF9E9E9E)
private val Ink = Color(0xFFE8EAED)

fun waypointColor(hue: Float): Color = Color.hsv(hue, 0.65f, 1f)

@Composable
fun CompassDial(headingProvider: () -> Float, targets: List<TargetUi>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Spacer(modifier.fillMaxSize().drawWithCache {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val degStyle = TextStyle(color = LabelGray, fontSize = (r * 0.095f).toSp())
        val cardStyle = TextStyle(color = Ink, fontSize = (r * 0.17f).toSp(), fontWeight = FontWeight.Medium)
        val degrees = (0 until 360 step 30).map { it to measurer.measure(it.toString(), degStyle) }
        val cardinals = listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").map { (a, s) -> a to measurer.measure(s, cardStyle) }
        val ringOuter = r * 0.84f; val ringInner = r * 0.72f
        val triangle = Path().apply {
            moveTo(c.x, c.y - r * 0.68f); lineTo(c.x - r * 0.06f, c.y - r * 0.58f); lineTo(c.x + r * 0.06f, c.y - r * 0.58f); close()
        }
        onDrawBehind {
            val heading = headingProvider().let { if (it.isNaN()) 0f else it }
            rotate(-heading, c) {
                for (d in 0 until 360 step 2) {
                    val heavy = d % 30 == 0
                    rotate(d.toFloat(), c) {
                        drawLine(
                            color = if (d == 0) NorthRed else if (heavy) Ink else TickGray,
                            start = Offset(c.x, c.y - ringOuter), end = Offset(c.x, c.y - ringInner),
                            strokeWidth = if (heavy) r * 0.022f else r * 0.01f, cap = StrokeCap.Round,
                        )
                    }
                }
                for ((angle, layout) in degrees) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * 0.93f) }
                for ((angle, layout) in cardinals) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * 0.50f) }
                // Unfocused markers first so the focused one is drawn on top.
                for (t in targets.sortedBy { it.focused }) {
                    val a = t.dialAngleDeg ?: continue
                    rotate(a, c) {
                        val at = Offset(c.x, c.y - (ringOuter + ringInner) / 2f)
                        val radius = if (t.focused) r * 0.075f else r * 0.048f
                        drawCircle(Color.Black, radius + r * 0.012f, at)
                        drawCircle(waypointColor(t.hue), radius, at)
                        if (t.focused) drawCircle(Color.Black, radius * 0.38f, at, style = Stroke(r * 0.012f))
                    }
                }
            }
            drawLine(HeadingBlue, Offset(c.x, c.y - ringOuter), Offset(c.x, c.y - ringInner), r * 0.022f, StrokeCap.Round)
            drawPath(triangle, HeadingBlue)
        }
    })
}

private fun DrawScope.centeredText(layout: TextLayoutResult, cx: Float, cy: Float) {
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}
