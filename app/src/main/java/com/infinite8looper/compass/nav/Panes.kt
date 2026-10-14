package com.infinite8looper.compass.nav

import kotlin.math.abs

/**
 * The three screens that sit side by side, from left to right. They form a ring: to the right of waypoints is
 * settings again, and to the left of settings is waypoints.
 */
enum class Pane { SETTINGS, COMPASS, WAYPOINTS }

/**
 * Where the ring stands is counted along a long row of pages in which the three panes repeat, so a swipe the same
 * way always finds a next page. The app opens in the middle of the row, on a compass page, a million and a half
 * swipes from either end.
 */
const val PANE_PAGES = 3_000_000
const val PANE_START_PAGE = PANE_PAGES / 2 + 1

/** The pane that the row's page number [page] shows. */
fun paneOf(page: Int): Pane = Pane.entries[page.mod(Pane.entries.size)]

/**
 * The page to move to from [page] to show [pane]: the same page if it shows it already, otherwise the page next
 * to it on whichever side has that pane, which in a ring of three is always one of the two. Only at the very ends
 * of the row, where that page does not exist, is it the page two away on the other side.
 */
fun pageToward(page: Int, pane: Pane): Int {
    val to = when ((pane.ordinal - page).mod(Pane.entries.size)) {
        0 -> page
        1 -> page + 1
        else -> page - 1
    }
    return when {
        to < 0 -> page + 2
        to >= PANE_PAGES -> page - 2
        else -> to
    }
}

/**
 * The page Back turns to: one page toward settings, as a right swipe does, which round the ring is settings from
 * the compass, waypoints from settings and the compass from waypoints. At rest that is the page before
 * [nearestPage]. While the row is being moved ([scrollInProgress]) it is the page before the one the row is headed
 * for ([targetPage]), so a second Back made before the first has come to rest goes one page further. Only at the
 * very start of the row, where that page does not exist, is it the page two on, which shows the same screen.
 */
fun backPage(nearestPage: Int, targetPage: Int, scrollInProgress: Boolean): Int {
    val to = (if (scrollInProgress) targetPage else nearestPage) - 1
    return if (to < 0) to + Pane.entries.size else to
}

/** How much of one of the row's pages the wearer has in front of them. */
enum class PaneView {
    /** No part of it is on the screen. */
    HIDDEN,
    /** Part of it is on the screen while the row moves: it is drawn, and it takes no input and gives no feedback. */
    PASSING,
    /** It is the screen in view, at rest. */
    IN_VIEW,
}

/** A page is in its place within this fraction of a page of it: a few pixels, less than a touch moves it. */
const val PANE_SETTLED_WITHIN = 0.01f
/** Less of a page than one pixel of any watch screen: an offset below it puts nothing of the next page on screen. */
private const val PANE_ON_SCREEN = 0.001f

/**
 * What the wearer has of the row's page number [page] when the row is at [nearestPage], the page nearest its
 * resting place, has moved [offsetFraction] of a page from there (-0.5 to 0.5, positive toward the next page), is
 * headed for [targetPage], which is the page it rests on when it is not moving, and is or is not being moved
 * ([scrollInProgress]: a finger has it, or a fling or a slide the app started is still running).
 *
 * A page is in view only when the row is at rest on it: in its place, staying there, and let go. While the row is
 * being moved the most a page can be is passing, however close to its place it is at that moment: a page that a
 * second swipe carries across the middle of the screen, or that a finger catches and drags on, is never in view on
 * the way. What a page starts when it comes into view therefore starts when the movement has ended.
 *
 * This is the one definition of "in view" for the three screens: everything a screen does only while it is the
 * one in view asks this. It is asked about a page, not about a pane, because the same pane is on every third page:
 * a screen asks about the page that shows it nearest the one in view ([pageToward]), the only one of them that is
 * anywhere near the screen.
 */
fun pageView(page: Int, nearestPage: Int, offsetFraction: Float, targetPage: Int, scrollInProgress: Boolean): PaneView = when {
    page == nearestPage ->
        if (!scrollInProgress && targetPage == page && abs(offsetFraction) <= PANE_SETTLED_WITHIN) PaneView.IN_VIEW
        else PaneView.PASSING
    page == nearestPage + 1 && offsetFraction > PANE_ON_SCREEN -> PaneView.PASSING
    page == nearestPage - 1 && offsetFraction < -PANE_ON_SCREEN -> PaneView.PASSING
    else -> PaneView.HIDDEN
}

/** Whether any page that shows [pane] has something of itself on screen: at rest there, or passing while the row moves. */
fun paneOnScreen(pane: Pane, nearestPage: Int, offsetFraction: Float, targetPage: Int, scrollInProgress: Boolean): Boolean =
    (nearestPage - 1..nearestPage + 1).any { page ->
        paneOf(page) == pane && pageView(page, nearestPage, offsetFraction, targetPage, scrollInProgress) != PaneView.HIDDEN
    }

/** What is on top: the three side-by-side screens, or one of the screens opened from a row (the last is the question asked before backups are turned off). */
enum class TopScreen { PANES, EDIT, CALIBRATION, BACKUP_OFF }

/** The routes of the navigation host: the three screens, the screen that edits the waypoint with an id, calibration, the question before backups are turned off. */
const val PANES_ROUTE = "panes"
const val EDIT_ROUTE = "edit/{id}"
const val CALIBRATE_ROUTE = "calibrate"
const val BACKUP_OFF_ROUTE = "backup-off"

/** The screen on top for the navigation host's current [route]; the three screens before it has one. */
fun topScreen(route: String?): TopScreen = when (route) {
    EDIT_ROUTE -> TopScreen.EDIT
    CALIBRATE_ROUTE -> TopScreen.CALIBRATION
    BACKUP_OFF_ROUTE -> TopScreen.BACKUP_OFF
    else -> TopScreen.PANES
}

/**
 * Whether the heading sensor should be running. This is the one decision: while the app is on screen, and either
 * calibration is open or the three screens are on top with the compass on screen ([paneOnScreen]: at rest in
 * view, or passing during a swipe, when it is drawn live). Settings, waypoints and the edit screen do not run it.
 */
fun headingWanted(top: TopScreen, compassOnScreen: Boolean, appOnScreen: Boolean): Boolean =
    appOnScreen && (top == TopScreen.CALIBRATION || (top == TopScreen.PANES && compassOnScreen))

/**
 * What the wearer has of a page whose place in the row gives it [view], when [top] is the screen on top. Under a
 * screen opened from a row a page is hidden, however it stands in the row: the navigation host can draw the
 * three screens behind such a screen while it is swiped aside, and a page that is only a picture there takes no
 * button, runs no frame loop and gives no tap.
 */
fun pageViewUnder(top: TopScreen, view: PaneView): PaneView = if (top == TopScreen.PANES) view else PaneView.HIDDEN
