package com.infinite8looper.compass.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.accuracyLabel
import com.infinite8looper.compass.nav.isFieldNormal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CalibrationScreen(vm: CompassViewModel) {
    val reading by vm.heading.collectAsStateWithLifecycle()
    val phase by rememberInfiniteTransition(label = "figure8")
        .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    val accuracy = reading?.accuracy
    val field = reading?.fieldMicroTesla
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Move your wrist in a figure 8", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
        Canvas(Modifier.fillMaxWidth(0.7f).height(46.dp)) {
            // A lemniscate traced as x = sin t, y = sin t cos t.
            fun at(t: Float) = Offset(center.x + size.width * 0.45f * sin(t), center.y + size.height * 0.85f * sin(t) * cos(t))
            val path = Path().apply {
                moveTo(at(0f).x, at(0f).y)
                for (i in 1..64) { val p = at(i * (2 * PI).toFloat() / 64); lineTo(p.x, p.y) }
            }
            drawPath(path, Color(0xFF6E6E6E), style = Stroke(2.dp.toPx()))
            drawCircle(HeadingBlue, 5.dp.toPx(), at(phase))
        }
        Text(
            "Accuracy: ${accuracyLabel(accuracy)}",
            color = if (accuracy != null && accuracy >= 2) Color(0xFF7BE07B) else NorthRed, fontSize = 13.sp,
        )
        if (field != null) {
            Text("Field: ${field.roundToInt()} µT", color = Color(0xFF9E9E9E), fontSize = 10.sp)
            if (!isFieldNormal(field)) {
                Text("Magnetic interference nearby. Move away from chargers, magnets and steel.",
                    color = NorthRed, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
