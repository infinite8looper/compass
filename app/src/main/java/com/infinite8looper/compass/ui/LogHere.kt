package com.infinite8looper.compass.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.logStatusShowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * What "log here" does wherever the app offers it: with the precise location permission it starts the save.
 * Without it, it shows the system's permission dialog first and starts the save when the dialog closes, so a grant
 * needs no second press and a denial is reported by the save itself as "Needs access to precise location". The same
 * goes for a grant of approximate location only: a waypoint needs the precise one, and the next press asks again.
 *
 * Precise and approximate location are requested together, as the system expects of an app that wants the
 * precise one.
 *
 * Call this above the navigation host: the dialog's answer comes back to the place that asked, and that place
 * must still exist when the wearer has moved to another screen in the meantime.
 */
@Composable
internal fun rememberLogHere(vm: CompassViewModel): () -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.permissionChanged()
        vm.logHere()
    }
    return remember(vm, ask) {
        { if (vm.locationSource.hasPermission()) vm.logHere() else ask.launch(LOCATION_PERMISSIONS) }
    }
}

/** Precise and approximate location, which are asked for together wherever the app asks. */
internal val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** The color of the logging status's words wherever they are shown. */
internal fun logStatusColor(status: LogStatus): Color = when (status) {
    is LogStatus.Saved -> ConfirmColor
    is LogStatus.Failed -> ErrorColor
    LogStatus.Idle, is LogStatus.Saving -> Ink
}

/**
 * The part of showing a logging result that a screen owes the view model, for a screen that is one of the three
 * side by side and shows the status: the compass and the waypoints list. Each calls this with its own [paneView].
 *
 * A result is shown for its time, counted only while the screen is in view and resumed. A result that arrives
 * while neither is (settings is in view, a screen opened from a row is on top, the watch is asleep) waits in the
 * view model, and a showing that is cut short, by a swipe as much as by sleep, starts again from the beginning on
 * whichever of the two is in view next. The confirming tap comes once, when a saved result is first shown. The
 * press number is part of the key, so a new result that reads the same as the last gets its own time.
 */
@Composable
internal fun LogResultTimer(vm: CompassViewModel, paneView: () -> PaneView) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val status = vm.logStatus.collectAsStateWithLifecycle()
    val logPress by vm.logPress.collectAsStateWithLifecycle()
    val resultShowMs by remember { derivedStateOf { logStatusShowMs(status.value) } }
    LaunchedEffect(vm, view, lifecycle, paneView, logPress, resultShowMs) {
        val showMs = resultShowMs ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { paneView() == PaneView.IN_VIEW }.collectLatest { showing ->
                if (!showing) return@collectLatest
                if (vm.logResultFirstSeen(logPress) && vm.logStatus.value is LogStatus.Saved) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                delay(showMs)
                vm.logResultShown(logPress)
            }
        }
    }
}

/**
 * A side button, on a screen that logs a waypoint with it: the compass and the waypoints screen. It follows a
 * button from down to up and calls `onPress` once for a short press, when the button is released. The repeats of a
 * held button and a canceled press call nothing. The crown's own press (KEYCODE_STEM_PRIMARY) belongs to the
 * system and is not touched.
 *
 * Key presses go to the screen that has the focus, which among the three side-by-side screens is one at a time,
 * so a press reaches one screen. Each screen takes every side-button event that reaches it and acts on a press
 * only while it is in view: a press that arrives while the screens are moving is taken and dropped.
 */
internal class SideButton {
    private var armed = KeyEvent.KEYCODE_UNKNOWN

    /** Takes a key event and says whether it was a side button's, which no one else should then handle. */
    fun onKey(key: KeyEvent, onPress: () -> Unit): Boolean {
        if (key.keyCode !in SIDE_BUTTONS) return false
        when (key.action) {
            KeyEvent.ACTION_DOWN -> armed = if (key.repeatCount == 0) key.keyCode else KeyEvent.KEYCODE_UNKNOWN
            KeyEvent.ACTION_UP -> {
                val pressed = armed == key.keyCode && !key.isCanceled
                armed = KeyEvent.KEYCODE_UNKNOWN
                if (pressed) onPress()
            }
        }
        return true
    }
}

private val SIDE_BUTTONS = setOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)
