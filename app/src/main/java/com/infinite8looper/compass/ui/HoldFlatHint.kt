package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.infinite8looper.compass.nav.HOLD_FLAT_LOOP_MS
import com.infinite8looper.compass.nav.HOLD_FLAT_STILL_MS
import com.infinite8looper.compass.nav.holdFlatLevelReached
import com.infinite8looper.compass.nav.holdFlatTiltDeg
import com.infinite8looper.compass.nav.holdFlatVisibility
import kotlinx.coroutines.flow.collectLatest

private const val STRAP_ANGLE = 26f

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
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    // The time into the loop, in milliseconds. With animations off it is the still position from the first
    // frame on; otherwise it starts at 0, where the watch has not yet faded in.
    val loopMs = remember { mutableFloatStateOf(if (motion?.scaleFactor == 0f) HOLD_FLAT_STILL_MS else 0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, motion) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { motion?.scaleFactor ?: 1f }.collectLatest { scale ->
                if (scale <= 0f) {
                    loopMs.floatValue = HOLD_FLAT_STILL_MS
                    return@collectLatest
                }
                val lengthNanos = (HOLD_FLAT_LOOP_MS * 1e6f * scale).toLong().coerceAtLeast(1L)
                val start = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    loopMs.floatValue = ((now - start) % lengthNanos) / lengthNanos.toFloat() * HOLD_FLAT_LOOP_MS
                }
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Spacer(Modifier.matchParentSize().drawBehind { drawWatch(loopMs.floatValue) })
        Text(
            "Hold flat", color = Dim, style = labelStyle, maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentSize(unbounded = true).offset(y = labelOffset),
        )
    }
}

/** The drawing at [ms] into the loop. Every length is a fraction of the box height. */
private fun DrawScope.drawWatch(ms: Float) {
    val h = size.height
    val visible = holdFlatVisibility(ms)
    val tilt = holdFlatTiltDeg(ms)
    val reached = holdFlatLevelReached(ms)

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
            drawRoundRect(Dim, Offset(right.x, right.y - strapDepth / 2f), strapSize, strapRound, alpha = visible)
        }
        val left = Offset(center.x - caseHalf + strapDepth / 2f, center.y + strapY)
        rotate(-STRAP_ANGLE, left) {
            drawRoundRect(Dim, Offset(left.x - strapLength, left.y - strapDepth / 2f), strapSize, strapRound, alpha = visible)
        }
        drawRoundRect(
            Ink, Offset(center.x - caseHalf, center.y - caseDepth / 2f), Size(caseHalf * 2f, caseDepth),
            CornerRadius(h * 0.055f), alpha = visible,
        )
        // The glass, in the accent color: the side that has to face up.
        val glassHalf = caseHalf * 0.74f
        drawRoundRect(
            AccentColor, Offset(center.x - glassHalf, center.y - caseDepth / 2f - glassDepth * 0.55f),
            Size(glassHalf * 2f, glassDepth), CornerRadius(glassDepth / 2f), alpha = visible,
        )
        // The crown, end-on.
        drawCircle(Color.Black, h * 0.034f, center, alpha = visible)
    }
}
