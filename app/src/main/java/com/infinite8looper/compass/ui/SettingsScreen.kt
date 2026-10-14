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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.BuildConfig
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.SwitchesUi
import com.infinite8looper.compass.nav.BackupTap
import com.infinite8looper.compass.nav.backupSwitchTap
import com.infinite8looper.compass.nav.NEEDS_PRECISE_LOCATION
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.Units
import com.infinite8looper.compass.nav.accuracyLabel
import com.infinite8looper.compass.nav.trailSettingDetail
import com.infinite8looper.compass.sensors.WatchButtons

/**
 * The settings, one of the three screens that sit side by side: a row for each switch, in the spec's order,
 * then `Calibrate` and the version, inside the frame, which shows how far the list is scrolled.
 * A switch is its row: one tap anywhere on the row turns it, and the tick at the right shows which way it stands.
 *
 * [paneView] says how much of the screen the wearer has in front of them. The screen is built when the three
 * screens are put up and stays while the ring of screens turns, hidden or not, so what has a consequence asks it first: a tap acts
 * only while the screen is in view. Its list is where it was left when the ring comes round to it again.
 * `Calibrate` shows the accuracy the magnetometer last reported while the compass or calibration had it running;
 * this screen does not run the sensor.
 *
 * A side button does nothing here. [onCalibrate] opens the calibration screen, and [onBackupOff] the question asked
 * before `Back up waypoints` is turned off.
 */
@Composable
fun SettingsScreen(vm: CompassViewModel, paneView: () -> PaneView, onCalibrate: () -> Unit, onBackupOff: () -> Unit) {
    remember { ScreenCompositions.countBuilt(Pane.SETTINGS) }
    SideEffect { ScreenCompositions.countComposed(Pane.SETTINGS) }
    // The switches come from the settings file alone. The screen state the other screens share can stay unloaded
    // for as long as the crumbs cannot be read with the trail setting on, and a switch does not wait for that: the
    // trail's own switch least of all, since turning it off is what ends that wait.
    val stored by vm.switches.collectAsStateWithLifecycle()
    val switches = stored ?: UnreadSwitches
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
    // The settings do not run the heading sensor: this is what it last reported on the compass or on calibration.
    val accuracy by vm.lastAccuracy.collectAsStateWithLifecycle()
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
    // The trail's row speaks of a press only on a watch with a button the app can act on.
    val context = LocalContext.current
    val trailDetail = remember(context) { trailSettingDetail(WatchButtons.assignable(context)) }
    // Until the settings file has been read the rows show defaults, which a tap would then store.
    val ready = stored != null

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
                    SwitchRow("True north", "Off: magnetic north", switches.north == NorthRef.TRUE, ready, styles) {
                        inView { vm.setNorth(if (it) NorthRef.TRUE else NorthRef.MAGNETIC) }
                    }
                }
                item(key = "units") {
                    SwitchRow("Feet and miles", "Off: meters and km", switches.units == Units.IMPERIAL, ready, styles) {
                        inView { vm.setUnits(if (it) Units.IMPERIAL else Units.METRIC) }
                    }
                }
                item(key = "ticks") {
                    SwitchRow("Tick feedback", "Faint tap at each tick mark", switches.tickFeedback, ready, styles) {
                        inView { vm.setTickFeedback(it) }
                    }
                }
                item(key = "blind") {
                    SwitchRow("Blind navigation", "Pulses when facing the target", switches.blindNavigation, ready, styles) {
                        inView { vm.setBlindNavigation(it) }
                    }
                }
                item(key = "trail") {
                    SwitchRow("Breadcrumb trail", trailDetail, switches.breadcrumbTrail, ready, styles) {
                        inView { vm.setBreadcrumbTrail(it) }
                    }
                }
                item(key = "coordinates") {
                    // After a denial the row says so, in the color of a failure, where it otherwise says what it shows:
                    // the same two lines as the logging status, since the sentence does not fit one line of a row
                    // that is above or below the middle of the screen.
                    SwitchRow(
                        "Show coordinates",
                        if (needsAccess) NEEDS_PRECISE_LOCATION.let { (first, second) -> "$first\n$second" } else "GPS position on the compass",
                        switches.showCoordinates, ready, styles, detailColor = if (needsAccess) ErrorColor else Dim,
                        detailLines = if (needsAccess) 2 else 1,
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
                    // Turning it on is one tap. Turning it off also replaces the stored backup, so that is asked first,
                    // on a screen of its own; the switch stays on until the answer.
                    SwitchRow("Back up waypoints", "With this watch's backup", switches.backupEnabled, ready, styles) {
                        inView {
                            when (backupSwitchTap(switches.backupEnabled)) {
                                BackupTap.TURN_ON -> vm.setBackupEnabled(true)
                                BackupTap.ASK_FIRST -> onBackupOff()
                            }
                        }
                    }
                }
                item(key = "calibrate") {
                    InstrumentRow(
                        Modifier.clickable(role = Role.Button, onClickLabel = "Open calibration") { inView(onCalibrate) },
                        fitContent = true,
                    ) {
                        Column {
                            Text("Calibrate", color = AccentColor, style = styles.labelOn, maxLines = 1)
                            // Empty until the sensor has said how accurate it is, on the compass or on calibration.
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

/** How many lines a switch's label may take, and how many more its smaller line may take than it has at the default font size. */
private const val LABEL_LINES = 2
private const val DETAIL_SPARE_LINES = 1

/** What the rows show before the settings file has been read; no row takes a tap then. */
private val UnreadSwitches = SwitchesUi()

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
    detailColor: Color = Dim, detailLines: Int = 1, onChange: (Boolean) -> Unit,
) {
    InstrumentRow(
        Modifier.toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        fitContent = true, trailing = { TickToggle(on) },
    ) {
        Column {
            // At the default font size each of these is one line in the width a row has in the middle of the
            // screen. At a larger one a line that no longer fits goes on to the next, and the row is taller for it.
            Text(
                label, color = if (on) Ink else Dim, style = if (on) styles.labelOn else styles.label,
                maxLines = LABEL_LINES, overflow = TextOverflow.Ellipsis,
            )
            Text(detail, color = detailColor, style = styles.detail, maxLines = detailLines + DETAIL_SPARE_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}
