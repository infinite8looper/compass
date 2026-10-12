package com.infinite8looper.compass.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.BuildConfig
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units

@Composable
fun SettingsScreen(vm: CompassViewModel, onCalibrate: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    var needsAccess by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.permissionChanged()
        needsAccess = !granted
        // The switch was never turned on while the permission was missing, so a denial leaves it off.
        if (granted) vm.setShowCoordinates(true)
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Settings") } }
            item {
                SwitchButton(
                    checked = nav.north == NorthRef.TRUE,
                    onCheckedChange = { vm.setNorth(if (it) NorthRef.TRUE else NorthRef.MAGNETIC) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("True north") }, secondaryLabel = { Text("Off: magnetic north") },
                )
            }
            item {
                SwitchButton(
                    checked = nav.units == Units.IMPERIAL,
                    onCheckedChange = { vm.setUnits(if (it) Units.IMPERIAL else Units.METRIC) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feet and miles") }, secondaryLabel = { Text("Off: meters and km") },
                )
            }
            item {
                SwitchButton(
                    checked = nav.tickFeedback,
                    onCheckedChange = { vm.setTickFeedback(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tick feedback") }, secondaryLabel = { Text("Faint tap at each tick mark") },
                )
            }
            item {
                SwitchButton(
                    checked = nav.showCoordinates,
                    onCheckedChange = { on ->
                        needsAccess = false
                        if (!on) vm.setShowCoordinates(false)
                        else if (vm.locationSource.hasPermission()) vm.setShowCoordinates(true)
                        else ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Show coordinates") }, secondaryLabel = { Text("GPS position on the compass") },
                )
            }
            if (needsAccess) item { Text("Needs location access", color = ErrorColor, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            item {
                SwitchButton(
                    checked = nav.backupEnabled,
                    onCheckedChange = { vm.setBackupEnabled(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Back up waypoints") }, secondaryLabel = { Text("With this watch's backup") },
                )
            }
            item { Button(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) { Text("Calibrate") } }
            item { Text("Compass ${BuildConfig.VERSION_NAME}\nMIT License", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        }
    }
}
