package com.infinite8looper.compass.ui

import android.os.SystemClock
import android.os.VibrationEffect.Composition
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.WatchScheme
import com.infinite8looper.compass.nav.buttonCountText
import com.infinite8looper.compass.nav.checkAwakeUntil
import com.infinite8looper.compass.nav.checkReadout
import com.infinite8looper.compass.nav.colorHex
import com.infinite8looper.compass.nav.headingKindInUse
import com.infinite8looper.compass.nav.headingKindName
import com.infinite8looper.compass.nav.headingSourceNote
import com.infinite8looper.compass.nav.nextHeadingKind
import com.infinite8looper.compass.nav.yesNo
import com.infinite8looper.compass.sensors.HeadingReading
import com.infinite8looper.compass.sensors.OneHandedGestures
import com.infinite8looper.compass.sensors.WatchButtons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private fun HeadingReading?.toReadout() = checkReadout(
    this?.fusedDeg ?: Float.NaN, this?.geomagneticDeg ?: Float.NaN, this?.blendDeg ?: Float.NaN, this?.headingErrorDeg, this?.accuracy, this?.fieldMicroTesla,
)

/**
 * The check screen (v3 spec, "Check screen"), opened from a row on calibration in the builds made with the
 * diagnostics switch on (nav/Check.kt, [com.infinite8looper.compass.nav.checkRoute]). A list, as settings is:
 *
 * - `Heading source`, the row that switches the compass's heading source for the running app
 *   ([CompassViewModel.setHeadingKind]): fused, magnetic, blend, and round again. It names the source the compass is
 *   on; on a watch that cannot work out the magnetic heading that is the fused one, with `Magnetic unavailable` or
 *   `Blend unavailable` under it ([headingKindInUse], [headingSourceNote]).
 * - Live, while the screen is open: the three headings, as `Fused heading`, `Magnetic heading` and `Blend heading` (the view model works all three out while it is,
 *   [CompassViewModel.setAllHeadings]), the estimated heading error, the magnetometer's level and field strength.
 * - What the watch returned for its colors: the six colors of the scheme the library gives (as `Watch face` uses
 *   them), each with its swatch, or `None`; read again each time the screen comes back on screen.
 * - Its buttons ([WatchButtons]), and the one-handed gestures ([OneHandedGestures]).
 * - Which of the eight composition primitives the vibrator supports, and three rows that play the dial's three
 *   vibrations as the dial plays them ([Vibrations.play]), each saying whether it plays as primitives or as its
 *   haptic feedback constant here.
 *
 * Each row that only shows a value is read by a screen reader as `label: value`. The screen does what it says only
 * while it is open; nothing of it is stored. It keeps the display on ([CompassViewModel.setCheckAwake]) for two
 * minutes from its opening or from the last touch or turn of the crown on it, whichever is later. A right swipe and Back leave (ui/CompassApp.kt).
 */
@Composable
fun CheckScreen(vm: CompassViewModel) {
    DisposableEffect(vm) {
        vm.setAllHeadings(true)
        onDispose { vm.setAllHeadings(false) }
    }
    // The reading as text: the screen changes when what it shows changes, not on every reading.
    val readout by remember(vm) { vm.heading.map { it.toReadout() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(remember(vm) { vm.heading.value.toReadout() })
    val kind by vm.headingKind.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val vibrations = remember(view) { Vibrations.of(view) }
    var scheme by remember { mutableStateOf<WatchScheme?>(null) }
    LifecycleResumeEffect(context) {
        scheme = watchScheme(context)
        onPauseOrDispose { }
    }
    val buttons = remember(context) { WatchButtons.report(context) }
    val gestures = remember(context) { OneHandedGestures.report(context) }
    // The vibrator is asked away from the main thread.
    var primitives by remember { mutableStateOf<BooleanArray?>(null) }
    LaunchedEffect(context) {
        primitives = withContext(Dispatchers.Default) {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            BooleanArray(PRIMITIVES.size) { i -> vibrator != null && vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported(PRIMITIVES[i].second) }
        }
    }
    // How each dial row plays: the player knows once it has asked the vibrator, which it starts when the activity is
    // made; the rows say so when it has, however long that takes.
    val asked by Vibrations.asked.collectAsStateWithLifecycle()
    val branches = remember(vibrations, asked) { DIAL.map { vibrations.branch(it.second) } }

    // The display is kept on for two minutes from the opening or from the last touch or turn of the crown, whichever
    // is later (nav/Check.kt, checkAwakeUntil).
    val openedAt = remember { SystemClock.elapsedRealtime() }
    var lastTouchAt by remember { mutableLongStateOf(NONE_YET) }
    LaunchedEffect(vm, lastTouchAt) {
        val left = checkAwakeUntil(openedAt, lastTouchAt.takeIf { it != NONE_YET }) - SystemClock.elapsedRealtime()
        if (left > 0) { vm.setCheckAwake(true); delay(left) }
        vm.setCheckAwake(false)
    }
    DisposableEffect(vm) { onDispose { vm.setCheckAwake(false) } }
    val touched = { lastTouchAt = SystemClock.elapsedRealtime() }

    val r = screenRadius()
    val density = LocalDensity.current
    val styles = remember(r, density) {
        CheckStyles(
            header = density.instrumentText(r * 0.105f, align = TextAlign.Center),
            section = density.instrumentText(r * 0.085f, FontWeight.Medium, align = TextAlign.Center),
            label = density.instrumentText(r * 0.085f),
            value = density.instrumentText(r * 0.12f),
            action = density.instrumentText(r * 0.12f, FontWeight.Medium),
        )
    }
    val listState = rememberScalingLazyListState()

    // A touch is seen before the list or a row takes it, and is left to them; so is a turn of the crown.
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) touched()
                    }
                }
            }
            .onPreRotaryScrollEvent { touched(); false },
    ) {
        Box(Modifier.fillMaxSize().padding(r * (1f - LIST_RADIUS)).clip(CircleShape)) {
            ScalingLazyColumn(
                state = listState,
                scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
                verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxSize().fadeTopAndBottom(),
            ) {
                item(key = "header") {
                    InstrumentRow(centered = true) { Text("Check", color = Dim, style = styles.header, maxLines = 1) }
                }
                item(key = "source") {
                    InstrumentRow(Modifier.clickable(role = Role.Button) { vm.setHeadingKind(nextHeadingKind(kind)) }, fitContent = true) {
                        Column {
                            Text("Heading source", color = Dim, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // The source the compass is on: the one asked for, or the fused one on a watch that
                            // cannot work out the magnetic heading, with a line saying which is unavailable.
                            val inUse = headingKindInUse(kind, vm.magneticHeadingAvailable)
                            Text(headingKindName(inUse), color = AccentColor, style = styles.action, maxLines = 1)
                            headingSourceNote(kind, inUse)?.let { Text(it, color = Dim, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                valueRow("Fused heading", readout.fused, styles)
                valueRow("Magnetic heading", readout.magnetic, styles)
                valueRow("Blend heading", readout.blend, styles)
                valueRow("Error", readout.error, styles)
                valueRow("Level", readout.level, styles)
                valueRow("Field", readout.field, styles)

                section("Watch colors", styles)
                val shownScheme = scheme
                if (shownScheme == null) valueRow("Color scheme", "None", styles)
                else for ((label, color) in schemeColors(shownScheme)) valueRow(label, colorHex(color), styles, swatch = color)

                section("Inputs", styles)
                valueRow("Buttons", buttonCountText(buttons.buttonCount), styles)
                valueRow("Side keys", buttons.sideKeys.joinToString { "STEM_${it - android.view.KeyEvent.KEYCODE_STEM_1 + 1}" }.ifEmpty { "None" }, styles)
                valueRow("Assignable button", yesNo(buttons.assignable), styles)
                valueRow("Wear SDK", yesNo(gestures.sdkListed), styles)
                valueRow("Wear SDK reached", yesNo(gestures.sdkReached), styles)
                valueRow("One-handed gestures", yesNo(gestures.gestures), styles)

                section("Vibrations", styles)
                val supported = primitives
                for ((i, primitive) in PRIMITIVES.withIndex()) {
                    valueRow(primitive.first, supported?.let { yesNo(it[i]) } ?: "--", styles, key = "primitive-${primitive.first}")
                }
                for ((i, dial) in DIAL.withIndex()) {
                    item(key = "play-${dial.first}") {
                        InstrumentRow(Modifier.clickable(role = Role.Button) { vibrations.play(dial.second) }, fitContent = true) {
                            Column {
                                Text(dial.first, color = AccentColor, style = styles.action, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (branches[i] == VibrationBranch.PRIMITIVES) "Primitives" else "Constant", color = Dim, style = styles.label,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        FrameRing(scroll = { listScrollArc(listState) })
    }
}

/** A row that shows a value: its label small and dim, the value under it; read as `label: value`. */
private fun ScalingLazyListScope.valueRow(label: String, value: String, styles: CheckStyles, swatch: Int? = null, key: String = label) {
    item(key = key) {
        InstrumentRow(Modifier.clearAndSetSemantics { contentDescription = "$label: $value" }, fitContent = true) {
            Column {
                Text(label, color = Dim, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (swatch != null) {
                        Spacer(Modifier.size(SWATCH).clip(CircleShape).background(Color(0xFF000000.toInt() or swatch)))
                        Spacer(Modifier.width(SWATCH / 2))
                    }
                    Text(value, color = Ink, style = styles.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A part's name, centered. */
private fun ScalingLazyListScope.section(title: String, styles: CheckStyles) {
    item(key = "section-$title") {
        InstrumentRow(centered = true) { Text(title, color = Dim, style = styles.section, maxLines = 1) }
    }
}

/** The six colors of a scheme as the app takes them (nav/WatchColors.kt), by name. */
private fun schemeColors(s: WatchScheme): List<Pair<String, Int>> = listOfNotNull(
    "Primary" to s.primary, "Tertiary" to s.tertiary, "Error color" to s.error,
    s.onSurface?.let { "On surface" to it }, s.onSurfaceVariant?.let { "On surface variant" to it }, s.outline?.let { "Outline" to it },
)

private class CheckStyles(val header: TextStyle, val section: TextStyle, val label: TextStyle, val value: TextStyle, val action: TextStyle)

/** The eight composition primitives, by name. */
private val PRIMITIVES = listOf(
    "Click" to Composition.PRIMITIVE_CLICK, "Thud" to Composition.PRIMITIVE_THUD, "Spin" to Composition.PRIMITIVE_SPIN,
    "Quick rise" to Composition.PRIMITIVE_QUICK_RISE, "Slow rise" to Composition.PRIMITIVE_SLOW_RISE,
    "Quick fall" to Composition.PRIMITIVE_QUICK_FALL, "Tick" to Composition.PRIMITIVE_TICK, "Low tick" to Composition.PRIMITIVE_LOW_TICK,
)

/** The dial's three vibrations, as the rows that play them name them. */
private val DIAL = listOf("Play thin line" to Feel.THIN, "Play thick line" to Feel.THICK, "Play target" to Feel.TARGET)

/** The colored dot beside a color's value. */
private val SWATCH = 12.dp

/** No touch yet. */
private const val NONE_YET = Long.MIN_VALUE
