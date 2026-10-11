package com.infinite8looper.compass.ui

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.acquireFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val TAG = "LogScreen"

private sealed interface LogState {
    data object NeedsPermission : LogState
    data object Denied : LogState
    data object LocationOff : LogState
    data class Searching(val accuracyM: Float?) : LogState
    data class Saved(val name: String, val accuracyM: Float) : LogState
    data object Failed : LogState
    data object SaveFailed : LogState
}

private fun isLocationOn(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/** With permission, a search starts only if location is on; otherwise the screen says so. */
private fun startState(context: Context): LogState =
    if (isLocationOn(context)) LogState.Searching(null) else LogState.LocationOff

@Composable
fun LogScreen(vm: CompassViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf<LogState>(if (vm.locationSource.hasPermission()) startState(context) else LogState.NeedsPermission)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.permissionChanged()
        state = if (granted) startState(context) else LogState.Denied
    }
    LaunchedEffect(state is LogState.NeedsPermission) {
        if (state is LogState.NeedsPermission) ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val searching = state is LogState.Searching
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        val fix = acquireFix(vm.locationSource.updates(1_000)) { state = LogState.Searching(it.accuracyM) }
        if (fix == null) {
            state = LogState.Failed
            return@LaunchedEffect
        }
        val saved = try {
            vm.logWaypoint(fix)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not save the waypoint", e)
            null
        }
        state = if (saved == null) LogState.SaveFailed else LogState.Saved(saved.name, fix.accuracyM)
    }
    // A separate effect: leaving Searching cancels the search effect above, which would cancel this wait too.
    val saved = state is LogState.Saved
    LaunchedEffect(saved) {
        if (!saved) return@LaunchedEffect
        delay(1_500)
        onDone()
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text = when (val s = state) {
            LogState.NeedsPermission -> "Waiting for location permission"
            LogState.Denied -> "Waypoints need location access. Allow it in Settings, then try again."
            LogState.LocationOff -> "Location is off. Turn it on in Settings, then try again."
            is LogState.Searching -> "Finding position…" + (s.accuracyM?.let { "\n±${it.roundToInt()} m" } ?: "")
            is LogState.Saved -> "Saved ${s.name}\n±${s.accuracyM.roundToInt()} m"
            LogState.Failed -> "Couldn't get a position. Try again outdoors."
            LogState.SaveFailed -> "Couldn't save the waypoint. Try again."
        }
        Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (state is LogState.Denied || state is LogState.LocationOff || state is LogState.Failed || state is LogState.SaveFailed) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone) { Text("OK") }
        }
    }
}
