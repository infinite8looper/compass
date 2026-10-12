package com.infinite8looper.compass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.formatDistance

@Composable
fun WaypointsScreen(vm: CompassViewModel, onEdit: (Long) -> Unit, onSettings: () -> Unit, onLog: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Button(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Log waypoint") } }
            if (nav.waypoints.isEmpty()) {
                item { Text("No waypoints yet.") }
            } else {
                item { ListHeader { Text("Waypoints") } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.setAllTargets(true) }) { Text("All") }
                        Button(onClick = { vm.setAllTargets(false) }) { Text("None") }
                    }
                }
                items(nav.waypoints, key = { it.id }) { w ->
                    SplitSwitchButton(
                        checked = w.isTarget,
                        onCheckedChange = { vm.setTarget(w.id, it) },
                        toggleContentDescription = "Set target",
                        onContainerClick = { onEdit(w.id) },
                        modifier = Modifier.fillMaxWidth(),
                        secondaryLabel = nav.distances[w.id]?.let { d -> { Text(formatDistance(d, nav.units)) } },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(waypointColor(w.hue), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") } }
        }
    }
}
