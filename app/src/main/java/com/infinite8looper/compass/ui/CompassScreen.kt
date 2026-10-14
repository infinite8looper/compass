package com.infinite8looper.compass.ui

import android.hardware.SensorManager
import androidx.compose.ui.unit.Dp
import com.infinite8looper.compass.NavUi
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.*
import com.infinite8looper.compass.sensors.HeadingReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * The compass, one of the three screens that sit side by side. [paneView] says how much of it the wearer has in
 * front of them, and everything here that depends on that asks it:
 * - hidden, the screen does no work: no heading is taken from the sensor, no frame is asked for, no timer runs;
 * - passing, while the row of screens moves, it is drawn live and does nothing else;
 * - in view, it also takes the crown, the double tap, the vertical swipe and the long press, gives the tick and
 *   the confirming tap and the blind navigation pulses, and counts the time a logging result is shown.
 *
 * The screen is built when the three screens are put up and stays while the ring of screens turns. The screen's keep-awake switch
 * is not set here: it has to hold on the other screens too.
 *
 * [onOpenList] is what a long press does. [onSidePress] saves the position, as a waypoint or, while a trail is
 * being laid, as a crumb: it is what a double tap on the dial does and what the dial's accessibility action does. On a
 * watch with a button the app can act on it is also what a single press of that button does, and a double press,
 * which there is only with the breadcrumb trail setting on, switches the trail ([rememberSideButtonKeys]).
 * [onGestureSave] is the same save for the one-handed primary gesture on a watch that has it, whose library plays
 * the press's vibration itself.
 */
@Composable
fun CompassScreen(
    vm: CompassViewModel, paneView: () -> PaneView, onOpenList: () -> Unit, onSidePress: () -> Unit, onCalibrate: () -> Unit,
    onGestureSave: () -> Unit = onSidePress,
) {
    remember { ScreenCompositions.countBuilt(Pane.COMPASS) }
    SideEffect { ScreenCompositions.countComposed(Pane.COMPASS) }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Whether any of the compass is on the screen. It is read in the effects below and never where the screen is
    // composed: the compass coming onto the screen in a swipe, or leaving it, composes nothing.
    val onScreen = remember(paneView) { derivedStateOf { paneView() != PaneView.HIDDEN } }

    // Held as a State and read only in the frame loop and derived states, so sensor events do not recompose.
    // The sensor is listened to only while the compass is on screen; hidden, it keeps the last reading it had.
    val readingState = remember(vm) { mutableStateOf(vm.heading.value) }
    LaunchedEffect(vm, lifecycle, onScreen) {
        snapshotFlow { onScreen.value }.collectLatest { on ->
            if (on) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.heading.collect { readingState.value = it } }
        }
    }
    // The screen state, held as a State and read only where it is shown ([CompassReadout]) and in the frame loop:
    // a position every few seconds composes the readout, not the screen.
    val navState = vm.nav.collectAsStateWithLifecycle()
    val shown = remember { mutableFloatStateOf(Float.NaN) }
    // The whole degree the heading number shows ([shownDegree]). Written in the frame loop when it changes, so the
    // number's text is made once for each whole degree and nothing is made for it while the dial turns between two.
    val shownWhole = remember { mutableIntStateOf(NO_DEGREE) }
    // Written every frame and read where the screen is composed: only a change of value recomposes.
    val holdFlatState = remember { mutableStateOf(false) }
    var holdFlat by holdFlatState
    // Whether the wearer faces the focused target. Written in the frame loop when it changes and read only where
    // the dial draws the focused target's line, so a change redraws that line and recomposes nothing.
    val facingState = remember { mutableStateOf(false) }

    LaunchedEffect(view, lifecycle, paneView) {
        var last = 0L
        var cell = TickCell.Unset
        var targetCell = TickCell.Unset
        var settling = TickSettling.Settled
        var lastTap = 0L
        var lastTargetTap = 0L
        // The shown heading's side of the focused target's bearing, for the target's vibration.
        var side = TargetSide.Unset
        val vibrations = Vibrations.of(view)
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
        // One function for every frame, made here: a frame makes nothing new.
        val frame: (Long) -> Unit = { now ->
            val reading = readingState.value
            if (reading != null) {
                val n = navState.value
                val wasHold = holdFlat
                val hold = nextHoldFlat(wasHold, reading.pitchDeg)
                holdFlat = hold
                val first = shown.floatValue.isNaN()
                val previousShown = shown.floatValue
                val gap = if (last == 0L) 0L else now - last
                val target = displayHeadingDeg(reading.magneticDeg, n.declination, n.north)
                // While the watch is tilted too far the heading is not taken, so the dial keeps its last value.
                if (!hold) shown.floatValue = if (first) target else smoothAngle(shown.floatValue, target, gap / 1e9f)
                // The number is given its whole degree here, and only when that is another one.
                val degree = shownDegree(shown.floatValue)
                if (degree != shownWhole.intValue) shownWhole.intValue = degree

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
                val positionStale = isStale(SystemClock.elapsedRealtime(), n.fixElapsedMs)
                val nextBlind = nextBlindPulses(
                    blind, facing, n.blindNavigation, inView, now, targetChanged, positionStale = positionStale,
                )
                val pulse = pulseBetween(blind, nextBlind, targetChanged)
                blind = nextBlind
                if (pulse != null) {
                    vibrations.play(if (pulse == Pulse.LOCK) Feel.LOCK else Feel.HEARTBEAT)
                    lastPulse = pulse
                    lastPulseNanos = now
                }

                // The dial's vibration: a thin line, a thick line or the target's bearing, the strongest that
                // applies. The frame on which the dial arrives is still part of the catch-up. One that falls on a
                // pulse, or just after one, is dropped: the pulse is the one the wearer is to feel.
                // The sides are both taken against the present bearing, so only the pointer's motion passes it. An
                // old position silences the target's vibration as it silences the blind navigation pulses.
                val bearing = n.focused?.dialAngleDeg
                val sideBefore = nextTargetSide(side, previousShown, bearing, targetChanged)
                val nextSide = nextTargetSide(sideBefore, shown.floatValue, bearing, targetChanged)
                val crossing = dialCrossing(cell, next, sideBefore, nextSide, n.tickFeedback, n.blindNavigation, positionStale)
                if (!wasSettling && !settling.active && !hold && inView &&
                    dialVibrationDue(crossing, now, lastTap, lastPulse, lastPulseNanos, lastTargetTap)
                ) {
                    Feel.of(crossing)?.let(vibrations::play)
                    lastTap = now
                    if (crossing == DialCrossing.TARGET) lastTargetTap = now
                }
                side = nextSide
                cell = next
            } else {
                // Without a heading nothing is faced.
                if (facing) { facing = false; facingState.value = false }
                blind = BlindPulses.Silent
            }
            last = now
        }
        while (true) {
            // Hidden, the loop waits here and asks for no frame. Its state is kept, so the first frame back sees
            // the whole time away as one long gap, which is handled like any other pause: the dial catches up
            // and the ticks stay quiet until it has. The pulses do not wait: they end here, so the first one
            // after the compass is back is the strong one.
            if (paneView() == PaneView.HIDDEN) {
                blind = BlindPulses.Silent
                snapshotFlow { paneView() }.first { it != PaneView.HIDDEN }
            }
            withFrameNanos(frame)
        }
    }

    // The logging status. Its words, and every other line of the readout that says something about position, are
    // decided in one place ([readout]), below, where the screen is composed: it changes with a press and with a
    // position fix, not with a frame.
    val logStatusState = vm.logStatus.collectAsStateWithLifecycle()

    LogResultTimer(vm, paneView)

    val headingShown = remember { derivedStateOf { headingText(shownWhole.intValue) } }
    val lowAccuracy = remember {
        derivedStateOf { readingState.value?.accuracy?.let { it <= SensorManager.SENSOR_STATUS_ACCURACY_LOW } ?: false }
    }

    // What the crown has scrolled toward the next step of the focus, and how far a step is on this screen's density.
    var crown by remember { mutableFloatStateOf(0f) }
    val crownStepPx = with(LocalDensity.current) { CROWN_STEP_DP.dp.toPx() }
    val sidePress by rememberUpdatedState(onSidePress)
    // On a watch with a button the app can act on, a single press of it saves too; on one without, this is nothing.
    val sideKeys = rememberSideButtonKeys(vm, paneView, doubleEnabled = { navState.value.breadcrumbTrail }, onSinglePress = onSidePress)
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
            // The crown, and a side button where there is one, come to whichever of the three screens has the focus, which the
            // ring gives to the one nearest its resting place. The compass acts on them only once it is in view:
            // until then a turn or a press is taken and dropped, so it reaches nothing else either.
            .onRotaryScrollEvent { e ->
                if (paneView() == PaneView.IN_VIEW) {
                    val steps = crownSteps(crown, e.verticalScrollPixels, crownStepPx)
                    crown = crownCarried(crown, e.verticalScrollPixels, crownStepPx)
                    if (steps != 0) vm.moveFocus(steps)
                } else crown = 0f
                true
            }
            .sideKeys(sideKeys)
            // For an accessibility service the save is an action of the dial with a name, so it does not depend
            // on a gesture. It acts as the double tap does, and only while the compass is in view.
            // The dial has a name of its own, the app's, so that a screen reader stops on it and offers the action;
            // what is on it (the heading, a target's name, a status) is still read piece by piece.
            .semantics {
                contentDescription = DIAL_NAME
                customActions = listOf(
                    CustomAccessibilityAction(SAVE_HERE_ACTION) { if (paneView() == PaneView.IN_VIEW) { sidePress(); true } else false }
                )
            }
            // A plain focus target, not `focusable()`. Observed on the API 35 emulator: with `focusable()` here,
            // every slide the app itself made onto the compass (Back from waypoints, a second Back on through it)
            // was cancelled just as the compass took the focus, which the pager the screens then stood in gave a page half way through a
            // slide (6 of 6); with `focusTarget()` and nothing else changed, none was (0 of 9). `focusable()`
            // asks to be brought into view when it takes the focus, which is the likely cause.
            .requestFocusOnHierarchyActive().focusTarget()
            // Four gestures start on the dial, and a touch becomes at most one of them. A sideways swipe is the
            // ring's, around this screen. The three below are the compass's own: a vertical swipe moves the focus
            // among the targets, a long press opens the waypoints, and two taps one after the other save the
            // position. Whichever of the ring and the vertical swipe first sees the finger travel its own way
            // takes the touch, and the others then see it as taken and let go, so a swipe that wanders is one or
            // the other. Either ends the wait for a long press, and a touch that is swiped is no tap.
            .pointerInput(vm, paneView) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > 48.dp.toPx() && paneView() == PaneView.IN_VIEW) vm.moveFocus(if (total < 0) 1 else -1)
                    },
                ) { _, dy -> total += dy }
            }
            // The long press and the double tap are this one's. It is the later of the two modifiers, so it sees
            // a touch first: once a long press has opened the waypoints it takes the rest of the touch, and a
            // finger that then moves swipes nothing.
            //
            // One tap does nothing, and gives no vibration either: a brush of the screen saves nothing. Two taps
            // save, as a single press of the side button did, when [nextTap] says they are a double tap: the
            // second goes down within the system's double-tap time after the first is let go, near it, with the
            // compass in view at both. Each tap is acted on when it is let go and nothing waits for a second one,
            // so the long press comes after the system's own long-press time whether or not a tap came just before
            // it. A tap that is no partner of the one before it waits for one in its turn, so a stray tap does not
            // use up the double tap after it. A touch that becomes a swipe or a long press leaves no tap waiting.
            .pointerInput(view, paneView) {
                val slopPx = android.view.ViewConfiguration.get(view.context).scaledDoubleTapSlop.toFloat()
                val timeoutMs = viewConfiguration.doubleTapTimeoutMillis
                val minMs = viewConfiguration.doubleTapMinTimeMillis
                // The touch that is down now, and the lone tap that waits for a partner.
                var downMs = 0L; var downX = 0f; var downY = 0f; var downInView = false
                var waiting: DialTap? = null
                detectTapGestures(
                    onPress = { at ->
                        downMs = SystemClock.uptimeMillis(); downX = at.x; downY = at.y
                        downInView = paneView() == PaneView.IN_VIEW
                        if (!tryAwaitRelease()) waiting = null
                    },
                    onTap = {
                        val tap = DialTap(downMs, SystemClock.uptimeMillis(), downX, downY, downInView, paneView() == PaneView.IN_VIEW)
                        val step = nextTap(waiting, tap, timeoutMs, minMs, slopPx)
                        waiting = step.waiting
                        if (step.saves) sidePress()
                    },
                    onLongPress = {
                        waiting = null
                        if (paneView() == PaneView.IN_VIEW) {
                            Vibrations.of(view).play(Feel.LONG_PRESS)
                            openList()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Like the dial, the readout is laid out in fractions of the dial radius. The number is pinned at the
        // center and every other line has a fixed slot above or below it, so nothing moves when a line appears,
        // and every slot is narrow enough to stay inside the circle the cardinal letters sweep around.
        // The readout is composed apart from the screen's own function, and counts as the screen.
        SideEffect { ScreenCompositions.countComposed(Pane.COMPASS) }
        OneHandedSave(paneView, onSave = onGestureSave)
        val r = minOf(maxWidth, maxHeight) / 2
        val density = LocalDensity.current
        // The readout's text styles depend on the dial's radius alone, and are made once for it: a line that
        // changes is composed again with the style it had.
        val styles = remember(r, density) {
            fun style(fraction: Float, weight: FontWeight = FontWeight.Normal) = with(density) {
                val size = (r * fraction).toSp()
                TextStyle(
                    fontSize = size, lineHeight = size * 1.1f, fontWeight = weight, textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                )
            }
            ReadoutStyles(
                body = style(READOUT_BODY_SIZE), detail = style(READOUT_DETAIL_SIZE),
                heading = style(READOUT_HEADING_SIZE, FontWeight.Light), coordinates = style(COORDINATES_SIZE),
            )
        }
        CompassReadout(
            vm, navState, logStatusState, holdFlatState, headingShown, lowAccuracy, onScreen, paneView, onCalibrate, r, styles,
            dial = { targets, focused, crumbTicks, modifier ->
                CompassDial(
                    headingProvider = { shown.floatValue }, targets = targets, modifier = modifier,
                    facing = { facingState.value }, focused = focused, crumbTicks = crumbTicks,
                )
            },
        )

        // At the bottom of the readout's own circle, under the lowest line of text: the one place at 6 o'clock
        // that nothing on the turning dial passes through.
        PanePosition(Pane.COMPASS.ordinal, radius = PANE_TICKS_ON_COMPASS, scale = PANE_TICKS_SCALE, alpha = { paneTicks.value })
    }
}

/**
 * Everything on the compass that the screen state, the logging status or the hold-flat state decide: the dial's
 * targets and every line of the readout. A composable of its own, so that a position fix, which comes every few
 * seconds while the compass is on screen, composes this and not the screen around it.
 */
@Composable
private fun BoxScope.CompassReadout(
    vm: CompassViewModel, navState: State<NavUi>, logStatusState: State<LogStatus>, holdFlatState: State<Boolean>,
    headingShownState: State<String>, lowAccuracyState: State<Boolean>, onScreen: State<Boolean>, paneView: () -> PaneView,
    onCalibrate: () -> Unit, r: Dp, styles: ReadoutStyles,
    dial: @Composable (targets: List<TargetUi>, focused: TargetUi?, crumbTicks: List<Float>, modifier: Modifier) -> Unit,
) {
    val nav = navState.value
    val logStatus = logStatusState.value
    val holdFlat = holdFlatState.value
    val lowAccuracy = lowAccuracyState.value
    val showingMagnetic = dialIsMagnetic(nav.north, nav.declination)
    val stale by produceState(false, nav.fixElapsedMs, onScreen) {
        snapshotFlow { onScreen.value }.collectLatest { on ->
            while (on) { value = isStale(SystemClock.elapsedRealtime(), nav.fixElapsedMs); delay(1_000) }
        }
    }
    fun Modifier.slot(y: Float, width: Float) = align(Alignment.Center).offset(y = r * y).width(r * width)
    val bodyStyle = styles.body
        // Where the rows stand. With the coordinates switched on there are two rows more, the latitude at the top
        // and the longitude at the bottom, and the rows between stand a little closer to the heading to make room.
        // Which set it is depends on the setting alone, so no row moves while the compass is in use.
        val rows = remember(nav.showCoordinates) { readoutRows(nav.showCoordinates) }

        // The dial steps back while the hint asks for something: its value is the last one taken, not a live one.
        val dialAlpha = animateFloatAsState(if (holdFlat) DIAL_DIMMED else 1f, tween(250), label = "dial")
        dial(nav.targets, nav.focused, nav.trail.tickAngles, Modifier.graphicsLayer { alpha = dialAlpha.value })

        // The lines that say something about position: the row under the heading (a status, the distance, or
        // `No target`), the smaller row under it, and the coordinates. One function decides them all.
        val focused = nav.focused
        val lines = readout(
            status = logStatus, units = nav.units,
            focus = when { focused == null -> FocusKind.NONE; focused.isCrumb -> FocusKind.CRUMB; else -> FocusKind.WAYPOINT },
            distanceM = focused?.distanceM, arrived = focused?.arrived == true,
            noTarget = focused == null && nav.loaded && nav.waypoints.isNotEmpty(),
            position = nav.position, stale = stale, fixAccuracyM = nav.fixAccuracyM, noPosition = nav.noPosition,
            showCoordinates = nav.showCoordinates && vm.supported, holdFlat = holdFlat, lowAccuracy = vm.supported && lowAccuracy,
        )
        // The hold-flat drawing has more room when the rows next to the heading are empty. A row added next to the
        // heading belongs here too.
        val linesAroundHeading = focused != null || lines.main != null
        // The place under the row under the heading holds one thing, and [readout] says which: a status's second
        // line, or the words of the hold-flat hint, or the calibrate prompt. The hold-flat drawing loops whether
        // or not its words have the place.
        val calibrate = lines.prompt == ReadoutPrompt.CALIBRATE
        // A tap on the compass does nothing, with one exception: while the calibrate prompt shows, the part of
        // the middle from the prompt's word down is its tap area, as tall as a touch needs at least. It starts at
        // the top of the word wherever the rows put it, and is there only while the word is. A long press that
        // starts on it is the area's own touch and opens nothing. The longitude under the word is drawn text with
        // no touch of its own.
        if (calibrate) {
            val tapHeight = maxOf(r * PROMPT_TAP_HEIGHT, HitHeight)
            Box(
                Modifier.align(Alignment.Center).offset(y = r * (rows.prompt - PROMPT_TAP_ABOVE) + tapHeight / 2).width(r * 0.9f).height(tapHeight)
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
                Row(Modifier.slot(rows.top, 0.6f), Arrangement.Center, Alignment.CenterVertically) {
                    if (showingMagnetic) Text("MAG", color = Dim, style = styles.detail, maxLines = 1)
                    if (showingMagnetic && laying) Spacer(Modifier.width(r * 0.06f))
                    if (laying) Text(trailLabel(nav.trail.count), color = CrumbColor, style = styles.detail, maxLines = 1, softWrap = false)
                }
            }
            // The middle slot holds the heading, or, while the heading cannot be trusted, the hint that says why.
            // The hint's drawing fills the gap between the line above the heading and the line below it, and
            // has more room when both of those slots are empty. Its words sit where the calibrate prompt does.
            val hintHeight = if (linesAroundHeading) 0.255f else 0.42f
            when {
                holdFlat -> HoldFlatHint(
                    labelStyle = bodyStyle, labelOffset = r * rows.prompt,
                    modifier = Modifier.slot(0f, hintHeight * 1.9f).height(r * hintHeight), showLabel = lines.prompt == ReadoutPrompt.HOLD_FLAT,
                    running = { paneView() != PaneView.HIDDEN },
                )
                else -> {
                    HeadingNumber({ headingShownState.value }, showingMagnetic, styles.heading, Modifier.slot(0f, 2 * READOUT_RADIUS))
                    if (calibrate) Text("Calibrate", color = Dim, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(rows.prompt, 0.6f))
                }
            }
        }

        if (focused != null) {
            Row(Modifier.slot(rows.name, 0.78f), Arrangement.Center, Alignment.CenterVertically) {
                // The crumb of a trail that is walked to stands here as a focused target does, in the trail's color.
                Box(Modifier.size(r * 0.062f).background(if (focused.isCrumb) CrumbColor else waypointColor(focused.hue), CircleShape))
                Spacer(Modifier.width(r * 0.035f))
                // A name too long for the slot slides back and forth in it, while the compass is in view.
                MarqueeText(focused.name, bodyStyle, { Ink }, view = paneView)
            }
        }

        lines.main?.let { main ->
            val color = toneColor(main.tone)
            if (lines.mainIsStatus) StatusLine(main.words, color, bodyStyle, Modifier.slot(rows.main, 0.78f), view = paneView)
            else Text(
                // For the crumb walked to, a fix too poor to consume it has its accuracy after the distance,
                // dim: the wearer standing on a crumb that stays sees why.
                text = remember(main.words, main.note) {
                    if (main.note == null) AnnotatedString(main.words) else buildAnnotatedString {
                        append(main.words); append(' ')
                        withStyle(SpanStyle(color = Dim)) { append(main.note) }
                    }
                },
                color = color, style = bodyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.slot(rows.main, 0.78f),
            )
        }
        lines.detail?.let { StatusLine(it.words, toneColor(it.tone), styles.detail, Modifier.slot(rows.detail, 0.62f), view = paneView) }

        // The watch's own position, when the setting asks for it: two small, quiet lines, the latitude above
        // everything else in the readout and the longitude below it. They stay through a save, a failure, the
        // calibrate prompt and a swipe, and are left out only in the hold-flat state.
        val coordinateStyle = styles.coordinates
        lines.latitude?.let {
            Text(it.words, color = toneColor(it.tone), style = coordinateStyle, maxLines = 1, softWrap = false, modifier = Modifier.slot(rows.latitude, 0.6f))
        }
        lines.longitude?.let {
            Text(it.words, color = toneColor(it.tone), style = coordinateStyle, maxLines = 1, softWrap = false, modifier = Modifier.slot(rows.longitude, 0.5f))
        }

}

/**
 * The one-handed primary action (a double pinch on the watches that have it) on the dial: it saves, as a double tap
 * does. The Wear Compose library carries the gesture from Wear OS 7 (API level 37) and does nothing on a watch
 * whose system lacks the framework; below that level this composes nothing at all.
 *
 * The gesture is registered only while the compass is the screen in view: the library acts on every gesture that
 * is registered, and answers each with a vibration of its own, wherever its composable is on the screen. So this
 * is a part of the compass of its own, composed again when the compass comes into view and when it leaves, twice
 * for a turn of the ring and not for a frame of it; the compass around it is not composed again.
 */
@Composable
private fun OneHandedSave(paneView: () -> PaneView, onSave: () -> Unit) {
    if (Build.VERSION.SDK_INT < ONE_HANDED_FROM_API) return
    val inView by remember(paneView) { derivedStateOf { paneView() == PaneView.IN_VIEW } }
    if (!inView) return
    val save by rememberUpdatedState(onSave)
    val configuration = rememberOneHandedGestureConfiguration(OneHandedGestureAction.Primary, gestureId = ONE_HANDED_SAVE_ID)
    // As large as the dial and under everything on it. It takes no touch.
    Box(Modifier.fillMaxSize().oneHandedGesture(configuration, onGestureLabel = SAVE_HERE_ACTION) { save() })
}

/** The first API level whose system can have the one-handed gesture framework. */
private const val ONE_HANDED_FROM_API = 37

/** The name the system keeps its record of this gesture's hints under. */
private const val ONE_HANDED_SAVE_ID = "compass-save-here"

/**
 * One line of the logging status, on the compass and on the waypoints screen. It starts at the size of [style] and, when the words are wider than the slot,
 * steps down to three quarters of it before it cuts them short or, where things may move, slides them: the longest of the fixed messages and a waypoint
 * number of several digits do not fit the slot at full size, and the width a font gives them differs by watch.
 */
@Composable
internal fun StatusLine(text: String, color: Color, style: TextStyle, modifier: Modifier, view: () -> PaneView = { PaneView.IN_VIEW }) {
    // Words that are too wide even at three quarters (`Saved Waypoint` with a number of many digits) slide back and
    // forth at that size, as a name does, on a screen that [view] says is in view. Whether they are is known once they have been
    // laid out at that size, so they are drawn cut short for one frame first.
    var tooWide by remember(text, style) { mutableStateOf(false) }
    val live = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (tooWide) MarqueeText(text, style.copy(fontSize = style.fontSize * STATUS_SMALLEST), { color }, live, view)
    else BasicText(
        text, live, style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = { color },
        onTextLayout = { if (it.hasVisualOverflow) tooWide = true },
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * STATUS_SMALLEST, maxFontSize = style.fontSize, stepSize = 0.25.sp),
    )
}

/** The text styles of the compass's readout, made once for a dial radius. */
private class ReadoutStyles(val body: TextStyle, val detail: TextStyle, val heading: TextStyle, val coordinates: TextStyle)

/** How small a line of the logging status is drawn, as a share of its full size, before it is cut short or slides. */
private const val STATUS_SMALLEST = 0.75f

/** The color a line of the readout is drawn in. Read where the line is composed; the role colors can change while the app runs. */
private fun toneColor(tone: ReadoutTone): Color = when (tone) {
    ReadoutTone.INK -> Ink
    ReadoutTone.DIM -> Dim
    ReadoutTone.FAINT -> Faint
    ReadoutTone.CONFIRM -> ConfirmColor
    ReadoutTone.ERROR -> ErrorColor
}

/** Stands for the focused target's id in the frame loop while no target is focused. Row ids start at 1. */
private const val NO_TARGET = 0L

/**
 * The calibrate prompt's tap area, in dial radii: how far above the middle of its word it starts, which is half the
 * word's line, and how far down it reaches from there.
 */
private const val PROMPT_TAP_ABOVE = 0.055f
private const val PROMPT_TAP_HEIGHT = 0.52f

/** How long the pane ticks stay after the compass has come to rest, and how long they take to fade. */
private const val PANE_TICKS_LINGER_MS = 1_200L
private const val PANE_TICKS_FADE_MS = 400

/** How much of the dial's brightness is left while the hold-flat hint shows. */
private const val DIAL_DIMMED = 0.4f

/**
 * The heading number. The text is read here, not in [CompassScreen], so a new whole degree recomposes only this.
 * A screen reader is given the number with its name and unit ([headingSpoken]) in place of the bare text; those
 * words are made when the text changes, once for each whole degree, and not while the dial turns between two.
 */
@Composable
private fun HeadingNumber(text: () -> String, magnetic: Boolean, style: TextStyle, modifier: Modifier) {
    val shown = text()
    val spoken = remember(shown, magnetic) { headingSpoken(shown, magnetic) }
    Text(shown, color = Ink, style = style, maxLines = 1, softWrap = false, modifier = modifier.semantics { contentDescription = spoken })
}
