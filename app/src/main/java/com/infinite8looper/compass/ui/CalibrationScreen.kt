package com.infinite8looper.compass.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.COVERAGE_BANDS
import com.infinite8looper.compass.nav.COVERAGE_SECTORS
import com.infinite8looper.compass.nav.accuracyLabel
import com.infinite8looper.compass.nav.RingRole
import com.infinite8looper.compass.nav.calibrationAwakeUntil
import com.infinite8looper.compass.nav.calibrationRing
import com.infinite8looper.compass.nav.coveredText
import com.infinite8looper.compass.nav.headingErrorText
import com.infinite8looper.compass.nav.isCovered
import com.infinite8looper.compass.nav.isInterference
import com.infinite8looper.compass.nav.nextCoverage
import com.infinite8looper.compass.sensors.HeadingReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * What the screen shows of the sensor. The sensor reports about 50 times a second; the level changes about once a
 * second at most, and the estimated heading error, in whole degrees, seldom.
 */
private data class CalibrationView(val accuracy: Int?, val fieldMicroTesla: Int?, val interference: Boolean, val errorText: String?)

private fun HeadingReading?.toView() = CalibrationView(
    this?.accuracy, this?.fieldMicroTesla?.roundToInt(), isInterference(this?.fieldMicroTesla), this?.headingErrorDeg?.let(::headingErrorText),
)

/**
 * Calibration, opened from the settings or from the compass's prompt, in fractions of the screen's radius from the
 * top: what to do; the ring of directions covered, with `Covered` and the share covered to its left and, when the
 * rotation vector gives one, the estimated heading error to its right; the accuracy the magnetometer reports as the
 * large word, and the field strength small under it. The frame fills in the confirming green by quarters as the
 * accuracy improves. A field outside what the earth's can be ([isInterference]) is reported under the rest, in the
 * error color, and for as long as it lasts the whole frame is filled in the error color, whatever the accuracy
 * ([calibrationRing]).
 *
 * The ring (v3 spec, "Calibration") has a segment for each region of directions the magnetometer's field can point
 * in, in the watch's own frame (nav/Calibration.kt): three rings, for the field through the back of the watch
 * (outer), across its face (middle) and out of its face (inner), each of eight sectors, placed by the direction of
 * the field across the face. A segment lights when the field has pointed there since the screen opened. It shows
 * the motion covered, not accuracy, and is labeled so.
 *
 * The screen keeps the display on ([CompassViewModel.setCalibrationAwake]) for two minutes from its opening or from
 * the last segment that lit, whichever is later.
 *
 * In a build with the diagnostics switch on, a row at the bottom, `Check`, opens the check screen ([onCheck]; null
 * where the build has no such screen, and then there is no row).
 *
 * Everything stands where it is whatever is shown: the warning and the heading error have their own places and come
 * and go there. A right swipe and Back leave, which is done where the screen is put into the navigation host
 * (ui/CompassApp.kt). The crown and a side button do nothing here.
 */
@Composable
fun CalibrationScreen(vm: CompassViewModel, onCheck: (() -> Unit)? = null) {
    // Collect only the displayed values, so the screen recomposes when one of them changes, not on every reading.
    // It opens on what the sensor last said, which the view model keeps while the sensor is stopped.
    val shown by remember(vm) { vm.heading.map { it.toView() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(remember(vm) { vm.heading.value.toView() })
    val ring = calibrationRing(shown.accuracy, shown.interference)
    val filled = animateFloatAsState(ring.fraction, tween(FILL_MS), label = "ring")

    // The regions covered since the screen opened, and when the last new one was: read where they are drawn and in
    // the effect that keeps the display on, so a reading that adds nothing changes nothing.
    val covered = remember { mutableIntStateOf(0) }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val lastNewAt = remember { mutableLongStateOf(NONE_YET) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The reading held from before the screen opened is not counted: the field may have been there long ago.
            vm.heading.drop(1).collect { r ->
                if (r == null) return@collect
                val next = nextCoverage(covered.intValue, r.fieldX, r.fieldY, r.fieldZ)
                if (next != covered.intValue) { covered.intValue = next; lastNewAt.longValue = SystemClock.elapsedRealtime() }
            }
        }
    }
    val lastNew = lastNewAt.longValue
    LaunchedEffect(vm, lastNew) {
        val left = calibrationAwakeUntil(openedAt, lastNew.takeIf { it != NONE_YET }) - SystemClock.elapsedRealtime()
        if (left > 0) { vm.setCalibrationAwake(true); delay(left) }
        vm.setCalibrationAwake(false)
    }
    DisposableEffect(vm) { onDispose { vm.setCalibrationAwake(false) } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val r = minOf(maxWidth, maxHeight) / 2
        val density = LocalDensity.current
        val smallStyle = remember(r, density) { density.instrumentText(r * 0.09f, align = TextAlign.Center) }
        val wordStyle = remember(r, density) { density.instrumentText(r * 0.24f, FontWeight.Light, TextAlign.Center) }
        // The lines that can grow too long for their room at a large font size are drawn by the text that can make
        // itself smaller, which takes nothing from the theme; given the theme's style under their own, their lines
        // break where the other lines' do.
        val themeStyle = LocalTextStyle.current
        val warningStyle = remember(r, density, themeStyle) { themeStyle.merge(density.instrumentText(r * 0.085f, align = TextAlign.Center)) }
        val guideStyle = remember(r, density, themeStyle) { themeStyle.merge(density.instrumentText(r * 0.09f, align = TextAlign.Center)) }
        val besideStyle = remember(r, density, themeStyle) { themeStyle.merge(density.instrumentText(r * 0.085f, align = TextAlign.Center)) }
        val checkStyle = remember(r, density) { density.instrumentText(r * 0.09f, FontWeight.Medium, TextAlign.Center) }

        // The level and the interference change about once a second at most, and the frame with them.
        FrameRing(fill = { filled.value }, fillColor = if (ring.role == RingRole.ERROR) ErrorColor else ConfirmColor, fillInner = FILL_INNER)

        // The line stands on its lower edge: where a larger font size puts it on more lines, the last line is
        // where the one line was and the others are above it, clear of the ring below. Past two lines it is drawn
        // smaller, until two hold it.
        Box(Modifier.offset(y = r * (GUIDE_TEXT_BOTTOM - GUIDE_TEXT_ROOM / 2f)).width(r * GUIDE_WIDTH).height(r * GUIDE_TEXT_ROOM), Alignment.BottomCenter) {
            BasicText(
                "Turn your wrist slowly in every direction", style = guideStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = { Dim },
                autoSize = TextAutoSize.StepBased(minFontSize = guideStyle.fontSize * SMALLEST, maxFontSize = guideStyle.fontSize, stepSize = 0.25.sp),
            )
        }
        CoverageRing({ covered.intValue }, Modifier.offset(y = r * RING_Y).size(r * RING_RADIUS * 2))
        Beside(
            "Covered", coveredText(covered.intValue), besideStyle,
            Modifier.offset(x = -r * BESIDE_X, y = r * RING_Y).width(r * BESIDE_WIDTH),
        )
        shown.errorText?.let { error ->
            Beside("Error", error, besideStyle, Modifier.offset(x = r * BESIDE_X, y = r * RING_Y).width(r * BESIDE_WIDTH))
        }
        Text(
            accuracyLabel(shown.accuracy), color = Ink, style = wordStyle, maxLines = 1,
            modifier = Modifier.offset(y = r * WORD_Y).semantics {
                contentDescription = "Accuracy: ${accuracyLabel(shown.accuracy)}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        shown.fieldMicroTesla?.let { field ->
            Text("Field: $field µT", color = Dim, style = smallStyle, maxLines = 1, modifier = Modifier.offset(y = r * FIELD_Y))
        }
        if (shown.interference) {
            // The warning has three lines' room where it stands. At a font size where its words need a fourth it
            // is drawn smaller, until the three hold it.
            BasicText(
                "Magnetic interference nearby. Move away from chargers, magnets and steel.",
                Modifier.offset(y = r * WARNING_Y).width(r * WARNING_WIDTH).semantics { liveRegion = LiveRegionMode.Polite },
                warningStyle, maxLines = 3, overflow = TextOverflow.Ellipsis, color = { ErrorColor },
                autoSize = TextAutoSize.StepBased(minFontSize = warningStyle.fontSize * SMALLEST, maxFontSize = warningStyle.fontSize, stepSize = 0.25.sp),
            )
        }
        // The row that opens the check screen, at the bottom: only in a build that has the screen (nav/Check.kt).
        if (onCheck != null) {
            Box(
                Modifier.offset(y = r * CHECK_Y).width(r * CHECK_WIDTH).heightIn(min = HitHeight).clickable(role = Role.Button, onClick = onCheck),
                Alignment.Center,
            ) {
                Text("Check", color = AccentColor, style = checkStyle, maxLines = 1)
            }
        }
    }
}

/** A label and its value on two lines, beside the ring: the label dim, the value in ink. Each line is drawn smaller rather than cut. */
@Composable
private fun Beside(label: String, value: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        for ((text, color) in listOf(label to Dim, value to Ink)) {
            BasicText(
                text, style = style, maxLines = 1, color = { color },
                autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * SMALLEST, maxFontSize = style.fontSize, stepSize = 0.25.sp),
            )
        }
    }
}

/**
 * The ring of segments: band 0 (the field through the back of the watch) outermost, band 2 (out of its face)
 * innermost; sector k centered k eighths of a turn counterclockwise from 3 o'clock, which is the direction the field
 * points across the face. Lit segments in the confirming green, the others faint. Read [covered] only here, while
 * drawing, so a new segment redraws the ring and recomposes nothing.
 */
@Composable
private fun CoverageRing(covered: () -> Int, modifier: Modifier) {
    Canvas(modifier) {
        val outer = size.minDimension / 2f
        val layer = outer * LAYER / (COVERAGE_BANDS * LAYER + (COVERAGE_BANDS - 1) * LAYER_GAP + CORE)
        val gap = outer * LAYER_GAP / (COVERAGE_BANDS * LAYER + (COVERAGE_BANDS - 1) * LAYER_GAP + CORE)
        val lit = covered()
        val sweep = 360f / COVERAGE_SECTORS
        for (band in 0 until COVERAGE_BANDS) {
            // The middle of this band's layer, from the center.
            val mid = outer - layer / 2f - band * (layer + gap)
            for (sector in 0 until COVERAGE_SECTORS) {
                val region = band * COVERAGE_SECTORS + sector
                drawArc(
                    color = if (isCovered(lit, region)) ConfirmColor else Faint,
                    // Counterclockwise on the screen is negative in drawArc, which measures clockwise from 3 o'clock.
                    startAngle = -(sector * sweep) - sweep / 2f + SEGMENT_GAP_DEG / 2f, sweepAngle = sweep - SEGMENT_GAP_DEG, useCenter = false,
                    topLeft = Offset(center.x - mid, center.y - mid), size = Size(mid * 2f, mid * 2f), style = Stroke(layer),
                )
            }
        }
    }
}

private const val NONE_YET = -1L

// Where things stand, in radii below the center (negative: above it), each by its middle.
/** The lower edge of the line that says what to do, the height kept for its lines above that, and its width. */
private const val GUIDE_TEXT_BOTTOM = -0.50f
private const val GUIDE_TEXT_ROOM = 0.30f
private const val GUIDE_WIDTH = 1.2f
/** The ring of directions covered: its middle and its outer radius. */
private const val RING_Y = -0.305f
private const val RING_RADIUS = 0.17f
/** The words either side of the ring: how far their middles are from the screen's middle, and their width. */
private const val BESIDE_X = 0.48f
private const val BESIDE_WIDTH = 0.5f
private const val WORD_Y = 0.02f
private const val FIELD_Y = 0.24f
private const val WARNING_Y = 0.49f
private const val WARNING_WIDTH = 1.3f
/** The row that opens the check screen: its middle and its width. */
private const val CHECK_Y = 0.78f
private const val CHECK_WIDTH = 0.6f
/** How small a line is drawn, as a share of its size, to keep it in its room. */
private const val SMALLEST = 0.75f
/** The ring's proportions: each layer's width, the gap between layers, the empty middle, as shares of one another; the gap between segments, in degrees. */
private const val LAYER = 1f
private const val LAYER_GAP = 0.35f
private const val CORE = 0.45f
private const val SEGMENT_GAP_DEG = 6f

/** The accuracy's fill reaches in from the frame's outer edge to this fraction of the radius. */
private const val FILL_INNER = 0.92f
/** How long the fill takes to move to a new level. */
private const val FILL_MS = 400
