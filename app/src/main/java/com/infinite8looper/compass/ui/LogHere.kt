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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.infinite8looper.compass.nav.PressEvent
import com.infinite8looper.compass.nav.PressKind
import com.infinite8looper.compass.nav.WaitingPress
import com.infinite8looper.compass.nav.pressKind
import com.infinite8looper.compass.nav.singleDueAtMs
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
internal fun rememberLogHere(vm: CompassViewModel): LogActions {
    // Which of the three asked, kept across the dialog, which can outlive this activity.
    var askedBy by rememberSaveable { mutableStateOf(LOG_HERE) }
    fun save(by: Int) { when (by) { SIDE_PRESS -> vm.sidePress(); DROP_CRUMB_ROW -> vm.dropCrumb(); else -> vm.logHere() } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.permissionChanged()
        save(askedBy)
    }
    return remember(vm, ask) {
        fun start(by: Int) {
            if (vm.locationSource.hasPermission()) save(by)
            else { askedBy = by; ask.launch(LOCATION_PERMISSIONS) }
        }
        LogActions(logHere = { start(LOG_HERE) }, sidePress = { start(SIDE_PRESS) }, dropCrumb = { start(DROP_CRUMB_ROW) })
    }
}

private const val LOG_HERE = 0
private const val SIDE_PRESS = 1
private const val DROP_CRUMB_ROW = 2

/**
 * The ways a position is saved, each asking for the permission first where it is not held: [logHere] is the
 * `Log here` row, which always saves a waypoint; [sidePress] is a single press of the side button, which drops
 * a crumb instead while a breadcrumb trail is being laid; and [dropCrumb] is the `Drop crumb` row, which drops a
 * crumb while a trail is being laid and does nothing otherwise.
 */
internal class LogActions(val logHere: () -> Unit, val sidePress: () -> Unit, val dropCrumb: () -> Unit)

/** Precise and approximate location, which are asked for together wherever the app asks. */
internal val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** The color of the logging status's words wherever they are shown. */
internal fun logStatusColor(status: LogStatus): Color = when (status) {
    is LogStatus.Saved, LogStatus.TrailComplete -> ConfirmColor
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
 * A side button, on a screen that acts on it: the compass and the waypoints screen. It follows a button from down
 * to up and makes a press of a short one, when the button is released. The repeats of a held button and a canceled
 * press make none. The crown's own press (KEYCODE_STEM_PRIMARY) belongs to the system and is not touched.
 *
 * Key presses go to the screen that has the focus, which among the three side-by-side screens is one at a time,
 * so a press reaches one screen. Each screen takes every side-button event that reaches it and acts on a press
 * only while it is in view: a press that arrives while the screens are moving is taken and dropped.
 *
 * What a press is, is decided by [pressKind]. With the breadcrumb trail setting off, each press is a single press
 * and is acted on at once, inside the call that brought its release. With the setting on, a press waits
 * [com.infinite8looper.compass.nav.DOUBLE_PRESS_WINDOW_MS] for a second one: the times compared are the key
 * events' own, and the single press that no second one follows is acted on by a timer in [scope], which ends with
 * the screen. Whichever way it comes, a press is acted on only if the screen is still in view at that moment.
 */
internal class SideButton(private val scope: CoroutineScope) {
    private var armed = KeyEvent.KEYCODE_UNKNOWN
    private var waiting: WaitingPress? = null
    private var timer: Job? = null
    private var act: (PressKind) -> Unit = {}

    /**
     * Takes a key event and says whether it was a side button's, which no one else should then handle.
     * [inView] says whether the screen is the one in view, [doubleEnabled] whether the breadcrumb trail setting is
     * on, and [onPress] is what a press does; all three are asked when they are needed.
     */
    fun onKey(key: KeyEvent, inView: () -> Boolean, doubleEnabled: () -> Boolean, onPress: (PressKind) -> Unit): Boolean {
        if (key.keyCode !in SIDE_BUTTONS) return false
        act = { kind -> if (inView()) onPress(kind) }
        when (key.action) {
            KeyEvent.ACTION_DOWN -> {
                val first = key.repeatCount == 0
                armed = if (first) key.keyCode else KeyEvent.KEYCODE_UNKNOWN
                step(if (first) PressEvent.DOWN else PressEvent.DROPPED, key.eventTime, doubleEnabled)
            }
            KeyEvent.ACTION_UP -> {
                // A release while the screen is not in view is no press, as before.
                val pressed = armed == key.keyCode && !key.isCanceled && inView()
                armed = KeyEvent.KEYCODE_UNKNOWN
                step(if (pressed) PressEvent.PRESSED else PressEvent.DROPPED, key.eventTime, doubleEnabled)
            }
        }
        return true
    }

    private fun step(event: PressEvent, atMs: Long, doubleEnabled: () -> Boolean) {
        val step = pressKind(waiting, event, atMs, doubleEnabled())
        waiting = step.waiting
        timer?.cancel()
        // Key event times are on the clock that counts while the watch is awake, and so is the timer.
        timer = singleDueAtMs(step.waiting)?.let { due ->
            scope.launch {
                kotlinx.coroutines.delay((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
                step(PressEvent.WINDOW_OVER, SystemClock.uptimeMillis(), doubleEnabled)
            }
        }
        step.kind?.let(act)
    }
}

private val SIDE_BUTTONS = setOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)
