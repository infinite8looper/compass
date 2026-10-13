package com.infinite8looper.compass.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.infinite8looper.compass.nav.MARQUEE_DP_PER_SECOND
import com.infinite8looper.compass.nav.marqueeOffset
import com.infinite8looper.compass.nav.marqueeRestMs
import com.infinite8looper.compass.nav.marqueeScrolls
import com.infinite8looper.compass.nav.marqueeTimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * One line of text in a place that may be too narrow for it: a waypoint's name, wherever one is shown.
 *
 * Text that fits is drawn as a plain line of text is, where [style]'s alignment puts it, and nothing moves.
 * Text that does not fit is cut off at the edges of its place and slides back and forth in it ([marqueeOffset]):
 * it waits at its start, slides until its end shows, waits, and slides back. The place itself neither moves nor
 * changes size, and takes no touch: a tap goes to whatever the text stands on. Where the text runs on past an edge
 * it fades out over a few pixels there.
 *
 * With the system's animations switched off nothing slides, and text that does not fit ends in an ellipsis.
 *
 * It slides only while the app is on screen and [running] says so; otherwise it stands at its start and asks for
 * no frame; and while it waits at an end it asks for none either. A new [text] starts from the first wait. The slide is applied to the text's layer while it is placed:
 * a frame of it measures nothing, draws no text again and composes nothing. [color] is read while drawing.
 */
@Composable
fun MarqueeText(
    text: String, style: TextStyle, color: () -> Color, modifier: Modifier = Modifier, running: () -> Boolean = { true },
) {
    val scale = motionScale()
    // Whether text that is too long would slide at all: not with the system's animations switched off.
    val slides = marqueeScrolls(overflowPx = 1, scale)
    // How much wider the text is than its place, in pixels, written when it is measured; and how far it has slid,
    // written every frame of a slide. Neither is read where anything is composed.
    val overflow = remember { mutableIntStateOf(0) }
    val offset = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The loop below outlives a recomposition, so it asks whichever [running] was passed last.
    val isRunning by rememberUpdatedState(running)
    val density = LocalDensity.current
    val perSecond = with(density) { MARQUEE_DP_PER_SECOND.dp.toPx() }

    LaunchedEffect(text, scale, perSecond, lifecycle) {
        offset.floatValue = 0f
        if (!slides) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Nothing to slide stands for "not running" too: the loop below is left, and no frame is asked for.
            snapshotFlow { if (isRunning()) overflow.intValue else 0 }.collectLatest { over ->
                offset.floatValue = 0f
                if (!marqueeScrolls(over, scale)) return@collectLatest
                var start = -1L
                var restMs = 0.0
                // One function for every frame, made here: a frame makes nothing new.
                val frame: (Long) -> Unit = { now ->
                    if (start < 0L) start = now
                    val ms = marqueeTimeMs(now - start, scale)
                    offset.floatValue = marqueeOffset(ms, over.toFloat(), perSecond)
                    restMs = marqueeRestMs(ms, over.toFloat(), perSecond)
                }
                try {
                    while (true) {
                        withFrameNanos(frame)
                        // While the text waits at an end nothing changes: no frame is asked for until the wait is
                        // nearly over. The time is the system's animation time, so the wait is scaled back.
                        if (restMs > MARQUEE_FRAME_MS) delay(((restMs - MARQUEE_FRAME_MS) * scale).toLong())
                    }
                } finally { offset.floatValue = 0f }
            }
        }
    }

    // Which edges the text runs on past: read while drawing, so the fades are drawn again only when one of the two
    // changes, which is four times in a cycle.
    val pastStart = remember { derivedStateOf { offset.floatValue > 0f } }
    val pastEnd = remember { derivedStateOf { overflow.intValue > 0 && offset.floatValue < overflow.intValue } }
    val fade = with(density) { MARQUEE_FADE.toPx() }

    Layout(
        content = {
            BasicText(
                text, style = style, maxLines = 1, softWrap = false, color = { color() },
                overflow = if (slides) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        },
        modifier = modifier
            // Cut off at the edges of the place. Only text that slides is drawn into a picture of its own before it
            // goes onto the screen, which is what lets its ends fade out.
            .graphicsLayer {
                clip = true
                compositingStrategy = if (overflow.intValue > 0) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                // No wider than the text is too long by: a name a few pixels too long loses a few pixels at an edge.
                val soft = minOf(fade, overflow.intValue.toFloat())
                if (soft > 0f && size.width > 2f * soft) {
                    if (pastStart.value) drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, soft), blendMode = BlendMode.DstIn,
                    )
                    if (pastEnd.value) drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - soft, size.width), blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) { measurables, constraints ->
        // Text that may slide is measured at its full width; text that may not is given its place and ends in an
        // ellipsis if that is too narrow.
        val line = measurables.first().measure(
            if (slides) Constraints(maxHeight = constraints.maxHeight) else constraints.copy(minWidth = 0, minHeight = 0)
        )
        val width = line.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = line.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val over = (line.width - width).coerceAtLeast(0)
        overflow.intValue = over
        layout(width, height) {
            val y = (height - line.height) / 2
            if (over > 0) {
                // Whole pixels: text moved by part of a pixel is drawn slightly differently from frame to frame.
                line.placeWithLayer(0, y) { translationX = -offset.floatValue.roundToInt().toFloat() }
            } else {
                // In a place wider than the text, the text stands where its alignment puts it.
                val x = when (style.textAlign) {
                    TextAlign.Center -> (width - line.width) / 2
                    TextAlign.End, TextAlign.Right -> width - line.width
                    else -> 0
                }
                line.place(x, y)
            }
        }
    }
}

/** The system's animation scale for this screen: 1 as it comes, 0 with animations removed. Composed again when it changes. */
@Composable
private fun motionScale(): Float = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f

/**
 * Whether text that is too long for its place slides here at all ([MarqueeText]), which it does not with the
 * system's animations removed. For a screen that lays a name out differently when it is going to slide.
 */
@Composable
internal fun marqueeSlides(): Boolean = marqueeScrolls(overflowPx = 1, motionScale())

/** About one frame, in milliseconds: a wait is left this much early, so the slide starts on time. */
private const val MARQUEE_FRAME_MS = 17.0

/** The most the text fades out over at an edge it runs on past. */
private val MARQUEE_FADE = 8.dp
