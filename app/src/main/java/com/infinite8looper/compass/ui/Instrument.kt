package com.infinite8looper.compass.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.dynamicColorScheme
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.ScrollArc
import com.infinite8looper.compass.nav.WatchScheme
import com.infinite8looper.compass.nav.ringColor
import com.infinite8looper.compass.nav.roleColors
import com.infinite8looper.compass.nav.rowInset
import com.infinite8looper.compass.nav.softTintOf

// The colors the screens share, each named for what it is used for. A screen takes a color from here by its
// role, so a role's color is changed in this one place.

/** The light color of text, heavy ticks and outlines. */
internal val Ink = Color(0xFFE8EAED)
/** Secondary text and prompts. */
internal val Dim = Color(0xFF9E9E9E)
/** Fine ticks, and anything that is switched off or not the current one. */
internal val Faint = Color(0xFF6E6E6E)

// Three of the colors follow the watch: on a watch whose system gives apps a color scheme taken from its watch
// face, the accent is that scheme's primary color, north its tertiary and the error color its error color, after
// the checks in nav/WatchColors.kt. On any other watch they are the app's own blue and red. They are held as
// state, so a place that reads one while composing is composed again when it changes, and a place that reads one
// while drawing is drawn again: nothing has to be told. Setting them to what they already are changes nothing.
// The waypoints' colors are made from these three ([waypointColor]) and follow them the same way.
private val roles = mutableStateOf(roleColors(null))

/** The heading pointer, and anything that is on or can be acted on. */
val AccentColor: Color get() = opaque(roles.value.accent)
/** North on the dial. */
val NorthColor: Color get() = opaque(roles.value.north)
/** A failure, and deleting. Without a scheme it has the same value as [NorthColor]; it is a separate role. */
val ErrorColor: Color get() = opaque(roles.value.error)

private fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

/**
 * The watch's color scheme as the Wear Material 3 library reports it, or null where the library reports none. The
 * library reports one only on API level 35 and above, and only while the system's setting for dynamic color is on.
 */
fun watchScheme(context: Context): WatchScheme? =
    dynamicColorScheme(context)?.let { WatchScheme(it.primary.toArgb(), it.tertiary.toArgb(), it.error.toArgb()) }

/**
 * Reads the watch's color scheme and sets the three colors that follow it. The activity calls this before it shows
 * anything and each time it comes back on screen, so a scheme that changed while the app was out of sight is in
 * the app when the wearer returns.
 */
fun followWatchColors(context: Context) {
    roles.value = roleColors(watchScheme(context))
}

/** A confirmation that something was done, such as a waypoint saved: a soft green. */
val ConfirmColor = Color(0xFF7FD99A)

/**
 * A waypoint's own color: its line and arrow on the dial, and its mark wherever it is listed. It is the color at the
 * waypoint's place on a ring that fades from the accent to north, on to the error color and back to the accent
 * ([ringColor]); [position] is the waypoint's stored value, from 0 to 360, the accent being at 0.
 *
 * The three colors are the ones that follow the watch, read as they are at this moment, so a waypoint's color
 * follows the watch too: read while drawing, it is drawn again when one of them changes, and read while composing,
 * composed again. Nothing is allocated.
 */
fun waypointColor(position: Float): Color = opaque(ringAt(position))

private fun ringAt(position: Float): Int = roles.value.let { ringColor(position / 360f, it.accent, it.north, it.error) }

/**
 * The breadcrumb trail: its ticks on the dial, the crumb that is walked to, and its marks wherever it is listed. One
 * muted blue-gray for every crumb. It does not follow the watch's color scheme and is not on the ring the waypoints'
 * colors come from; with a pale scheme a waypoint's color can come near it.
 */
val CrumbColor = Color(0xFF8FA6BF)

/**
 * The pale, partly transparent version of a waypoint's color that its line has while another target is focused. It
 * is made from [waypointColor] for the same [position], so the two always have one hue: the same color with less
 * saturation ([softTintOf]), drawn at 0.6 of full strength.
 */
fun softTint(position: Float): Color = Color(SOFT_TINT_ALPHA or softTintOf(ringAt(position)))

/** 0.6 of full strength (153 of 255), as the top eight bits of a color. */
private const val SOFT_TINT_ALPHA = 0x99 shl 24

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
 *
 * A screen with a [FrameRing] gives the ring its place among the three instead of calling this: the ring leaves
 * room for the ticks and draws them in its own band.
 */
@Composable
fun PanePosition(
    index: Int, modifier: Modifier = Modifier, radius: Float = PANE_TICKS_AT_EDGE, scale: Float = 1f, alpha: () -> Float = { 1f },
) {
    Spacer(modifier.fillMaxSize().drawBehind { paneTicks(index, radius, scale, alpha()) })
}

private fun DrawScope.paneTicks(index: Int, radius: Float, scale: Float, shown: Float) {
    if (shown <= 0f) return
    val r = size.minDimension / 2f
    val step = paneTickStepDeg(radius, scale)
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
}

/** The angle between two pane ticks: the same distance apart wherever on the radius they sit. */
private fun paneTickStepDeg(radius: Float, scale: Float): Float =
    Math.toDegrees((PANE_TICK_SPACING * scale / radius).toDouble()).toFloat()

// The frame: a thin ring of ticks at the edge of every screen but the compass.

/** The outer and inner edge of the frame's ticks, in fractions of the screen's radius. */
internal const val FRAME_OUTER = 0.97f
internal const val FRAME_INNER = 0.935f
/** Every thirtieth degree has a tick that is a little longer, as on the dial. */
private const val FRAME_INNER_HEAVY = 0.92f
private const val FRAME_TICK_WIDTH = 0.008f
private const val FRAME_TICK_STEP_DEG = 3
/** Around 6 o'clock the frame's own ticks leave this much room, each side, beyond the outermost pane tick. */
private const val FRAME_PANE_MARGIN_DEG = 5f
/** The scroll arc's track: this many degrees of the frame, centered on 3 o'clock, the top of the list at its upper end. */
private const val FRAME_SCROLL_TRACK_DEG = 56f
private const val FRAME_SCROLL_WIDTH = 0.024f

/**
 * The frame of a screen: a thin, still ring of ticks at the edge of the display, and what the frame says there.
 * It fills the box it is given and draws nothing in the middle, so it is put over or under a screen's content.
 *
 * - [paneIndex], on one of the three side-by-side screens: the screen's place among them (a [Pane]'s place in
 *   their order). The ring leaves 6 o'clock clear and the three pane ticks stand there, in the ring's own band.
 * - [scroll]: the part of a list that is on screen, as an arc in the accent color at 3 o'clock; null from the
 *   function when everything fits.
 * - [fill]: a part of the whole ring from 0 to 1, drawn in [fillColor] clockwise from 12 o'clock and reaching in
 *   to [fillInner] of the radius: how far something has come, or how much of it is left.
 *
 * [scroll] and [fill] are read while drawing: a list that scrolls or a value that runs redraws the arcs and
 * recomposes nothing. The ticks are drawn on a layer of their own and are not drawn again for it.
 */
@Composable
fun FrameRing(
    modifier: Modifier = Modifier,
    paneIndex: Int? = null,
    scroll: (() -> ScrollArc?)? = null,
    fill: (() -> Float)? = null,
    fillColor: Color = AccentColor,
    fillInner: Float = FRAME_INNER,
) {
    Box(modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxSize().graphicsLayer().drawBehind {
            val r = size.minDimension / 2f
            // The pane ticks stand a step apart either side of 6 o'clock; the ring's own stay clear of them.
            val clear = if (paneIndex == null) 0f else paneTickStepDeg(FRAME_OUTER, 1f) + FRAME_PANE_MARGIN_DEG
            for (d in 0 until 360 step FRAME_TICK_STEP_DEG) {
                if (kotlin.math.abs(d - 180) < clear) continue
                val heavy = d % 30 == 0
                rotate(d.toFloat(), center) {
                    drawLine(
                        if (heavy) Dim else Faint, Offset(center.x, center.y - r * FRAME_OUTER),
                        Offset(center.x, center.y - r * if (heavy) FRAME_INNER_HEAVY else FRAME_INNER),
                        r * FRAME_TICK_WIDTH * if (heavy) 1.5f else 1f,
                    )
                }
            }
            if (paneIndex != null) paneTicks(paneIndex, FRAME_OUTER, 1f, 1f)
        })
        if (scroll != null || fill != null) {
            Spacer(Modifier.fillMaxSize().drawBehind {
                val r = size.minDimension / 2f
                fill?.invoke()?.let { part ->
                    if (part > 0f) {
                        val mid = r * (FRAME_OUTER + fillInner) / 2f
                        drawArc(
                            fillColor, -90f, 360f * part.coerceAtMost(1f), false,
                            Offset(center.x - mid, center.y - mid), Size(2f * mid, 2f * mid), style = Stroke(r * (FRAME_OUTER - fillInner)),
                        )
                    }
                }
                scroll?.invoke()?.let { arc ->
                    val mid = r * (FRAME_OUTER + FRAME_INNER) / 2f
                    // 0 degrees is 3 o'clock for an arc, and the angle grows clockwise, which is down the list.
                    drawArc(
                        AccentColor, (arc.start - 0.5f) * FRAME_SCROLL_TRACK_DEG, (arc.end - arc.start) * FRAME_SCROLL_TRACK_DEG, false,
                        Offset(center.x - mid, center.y - mid), Size(2f * mid, 2f * mid),
                        style = Stroke(r * FRAME_SCROLL_WIDTH, cap = StrokeCap.Round),
                    )
                }
            })
        }
    }
}

// Rows and switches: text on black, with a tick at the right of a row for a switch.

/** The least height of anything that can be tapped, however small the mark drawn for it. */
internal val HitHeight = 48.dp

/** A list's rows are clipped to a circle of this fraction of the radius, inside the frame and the pane ticks. */
internal const val LIST_RADIUS = 0.875f
/** The ends of a row follow a ring of this fraction of the radius. */
internal const val ROW_RING = 0.80f

/** How much of a list's height, at its top and again at its bottom, the rows fade out over. */
private const val LIST_FADE = 0.2f

/** Fades what it is put on out toward its top and bottom edges: for a list whose rows scroll in and out of the frame. */
internal fun Modifier.fadeTopAndBottom(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, LIST_FADE to Color.Black, 1f - LIST_FADE to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

private val TickLength = 22.dp
private val TickStub = 7.dp
private val TickWidth = 3.dp

/**
 * A switch, drawn as a tick: long and in its color when [on], a short gray stub when off. It is a mark only; the
 * row it stands in is what is tapped, and says what it switches. [color] is read while drawing, so a color that
 * is being changed redraws the tick and recomposes nothing.
 */
@Composable
fun TickToggle(on: Boolean, modifier: Modifier = Modifier, color: () -> Color = { AccentColor }) {
    val shown = animateFloatAsState(if (on) 1f else 0f, tween(120), label = "tick")
    Spacer(modifier.size(TickLength + TickWidth, HitHeight / 2).drawBehind {
        // The tick grows inward from its right end, the way a tick stands on the edge of a dial.
        val right = size.width - TickWidth.toPx() / 2f
        val length = TickStub.toPx() + (TickLength.toPx() - TickStub.toPx()) * shown.value
        drawLine(lerp(Faint, color(), shown.value), Offset(right, center.y), Offset(right - length, center.y), TickWidth.toPx(), StrokeCap.Round)
    })
}

/**
 * A row of a list on the round screen: at least [HitHeight] tall and as wide as the list, with [content] at the
 * left and [trailing] (a [TickToggle], or nothing) at the right. What a tap or a long press on the row does comes
 * in with [modifier], so the whole row is the touch target.
 *
 * The two ends follow the round edge: a row at the middle of the screen reaches out to [ROW_RING] of the radius on
 * both sides, and a row above or below it is drawn as much shorter as the ring is narrower there. The row's place
 * is read after layout and applied while drawing, so scrolling moves the ends and neither measures nor recomposes
 * anything. [content] has the width that is free at about half the radius above or below the middle; nearer the
 * top and bottom its end can run under the tick.
 *
 * With [fitContent], [content] has all the width up to the tick instead, which is what a row in the middle of the
 * screen has free, and whatever the ends coming in leave no room for fades out at its right end, while drawing: a
 * row away from the middle shows the start of a long line, and all of it once it is scrolled to the middle. For
 * rows whose text is fixed and long.
 *
 * With [centered], [content] sits in the middle of the row instead and nothing moves: for a header, or a row that
 * is one word.
 */
@Composable
fun InstrumentRow(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    fitContent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val radius = screenRadius()
    val inset = remember { mutableFloatStateOf(0f) }
    Row(
        modifier.fillMaxWidth().heightIn(min = HitHeight)
            .onGloballyPositioned { row ->
                val screen = row.findRootCoordinates().size
                val y = row.boundsInRoot().center.y - screen.height / 2f
                inset.floatValue = rowInset(y, ROW_RING * minOf(screen.width, screen.height) / 2f)
            }
            .padding(horizontal = radius * (LIST_RADIUS - ROW_RING)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (centered) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
        else {
            Box(
                Modifier.weight(1f).then(
                    if (fitContent) Modifier.graphicsLayer { translationX = inset.floatValue; compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Both ends have come in by the inset, so the tick now starts that much twice over
                            // before the end of this box. The text is gone where the tick starts and whole a
                            // gap before that, which in the middle of the screen is where the text ends anyway.
                            val tick = size.width - 2f * inset.floatValue
                            if (tick < size.width) drawRect(
                                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), tick - ROW_FIT_GAP.toPx(), tick),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        .padding(end = ROW_FIT_GAP)
                    else Modifier.graphicsLayer { translationX = inset.floatValue }.padding(end = radius * ROW_TEXT_ROOM)
                ),
                contentAlignment = Alignment.CenterStart,
            ) { content() }
            if (trailing != null) Box(Modifier.graphicsLayer { translationX = -inset.floatValue }) { trailing() }
        }
    }
}

/** What the two ends of a row at half the radius from the middle have come in by, together: kept free of text. */
private const val ROW_TEXT_ROOM = 0.35f

/** With `fitContent`: the room kept between the text and the tick, which is also the width a line that is cut short fades out over. */
private val ROW_FIT_GAP = 8.dp

/** Half the shorter side of the app's window, which on a watch is the radius of the screen. */
@Composable
internal fun screenRadius(): Dp {
    val window = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { (minOf(window.width, window.height) / 2f).toDp() }
}

/** Text of the given height, set the way the compass sets its readout: no font padding, so lines sit where they are put. */
internal fun Density.instrumentText(size: Dp, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start): TextStyle {
    val sp = size.toSp()
    return TextStyle(
        fontSize = sp, lineHeight = sp * 1.15f, fontWeight = weight, textAlign = align,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}
