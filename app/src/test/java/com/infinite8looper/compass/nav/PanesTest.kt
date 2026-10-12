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

    // In the tests below the compass is page [start], waypoints the page after it and settings the page before.
    private val compass = start
    private val waypoints = start + 1
    private val settings = start - 1

    @Test fun aPageAtRestIsInViewAndItsNeighborsAreHidden() {
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0f, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0f, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0f, targetPage = compass))
        assertEquals(PaneView.IN_VIEW, pageView(waypoints, waypoints, 0f, targetPage = waypoints))
        assertEquals(PaneView.HIDDEN, pageView(compass, waypoints, 0f, targetPage = waypoints))
        assertEquals(PaneView.IN_VIEW, pageView(settings, settings, 0f, targetPage = settings))
        assertEquals(PaneView.HIDDEN, pageView(compass, settings, 0f, targetPage = settings))
    }
    @Test fun aPageBeingDraggedAwayIsPassingAndSoIsTheOneComingIn() {
        // A positive offset is toward the next page, which from the compass is waypoints.
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.3f, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.3f, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0.3f, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -0.3f, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(settings, compass, -0.3f, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, -0.3f, targetPage = compass))
    }
    @Test fun pastHalfwayTheNearestPageChangesAndBothAreStillPassing() {
        assertEquals(PaneView.PASSING, pageView(compass, waypoints, -0.4f, targetPage = waypoints))
        assertEquals(PaneView.PASSING, pageView(waypoints, waypoints, -0.4f, targetPage = waypoints))
        assertEquals(PaneView.HIDDEN, pageView(waypoints + 1, waypoints, -0.4f, targetPage = waypoints))
    }
    @Test fun aPageCountsAsInViewOnlyWithinAHundredthOfItsPlace() {
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, PANE_SETTLED_WITHIN, targetPage = compass))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, -PANE_SETTLED_WITHIN, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(compass, compass, PANE_SETTLED_WITHIN + 0.001f, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -PANE_SETTLED_WITHIN - 0.001f, targetPage = compass))
        assertEquals(0.01f, PANE_SETTLED_WITHIN, 0f)
    }
    @Test fun theNeighborIsOnScreenAsSoonAsAPixelOfItShows() {
        // One pixel of a 192 px screen is 0.0052 of a page, and of a 454 px screen 0.0022.
        assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.0022f, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(settings, compass, -0.0022f, targetPage = compass))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0.0022f, targetPage = compass))
        // Less than any pixel is the pager's own rounding, not a page on screen.
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0.0005f, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, -0.0005f, targetPage = compass))
    }
    @Test fun aPageTheRowIsOnlyPassingThroughIsNotInView() {
        // A long swipe from settings toward waypoints carries the compass across the middle of the screen. On the
        // frame it is exactly centered it is still on its way out.
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0f, targetPage = waypoints))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.004f, targetPage = waypoints))
        assertEquals(PaneView.PASSING, pageView(compass, compass, -0.004f, targetPage = settings))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0f, targetPage = compass + 3))
    }
    @Test fun aPageTheRowIsHeadedForIsInViewOnceItIsInPlace() {
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, 0.004f, targetPage = compass))
        assertEquals(PaneView.IN_VIEW, pageView(compass, compass, -0.01f, targetPage = compass))
        assertEquals(PaneView.PASSING, pageView(compass, compass, 0.2f, targetPage = compass))
    }
    @Test fun whereTheRowIsHeadedDoesNotChangeWhatIsOnScreen() {
        for (target in listOf(settings, compass, waypoints)) {
            assertEquals(PaneView.PASSING, pageView(waypoints, compass, 0.3f, targetPage = target))
            assertEquals(PaneView.HIDDEN, pageView(settings, compass, 0.3f, targetPage = target))
            assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, 0f, targetPage = target))
            assertEquals(PaneView.PASSING, pageView(compass, compass, 0.3f, targetPage = target))
        }
    }
    @Test fun anotherPageOfTheSamePaneFurtherAlongTheRowIsHidden() {
        // Three pages on is the compass again. The pager may have built it; the wearer has none of it.
        assertEquals(Pane.COMPASS, paneOf(compass + 3))
        for (offset in listOf(-0.5f, -0.3f, 0f, 0.3f, 0.5f)) {
            for (nearest in compass - 1..compass + 1) {
                assertEquals(PaneView.HIDDEN, pageView(compass + 3, nearest, offset, targetPage = nearest))
                assertEquals(PaneView.HIDDEN, pageView(compass - 3, nearest, offset, targetPage = nearest))
            }
        }
    }
    @Test fun anOffsetThatIsNotANumberLeavesNoPageInView() {
        assertEquals(PaneView.PASSING, pageView(compass, compass, Float.NaN, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(waypoints, compass, Float.NaN, targetPage = compass))
        assertEquals(PaneView.HIDDEN, pageView(settings, compass, Float.NaN, targetPage = compass))
    }
    @Test fun exactlyOnePageIsInViewAtRestAndAtMostOneAnywhere() {
        val pages = start - 6..start + 6
        for (nearest in start - 3..start + 3) {
            assertEquals(listOf(nearest), pages.filter { pageView(it, nearest, 0f, targetPage = nearest) == PaneView.IN_VIEW })
            for (offset in listOf(-0.5f, -0.2f, -0.01f, 0.004f, 0.02f, 0.5f)) {
                assertTrue(pages.count { pageView(it, nearest, offset, targetPage = nearest) == PaneView.IN_VIEW } <= 1)
                val onScreen = pages.filter { pageView(it, nearest, offset, targetPage = nearest) != PaneView.HIDDEN }
                assertTrue(onScreen.size in 1..2)
                // The pages on screen are next to each other, so they are never two pages of one pane.
                assertEquals(onScreen.size, onScreen.map { paneOf(it) }.distinct().size)
                assertTrue(onScreen.last() - onScreen.first() <= 1)
            }
        }
    }
}
