package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.infinite8looper.compass.CompassViewModel

@Composable
fun CompassApp() {
    val vm: CompassViewModel = viewModel()
    val nav = rememberSwipeDismissableNavController()
    AppScaffold(timeText = {}) {
        SwipeDismissableNavHost(navController = nav, startDestination = "compass") {
            composable("compass") {
                CompassScreen(vm, onOpenList = { nav.navigate("waypoints") }, onLog = { nav.navigate("log") }, onCalibrate = { nav.navigate("calibrate") })
            }
            composable("log") { Pending("log") }
            composable("waypoints") { Pending("waypoints") }
            composable("edit/{id}") { Pending("edit") }
            composable("hue/{id}") { Pending("hue") }
            composable("settings") { Pending("settings") }
            composable("calibrate") { Pending("calibrate") }
        }
    }
}

@Composable
private fun Pending(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) }
}
