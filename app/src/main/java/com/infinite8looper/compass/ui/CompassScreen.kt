package com.infinite8looper.compass.ui

import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.focus.focusTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.*
import com.infinite8looper.compass.sensors.HeadingReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The compass, one of the three screens that sit side by side. [paneView] says how much of it the wearer has in
 * front of them, and everything here that depends on that asks it:
 * - hidden, the screen does no work: no heading is taken from the sensor, no frame is asked for, no timer runs;
 * - passing, while the row of screens moves, it is drawn live and does nothing else;
 * - in view, it also takes the crown, the side button, the vertical swipe and the long press, gives the tick and
 *   the confirming tap and the blind navigation pulses, and counts the time a logging result is shown.
 *
 * The pager may build this screen twice, once where the wearer sees it and once further along its row, so nothing
 * here may count on being the only one: what there is one of (the screen's keep-awake switch) is not set here.
 *
 * [onOpenList] is what a long press does, and [onSidePress] what a single press of a side button does. A double
 * press, which there is only with the breadcrumb trail setting on, switches the trail.
 */
@Composable
fun CompassScreen(
    vm: CompassViewModel, paneView: () -> PaneView, onOpenList: () -> Unit, onSidePress: () -> Unit, onCalibrate: () -> Unit,
) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Read where the screen is composed, so it is a derived state: the screen is composed again when it changes,
    // which is when the compass comes onto the screen or has left it, and not while the row moves.
    val onScreen by remember(paneView) { derivedStateOf { paneView() != PaneView.HIDDEN } }

    // Held as a State and read only in the frame loop and derived states, so sensor events do not recompose.
    // The sensor is listened to only while the compass is on screen; hidden, it keeps the last reading it had.
    val readingState = remember(vm) { mutableStateOf(vm.heading.value) }
    LaunchedEffect(vm, lifecycle, onScreen) {
        if (onScreen) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.heading.collect { readingState.value = it } }
    }
    val nav by vm.nav.collectAsStateWithLifecycle()
    val navState = rememberUpdatedState(nav)
    val shown = remember { mutableFloatStateOf(Float.NaN) }
    // Written every frame and read where the screen is composed: only a change of value recomposes.
    var holdFlat by remember { mutableStateOf(false) }
    // Whether the wearer faces the focused target. Written in the frame loop when it changes and read only where
    // the dial draws the focused target's line, so a change redraws that line and recomposes nothing.
    val facingState = remember { mutableStateOf(false) }

    LaunchedEffect(view, lifecycle, paneView) {
        var last = 0L
        var cell = TickCell.Unset
        var targetCell = TickCell.Unset
        var settling = TickSettling.Settled
        var lastTap = 0L
        var reference = Float.NaN
        var stale: HeadingReading? = null
        // The facing state for this loop. The blind navigation pulses are decided from it below, on the frame,
        // without going through composition.
        var facing = false
        var facingId = NO_TARGET
        var blind = BlindPulses.Silent
        var lastPulse: Pulse? = null
        var lastPulseNanos = 0L
        facingState.value = false
        while (true) {
            // Hidden, the loop waits here and asks for no frame. Its state is kept, so the first frame back sees
            // the whole time away as one long gap, which is handled like any other pause: the dial catches up
            // and the ticks stay quiet until it has. The pulses do not wait: they end here, so the first one
            // after the compass is back is the strong one.
            if (paneView() == PaneView.HIDDEN) {
                blind = BlindPulses.Silent
                snapshotFlow { paneView() }.first { it != PaneView.HIDDEN }
            }
            withFrameNanos { now ->
                val reading = readingState.value
                if (reading != null) {
                    val n = navState.value
                    val wasHold = holdFlat
                    val hold = nextHoldFlat(wasHold, reading.pitchDeg)
                    holdFlat = hold
                    val first = shown.floatValue.isNaN()
                    val gap = if (last == 0L) 0L else now - last
                    val target = displayHeading(reading.magneticDeg, n.declination, n.north).deg
                    // While the watch is tilted too far the heading is not taken, so the dial keeps its last value.
                    if (!hold) shown.floatValue = if (first) target else smoothAngle(shown.floatValue, target, gap / 1e9f)

                    // After a pause the reading in hand is the one from before it, until the sensor sends another.
                    if (gap > TICK_GAP_NANOS) stale = reading else if (reading !== stale) stale = null
                    // How far the dial's north is from magnetic north: it moves when the declination or the setting does.
                    val newReference = shortestDelta(reading.magneticDeg, target)
                    val disturbed = isTickDisturbance(
                        firstHeading = first, staleReading = stale != null, frameGapNanos = gap, leftHoldFlat = wasHold && !hold,
                        referenceShiftDeg = if (reference.isNaN()) 0f else shortestDelta(reference, newReference),
                    )
                    reference = newReference

                    // Both cells are followed on every frame, so nothing is owed when the taps are allowed again.
                    val next = nextTickCell(cell, shown.floatValue)
                    targetCell = nextTickCell(targetCell, target)
                    val wasSettling = settling.active
                    settling = nextTickSettling(settling, disturbed, now, next, targetCell)

                    // Facing is decided from the heading the dial shows, and only while that heading is live: not
                    // while the dial is frozen by the hold-flat state, and not while it is catching up.
                    // A focus that has moved to another target starts from not facing.
                    val wasFacing = facing
                    val focusedId = n.focused?.id ?: NO_TARGET
                    val targetChanged = focusedId != facingId
                    facingId = focusedId
                    facing = nextFacing(
                        wasFacing, shown.floatValue, n.focused?.dialAngleDeg, headingLive = !hold && !settling.active,
                        targetChanged = targetChanged,
                    )
                    if (facing != wasFacing) facingState.value = facing

                    // A compass that is sliding past, or stands behind a screen that is being swiped away, is
                    // drawn and gives no tap and no pulse.
                    val inView = paneView() == PaneView.IN_VIEW && lifecycle.currentState == Lifecycle.State.RESUMED

                    // Blind navigation: the strong pulse on turning onto the focused target, the soft one each
                    // second while still facing it. Not from an old position: the mark on the distance that says
                    // so is for the eye, and the same condition keeps the pulses silent.
                    val nextBlind = nextBlindPulses(
                        blind, facing, n.blindNavigation, inView, now, targetChanged,
                        positionStale = isStale(SystemClock.elapsedRealtime(), n.fixElapsedMs),
                    )
                    val pulse = pulseBetween(blind, nextBlind, targetChanged)
                    blind = nextBlind
                    if (pulse != null) {
                        view.performHapticFeedback(if (pulse == Pulse.LOCK) LOCK_HAPTIC else HEARTBEAT_HAPTIC)
                        lastPulse = pulse
                        lastPulseNanos = now
                    }

                    // The frame on which the dial arrives is still part of the catch-up. A tick that falls on a
                    // pulse, or just after one, is dropped: the pulse is the one the wearer is to feel.
                    if (isTickCrossing(cell, next) && !wasSettling && !settling.active && !hold && n.tickFeedback &&
                        inView && tickTapDue(now, lastTap) && tickClearOfPulse(now, lastPulse, lastPulseNanos)
                    ) {
                        view.performHapticFeedback(TICK_HAPTIC)
                        lastTap = now
                    }
                    cell = next
                } else {
                    // Without a heading nothing is faced.
                    if (facing) { facing = false; facingState.value = false }
                    blind = BlindPulses.Silent
                }
                last = now
            }
        }
    }

    // What the logging status puts on the screen, or null while there is none. Read through a derived state, so
    // the screen is composed again only when the words or their color change.
    val logStatusState = vm.logStatus.collectAsStateWithLifecycle()
    val logLines by remember {
        derivedStateOf {
            val status = logStatusState.value
            if (status == LogStatus.Idle) null
            else statusText(status, navState.value.units).let { (text, detail) ->
                StatusLines(text, detail, logStatusColor(status), if (statusDetailContinues(status)) logStatusColor(status) else Dim)
            }
        }
    }

    // While a trail is retraced and no position can come at all (the permission is not held, or location is switched
    // off), the compass says so where the crumb's distance would be, in the words the logging status uses for it.
    val noPositionLines by remember {
        derivedStateOf {
            val n = navState.value
            val reason = n.noPosition
            if (reason == null || n.focused?.isCrumb != true) null
            else statusText(LogStatus.Failed(reason), n.units).let { (text, detail) -> StatusLines(text, detail, ErrorColor, ErrorColor) }
        }
    }

    LogResultTimer(vm, paneView)

    val headingText by remember { derivedStateOf { if (shown.floatValue.isNaN()) "--" else "${shown.floatValue.roundToInt() % 360}°" } }
    val lowAccuracy by remember {
        derivedStateOf { readingState.value?.accuracy?.let { it <= SensorManager.SENSOR_STATUS_ACCURACY_LOW } ?: false }
    }
    val showingMagnetic = nav.north == NorthRef.MAGNETIC || nav.declination == null
    val stale by produceState(false, nav.fixElapsedMs, onScreen) {
        while (onScreen) { value = isStale(SystemClock.elapsedRealtime(), nav.fixElapsedMs); delay(1_000) }
    }

    var crown by remember { mutableFloatStateOf(0f) }
    val pressScope = rememberCoroutineScope()
    val sideButton = remember(pressScope) { SideButton(pressScope) }
    val sidePress by rememberUpdatedState(onSidePress)
    val openList by rememberUpdatedState(onOpenList)

    // The ticks that say which of the three screens this is show only around a swipe: from the moment the compass
    // starts to move until a little after it has come to rest. At rest the screen is the compass and nothing else.
    // Only the row moving brings them: a compass that was hidden under a screen opened from a row and is shown
    // again has not moved, and gets none.
    val paneTicks = remember { Animatable(0f) }
    LaunchedEffect(paneView) {
        // The compass opens at rest, so the first value changes nothing.
        snapshotFlow { paneView() == PaneView.PASSING }.collectLatest { moving ->
            if (moving) paneTicks.snapTo(1f)
            else if (paneTicks.value > 0f) { delay(PANE_TICKS_LINGER_MS); paneTicks.animateTo(0f, tween(PANE_TICKS_FADE_MS)) }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            // The crown and the side buttons come to whichever of the three screens has the focus, which the
            // pager gives to the one nearest its resting place. The compass acts on them only once it is in view:
            // until then a turn or a press is taken and dropped, so it reaches nothing else either.
            .onRotaryScrollEvent { e ->
                if (paneView() == PaneView.IN_VIEW) {
                    crown += e.verticalScrollPixels
                    if (abs(crown) > 80f) { vm.moveFocus(if (crown > 0) 1 else -1); crown = 0f }
                } else crown = 0f
                true
            }
            // One press of a side button saves the position; with the breadcrumb trail setting on, two close
            // together switch the trail. What a press means is decided here.
            .onPreviewKeyEvent { e ->
                sideButton.onKey(
                    e.nativeKeyEvent, inView = { paneView() == PaneView.IN_VIEW }, doubleEnabled = { navState.value.breadcrumbTrail },
                ) { kind -> if (kind == PressKind.DOUBLE) vm.trailDoublePress() else sidePress() }
            }
            // A plain focus target, not `focusable()`. Observed on the API 35 emulator: with `focusable()` here,
            // every slide the app itself made onto the compass (Back from waypoints, a second Back on through it)
            // was cancelled just as the compass took the focus, which the pager gives a page half way through a
            // slide (6 of 6); with `focusTarget()` and nothing else changed, none was (0 of 9). `focusable()`
            // asks to be brought into view when it takes the focus, which is the likely cause.
            .requestFocusOnHierarchyActive().focusTarget()
            // Three gestures start on the dial, and a touch becomes at most one of them. A sideways swipe is the
            // pager's, around this screen. The two below are the compass's own: a vertical swipe moves the focus
            // among the targets, and a long press opens the waypoints. Whichever of the pager and the vertical
            // swipe first sees the finger travel its own way takes the touch, and the other then sees it as taken
            // and lets go, so a swipe that wanders is one or the other. Either ends the wait for a long press.
            .pointerInput(vm, paneView) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > 48.dp.toPx() && paneView() == PaneView.IN_VIEW) vm.moveFocus(if (total < 0) 1 else -1)
                    },
                ) { _, dy -> total += dy }
            }
            // The long press is this one's, after the system's own long-press time, and a tap does nothing. It is
            // the later of the two modifiers, so it sees a touch first: once a long press has opened the waypoints
            // it takes the rest of the touch, and a finger that then moves swipes nothing.
            .pointerInput(view, paneView) {
                detectTapGestures(onLongPress = {
                    if (paneView() == PaneView.IN_VIEW) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        openList()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Like the dial, the readout is laid out in fractions of the dial radius. The number is pinned at the
        // center and every other line has a fixed slot above or below it, so nothing moves when a line appears,
        // and every slot is narrow enough to stay inside the circle the cardinal letters sweep around.
        val r = minOf(maxWidth, maxHeight) / 2
        val density = LocalDensity.current
        fun style(fraction: Float, weight: FontWeight = FontWeight.Normal) = with(density) {
            val size = (r * fraction).toSp()
            TextStyle(
                fontSize = size, lineHeight = size * 1.1f, fontWeight = weight, textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            )
        }
        val bodyStyle = style(0.097f)
        fun Modifier.slot(y: Float, width: Float) = align(Alignment.Center).offset(y = r * y).width(r * width)

        // The dial steps back while the hint asks for something: its value is the last one taken, not a live one.
        val dialAlpha = animateFloatAsState(if (holdFlat) DIAL_DIMMED else 1f, tween(250), label = "dial")
        CompassDial(
            headingProvider = { shown.floatValue }, targets = nav.targets,
            modifier = Modifier.graphicsLayer { alpha = dialAlpha.value }, facing = { facingState.value },
            focused = nav.focused, crumbTicks = nav.trail.tickAngles,
        )

        // What the slots just above and below the heading hold: a target's name and distance, the words
        // "No target", or nothing. While a waypoint is being logged the slot below holds the status instead, with
        // or without targets. A slot added next to the heading belongs in [linesAroundHeading] too.
        val focused = nav.focused
        val noTarget = focused == null && nav.loaded && nav.waypoints.isNotEmpty()
        val status = logLines ?: noPositionLines
        val linesAroundHeading = focused != null || noTarget || status != null
        // The status's second line sits where the prompts' words do, and has that place for as long as it shows.
        // The hold-flat drawing goes on looping above it.
        val promptWords = status?.detail == null

        // A tap on the compass does nothing, with one exception: while the calibrate prompt shows, the part of
        // the middle from the prompt down is its tap area, at least 48 dp both ways. The prompt, words and tap
        // area alike, is there only while its words have their place. A long press that starts on that area
        // is the area's own touch and opens nothing.
        val calibrate = vm.supported && !holdFlat && lowAccuracy && promptWords
        if (calibrate) {
            Box(
                Modifier.slot(y = 0.255f + 0.26f, width = 0.9f).height(r * 0.52f)
                    .clickable(interactionSource = null, indication = null, onClick = onCalibrate)
            )
        }

        if (!vm.supported) {
            Text("This watch has no compass sensor", color = Ink, style = bodyStyle, modifier = Modifier.slot(0f, 0.8f))
        } else {
            // The top slot: `MAG`, and while a trail is being laid `Trail` with the number of crumbs, side by side
            // when there are both.
            val laying = nav.trail.mode == TrailMode.LAYING
            if (showingMagnetic || laying) {
                Row(Modifier.slot(-0.31f, 0.6f), Arrangement.Center, Alignment.CenterVertically) {
                    if (showingMagnetic) Text("MAG", color = Dim, style = style(0.079f), maxLines = 1)
                    if (showingMagnetic && laying) Spacer(Modifier.width(r * 0.06f))
                    if (laying) Text(trailLabel(nav.trail.count), color = CrumbColor, style = style(0.079f), maxLines = 1, softWrap = false)
                }
            }
            // The middle slot holds the heading, or, while the heading cannot be trusted, the hint that says why.
            // The hint's drawing fills the gap between the line above the heading and the line below it, and
            // has more room when both of those slots are empty. Its words sit where the calibrate prompt does.
            val hintHeight = if (linesAroundHeading) 0.255f else 0.42f
            when {
                holdFlat -> HoldFlatHint(
                    labelStyle = bodyStyle, labelOffset = r * PROMPT_Y,
                    modifier = Modifier.slot(0f, hintHeight * 1.9f).height(r * hintHeight), showLabel = promptWords,
                    running = { paneView() != PaneView.HIDDEN },
                )
                else -> {
                    HeadingNumber({ headingText }, style(0.30f, FontWeight.Light), Modifier.slot(0f, 2 * READOUT_RADIUS))
                    if (calibrate) Text("Calibrate", color = Dim, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(PROMPT_Y, 0.6f))
                }
            }
        }

        when {
            focused != null -> {
                Row(Modifier.slot(-0.195f, 0.78f), Arrangement.Center, Alignment.CenterVertically) {
                    // The crumb of a trail that is walked to stands here as a focused target does, in the trail's color.
                    Box(Modifier.size(r * 0.062f).background(if (focused.isCrumb) CrumbColor else waypointColor(focused.hue), CircleShape))
                    Spacer(Modifier.width(r * 0.035f))
                    Text(focused.name, color = Ink, style = bodyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val d = focused.distanceM
                if (status == null) Text(
                    text = when {
                        d == null -> "Locating…"
                        focused.arrived -> "Arrived at target"
                        stale -> "${formatDistance(d, nav.units)} (old)"
                        else -> formatDistance(d, nav.units)
                    },
                    color = if (stale) Dim else Ink, style = bodyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.slot(0.195f, 0.78f),
                )
            }
            // With no waypoints saved, or before the first real state arrives, nothing is shown here: the screen
            // is a plain compass.
            noTarget && status == null ->
                Text("No target", color = Dim, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(0.195f, 0.78f))
        }

        if (status != null) {
            StatusLine(status.text, status.color, bodyStyle, Modifier.slot(0.195f, 0.78f))
            status.detail?.let { StatusLine(it, status.detailColor, style(0.079f), Modifier.slot(STATUS_DETAIL_Y, 0.62f)) }
        }

        // At the bottom of the readout's own circle, under the lowest line of text: the one place at 6 o'clock
        // that nothing on the turning dial passes through.
        PanePosition(Pane.COMPASS.ordinal, radius = PANE_TICKS_ON_COMPASS, scale = PANE_TICKS_SCALE, alpha = { paneTicks.value })
    }
}

/**
 * The logging status as the compass shows it: a line in the distance slot and, for some, a smaller one under it,
 * which is dim when it is a note and in the first line's color when it carries the first line's words on.
 */
private data class StatusLines(val text: String, val detail: String?, val color: Color, val detailColor: Color)

/**
 * One line of the logging status, on the compass and on the waypoints screen. It starts at the size of [style] and, when the words are wider than the slot,
 * steps down to three quarters of it before it cuts them short: the longest of the fixed messages and a waypoint
 * number of several digits do not fit the slot at full size, and the width a font gives them differs by watch.
 */
@Composable
internal fun StatusLine(text: String, color: Color, style: TextStyle, modifier: Modifier) {
    BasicText(
        text, modifier.semantics { liveRegion = LiveRegionMode.Polite }, style, maxLines = 1, softWrap = false,
        overflow = TextOverflow.Ellipsis, color = { color },
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.75f, maxFontSize = style.fontSize, stepSize = 0.25.sp),
    )
}

/** Stands for the focused target's id in the frame loop while no target is focused. Row ids start at 1. */
private const val NO_TARGET = 0L

/** Where the calibrate prompt and the words of the hold-flat hint sit, in dial radii below the center. */
private const val PROMPT_Y = 0.31f

/** Where the second line of the logging status sits: under the distance slot, where the prompts' words are. */
private const val STATUS_DETAIL_Y = 0.30f

/** Where the outer ends of the pane ticks are on the compass, just inside [READOUT_RADIUS], and their size there. */
private const val PANE_TICKS_ON_COMPASS = 0.465f
private const val PANE_TICKS_SCALE = 0.75f
/** How long the pane ticks stay after the compass has come to rest, and how long they take to fade. */
private const val PANE_TICKS_LINGER_MS = 1_200L
private const val PANE_TICKS_FADE_MS = 400

/** How much of the dial's brightness is left while the hold-flat hint shows. */
private const val DIAL_DIMMED = 0.4f

/**
 * The softest tick the platform maps to a vibration. From API 34 that is the constant made for many choices in
 * quick succession. API 33 does not have it; there the clock tick plays the platform's lightest effect.
 */
private val TICK_HAPTIC =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    else HapticFeedbackConstants.CLOCK_TICK

/**
 * The blind navigation pulses: the platform's confirmation for the strong one and its context click for the soft
 * one, on every API level the app runs on. Both are haptic feedback, which needs no permission and which the
 * system drops while touch vibration is switched off in the watch's settings.
 * In the platform's own table (API 33 to 35) the confirmation plays the click effect, the context click the tick
 * effect and the tick tap the texture tick, strongest first; a watch's system can replace entries, and the Wear OS
 * emulator images play the confirmation as two thuds. The long press, the platform's heavy click, is not the
 * strong pulse because a vibrator need not support that effect: the emulators' does not, and nothing plays.
 */
private const val LOCK_HAPTIC = HapticFeedbackConstants.CONFIRM
private const val HEARTBEAT_HAPTIC = HapticFeedbackConstants.CONTEXT_CLICK

/** The heading number. The text is read here, not in [CompassScreen], so a new whole degree recomposes only this. */
@Composable
private fun HeadingNumber(text: () -> String, style: TextStyle, modifier: Modifier) {
    Text(text(), color = Ink, style = style, maxLines = 1, softWrap = false, modifier = modifier)
}
