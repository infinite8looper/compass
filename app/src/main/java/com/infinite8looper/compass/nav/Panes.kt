package com.infinite8looper.compass.nav

import kotlin.math.abs

/**
 * The three screens that sit side by side, from left to right. They form a ring: to the right of waypoints is
 * settings again, and to the left of settings is waypoints.
 */
enum class Pane { SETTINGS, COMPASS, WAYPOINTS }

/**
 * The ring is laid out as a long row of pages in which the three panes repeat, so a swipe the same way always
 * finds a next page. The app opens in the middle of the row, on a compass page, a million and a half swipes
 * from either end.
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

/** How much of one of the row's pages the wearer has in front of them. */
enum class PaneView {
    /** No part of it is on the screen. */
    HIDDEN,
    /** Part of it is on the screen while the row moves: it is drawn, and it takes no input and gives no feedback. */
    PASSING,
    /** It is the screen in view, at rest. */
    IN_VIEW,
}

/** A page counts as at rest within this fraction of a page of its place: a few pixels, less than a touch moves it. */
const val PANE_SETTLED_WITHIN = 0.01f
/** Less of a page than one pixel of any watch screen: an offset below it puts nothing of the next page on screen. */
private const val PANE_ON_SCREEN = 0.001f

/**
 * What the wearer has of the row's page number [page] when the row is at [nearestPage], the page nearest its
 * resting place, has moved [offsetFraction] of a page from there (-0.5 to 0.5, positive toward the next page), and
 * is headed for [targetPage], which is the page it rests on when it is not moving.
 *
 * A page is in view only when it is in its place and the row is staying there. A page that a long swipe carries
 * across the middle of the screen on the way to another is in its place for a frame and is still only passing.
 *
 * This is the one definition of "in view" for the three screens: everything a screen does only while it is the
 * one in view asks this. It is asked about a page, not about a pane, because the same pane is on every third page
 * and the pager may have built two pages of one pane at once, the one the wearer sees and one further along the
 * row; only the first of them is anywhere near the screen.
 */
fun pageView(page: Int, nearestPage: Int, offsetFraction: Float, targetPage: Int): PaneView = when {
    page == nearestPage ->
        if (targetPage == page && abs(offsetFraction) <= PANE_SETTLED_WITHIN) PaneView.IN_VIEW else PaneView.PASSING
    page == nearestPage + 1 && offsetFraction > PANE_ON_SCREEN -> PaneView.PASSING
    page == nearestPage - 1 && offsetFraction < -PANE_ON_SCREEN -> PaneView.PASSING
    else -> PaneView.HIDDEN
}
