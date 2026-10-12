package com.infinite8looper.compass.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
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
import kotlin.math.abs
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

/**
 * Calls [onDone] at most once. Used by screens that leave both when the wearer finishes and when the waypoint they
 * show has gone, which can happen together (a delete finishes the screen and then the waypoint list updates).
 */
@Composable
private fun rememberLeaveOnce(onDone: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(onDone)
    var left by remember { mutableStateOf(false) }
    return { if (!left) { left = true; current() } }
}

@Composable
fun EditScreen(vm: CompassViewModel, id: Long, onPickHue: () -> Unit, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val w = nav.waypoints.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    val rename = rememberTextInput { vm.rename(id, it) }
    val listState = rememberScalingLazyListState()
    val leave = rememberLeaveOnce(onDone)

    if (w == null) {
        // Deleted, or not loaded yet. Leave once the stores have answered and the waypoint is still missing.
        LaunchedEffect(nav.loaded) { if (nav.loaded) leave() }
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
        title = { Text("Delete ${w.name}?", maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { confirmDelete = false; vm.delete(id); leave() }) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmDelete = false }) },
    )
}

@Composable
fun HueScreen(vm: CompassViewModel, id: Long, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val saved = nav.waypoints.firstOrNull { it.id == id }
    val leave = rememberLeaveOnce(onDone)
    if (saved == null) {
        LaunchedEffect(nav.loaded) { if (nav.loaded) leave() }
        return
    }
    var hue by remember(saved.hue) { mutableFloatStateOf(saved.hue) }
    // Hue 0 is drawn at 12 o'clock and increases clockwise.
    fun hueAt(p: Offset, center: Offset) =
        normalizeDeg(Math.toDegrees(atan2((p.x - center.x).toDouble(), (center.y - p.y).toDouble())).toFloat())

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> hue = normalizeDeg(hue + e.verticalScrollPixels * 0.25f); true }
            .requestFocusOnHierarchyActive().focusable()
            // Only a touch that starts on the ring and moves along it sets the hue (and is consumed); a tap on the
            // ring sets it too. Anything else, such as the swipe-right that goes back, is left to the system.
            .pointerInput(Unit) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val ringInner = size.width * 0.3f
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if ((down.position - c).getDistance() < ringInner) return@awaitEachGesture
                    val radial = (down.position - c).let { it / it.getDistance() }
                    val tangent = Offset(-radial.y, radial.x)
                    var moved = Offset.Zero
                    var claimed = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (change.isConsumed) return@awaitEachGesture
                        if (!change.pressed) {
                            if (!claimed) hue = hueAt(down.position, c)
                            return@awaitEachGesture
                        }
                        if (!claimed) {
                            moved += change.positionChange()
                            if (moved.getDistance() > viewConfiguration.touchSlop) {
                                val along = abs(moved.x * tangent.x + moved.y * tangent.y)
                                val across = abs(moved.x * radial.x + moved.y * radial.y)
                                if (along < across) return@awaitEachGesture
                                claimed = true
                            }
                        }
                        if (claimed) { hue = hueAt(change.position, c); change.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val r = minOf(maxWidth, maxHeight) / 2
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
            drawCircle(waypointColor(hue), r * 0.17f, Offset(center.x, center.y - r * 0.30f))
        }
        // The preview sits above the middle and the button below it, both scaled to the screen.
        Button(onClick = { vm.setHue(id, hue); leave() }, modifier = Modifier.offset(y = r * 0.30f)) { Text("Done") }
    }
}
