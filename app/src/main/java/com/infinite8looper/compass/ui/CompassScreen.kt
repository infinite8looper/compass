package com.infinite8looper.compass.ui

import android.hardware.SensorManager
import android.os.SystemClock
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
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

    val focus = remember { FocusRequester() }
    var crown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                crown += e.verticalScrollPixels
                if (abs(crown) > 80f) { vm.moveFocus(if (crown > 0) 1 else -1); crown = 0f }
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
        // Like the dial, the readout is sized in fractions of the dial radius, so it stays inside the circle
        // the cardinal letters sweep around at every screen size. The factors give the same sizes as 38, 11, 10
        // and 9 sp on a 454 px, 320 dpi screen.
        val r = minOf(maxWidth, maxHeight) / 2
        val density = LocalDensity.current
        fun style(fraction: Float, lineHeightEm: Float = 1.15f) = with(density) {
            val size = (r * fraction).toSp()
            TextStyle(
                fontSize = size, lineHeight = size * lineHeightEm, textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            )
        }
        val headingStyle = style(0.335f, lineHeightEm = 0.85f)
        val bodyStyle = style(0.097f)
        val tipStyle = style(0.088f)
        val magStyle = style(0.079f)

        CompassDial(headingProvider = { shown.floatValue }, targets = nav.targets)

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(r * 0.72f)) {
            if (!vm.supported) {
                Text("This watch has no compass sensor", color = Ink, style = bodyStyle)
            } else {
                if (showingMagnetic) Text("MAG", color = Dim, style = magStyle)
                HeadingReadout(
                    headingText = { headingText }, holdFlat = { holdFlat }, lowAccuracy = { lowAccuracy },
                    headingStyle = headingStyle, statusStyle = bodyStyle, onCalibrate = onCalibrate,
                )
            }
            val focused = nav.focused
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onOpenList)) {
                when {
                    focused != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = if (stale) Dim else Ink, style = bodyStyle,
                        )
                    }
                    nav.waypoints.isEmpty() -> Text("Tap + to save\na waypoint", color = Dim, style = tipStyle)
                    else -> Text("No target", color = Dim, style = bodyStyle)
                }
            }
        }

        // Sits over the bottom of the ring, low enough to clear the cardinal letters as they pass.
        // The black rim masks the ticks and labels that pass underneath, so none is cut off at the button's edge.
        Box(
            Modifier.align(Alignment.BottomCenter).size(r * 0.36f).background(Color.Black, CircleShape)
                .padding(r * 0.03f).background(Color(0xFF1F1F1F), CircleShape).clickable(onClick = onLog),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Ink, style = style(0.176f, lineHeightEm = 1f)) }
    }
}

/**
 * The heading number and the warning under it. The states are read here, not in [CompassScreen], so a new
 * whole degree recomposes only these texts.
 */
@Composable
private fun HeadingReadout(
    headingText: () -> String, holdFlat: () -> Boolean, lowAccuracy: () -> Boolean,
    headingStyle: TextStyle, statusStyle: TextStyle, onCalibrate: () -> Unit,
) {
    Text(headingText(), color = Ink, style = headingStyle, maxLines = 1, softWrap = false)
    when {
        holdFlat() -> Text("Hold flat", color = NorthRed, style = statusStyle)
        lowAccuracy() -> Text("Calibrate", color = NorthRed, style = statusStyle, modifier = Modifier.clickable(onClick = onCalibrate))
    }
}
