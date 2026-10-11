package com.infinite8looper.compass.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.normalizeDeg
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

@Composable
fun EditScreen(vm: CompassViewModel, id: Long, onPickHue: () -> Unit, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val w = nav.waypoints.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    val rename = rememberTextInput { vm.rename(id, it) }
    val listState = rememberScalingLazyListState()

    if (w == null) {
        // Deleted, or not loaded yet. Leave once the list has loaded without it.
        LaunchedEffect(nav.waypoints) { if (nav !== com.infinite8looper.compass.NavUi.EMPTY) onDone() }
        return
    }
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(w.name, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            item {
                SwitchButton(checked = w.isTarget, onCheckedChange = { vm.setTarget(id, it) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Set target") })
            }
            item { Button(onClick = rename, modifier = Modifier.fillMaxWidth()) { Text("Rename") } }
            item {
                Button(onClick = onPickHue, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(12.dp).background(waypointColor(w.hue), androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(8.dp)); Text("Color")
                }
            }
            item { Button(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete") } }
        }
    }
    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${w.name}?") },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { confirmDelete = false; vm.delete(id); onDone() }) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmDelete = false }) },
    )
}

@Composable
fun HueScreen(vm: CompassViewModel, id: Long, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val start = nav.waypoints.firstOrNull { it.id == id }?.hue ?: 0f
    var hue by remember(start) { mutableFloatStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Hue 0 is drawn at 12 o'clock and increases clockwise.
    fun hueAt(p: Offset, center: Offset) =
        normalizeDeg(Math.toDegrees(atan2((p.x - center.x).toDouble(), (center.y - p.y).toDouble())).toFloat())

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> hue = normalizeDeg(hue + e.verticalScrollPixels * 0.25f); true }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) { detectDragGestures { change, _ -> hue = hueAt(change.position, Offset(size.width / 2f, size.height / 2f)) } }
            .pointerInput(Unit) { detectTapGestures { p ->
                val c = Offset(size.width / 2f, size.height / 2f)
                if ((p - c).getDistance() > size.width * 0.3f) hue = hueAt(p, c)
            } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val ring = r * 0.16f
            val colors = (0..360 step 10).map { waypointColor(((it + 90) % 360).toFloat()) }
            // sweepGradient starts at 3 o'clock, so the list is offset by 90 degrees to put hue 0 at the top.
            drawCircle(Brush.sweepGradient(colors, center), r - ring / 2f - r * 0.04f, center, style = Stroke(ring))
            val a = Math.toRadians(hue.toDouble())
            val at = Offset(center.x + (r - ring / 2f - r * 0.04f) * sin(a).toFloat(), center.y - (r - ring / 2f - r * 0.04f) * cos(a).toFloat())
            drawCircle(Color.Black, ring * 0.62f, at)
            drawCircle(waypointColor(hue), ring * 0.5f, at)
            drawCircle(waypointColor(hue), r * 0.2f, Offset(center.x, center.y - r * 0.22f))
        }
        Button(onClick = { vm.setHue(id, hue); onDone() }, modifier = Modifier.padding(top = 46.dp)) { Text("Done") }
    }
}
