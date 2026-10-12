package com.infinite8looper.compass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.BuildConfig
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.Units
import com.infinite8looper.compass.nav.accuracyLabel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull

/**
 * The settings, one of the three screens that sit side by side: a row for each switch, in the spec's order,
 * then `Calibrate` and the version, inside the frame, which shows how far the list is scrolled.
 * A switch is its row: one tap anywhere on the row turns it, and the tick at the right shows which way it stands.
 *
 * [paneView] says how much of the screen the wearer has in front of them. The pager may build this screen while it
 * is hidden, and twice, so what has a consequence asks it first: a tap acts only while the screen is in view, and
 * the sensor that gives `Calibrate` its accuracy is listened to only then.
 *
 * A side button does nothing here. [onCalibrate] opens the calibration screen.
 */
@Composable
fun SettingsScreen(vm: CompassViewModel, paneView: () -> PaneView, onCalibrate: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    var needsAccess by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.permissionChanged()
        // The precise permission is the one the coordinates need: approximate alone counts as a denial.
        val granted = vm.locationSource.hasPermission()
        needsAccess = !granted
        // The switch was never turned on while the permission was missing, so a denial leaves it off.
        if (granted) vm.setShowCoordinates(true)
    }
    val listState = rememberScalingLazyListState()
    val accuracy by accuracyWhileInView(vm, paneView)
    // A touch that lands while the screens are still sliding does nothing.
    fun inView(act: () -> Unit) { if (paneView() == PaneView.IN_VIEW) act() }

    val r = screenRadius()
    val density = LocalDensity.current
    val styles = remember(r, density) {
        SettingsStyles(
            header = density.instrumentText(r * 0.105f, align = TextAlign.Center),
            label = density.instrumentText(r * 0.12f),
            labelOn = density.instrumentText(r * 0.12f, FontWeight.Medium),
            detail = density.instrumentText(r * 0.085f),
            footer = density.instrumentText(r * 0.085f, align = TextAlign.Center),
        )
    }
    // Until the stores have answered the rows show defaults, which a tap would then store.
    val ready = nav.loaded

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The list is cut off at one circle inside the frame.
        Box(Modifier.fillMaxSize().padding(r * (1f - LIST_RADIUS)).clip(CircleShape)) {
            ScalingLazyColumn(
                state = listState,
                // As on the waypoints screen: the rows keep their size and spacing all the way and fade out toward
                // the top and bottom of the frame.
                scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
                verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
                // The rows reach the ring their ends follow: the labels are fixed words and need the width.
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxSize().fadeTopAndBottom(),
            ) {
                item(key = "header") {
                    InstrumentRow(centered = true) { Text("Settings", color = Dim, style = styles.header, maxLines = 1) }
                }
                item(key = "north") {
                    SwitchRow("True north", "Off: magnetic north", nav.north == NorthRef.TRUE, ready, styles) {
                        inView { vm.setNorth(if (it) NorthRef.TRUE else NorthRef.MAGNETIC) }
                    }
                }
                item(key = "units") {
                    SwitchRow("Feet and miles", "Off: meters and km", nav.units == Units.IMPERIAL, ready, styles) {
                        inView { vm.setUnits(if (it) Units.IMPERIAL else Units.METRIC) }
                    }
                }
                item(key = "ticks") {
                    SwitchRow("Tick feedback", "Faint tap at each tick mark", nav.tickFeedback, ready, styles) {
                        inView { vm.setTickFeedback(it) }
                    }
                }
                item(key = "blind") {
                    SwitchRow("Blind navigation", "Pulses when facing the target", nav.blindNavigation, ready, styles) {
                        inView { vm.setBlindNavigation(it) }
                    }
                }
                item(key = "trail") {
                    SwitchRow("Breadcrumb trail", "Double press starts a trail", nav.breadcrumbTrail, ready, styles) {
                        inView { vm.setBreadcrumbTrail(it) }
                    }
                }
                item(key = "coordinates") {
                    // After a denial the row says so, in the color of a failure, where it otherwise says what it shows.
                    SwitchRow(
                        "Show coordinates", if (needsAccess) "Needs location access" else "GPS position on the compass",
                        nav.showCoordinates, ready, styles, detailColor = if (needsAccess) ErrorColor else Dim,
                    ) { on ->
                        inView {
                            needsAccess = false
                            if (!on) vm.setShowCoordinates(false)
                            else if (vm.locationSource.hasPermission()) vm.setShowCoordinates(true)
                            else ask.launch(LOCATION_PERMISSIONS)
                        }
                    }
                }
                item(key = "backup") {
                    SwitchRow("Back up waypoints", "With this watch's backup", nav.backupEnabled, ready, styles) {
                        inView { vm.setBackupEnabled(it) }
                    }
                }
                item(key = "calibrate") {
                    InstrumentRow(
                        Modifier.clickable(role = Role.Button, onClickLabel = "Open calibration") { inView(onCalibrate) },
                        fitContent = true,
                    ) {
                        Column {
                            Text("Calibrate", color = AccentColor, style = styles.labelOn, maxLines = 1)
                            // Empty until the sensor has said how accurate it is.
                            Text(
                                accuracy?.let { "Accuracy: ${accuracyLabel(it)}" } ?: "", color = Dim, style = styles.detail,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item(key = "version") {
                    InstrumentRow(centered = true) {
                        Text("Compass ${BuildConfig.VERSION_NAME}\nMIT License", color = Faint, style = styles.footer)
                    }
                }
            }
        }
        FrameRing(paneIndex = Pane.SETTINGS.ordinal, scroll = { listScrollArc(listState) })
    }
}

private class SettingsStyles(
    val header: TextStyle, val label: TextStyle, val labelOn: TextStyle, val detail: TextStyle, val footer: TextStyle,
)

/**
 * A switch as a row: what it switches, a smaller line saying what that does, and the tick. The whole row is the
 * switch, for a finger and for an accessibility service, which is told its state and reads the two lines as its
 * description.
 */
@Composable
private fun SwitchRow(
    label: String, detail: String, on: Boolean, enabled: Boolean, styles: SettingsStyles,
    detailColor: Color = Dim, onChange: (Boolean) -> Unit,
) {
    InstrumentRow(
        Modifier.toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        fitContent = true, trailing = { TickToggle(on) },
    ) {
        Column {
            Text(
                label, color = if (on) Ink else Dim, style = if (on) styles.labelOn else styles.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(detail, color = detailColor, style = styles.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The accuracy the magnetometer reports, for the `Calibrate` row: listened for only while this screen is the one
 * in view and the app is on screen, and kept as it last was otherwise. Null until the sensor has reported one.
 * It changes when the level changes, not with every reading.
 */
@Composable
private fun accuracyWhileInView(vm: CompassViewModel, paneView: () -> PaneView): State<Int?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<Int?>(null, vm, paneView, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { paneView() == PaneView.IN_VIEW }.collectLatest { inView ->
                if (inView) vm.heading.filterNotNull().mapNotNull { it.accuracy }.distinctUntilChanged().collect { value = it }
            }
        }
    }
}
