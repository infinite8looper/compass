package com.infinite8looper.compass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.GestureInclusion
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.PANE_PAGES
import com.infinite8looper.compass.nav.PANE_START_PAGE
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.pageToward
import com.infinite8looper.compass.nav.pageView
import com.infinite8looper.compass.nav.paneOf
import kotlinx.coroutines.launch

@Composable
fun CompassApp() {
    val vm: CompassViewModel = viewModel()
    val nav = rememberSwipeDismissableNavController()
    val logHere = rememberLogHere(vm)
    // Where the row of three screens stands. It is held here, above the navigation host, so a screen opened from
    // a row comes back to the screen it was opened from. It is not saved with the activity: an app that is
    // started again opens on the compass.
    val panes = remember { PagerState(currentPage = PANE_START_PAGE, pageCount = { PANE_PAGES }) }
    AppScaffold(timeText = {}) {
        // The three screens are the host's first destination, which has nothing behind it to swipe back to, so a
        // right swipe there is only ever the pager's. The screens opened from a row keep the usual swipe back.
        SwipeDismissableNavHost(navController = nav, startDestination = "panes") {
            composable("panes") {
                Panes(
                    vm, panes, onLogHere = logHere, onEdit = { nav.navigate("edit/$it") { launchSingleTop = true } },
                    onCalibrate = { nav.navigate("calibrate") { launchSingleTop = true } },
                )
            }
            composable("edit/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                if (id == null) LeaveOnce { nav.popBackStack() }
                else EditScreen(vm, id, onPickHue = { nav.navigate("hue/$id") { launchSingleTop = true } }, onDone = { nav.popBackStack() })
            }
            composable("hue/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                if (id == null) LeaveOnce { nav.popBackStack() }
                else HueScreen(vm, id, onDone = { nav.popBackStack() })
            }
            composable("calibrate") { CalibrationScreen(vm) }
        }
    }
}

/**
 * Settings, the compass and the waypoints side by side, in a ring that a sideways swipe turns either way.
 *
 * The pager is a long row of pages in which the three repeat ([paneOf] says which a page shows), and it keeps the
 * page on either side of the one in view composed, so all three screens exist at all times and a swipe never
 * waits for one to be built. The pager marks the page nearest its resting place as the active one, which is how
 * that screen and no other gets the focus, and with it the crown and the side buttons.
 */
@Composable
private fun Panes(vm: CompassViewModel, panes: PagerState, onLogHere: () -> Unit, onEdit: (Long) -> Unit, onCalibrate: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A move the app makes itself goes to the nearest page that shows the screen, and slides there.
    val show: (Pane) -> Unit = remember(panes, scope) {
        { pane -> scope.launch { panes.animateScrollToPage(pageToward(panes.currentPage, pane)) } }
    }
    // The pane the wearer has in view, at rest, or none while the row moves.
    val inView = remember(panes) {
        derivedStateOf {
            val page = panes.currentPage
            if (pageView(page, page, panes.currentPageOffsetFraction) == PaneView.IN_VIEW) paneOf(page) else null
        }
    }
    KeepAwake(vm, compassInView = { inView.value == Pane.COMPASS })

    // Back, where a watch has it, returns to the compass from the other two screens. On the compass it is left to
    // the system, which closes the app.
    val awayFromCompass by remember(panes) { derivedStateOf { paneOf(panes.currentPage) != Pane.COMPASS } }
    BackHandler(enabled = awayFromCompass) { show(Pane.COMPASS) }

    HorizontalPager(state = panes, beyondViewportPageCount = 1, gestureInclusion = EveryTouchPages) { page ->
        // How much of this page the wearer has. The pager can have two pages of one pane built at a time (the one
        // on screen and one it has prepared further along the row), so each page is told about itself.
        val view: () -> PaneView = remember(panes, page) {
            val state = derivedStateOf { pageView(page, panes.currentPage, panes.currentPageOffsetFraction) }
            return@remember { state.value }
        }
        when (val pane = paneOf(page)) {
            Pane.SETTINGS -> Box {
                SettingsScreen(vm, onCalibrate = onCalibrate)
                PanePosition(pane.ordinal)
            }
            Pane.COMPASS -> CompassScreen(
                vm, paneView = view, onOpenList = { show(Pane.WAYPOINTS) }, onLogHere = onLogHere, onCalibrate = onCalibrate,
            )
            Pane.WAYPOINTS -> Box {
                // The save runs in the view model and its status shows on the compass, so the list turns to it.
                WaypointsScreen(vm, onEdit = onEdit, onLog = { onLogHere(); show(Pane.COMPASS) })
                PanePosition(pane.ordinal)
            }
        }
    }
}

/**
 * Keeps the screen on while the compass is in view with targets to walk to. It also keeps it on while a save is in
 * progress, with or without targets and on whichever of the three screens the wearer is: the system gives an app
 * that is not on screen no location, so a watch that went to sleep during a save would end it in "No GPS fix".
 *
 * This is here, once for the three screens, and not on the compass, because the screen has one such switch.
 */
@Composable
private fun KeepAwake(vm: CompassViewModel, compassInView: () -> Boolean) {
    val view = LocalView.current
    val nav = vm.nav.collectAsStateWithLifecycle()
    val status = vm.logStatus.collectAsStateWithLifecycle()
    val awake by remember(compassInView) {
        derivedStateOf { (nav.value.targets.isNotEmpty() && compassInView()) || status.value is LogStatus.Saving }
    }
    DisposableEffect(view, awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The pager takes a swipe wherever it starts. Its own default leaves the left edge of its first page to a
 * swipe-to-dismiss handler, and here no swipe leaves the three screens.
 */
private val EveryTouchPages = object : GestureInclusion {
    override fun ignoreGestureStart(offset: Offset, layoutCoordinates: LayoutCoordinates): Boolean = false
}

/** A route reached with an unusable id has nothing to show, so it goes back, once. */
@Composable
private fun LeaveOnce(leave: () -> Unit) {
    LaunchedEffect(Unit) { leave() }
}
