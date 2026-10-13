package com.infinite8looper.compass.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.GestureInclusion
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.CALIBRATE_ROUTE
import com.infinite8looper.compass.nav.EDIT_ROUTE
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.PANE_PAGES
import com.infinite8looper.compass.nav.PANES_ROUTE
import com.infinite8looper.compass.nav.PANE_START_PAGE
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.TopScreen
import com.infinite8looper.compass.nav.backPage
import com.infinite8looper.compass.nav.headingWanted
import com.infinite8looper.compass.nav.pageToward
import com.infinite8looper.compass.nav.pageView
import com.infinite8looper.compass.nav.pageViewUnder
import com.infinite8looper.compass.nav.paneOnScreen
import com.infinite8looper.compass.nav.paneOf
import com.infinite8looper.compass.nav.topScreen
import com.infinite8looper.compass.nav.compassInViewForTrail
import com.infinite8looper.compass.nav.trailCompleteKept
import com.infinite8looper.compass.nav.trailKeepsAwake
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
            paneOf(page) == Pane.COMPASS && pageViewUnder(
                topScreen(route?.destination?.route),
                pageView(page, page, panes.currentPageOffsetFraction, panes.targetPage, panes.isScrollInProgress),
            ) == PaneView.IN_VIEW
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Whether the app is on screen, and in front, for the decisions below.
    val appState by lifecycle.currentStateFlow.collectAsState()
    KeepAwake(vm, compassInView = { compassInView.value })
    // A crumb of a trail is consumed only while the compass is the screen in view with the app in front; the view
    // model is told when. Whether the app is in front is taken from the activity's lifecycle as each change of it
    // is announced, not from a recomposition: leaving for Home pauses and stops the activity one right after the
    // other, and no frame, and so no recomposition, has to come in between.
    val appInFront = remember(lifecycle) { object { var now = false } }
    DisposableEffect(vm, lifecycle) {
        // An observer that is added is first brought up to the state the lifecycle is in, so it starts out right.
        val observer = LifecycleEventObserver { _, event ->
            appInFront.now = event.targetState == Lifecycle.State.RESUMED
            vm.setCompassInView(compassInViewForTrail(compassInView.value, appInFront.now))
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setCompassInView(false)
        }
    }
    // The row of screens coming to rest on the compass, or leaving it, happens with the app in front and is followed here.
    val compassAtRest = compassInView.value
    DisposableEffect(vm, compassAtRest) {
        vm.setCompassInView(compassInViewForTrail(compassAtRest, appInFront.now))
        onDispose { }
    }
    // Whether a position can come at all is looked at again each time the app is back on screen: the permission and
    // the location switch are changed in the system's settings, away from the app.
    LifecycleResumeEffect(vm) {
        vm.refreshLocationState()
        onPauseOrDispose { }
    }
    // The heading sensor runs for the compass and for calibration and for nothing else. What is on screen is
    // gathered here, above every screen, and the decision is [headingWanted].
    val wantHeading by remember(panes) {
        derivedStateOf {
            headingWanted(
                topScreen(route?.destination?.route),
                paneOnScreen(Pane.COMPASS, panes.currentPage, panes.currentPageOffsetFraction, panes.targetPage, panes.isScrollInProgress),
                appOnScreen = appState.isAtLeast(Lifecycle.State.STARTED),
            )
        }
    }
    DisposableEffect(vm, wantHeading) {
        vm.setHeadingWanted(wantHeading)
        onDispose { vm.setHeadingWanted(false) }
    }
    // A breadcrumb trail asks for location only while the compass is on screen: one of the three screens is on top
    // and the compass is at rest in view or passing in a swipe, so a swipe that is begun and let go does not stop
    // and start the fixes. The view model decides what that means for location.
    val compassOnScreen by remember(panes) {
        derivedStateOf {
            topScreen(route?.destination?.route) == TopScreen.PANES &&
                paneOnScreen(Pane.COMPASS, panes.currentPage, panes.currentPageOffsetFraction, panes.targetPage, panes.isScrollInProgress)
        }
    }
    DisposableEffect(vm, compassOnScreen) {
        vm.setCompassOnScreen(compassOnScreen)
        onDispose { vm.setCompassOnScreen(false) }
    }
    // `Trail complete` is for the wearer who has just reached the last crumb. Once the compass is off the screen,
    // or the app is no longer in front, it is given up, so it cannot come up on a later visit.
    val completeKept = trailCompleteKept(compassOnScreen, appState == Lifecycle.State.RESUMED)
    LaunchedEffect(vm, completeKept) { if (!completeKept) vm.dropTrailComplete() }
    AppScaffold(timeText = {}) {
        // The three screens are the host's first destination, which has nothing behind it to swipe back to, so a
        // right swipe there is only ever the pager's. The screens opened from a row keep the usual swipe back.
        SwipeDismissableNavHost(navController = nav, startDestination = PANES_ROUTE) {
            composable(PANES_ROUTE) {
                Panes(
                    vm, panes, top = { topScreen(route?.destination?.route) }, log = logHere, onEdit = { nav.navigate("edit/$it") { launchSingleTop = true } },
                    onCalibrate = { nav.navigate(CALIBRATE_ROUTE) { launchSingleTop = true } },
                )
            }
            composable(EDIT_ROUTE) { entry ->
                // Leaving takes the edit screen off the stack and nothing else: asked a second time, when the
                // screen is already gone, it finds no edit screen and does nothing.
                val leave = { nav.popBackStack(EDIT_ROUTE, inclusive = true); Unit }
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                if (id == null) LeaveOnce(leave) else SwipeBack(leave) { EditScreen(vm, id, onDone = leave) }
            }
            composable(CALIBRATE_ROUTE) {
                SwipeBack(onLeave = { nav.popBackStack(CALIBRATE_ROUTE, inclusive = true) }) { CalibrationScreen(vm) }
            }
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
private fun Panes(
    vm: CompassViewModel, panes: PagerState, top: () -> TopScreen, log: LogActions, onEdit: (Long) -> Unit, onCalibrate: () -> Unit,
) {
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

    HorizontalPager(state = panes, modifier = Modifier.semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = Pane.entries.size) }, beyondViewportPageCount = 1, gestureInclusion = EveryTouchPages) { page ->
        // How much of this page the wearer has. The pager can have two pages of one pane built at a time (the one
        // on screen and one it has prepared further along the row), so each page is told about itself. The
        // navigation host can also draw the three screens behind a screen opened from a row, while that screen is
        // swiped aside: there every page is hidden, a picture that does nothing ([pageViewUnder]).
        val view: () -> PaneView = remember(panes, page, top) {
            val state = derivedStateOf {
                pageViewUnder(top(), pageView(page, panes.currentPage, panes.currentPageOffsetFraction, panes.targetPage, panes.isScrollInProgress))
            }
            return@remember { state.value }
        }
        when (paneOf(page)) {
            // The frames of the settings and waypoints screens hold their pane ticks.
            Pane.SETTINGS -> SettingsScreen(vm, paneView = view, onCalibrate = onCalibrate)
            Pane.COMPASS -> CompassScreen(
                vm, paneView = view, onOpenList = { show(Pane.WAYPOINTS) }, onSidePress = log.sidePress, onCalibrate = onCalibrate,
            )
            Pane.WAYPOINTS -> WaypointsScreen(vm, paneView = view, onEdit = onEdit, onLogHere = log.logHere, onSidePress = log.sidePress)
        }
    }
}

/**
 * Keeps the screen on while the compass is in view with targets to walk to, or with a breadcrumb trail being
 * retraced while a position can come ([trailKeepsAwake]). It also keeps it on while a save is in
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
        derivedStateOf {
            (nav.value.targets.isNotEmpty() && compassInView()) || trailKeepsAwake(nav.value.trail.mode, compassInView(), locationCanRun = nav.value.noPosition == null) ||
                status.value is LogStatus.Saving
        }
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

/**
 * Whether the navigation host leaves the right swipe that goes back to the system. Above API level 35 it does: it
 * only listens for the system's back gesture, and this app's window has the system's swipe switched off (see
 * res/values/themes.xml), so there no swipe would go back. This is the one place that knows the level.
 */
private val hostLeavesSwipeBackToSystem = Build.VERSION.SDK_INT > 35

/**
 * A screen opened from a row, with its swipe back. Up to API level 35 the navigation host swipes its screens back
 * itself and [content] is shown as it is. Above that, [content] is put in the same swipe box the host uses on the
 * older levels: a right swipe slides the screen away over black and then calls [onLeave], once. Back is the
 * host's on every level.
 */
@Composable
private fun SwipeBack(onLeave: () -> Unit, content: @Composable () -> Unit) {
    if (!hostLeavesSwipeBackToSystem) content()
    else BasicSwipeToDismissBox(onDismissed = onLeave) { isBackground ->
        if (isBackground) Box(Modifier.fillMaxSize().background(Color.Black)) else content()
    }
}

/** A route reached with an unusable id has nothing to show, so it goes back, once. */
@Composable
private fun LeaveOnce(leave: () -> Unit) {
    LaunchedEffect(Unit) { leave() }
}
