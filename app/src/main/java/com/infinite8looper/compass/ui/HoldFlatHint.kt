package com.infinite8looper.compass.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.flow.collectLatest

// One loop, in milliseconds: the watch appears upright, turns level, rests there, and fades out.
private const val LOOP_MS = 2000f
private const val APPEAR_END = 200f
private const val TILT_START = 300f
private const val TILT_END = 1300f
private const val REST_END = 1800f
/** The point of the loop shown when the system has animations switched off: level and fully visible. */
private const val STILL = (TILT_END + REST_END) / 2f / LOOP_MS

private const val STRAP_ANGLE = 26f

private val Ink = Color(0xFFE8EAED)
private val Strap = Color(0xFF9E9E9E)
private val LevelMark = Color(0xFF6E6E6E)

/**
 * What the compass shows in place of the heading while the watch is tilted too far to give one: a watch seen
 * from the side that turns from upright to level, again and again, between two level marks, and the words
 * "Hold flat".
 *
 * [modifier] places and sizes the drawing, which scales with the box's height and is 1.9 times as wide as it is
 * tall. The words are centered [labelOffset] below the middle of the drawing, which may be further down than the
 * drawing's own edge: the compass keeps a line of its own between the two.
 *
 * The loop runs only while the screen is started, stands still on the level position when the system has
 * animations switched off, and is read only while drawing, so a new frame recomposes nothing.
 */
@Composable
fun HoldFlatHint(labelStyle: TextStyle, labelOffset: Dp, modifier: Modifier = Modifier) {
    // The position in the loop, 0 to 1. It starts at 0, where the watch has not yet faded in.
    val loop = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val motion = coroutineContext[MotionDurationScale]
            snapshotFlow { motion?.scaleFactor ?: 1f }.collectLatest { scale ->
                if (scale <= 0f) {
                    loop.floatValue = STILL
                    return@collectLatest
                }
                val lengthNanos = (LOOP_MS * 1e6f * scale).toLong().coerceAtLeast(1L)
                val start = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    loop.floatValue = ((now - start) % lengthNanos) / lengthNanos.toFloat()
                }
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Spacer(Modifier.matchParentSize().drawBehind { drawWatch(loop.floatValue * LOOP_MS) })
        Text(
            "Hold flat", color = Dim, style = labelStyle, maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentSize(unbounded = true).offset(y = labelOffset),
        )
    }
}

/** The drawing at [ms] into the loop. Every length is a fraction of the box height. */
private fun DrawScope.drawWatch(ms: Float) {
    val h = size.height
    val visible = when {
        ms < APPEAR_END -> ms / APPEAR_END
        ms > REST_END -> (LOOP_MS - ms) / (LOOP_MS - REST_END)
        else -> 1f
    }.coerceIn(0f, 1f)
    val laidDown = EaseInOutCubic.transform(((ms - TILT_START) / (TILT_END - TILT_START)).coerceIn(0f, 1f))
    val tilt = 90f * (1f - laidDown)
    val reached = ((ms - TILT_END) / 150f).coerceIn(0f, 1f)

    // Two level marks, one either side, in the weight of the dial's fine ticks. They do not fade with the watch:
    // they are what it lines up with, and they brighten when it does.
    val center = Offset(size.width / 2f, h / 2f)
    val markColor = lerp(LevelMark, Ink, reached * visible * 0.75f)
    val weight = h * 0.04f
    drawLine(markColor, Offset(center.x - h * 0.90f, center.y), Offset(center.x - h * 0.62f, center.y), weight, StrokeCap.Round)
    drawLine(markColor, Offset(center.x + h * 0.62f, center.y), Offset(center.x + h * 0.90f, center.y), weight, StrokeCap.Round)

    // The watch is drawn lying level (glass up, 12 o'clock end to the right, a strap stub bending down from each
    // end) and turned about the middle of its case, so upright it stands between the marks, glass to the left.
    val caseHalf = h * 0.27f
    val caseDepth = h * 0.18f
    val glassDepth = h * 0.06f
    val strapLength = h * 0.20f
    val strapDepth = h * 0.08f
    val strapY = caseDepth * 0.12f
    rotate(-tilt, center) {
        val strapSize = Size(strapLength, strapDepth)
        val strapRound = CornerRadius(strapDepth / 2f)
        val right = Offset(center.x + caseHalf - strapDepth / 2f, center.y + strapY)
        rotate(STRAP_ANGLE, right) {
            drawRoundRect(Strap, Offset(right.x, right.y - strapDepth / 2f), strapSize, strapRound, alpha = visible)
        }
        val left = Offset(center.x - caseHalf + strapDepth / 2f, center.y + strapY)
        rotate(-STRAP_ANGLE, left) {
            drawRoundRect(Strap, Offset(left.x - strapLength, left.y - strapDepth / 2f), strapSize, strapRound, alpha = visible)
        }
        drawRoundRect(
            Ink, Offset(center.x - caseHalf, center.y - caseDepth / 2f), Size(caseHalf * 2f, caseDepth),
            CornerRadius(h * 0.055f), alpha = visible,
        )
        // The glass, in the heading's blue: the side that has to face up.
        val glassHalf = caseHalf * 0.74f
        drawRoundRect(
            HeadingBlue, Offset(center.x - glassHalf, center.y - caseDepth / 2f - glassDepth * 0.55f),
            Size(glassHalf * 2f, glassDepth), CornerRadius(glassDepth / 2f), alpha = visible,
        )
        // The crown, end-on.
        drawCircle(Color.Black, h * 0.034f, center, alpha = visible)
    }
}
