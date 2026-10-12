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
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.PANE_PAGES
import com.infinite8looper.compass.nav.PANE_START_PAGE
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.backPage
import com.infinite8looper.compass.nav.pageToward
import com.infinite8looper.compass.nav.pageView
import com.infinite8looper.compass.nav.paneOf
import kotlinx.coroutines.Job
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
    // Whether the compass is the screen in view: the three screens are the route on top, and the row rests on a
    // compass page.
    val route by nav.currentBackStackEntryAsState()
    val compassInView = remember(panes) {
        derivedStateOf {
            val page = panes.currentPage
            route?.destination?.route == PANES_ROUTE && paneOf(page) == Pane.COMPASS &&
                pageView(page, page, panes.currentPageOffsetFraction, panes.targetPage, panes.isScrollInProgress) == PaneView.IN_VIEW
        }
    }
    KeepAwake(vm, compassInView = { compassInView.value })
    AppScaffold(timeText = {}) {
        // The three screens are the host's first destination, which has nothing behind it to swipe back to, so a
        // right swipe there is only ever the pager's. The screens opened from a row keep the usual swipe back.
        SwipeDismissableNavHost(navController = nav, startDestination = PANES_ROUTE) {
            composable(PANES_ROUTE) {
                Panes(
                    vm, panes, onLogHere = logHere, onEdit = { nav.navigate("edit/$it") { launchSingleTop = true } },
                    onCalibrate = { nav.navigate("calibrate") { launchSingleTop = true } },
                )
            }
            composable(EDIT_ROUTE) { entry ->
                // Leaving takes the edit screen off the stack and nothing else: asked a second time, when the
                // screen is already gone, it finds no edit screen and does nothing.
                val leave = { nav.popBackStack(EDIT_ROUTE, inclusive = true); Unit }
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                if (id == null) LeaveOnce(leave) else EditScreen(vm, id, onDone = leave)
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
    // Back, where a watch has it, never leaves the app from the three screens: it turns one screen toward
    // settings, as a right swipe does, and slides there. Leaving the app is the crown's press, which is the system's.
    // Backs made close together each count. The page the Backs so far lead to is kept here while the row slides
    // there, and a Back that comes meanwhile moves it one page on. One slide runs at a time and goes on to the page
    // kept here when it ends: a second slide started over the first would stop it half way.
    val backSlide = remember(panes) { object { var to: Int? = null; var job: Job? = null } }
    BackHandler {
        backSlide.to = backPage(panes.currentPage, backSlide.to ?: panes.targetPage, backSlide.to != null || panes.isScrollInProgress)
        if (backSlide.job?.isActive != true) {
            backSlide.job = scope.launch {
                try {
                    while (true) {
                        val to = backSlide.to ?: break
                        panes.animateScrollToPage(to)
                        if (backSlide.to == to) backSlide.to = null
                    }
                } finally {
                    // Ended, or stopped by a finger on the screens: nothing is owed.
                    backSlide.to = null
                }
            }
        }
    }

    HorizontalPager(state = panes, beyondViewportPageCount = 1, gestureInclusion = EveryTouchPages) { page ->
        // How much of this page the wearer has. The pager can have two pages of one pane built at a time (the one
        // on screen and one it has prepared further along the row), so each page is told about itself.
        val view: () -> PaneView = remember(panes, page) {
            val state = derivedStateOf { pageView(page, panes.currentPage, panes.currentPageOffsetFraction, panes.targetPage, panes.isScrollInProgress) }
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
            // The frame of the waypoints screen holds its pane ticks.
            Pane.WAYPOINTS -> WaypointsScreen(vm, paneView = view, onEdit = onEdit, onLogHere = onLogHere)
        }
    }
}

/**
 * Keeps the screen on while the compass is in view with targets to walk to. It also keeps it on while a save is in
 * progress, with or without targets and on whichever screen the wearer is, the ones opened from a row included:
 * the system gives an app that is not on screen no location, so a watch that went to sleep during a save would end
 * it in "No GPS fix".
 *
 * This is called once, above the navigation host, and not on the compass: the screen has one such switch, and it
 * has to hold whatever route is on top.
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

/** The route of the three side-by-side screens, the navigation host's first destination. */
private const val PANES_ROUTE = "panes"

/** The route of the screen that edits the waypoint with the given id. */
private const val EDIT_ROUTE = "edit/{id}"

/** A route reached with an unusable id has nothing to show, so it goes back, once. */
@Composable
private fun LeaveOnce(leave: () -> Unit) {
    LaunchedEffect(Unit) { leave() }
}
