package com.infinite8looper.compass.ui

import android.app.RemoteInput
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.nav.HOLD_TO_DELETE_MS
import com.infinite8looper.compass.nav.WHEEL_BAND_INNER
import com.infinite8looper.compass.nav.WHEEL_BAND_OUTER
import com.infinite8looper.compass.nav.colorWriteWaitMs
import com.infinite8looper.compass.nav.holdProgress
import com.infinite8looper.compass.nav.isAlongWheel
import com.infinite8looper.compass.nav.isBackSwipe
import com.infinite8looper.compass.nav.normalizeDeg
import com.infinite8looper.compass.nav.startsOnWheel
import com.infinite8looper.compass.nav.staysOnWheel
import com.infinite8looper.compass.nav.wheelHue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** Opens the system voice or keyboard input and returns what was entered. */
@Composable
fun rememberTextInput(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("name") }
        if (text != null) onText(text.toString())
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("name").setLabel("Name").build()))
        launcher.launch(intent)
    }
}

/**
 * Calls [onDone] at most once. The screen leaves both when the wearer deletes its waypoint and when the waypoint
 * it shows has gone, which happen together (a delete leaves the screen and then the waypoint list updates).
 */
@Composable
private fun rememberLeaveOnce(onDone: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(onDone)
    var left by remember { mutableStateOf(false) }
    return { if (!left) { left = true; current() } }
}

/**
 * Everything about one waypoint, on one screen, in fractions of the screen's radius:
 * - the frame is the color wheel. A finger that goes down on it and moves along it, or taps it, picks the color
 *   under it, and the crown turns the color;
 * - a row above the middle shows and switches whether the waypoint is a target;
 * - the name is in the middle, and a tap on it opens the system's text input;
 * - `Hold to delete` is below it.
 *
 * A change is stored as it is made; there is nothing to confirm and no way to leave without it. A right swipe
 * goes back, which is the navigation host's doing, wherever it starts: the wheel takes a touch that goes down on
 * it only when the touch then moves along the wheel or does not move, and a swipe from the edge moves toward the
 * middle. A drag along the wheel that leaves it toward the middle puts the color back as it was.
 *
 * [onDone] leaves the screen. It is called once: when the waypoint has been deleted here, when a touch the wheel
 * gave up ends as a swipe back, or when the screen finds that its waypoint does not exist.
 */
@Composable
fun EditScreen(vm: CompassViewModel, id: Long, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val w = nav.waypoints.firstOrNull { it.id == id }
    val leave = rememberLeaveOnce(onDone)
    if (w == null) {
        // Deleted, or not loaded yet. Leave once the stores have answered and the waypoint is still missing.
        LaunchedEffect(nav.loaded) { if (nav.loaded) leave() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    EditWaypoint(vm, w, leave)
}

@Composable
private fun EditWaypoint(vm: CompassViewModel, w: Waypoint, leave: () -> Unit) {
    val view = LocalView.current
    val id = w.id
    // The color as it is drawn. It starts from the stored one and from then on is the wearer's: what the store
    // sends back while the wheel is being turned is an earlier value of this, and is not taken.
    val hue = remember(id) { mutableFloatStateOf(w.hue) }
    val writes = remember(vm, id) { HueWrites(w.hue) { vm.setHue(id, it) } }
    // The color is drawn at once and written at most a few times a second: a change waits out what is left of
    // the gap since the last write, and gives way to a newer change that comes before then.
    LaunchedEffect(writes) {
        snapshotFlow { hue.floatValue }.collectLatest { changed ->
            delay(colorWriteWaitMs(SystemClock.uptimeMillis(), writes.lastWriteMs))
            // Only what is still drawn is written: a color the wearer has since given up is not.
            if (changed == hue.floatValue) writes.write(changed)
        }
    }
    // Leaving the screen within the gap writes what is drawn. The write is the view model's and outlives the screen.
    DisposableEffect(writes) { onDispose { writes.write(hue.floatValue) } }

    // How far a hold on `Hold to delete` has come, from 0 to 1. Written every frame of a hold and read only where
    // the frame is drawn.
    val held = remember(id) { Animatable(0f) }
    var holding by remember(id) { mutableStateOf(false) }
    var holdStartedMs by remember(id) { mutableLongStateOf(0L) }
    val delete = remember(vm, id, leave) { { vm.delete(id); leave() } }
    LaunchedEffect(holding) {
        if (!holding) {
            // Let go before the end: nothing is deleted and the frame fills again.
            if (held.value < 1f) held.animateTo(0f, tween(HOLD_REFILL_MS))
            return@LaunchedEffect
        }
        while (true) {
            val progress = holdProgress(withFrameMillis { SystemClock.uptimeMillis() } - holdStartedMs, HOLD_TO_DELETE_MS)
            held.snapTo(progress)
            if (progress >= 1f) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                delete()
                return@LaunchedEffect
            }
        }
    }

    val rename = rememberTextInput { vm.rename(id, it) }
    val tolerance = with(LocalDensity.current) { WHEEL_TOLERANCE.toPx() }
    val leaveTolerance = with(LocalDensity.current) { WHEEL_LEAVE_TOLERANCE.toPx() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                hue.floatValue = normalizeDeg(hue.floatValue + e.verticalScrollPixels * CROWN_DEG_PER_PIXEL)
                true
            }
            .requestFocusOnHierarchyActive().focusable()
            // The wheel. It sees a touch before anything inside the screen does and before the swipe that goes
            // back. A touch that goes down on it is no one else's tap or hold. What it becomes is decided when it
            // has moved far enough to be a drag:
            // - along the wheel, it is the wheel's and picks the color under the finger for as long as it stays on
            //   the wheel. If it leaves the wheel toward the middle the color goes back to what it was when the
            //   finger came down, drawn and stored, and the rest of the touch changes nothing; lifted more than
            //   half the screen to the right of where it began, it goes back;
            // - toward the middle, it is left alone from the first movement, so the swipe from the edge that goes
            //   back is the navigation host's like any other and no color changes.
            // A touch that does not move is a tap, and picks the color it landed on.
            .pointerInput(id, tolerance, leaveTolerance) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = minOf(size.width, size.height) / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val from = down.position - c
                    if (!startsOnWheel(from.getDistance(), r, tolerance)) return@awaitEachGesture
                    down.consume()
                    val before = hue.floatValue
                    var touch = WheelTouch.UNDECIDED
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val moved = change.position - down.position
                        if (!change.pressed) {
                            when (touch) {
                                // A finger that rests on the wheel and is lifted again, as one that missed
                                // `Hold to delete` would, picks nothing.
                                WheelTouch.UNDECIDED -> if (change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                                    wheelHue(from.x, from.y, r)?.let { hue.floatValue = it }
                                }
                                WheelTouch.RECOLOR -> Unit
                                WheelTouch.GIVEN_UP -> if (isBackSwipe(moved.x, size.width.toFloat())) leave()
                            }
                            change.consume()
                            break
                        }
                        if (touch == WheelTouch.UNDECIDED && moved.getDistance() > viewConfiguration.touchSlop) {
                            // Not the wheel's: nothing of it has been taken but its first contact.
                            if (!isAlongWheel(from.x, from.y, moved.x, moved.y)) return@awaitEachGesture
                            touch = WheelTouch.RECOLOR
                        }
                        val at = change.position - c
                        if (touch == WheelTouch.RECOLOR) {
                            if (staysOnWheel(at.getDistance(), r, leaveTolerance)) wheelHue(at.x, at.y, r)?.let { hue.floatValue = it }
                            else {
                                touch = WheelTouch.GIVEN_UP
                                hue.floatValue = before
                                writes.write(before)
                            }
                        }
                        if (touch != WheelTouch.UNDECIDED) change.consume()
                    }
                    // Let go: what is drawn is written now.
                    writes.write(hue.floatValue)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val r = minOf(maxWidth, maxHeight) / 2
        val density = LocalDensity.current
        val labelStyle = remember(r, density) { density.instrumentText(r * 0.105f, align = TextAlign.Center) }
        val nameStyle = remember(r, density) { density.instrumentText(r * 0.17f, FontWeight.Light, TextAlign.Center) }

        // While `Hold to delete` is held the wheel gives its place to a ring in the color of deleting, which
        // drains as the hold goes on; the frame's ticks are what is left behind it.
        FrameRing(fill = { if (held.value > 0f) 1f - held.value else 0f }, fillColor = ErrorColor, fillInner = WHEEL_BAND_INNER)
        ColorWheel(hue = { hue.floatValue }, shown = { held.value <= 0f }, onHue = { hue.floatValue = it; writes.write(it) })

        // The three rows are each as tall as a touch needs and stand half a radius apart.
        TargetRow(
            w.isTarget, { hue.floatValue }, labelStyle,
            Modifier.offset(y = -r * ROW_Y).width(r * 1.0f).height(HitHeight)
                .toggleable(w.isTarget, role = Role.Switch) { vm.setTarget(id, it) },
        )
        Row(
            Modifier.width(r * 1.36f).height(HitHeight)
                .clickable(role = Role.Button, onClickLabel = "Rename", onClick = rename),
            Arrangement.Center, Alignment.CenterVertically,
        ) {
            ColorDot({ hue.floatValue }, Modifier.size(r * 0.075f))
            Spacer(Modifier.width(r * 0.05f))
            BasicText(
                w.name, Modifier.weight(1f, fill = false), nameStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = { Ink },
                autoSize = TextAutoSize.StepBased(minFontSize = nameStyle.fontSize * 0.62f, maxFontSize = nameStyle.fontSize, stepSize = 0.5.sp),
            )
        }
        var place by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            Modifier.offset(y = r * ROW_Y).width(r * 1.1f).height(HitHeight)
                .onGloballyPositioned { place = it }
                // For an accessibility service the hold is a long press with a name, and the same thing is offered
                // as a named action of its own.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Hold to delete ${w.name}"
                    onLongClick("Delete") { delete(); true }
                    customActions = listOf(CustomAccessibilityAction("Delete") { delete(); true })
                }
                .pointerInput(id, tolerance) {
                    awaitEachGesture {
                        // A touch the wheel has taken does not reach here unconsumed. The same question is asked
                        // again of where the finger went down on the screen, so the answer does not rest on the
                        // order the two are asked in.
                        val down = awaitFirstDown()
                        val onWheel = place?.takeIf { it.isAttached }?.let { here ->
                            val screen = here.findRootCoordinates().size
                            val at = here.localToRoot(down.position) - Offset(screen.width / 2f, screen.height / 2f)
                            startsOnWheel(at.getDistance(), minOf(screen.width, screen.height) / 2f, tolerance)
                        } ?: true
                        if (onWheel) return@awaitEachGesture
                        holdStartedMs = down.uptimeMillis
                        holding = true
                        // Ends when the finger lifts, leaves the row, or becomes the swipe that goes back.
                        try { waitForUpOrCancellation() } finally { holding = false }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("Hold to delete", color = ErrorColor, style = labelStyle, maxLines = 1)
        }
    }
}

/** The color's writes to the store: the last color written and when, so the same color is not written twice. */
private class HueWrites(initial: Float, private val store: (Float) -> Unit) {
    private var written = initial
    var lastWriteMs: Long? = null
        private set

    fun write(hue: Float) {
        if (hue == written) return
        written = hue
        lastWriteMs = SystemClock.uptimeMillis()
        store(hue)
    }
}

/**
 * The frame as a color wheel: every hue around the ring, 0 at 12 o'clock and growing clockwise, and a line across
 * the ring at the waypoint's color, drawn like a focused target's line on the dial. [hue] and [shown] are read
 * while drawing. An accessibility service sets the color through [onHue].
 */
@Composable
private fun ColorWheel(hue: () -> Float, shown: () -> Boolean, onHue: (Float) -> Unit) {
    Spacer(
        Modifier.fillMaxSize()
            // The hue is read where the description is made and where the wheel is drawn, not where this is
            // composed, so turning the color composes nothing again.
            .semantics {
                val current = hue()
                contentDescription = "Color"
                stateDescription = "Hue ${current.roundToInt()} of 360"
                progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..360f)
                setProgress("Set color") { value -> onHue(normalizeDeg(value.coerceIn(0f, 359.9f))); true }
            }
            .drawWithCache {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                // A sweep starts at 3 o'clock, so the list is a quarter turn on to put hue 0 at the top.
                val wheel = Brush.sweepGradient((0..360 step 10).map { waypointColor(((it + 90) % 360).toFloat()) }, c)
                val mid = r * (WHEEL_BAND_OUTER + WHEEL_BAND_INNER) / 2f
                val band = Stroke(r * (WHEEL_BAND_OUTER - WHEEL_BAND_INNER))
                onDrawBehind {
                    if (!shown()) return@onDrawBehind
                    drawCircle(wheel, mid, c, style = band)
                    val h = hue()
                    rotate(h, c) {
                        val outer = c.y - r * WHEEL_MARK_OUTER
                        val inner = c.y - r * WHEEL_MARK_INNER
                        val casing = r * WHEEL_MARK_CASING
                        drawLine(Color.Black, Offset(c.x, outer - casing), Offset(c.x, inner + casing), r * WHEEL_MARK_WIDTH + 2f * casing, StrokeCap.Round)
                        drawLine(Ink, Offset(c.x, outer), Offset(c.x, inner), r * WHEEL_MARK_WIDTH, StrokeCap.Round)
                        drawLine(waypointColor(h), Offset(c.x, outer), Offset(c.x, inner), r * WHEEL_MARK_WIDTH * 0.56f, StrokeCap.Round)
                    }
                }
            }
    )
}

/** The switch for "is a target": the word and a tick in the waypoint's color, as in a row of the list. */
@Composable
private fun TargetRow(on: Boolean, hue: () -> Float, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    Row(modifier, Arrangement.Center, Alignment.CenterVertically) {
        Text("Target", color = if (on) Ink else Dim, style = style, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        TickToggle(on, color = { waypointColor(hue()) })
    }
}

/** A dot in the waypoint's color beside its name, as on the compass. The color is read while drawing. */
@Composable
private fun ColorDot(hue: () -> Float, modifier: Modifier) {
    Spacer(modifier.drawBehind { drawCircle(waypointColor(hue())) })
}

/** How far inside the wheel's band a touch still starts on it: the room a fingertip aimed at the band needs. */
private val WHEEL_TOLERANCE = 8.dp

/**
 * How far inside the wheel's band a drag along it may stray before it has left the wheel: twice the room a touch
 * gets to start on it, since a finger that follows the ring round wanders more than one that is put down on it.
 */
private val WHEEL_LEAVE_TOLERANCE = 16.dp

/** What a touch that went down on the wheel is. */
private enum class WheelTouch {
    /** It has not moved far enough to be a drag: a tap or a rest so far. */
    UNDECIDED,
    /** A drag on the wheel: the color follows the finger. */
    RECOLOR,
    /** A drag that left the wheel toward the middle: the color is back where it was and stays there. */
    GIVEN_UP,
}

/** One click of the emulator's crown is 128 pixels: 32 degrees of hue, about eleven clicks to the turn. */
private const val CROWN_DEG_PER_PIXEL = 0.25f

/** Where the target row and `Hold to delete` stand, in radii above and below the name. */
private const val ROW_Y = 0.5f

/** How long the frame takes to fill again when a hold is let go. */
private const val HOLD_REFILL_MS = 180

// The line that marks the waypoint's color on the wheel runs past both edges of the band.
private const val WHEEL_MARK_OUTER = 0.985f
private const val WHEEL_MARK_INNER = 0.80f
private const val WHEEL_MARK_WIDTH = 0.05f
private const val WHEEL_MARK_CASING = 0.014f
