package com.infinite8looper.compass.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.infinite8looper.compass.CompassViewModel

/**
 * What "log here" does wherever the app offers it: with the location permission it starts the save. Without it,
 * it shows the system's permission dialog first and starts the save when the dialog closes, so a grant needs no
 * second press and a denial is reported by the save itself as "Needs location access".
 *
 * Call this above the navigation host: the dialog's answer comes back to the place that asked, and that place
 * must still exist when the wearer has moved to another screen in the meantime.
 */
@Composable
internal fun rememberLogHere(vm: CompassViewModel): () -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.permissionChanged()
        vm.logHere()
    }
    return remember(vm, ask) {
        { if (vm.locationSource.hasPermission()) vm.logHere() else ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
}
