package com.infinite8looper.compass.ui

import android.hardware.SensorManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import androidx.wear.input.WearableButtons
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFFE8EAED)
private val Dim = Color(0xFF9E9E9E)

@Composable
fun CompassScreen(vm: CompassViewModel, onOpenList: () -> Unit, onLog: () -> Unit, onCalibrate: () -> Unit) {
    // Held as a State and read only in the frame loop and derived states, so sensor events do not recompose.
    val readingState = vm.heading.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val navState = rememberUpdatedState(nav)
    val shown = remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val reading = readingState.value
            if (reading != null && !shouldHoldFlat(reading.pitchDeg)) {
                val n = navState.value
                val target = displayHeading(reading.magneticDeg, n.declination, n.north).deg
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                shown.floatValue = if (shown.floatValue.isNaN()) target else smoothAngle(shown.floatValue, target, dt)
            }
            last = now
        }
    }

    val headingText by remember { derivedStateOf { if (shown.floatValue.isNaN()) "--" else "${shown.floatValue.roundToInt() % 360}°" } }
    val holdFlat by remember { derivedStateOf { readingState.value?.let { shouldHoldFlat(it.pitchDeg) } ?: false } }
    val lowAccuracy by remember {
        derivedStateOf { readingState.value?.accuracy?.let { it <= SensorManager.SENSOR_STATUS_ACCURACY_LOW } ?: false }
    }
    val showingMagnetic = nav.north == NorthRef.MAGNETIC || nav.declination == null
    val stale by produceState(false, nav.fixElapsedMs) {
        while (true) { value = isStale(SystemClock.elapsedRealtime(), nav.fixElapsedMs); delay(1_000) }
    }

    val view = LocalView.current
    val navigating = nav.targets.isNotEmpty()
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        onDispose { view.keepScreenOn = false }
    }

    // A watch may have no side button that apps can use; then logging is reached from the waypoints list.
    val context = LocalContext.current
    val hasSideButton = remember(context) {
        // The lookup throws IllegalStateException on a watch without the wearable system library.
        try { WearableButtons.getButtonInfo(context, KeyEvent.KEYCODE_STEM_1) != null } catch (e: IllegalStateException) { false }
    }

    val focus = remember { FocusRequester() }
    var crown by remember { mutableFloatStateOf(0f) }
    var armedStem by remember { mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN) }
    LaunchedEffect(Unit) { focus.requestFocus() }

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
            .focusRequester(focus).focusable()
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

        CompassDial(headingProvider = { shown.floatValue }, targets = nav.targets)

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

        if (!vm.supported) {
            Text("This watch has no compass sensor", color = Ink, style = bodyStyle, modifier = Modifier.slot(0f, 0.8f))
        } else {
            if (showingMagnetic) Text("MAG", color = Dim, style = style(0.079f), maxLines = 1, modifier = Modifier.slot(-0.31f, 0.6f))
            HeadingNumber({ headingText }, style(0.30f, FontWeight.Light), Modifier.slot(0f, 2 * READOUT_RADIUS))
            when {
                holdFlat -> Text("Hold flat", color = NorthRed, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(0.31f, 0.6f))
                lowAccuracy -> Text("Calibrate", color = NorthRed, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(0.31f, 0.6f))
            }
        }

        val focused = nav.focused
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
            nav.waypoints.isNotEmpty() ->
                Text("No target", color = Dim, style = bodyStyle, maxLines = 1, modifier = Modifier.slot(0.195f, 0.78f))
            // The hint needs the warning's slot as well, so it waits while a warning is showing.
            !vm.supported || holdFlat || lowAccuracy -> Unit
            hasSideButton ->
                Text("Press the side\nbutton to save\na waypoint", color = Dim, style = style(0.085f), modifier = Modifier.slot(0.285f, 0.8f))
            else ->
                Text("Open the list to\nsave a waypoint", color = Dim, style = style(0.085f), modifier = Modifier.slot(0.25f, 0.8f))
        }
    }
}

private val SIDE_BUTTONS = setOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)

/** The heading number. The text is read here, not in [CompassScreen], so a new whole degree recomposes only this. */
@Composable
private fun HeadingNumber(text: () -> String, style: TextStyle, modifier: Modifier) {
    Text(text(), color = Ink, style = style, maxLines = 1, softWrap = false, modifier = modifier)
}
