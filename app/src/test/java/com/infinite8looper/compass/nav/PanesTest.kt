package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class PanesTest {
    private val start = PANE_START_PAGE

    @Test fun theRingStartsOnTheCompassWithRoomBothWays() {
        assertEquals(Pane.COMPASS, paneOf(start))
        assertEquals(0, PANE_PAGES % 3)
        assertTrue(start > 1_000_000 && PANE_PAGES - start > 1_000_000)
        // Every page number below the count is a whole number as a Float, which the pager's semantics use.
        assertEquals(PANE_PAGES - 1, (PANE_PAGES - 1).toFloat().toInt())
    }
    @Test fun thePanesAreSettingsCompassWaypointsFromLeftToRight() {
        assertEquals(listOf(Pane.SETTINGS, Pane.COMPASS, Pane.WAYPOINTS), Pane.entries)
        assertEquals(listOf(0, 1, 2), Pane.entries.map { it.ordinal })
    }
    @Test fun aPageToTheRightOfTheCompassIsWaypointsAndToTheLeftIsSettings() {
        assertEquals(Pane.WAYPOINTS, paneOf(start + 1))
        assertEquals(Pane.SETTINGS, paneOf(start - 1))
    }
    @Test fun twoPagesEitherWayReachTheOtherPane() {
        assertEquals(Pane.SETTINGS, paneOf(start + 2))
        assertEquals(Pane.WAYPOINTS, paneOf(start - 2))
        assertEquals(Pane.COMPASS, paneOf(start + 3))
        assertEquals(Pane.COMPASS, paneOf(start - 3))
    }
    @Test fun everyPageOfTheRingMapsToItsPaneIncludingTheFirstAndLast() {
        assertEquals(Pane.SETTINGS, paneOf(0))
        assertEquals(Pane.WAYPOINTS, paneOf(PANE_PAGES - 1))
        // Literal page numbers: the first pages of the row, the last ones, and those around the start page.
        assertEquals(Pane.COMPASS, paneOf(1))
        assertEquals(Pane.WAYPOINTS, paneOf(2))
        assertEquals(Pane.SETTINGS, paneOf(3))
        assertEquals(Pane.COMPASS, paneOf(4))
        assertEquals(Pane.SETTINGS, paneOf(2_999_997))
        assertEquals(Pane.COMPASS, paneOf(2_999_998))
        assertEquals(Pane.WAYPOINTS, paneOf(2_999_999))
        assertEquals(3_000_000, PANE_PAGES)
        assertEquals(1_500_001, PANE_START_PAGE)
        assertEquals(Pane.WAYPOINTS, paneOf(1_499_999))
        assertEquals(Pane.SETTINGS, paneOf(1_500_000))
        assertEquals(Pane.COMPASS, paneOf(1_500_001))
        assertEquals(Pane.WAYPOINTS, paneOf(1_500_002))
        assertEquals(Pane.SETTINGS, paneOf(1_500_003))
        assertEquals(Pane.COMPASS, paneOf(1_500_004))
        assertEquals(Pane.COMPASS, paneOf(1_499_998))
        assertEquals(Pane.COMPASS, paneOf(1_000_000))
        assertEquals(Pane.SETTINGS, paneOf(999_999))
    }
    @Test fun aMoveToAPaneGoesToTheNearestPageThatShowsIt() {
        for (from in start - 4..start + 4) for (pane in Pane.entries) {
            val to = pageToward(from, pane)
            assertEquals("from $from to $pane", pane, paneOf(to))
            assertTrue("from $from to $pane went ${to - from} pages", to - from in -1..1)
        }
    }
    @Test fun aMoveToThePaneInViewStaysWhereItIs() {
        for (from in start - 3..start + 3) assertEquals(from, pageToward(from, paneOf(from)))
    }
    @Test fun theShortWayFromEachPane() {
        assertEquals(start + 1, pageToward(start, Pane.WAYPOINTS))
        assertEquals(start - 1, pageToward(start, Pane.SETTINGS))
        assertEquals(start, pageToward(start + 1, Pane.COMPASS))
        assertEquals(start, pageToward(start - 1, Pane.COMPASS))
        // From waypoints, settings is one page on to the right, round the ring, not two pages back.
        assertEquals(start + 2, pageToward(start + 1, Pane.SETTINGS))
        assertEquals(start - 2, pageToward(start - 1, Pane.WAYPOINTS))
    }
    @Test fun aMoveNeverLeavesTheRing() {
        assertEquals(1, pageToward(0, Pane.COMPASS))
        assertEquals(2, pageToward(0, Pane.WAYPOINTS))            // the short way would be page -1
        assertEquals(PANE_PAGES - 2, pageToward(PANE_PAGES - 1, Pane.COMPASS))
        assertEquals(PANE_PAGES - 3, pageToward(PANE_PAGES - 1, Pane.SETTINGS))   // the short way would be past the end
    }

    @Test fun backGoesOnePageTowardSettingsRoundTheRing() {
        assertEquals(Pane.COMPASS, paneOf(start))
        assertEquals(Pane.SETTINGS, paneOf(backPage(start, start, false)))
        assertEquals(Pane.WAYPOINTS, paneOf(backPage(start - 1, start - 1, false)))
        assertEquals(Pane.COMPASS, paneOf(backPage(start + 1, start + 1, false)))
        for (page in start - 4..start + 4) assertEquals(page - 1, backPage(page, page, false))
    }
    @Test fun backWhileThePagesAreMovingGoesOnFromWhereTheyAreHeaded() {
        // Sliding from the compass to settings: another Back goes one further, to waypoints.
        assertEquals(start - 2, backPage(start, start - 1, true))
        assertEquals(start - 2, backPage(start - 1, start - 1, true))
        // A finger has the pages and they are headed for the page after: Back is one page before that.
        assertEquals(start, backPage(start, start + 1, true))
        // At rest the page in view counts, whatever the target last was.
        assertEquals(start - 1, backPage(start, start + 1, false))
    }
    @Test fun tenBacksInARowGoRoundTheRingAndEndOnAPage() {
        var page = start
        val seen = mutableListOf<Pane>()
        repeat(10) { page = backPage(page, page, false); seen += paneOf(page) }
        assertEquals(start - 10, page)
        assertEquals(listOf(Pane.SETTINGS, Pane.WAYPOINTS, Pane.COMPASS, Pane.SETTINGS), seen.take(4))
        assertEquals(setOf(Pane.SETTINGS, Pane.WAYPOINTS, Pane.COMPASS), seen.toSet())
    }
    @Test fun backNeverLeavesTheRow() {
        // At the very start of the row the same pane is two pages on.
        assertEquals(2, backPage(0, 0, false))
        assertEquals(paneOf(-1 + 3), paneOf(backPage(0, 0, false)))
        assertEquals(0, backPage(1, 1, false))
        assertEquals(2, backPage(1, 0, true))
        assertEquals(PANE_PAGES - 2, backPage(PANE_PAGES - 1, PANE_PAGES - 1, false))
    }

    // In the tests below the compass is page [start], waypoints the page after it and settings the page before.
    private val compass = start
    private val waypoints = start + 1
    private val settings = start - 1

    @Test fun aPageAtRestIsInViewAndItsNeighborsAreHidden() {
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.IN_VIEW, pageView(waypoints, waypoints, 0f, targetPage = waypoints, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(compass, waypoints, 0f, targetPage = waypoints, scrollInProgress = false))
        assertEquals(PaneView.IN_VIEW, pageView(settings, settings, 0f, targetPage = settings, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(compass, settings, 0f, targetPage = settings, scrollInProgress = false))
    }
    @Test fun aPageBeingDraggedAwayIsPassingAndSoIsTheOneComingIn() {
        // A positive offset is toward the next page, which from the compass is waypoints.
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.3f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.3f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0.3f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -0.3f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(settings, compass, -0.3f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, -0.3f, targetPage = compass, scrollInProgress = false))
    }
    @Test fun pastHalfwayTheNearestPageChangesAndBothAreStillPassing() {
        assertEquals(PaneView.PASSING, pageView(compass, waypoints, -0.4f, targetPage = waypoints, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(waypoints, waypoints, -0.4f, targetPage = waypoints, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(waypoints + 1, waypoints, -0.4f, targetPage = waypoints, scrollInProgress = false))
    }
    @Test fun aPageCountsAsInViewOnlyWithinAHundredthOfItsPlace() {
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, PANE_SETTLED_WITHIN, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, -PANE_SETTLED_WITHIN, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, PANE_SETTLED_WITHIN + 0.001f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -PANE_SETTLED_WITHIN - 0.001f, targetPage = compass, scrollInProgress = false))
        assertEquals(0.01f, PANE_SETTLED_WITHIN, 0f)
    }
    @Test fun theNeighborIsOnScreenAsSoonAsAPixelOfItShows() {
        // One pixel of a 192 px screen is 0.0052 of a page, and of a 454 px screen 0.0022.
        assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.0022f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(settings, compass, -0.0022f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0.0022f, targetPage = compass, scrollInProgress = false))
        // Less than any pixel is the pager's own rounding, not a page on screen.
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0.0005f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, -0.0005f, targetPage = compass, scrollInProgress = false))
    }
    @Test fun aPageTheRowIsOnlyPassingThroughIsNotInView() {
        // A long swipe from settings toward waypoints carries the compass across the middle of the screen. On the
        // frame it is exactly centered it is still on its way out.
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0f, targetPage = waypoints, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.004f, targetPage = waypoints, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -0.004f, targetPage = settings, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0f, targetPage = compass + 3, scrollInProgress = false))
    }
    @Test fun aPageIsInViewOnlyOnceTheRowIsAtRestOnIt() {
        // In its place and the row headed for it, but a finger, a fling or a slide still has the row: passing.
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0f, targetPage = compass, scrollInProgress = true))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.004f, targetPage = compass, scrollInProgress = true))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -0.01f, targetPage = compass, scrollInProgress = true))
        // The same place with the row let go and still: in view.
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0.004f, targetPage = compass, scrollInProgress = false))
    }
    @Test fun aPageDraggedAcrossTheMiddleByAFingerThatCaughtTheRowIsPassingAllTheWay() {
        // A swipe from settings toward the compass is caught in flight and dragged on to waypoints. Under the
        // finger the pager reports the compass as where it is headed until the drag is well past it.
        for (offset in listOf(-0.3f, -0.01f, -0.004f, 0f, 0.004f, 0.01f, 0.3f)) {
            assertEquals(
                "offset $offset", PaneView.PASSING,
                pageView(compass, compass, offset, targetPage = compass, scrollInProgress = true),
            )
        }
        // A fling that was cut short leaves its old target behind: still passing while the finger has the row.
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0f, targetPage = waypoints, scrollInProgress = true))
        assertEquals(PaneView.PASSING, pageView(waypoints, waypoints, 0f, targetPage = waypoints, scrollInProgress = true))
    }
    @Test fun aRowInMotionHidesAndShowsPagesAsAtRest() {
        assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.3f, targetPage = waypoints, scrollInProgress = true))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0.3f, targetPage = waypoints, scrollInProgress = true))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0f, targetPage = compass, scrollInProgress = true))
        assertEquals(PaneView.HIDDEN, pageView(compass + 3, compass, 0f, targetPage = compass, scrollInProgress = true))
    }
    @Test fun aPageTheRowIsHeadedForIsInViewOnceItIsInPlace() {
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0.004f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, -0.01f, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.2f, targetPage = compass, scrollInProgress = false))
    }
    @Test fun whereTheRowIsHeadedDoesNotChangeWhatIsOnScreen() {
        for (target in listOf(settings, compass, waypoints)) {
            assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.3f, targetPage = target, scrollInProgress = false))
            assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0.3f, targetPage = target, scrollInProgress = false))
            assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0f, targetPage = target, scrollInProgress = false))
            assertEquals(PaneView.PASSING, pageView(compass, compass, 0.3f, targetPage = target, scrollInProgress = false))
        }
    }
    @Test fun anotherPageOfTheSamePaneFurtherAlongTheRowIsHidden() {
        // Three pages on is the compass again. The pager may have built it; the wearer has none of it.
        assertEquals(Pane.COMPASS, paneOf(compass + 3))
        for (offset in listOf(-0.5f, -0.3f, 0f, 0.3f, 0.5f)) {
            for (nearest in compass - 1..compass + 1) {
                assertEquals(PaneView.HIDDEN, pageView(compass + 3, nearest, offset, targetPage = nearest, scrollInProgress = false))
                assertEquals(PaneView.HIDDEN, pageView(compass - 3, nearest, offset, targetPage = nearest, scrollInProgress = false))
            }
        }
    }
    @Test fun anOffsetThatIsNotANumberLeavesNoPageInView() {
        assertEquals(PaneView.PASSING, pageView(compass, compass, Float.NaN, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, Float.NaN, targetPage = compass, scrollInProgress = false))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, Float.NaN, targetPage = compass, scrollInProgress = false))
    }
    @Test fun exactlyOnePageIsInViewAtRestAndAtMostOneAnywhere() {
        val pages = start - 6..start + 6
        for (nearest in start - 3..start + 3) {
            assertEquals(listOf(nearest), pages.filter { pageView(it, nearest, 0f, targetPage = nearest, scrollInProgress = false) == PaneView.IN_VIEW })
            for (offset in listOf(-0.5f, -0.2f, -0.01f, 0.004f, 0.02f, 0.5f)) {
                assertTrue(pages.count { pageView(it, nearest, offset, targetPage = nearest, scrollInProgress = false) == PaneView.IN_VIEW } <= 1)
                val onScreen = pages.filter { pageView(it, nearest, offset, targetPage = nearest, scrollInProgress = false) != PaneView.HIDDEN }
                assertTrue(onScreen.size in 1..2)
                // The pages on screen are next to each other, so they are never two pages of one pane.
                assertEquals(onScreen.size, onScreen.map { paneOf(it) }.distinct().size)
                assertTrue(onScreen.last() - onScreen.first() <= 1)
            }
        }
    }

    // Whether a pane has anything on screen, and whether the heading sensor is wanted

    @Test fun aPaneIsOnScreenWhenThePageAtRestOrAPagePassingShowsIt() {
        // At rest on the compass: only the compass.
        assertTrue(paneOnScreen(Pane.COMPASS, start, 0f, start, false))
        assertFalse(paneOnScreen(Pane.WAYPOINTS, start, 0f, start, false))
        assertFalse(paneOnScreen(Pane.SETTINGS, start, 0f, start, false))
        // Dragged a third of the way toward waypoints: both, and not settings.
        assertTrue(paneOnScreen(Pane.COMPASS, start, 0.33f, start, true))
        assertTrue(paneOnScreen(Pane.WAYPOINTS, start, 0.33f, start, true))
        assertFalse(paneOnScreen(Pane.SETTINGS, start, 0.33f, start, true))
        // Past half way the nearest page is waypoints and the compass is the one behind.
        assertTrue(paneOnScreen(Pane.COMPASS, start + 1, -0.4f, start + 1, true))
        assertTrue(paneOnScreen(Pane.WAYPOINTS, start + 1, -0.4f, start + 1, true))
        // The other way, toward settings.
        assertTrue(paneOnScreen(Pane.SETTINGS, start, -0.2f, start - 1, true))
        assertTrue(paneOnScreen(Pane.COMPASS, start, -0.2f, start - 1, true))
        assertFalse(paneOnScreen(Pane.WAYPOINTS, start, -0.2f, start - 1, true))
        // At rest on waypoints or on settings the compass has nothing on screen.
        assertFalse(paneOnScreen(Pane.COMPASS, start + 1, 0f, start + 1, false))
        assertFalse(paneOnScreen(Pane.COMPASS, start - 1, 0f, start - 1, false))
        assertFalse(paneOnScreen(Pane.COMPASS, start + 2, 0f, start + 2, false))
        // At the two ends of the row.
        assertTrue(paneOnScreen(Pane.SETTINGS, 0, 0f, 0, false))
        assertTrue(paneOnScreen(Pane.WAYPOINTS, PANE_PAGES - 1, 0f, PANE_PAGES - 1, false))
    }
    @Test fun theHeadingIsWantedOnTheCompassAndOnCalibrationWhileTheAppIsOnScreen() {
        assertTrue(headingWanted(TopScreen.PANES, compassOnScreen = true, appOnScreen = true))
        assertTrue(headingWanted(TopScreen.CALIBRATION, compassOnScreen = false, appOnScreen = true))
        assertTrue(headingWanted(TopScreen.CALIBRATION, compassOnScreen = true, appOnScreen = true))
    }
    @Test fun theHeadingIsNotWantedOnSettingsWaypointsOrEdit() {
        // Settings or waypoints: the three screens are on top and the compass has nothing on screen.
        assertFalse(headingWanted(TopScreen.PANES, compassOnScreen = false, appOnScreen = true))
        // Edit covers the three screens, wherever their row stands under it.
        assertFalse(headingWanted(TopScreen.EDIT, compassOnScreen = false, appOnScreen = true))
        assertFalse(headingWanted(TopScreen.EDIT, compassOnScreen = true, appOnScreen = true))
    }
    @Test fun theHeadingIsNeverWantedWhileTheAppIsOffScreen() {
        for (top in TopScreen.entries) for (compass in listOf(true, false)) assertFalse(headingWanted(top, compass, appOnScreen = false))
    }
    @Test fun theTopScreenIsReadFromTheRoute() {
        assertEquals(TopScreen.PANES, topScreen("panes"))
        assertEquals(TopScreen.EDIT, topScreen("edit/{id}"))
        assertEquals(TopScreen.CALIBRATION, topScreen("calibrate"))
        // Before the navigation host has a destination the three screens are what it starts on.
        assertEquals(TopScreen.PANES, topScreen(null))
    }

    // A page under a screen opened from a row

    @Test fun aPageIsWhatItsPlaceInTheRowSaysWhileTheThreeScreensAreOnTop() {
        for (view in PaneView.entries) assertEquals(view, pageViewUnder(TopScreen.PANES, view))
    }
    @Test fun aPageUnderEditOrCalibrationIsHiddenHoweverItStandsInTheRow() {
        for (top in listOf(TopScreen.EDIT, TopScreen.CALIBRATION)) {
            for (view in PaneView.entries) assertEquals("$top $view", PaneView.HIDDEN, pageViewUnder(top, view))
        }
        // The page the row rests on, which alone would be in view.
        assertEquals(PaneView.IN_VIEW, pageView(start, start, 0f, start, false))
        assertEquals(PaneView.HIDDEN, pageViewUnder(TopScreen.EDIT, pageView(start, start, 0f, start, false)))
        assertEquals(PaneView.HIDDEN, pageViewUnder(TopScreen.CALIBRATION, pageView(start, start, 0f, start, false)))
    }

    @Test fun theQuestionBeforeBackupsAreTurnedOffIsAScreenOnTopLikeTheOthers() {
        assertEquals(TopScreen.BACKUP_OFF, topScreen(BACKUP_OFF_ROUTE))
        assertEquals("backup-off", BACKUP_OFF_ROUTE)
        // Under it every page is hidden, and the heading sensor does not run for it.
        val start = PANE_START_PAGE
        for (view in PaneView.entries) assertEquals(PaneView.HIDDEN, pageViewUnder(TopScreen.BACKUP_OFF, view))
        assertEquals(PaneView.HIDDEN, pageViewUnder(TopScreen.BACKUP_OFF, pageView(start, start, 0f, start, false)))
        for (compass in listOf(true, false)) assertFalse(headingWanted(TopScreen.BACKUP_OFF, compass, appOnScreen = true))
    }
}
