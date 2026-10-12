package com.infinite8looper.compass.ui

import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFFE8EAED)
internal val Dim = Color(0xFF9E9E9E)

@Composable
fun CompassScreen(vm: CompassViewModel, onOpenList: () -> Unit, onLog: () -> Unit, onCalibrate: () -> Unit) {
    // Held as a State and read only in the frame loop and derived states, so sensor events do not recompose.
    val readingState = vm.heading.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val navState = rememberUpdatedState(nav)
    val shown = remember { mutableFloatStateOf(Float.NaN) }
    // Written every frame and read where the screen is composed: only a change of value recomposes.
    var holdFlat by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(view, lifecycle) {
        var last = 0L
        var cell = TickCell.Unset
        var lastTap = 0L
        while (true) withFrameNanos { now ->
            val reading = readingState.value
            if (reading != null) {
                val n = navState.value
                val hold = nextHoldFlat(holdFlat, reading.pitchDeg)
                holdFlat = hold
                // While the watch is tilted too far the heading is not taken, so the dial keeps its last value.
                if (!hold) {
                    val target = displayHeading(reading.magneticDeg, n.declination, n.north).deg
                    val dt = if (last == 0L) 0f else (now - last) / 1e9f
                    shown.floatValue = if (shown.floatValue.isNaN()) target else smoothAngle(shown.floatValue, target, dt)
                }
                // The cell is followed on every frame, so nothing is owed when the taps are allowed again.
                val next = nextTickCell(cell, shown.floatValue)
                if (isTickCrossing(cell, next) && !hold && n.tickFeedback &&
                    lifecycle.currentState == Lifecycle.State.RESUMED && tickTapDue(now, lastTap)
                ) {
                    view.performHapticFeedback(TICK_HAPTIC)
                    lastTap = now
                }
                cell = next
            }
            last = now
        }
    }

    val headingText by remember { derivedStateOf { if (shown.floatValue.isNaN()) "--" else "${shown.floatValue.roundToInt() % 360}°" } }
    val lowAccuracy by remember {
        derivedStateOf { readingState.value?.accuracy?.let { it <= SensorManager.SENSOR_STATUS_ACCURACY_LOW } ?: false }
    }
    val showingMagnetic = nav.north == NorthRef.MAGNETIC || nav.declination == null
    val stale by produceState(false, nav.fixElapsedMs) {
        while (true) { value = isStale(SystemClock.elapsedRealtime(), nav.fixElapsedMs); delay(1_000) }
    }

    val navigating = nav.targets.isNotEmpty()
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        onDispose { view.keepScreenOn = false }
    }

    var crown by remember { mutableFloatStateOf(0f) }
    var armedStem by remember { mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                crown += e.verticalScrollPixels
                if (abs(crown) > 80f) { vm.moveFocus(if (crown > 0) 1 else -1); crown = 0f }
                true
            }
            // A short press of a side button logs a waypoint, once, when the button is released. The crown's own
            // press (KEYCODE_STEM_PRIMARY) belongs to the system and is not touched.
            .onPreviewKeyEvent { e ->
                val key = e.nativeKeyEvent
                if (key.keyCode !in SIDE_BUTTONS) return@onPreviewKeyEvent false
                when (key.action) {
                    KeyEvent.ACTION_DOWN -> armedStem = if (key.repeatCount == 0) key.keyCode else KeyEvent.KEYCODE_UNKNOWN
                    KeyEvent.ACTION_UP -> {
                        val pressed = armedStem == key.keyCode && !key.isCanceled
                        armedStem = KeyEvent.KEYCODE_UNKNOWN
                        if (pressed) onLog()
                    }
                }
                true
            }
            .requestFocusOnHierarchyActive().focusable()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) > 48.dp.toPx()) vm.moveFocus(if (total < 0) 1 else -1) },
                ) { _, dy -> total += dy }
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
            modifier = Modifier.graphicsLayer { alpha = dialAlpha.value },
        )

        // Tap areas, each at least 48 dp both ways. The whole middle opens the waypoints list. While the
        // calibrate prompt shows, the part from the prompt down is its own area and the list keeps the rest.
        val calibrate = vm.supported && !holdFlat && lowAccuracy
        val listBottom = if (calibrate) 0.255f else 0.5f
        Box(
            Modifier.slot(y = (listBottom - 0.5f) / 2f, width = 0.9f).height(r * (listBottom + 0.5f))
                .clickable(interactionSource = null, indication = null, onClick = onOpenList)
        )
        if (calibrate) {
            Box(
                Modifier.slot(y = 0.255f + 0.26f, width = 0.9f).height(r * 0.52f)
                    .clickable(interactionSource = null, indication = null, onClick = onCalibrate)
            )
        }

        val focused = nav.focused
        // Whether the slots just above and below the heading hold any text: a target's name and distance, or "No target".
        val linesAroundHeading = focused != null || (nav.loaded && nav.waypoints.isNotEmpty())

        if (!vm.supported) {
            Text("This watch has no compass sensor", color = Ink, style = bodyStyle, modifier = Modifier.slot(0f, 0.8f))
        } else {
            if (showingMagnetic) Text("MAG", color = Dim, style = style(0.079f), maxLines = 1, modifier = Modifier.slot(-0.31f, 0.6f))
            // The middle slot holds the heading, or, while the heading cannot be trusted, the hint that says why.
            // The hint's drawing fills the gap between the line above the heading and the line below it, and
            // has more room when both of those slots are empty. Its words sit where the calibrate prompt does.
            val hintHeight = if (linesAroundHeading) 0.255f else 0.42f
            when {
                holdFlat -> HoldFlatHint(
                    labelStyle = bodyStyle, labelOffset = r * PROMPT_Y,
                    modifier = Modifier.slot(0f, hintHeight * 1.9f).height(r * hintHeight),
                )
                else -> {
                    HeadingNumber({ headingText }, style(0.30f, FontWeight.Light), Modifier.slot(0f, 2 * READOUT_RADIUS))
                    if (lowAccuracy) Text("Calibrate", color = Dim, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(PROMPT_Y, 0.6f))
                }
            }
        }

        when {
            focused != null -> {
                Row(Modifier.slot(-0.195f, 0.78f), Arrangement.Center, Alignment.CenterVertically) {
                    Box(Modifier.size(r * 0.062f).background(waypointColor(focused.hue), CircleShape))
                    Spacer(Modifier.width(r * 0.035f))
                    Text(focused.name, color = Ink, style = bodyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val d = focused.distanceM
                Text(
                    text = when {
                        d == null -> "Locating…"
                        hasArrived(d, nav.units) -> "Arrived at target"
                        stale -> "${formatDistance(d, nav.units)} (old)"
                        else -> formatDistance(d, nav.units)
                    },
                    color = if (stale) Dim else Ink, style = bodyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.slot(0.195f, 0.78f),
                )
            }
            // With no waypoints saved, or before the first real state arrives, nothing is shown here: the screen
            // is a plain compass, and a tap on the middle still opens the waypoints list.
            nav.loaded && nav.waypoints.isNotEmpty() ->
                Text("No target", color = Dim, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(0.195f, 0.78f))
        }
    }
}

/** Where the calibrate prompt and the words of the hold-flat hint sit, in dial radii below the center. */
private const val PROMPT_Y = 0.31f

/** How much of the dial's brightness is left while the hold-flat hint shows. */
private const val DIAL_DIMMED = 0.4f

/**
 * The softest tick the platform maps to a vibration. From API 34 that is the constant made for many choices in
 * quick succession. API 33 does not have it; there the clock tick plays the platform's lightest effect.
 */
private val TICK_HAPTIC =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    else HapticFeedbackConstants.CLOCK_TICK

private val SIDE_BUTTONS = setOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)

/** The heading number. The text is read here, not in [CompassScreen], so a new whole degree recomposes only this. */
@Composable
private fun HeadingNumber(text: () -> String, style: TextStyle, modifier: Modifier) {
    Text(text(), color = Ink, style = style, maxLines = 1, softWrap = false, modifier = modifier)
}
