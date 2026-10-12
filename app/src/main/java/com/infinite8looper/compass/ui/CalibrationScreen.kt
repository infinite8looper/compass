package com.infinite8looper.compass.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.accuracyLabel
import com.infinite8looper.compass.nav.RingRole
import com.infinite8looper.compass.nav.calibrationRing
import com.infinite8looper.compass.nav.isInterference
import com.infinite8looper.compass.sensors.HeadingReading
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the screen shows. The sensor reports about 50 times a second; this changes about once a second. */
private data class CalibrationView(val accuracy: Int?, val fieldMicroTesla: Int?, val interference: Boolean)

private fun HeadingReading?.toView() = CalibrationView(
    this?.accuracy, this?.fieldMicroTesla?.roundToInt(), isInterference(this?.fieldMicroTesla),
)

/**
 * Calibration, opened from the settings or from the compass's prompt, in fractions of the screen's radius from the
 * top: what to do, the figure 8 to follow, the accuracy the magnetometer reports as the large word, and the field
 * strength small under it. The frame fills in the confirming green by quarters as the accuracy improves. A field
 * outside what the earth's can be ([isInterference]) is reported under the rest, in the error color, and for as
 * long as it lasts the whole frame is filled in the error color, whatever the accuracy ([calibrationRing]).
 *
 * Everything stands where it is whatever is shown: the warning has its own place and comes and goes there.
 * A right swipe and Back leave, which is done where the screen is put into the navigation host (ui/CompassApp.kt).
 * The crown and a side button do nothing here.
 */
@Composable
fun CalibrationScreen(vm: CompassViewModel) {
    // Collect only the displayed values, so the screen recomposes when one of them changes, not on every reading.
    // It opens on what the sensor last said, which the view model keeps while the sensor is stopped.
    val shown by remember(vm) { vm.heading.map { it.toView() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(remember(vm) { vm.heading.value.toView() })
    // The two that move are read only where they are drawn.
    val phase = rememberInfiniteTransition(label = "figure8")
        .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    val ring = calibrationRing(shown.accuracy, shown.interference)
    val filled = animateFloatAsState(ring.fraction, tween(FILL_MS), label = "ring")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val r = minOf(maxWidth, maxHeight) / 2
        val density = LocalDensity.current
        val smallStyle = remember(r, density) { density.instrumentText(r * 0.09f, align = TextAlign.Center) }
        val wordStyle = remember(r, density) { density.instrumentText(r * 0.24f, FontWeight.Light, TextAlign.Center) }
        val warningStyle = remember(r, density) { density.instrumentText(r * 0.085f, align = TextAlign.Center) }

        // The level and the interference change about once a second at most, and the ring with them.
        FrameRing(fill = { filled.value }, fillColor = if (ring.role == RingRole.ERROR) ErrorColor else ConfirmColor, fillInner = FILL_INNER)

        Text(
            "Move your wrist in a figure 8", color = Dim, style = smallStyle, maxLines = 2,
            modifier = Modifier.offset(y = r * GUIDE_TEXT_Y).width(r * 1.2f),
        )
        Canvas(Modifier.offset(y = r * GUIDE_Y).width(r * 0.84f).height(r * 0.26f)) {
            // A lemniscate traced as x = sin t, y = sin t cos t.
            fun at(t: Float) = Offset(center.x + size.width * 0.45f * sin(t), center.y + size.height * 0.85f * sin(t) * cos(t))
            val path = Path().apply {
                moveTo(at(0f).x, at(0f).y)
                for (i in 1..64) { val p = at(i * (2 * PI).toFloat() / 64); lineTo(p.x, p.y) }
                close()
            }
            drawPath(path, Faint, style = Stroke(size.height * 0.07f, cap = StrokeCap.Round))
            drawCircle(Ink, size.height * 0.16f, at(phase.value))
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
            Text(
                "Magnetic interference nearby. Move away from chargers, magnets and steel.",
                color = ErrorColor, style = warningStyle, maxLines = 3,
                modifier = Modifier.offset(y = r * WARNING_Y).width(r * WARNING_WIDTH).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// Where things stand, in radii below the center (negative: above it), each by its middle.
private const val GUIDE_TEXT_Y = -0.54f
private const val GUIDE_Y = -0.30f
private const val WORD_Y = 0.01f
private const val FIELD_Y = 0.22f
private const val WARNING_Y = 0.49f
private const val WARNING_WIDTH = 1.3f

/** The accuracy's fill reaches in from the frame's outer edge to this fraction of the radius. */
private const val FILL_INNER = 0.92f
/** How long the fill takes to move to a new level. */
private const val FILL_MS = 400
