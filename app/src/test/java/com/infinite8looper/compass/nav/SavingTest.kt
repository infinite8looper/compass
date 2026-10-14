package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/**
 * The decisions of a save (v3 spec, "Saving"): the position in hand is used at once when it is not older than
 * 10 seconds at the press, a more accurate one taken within 10 seconds of the press replaces it, and a replacement
 * is written only while the row still holds what the save wrote.
 */
class SavingTest {
    private val press = 1_000_000L
    private fun fixAt(elapsedMs: Long, accuracyM: Float, lat: Double = 43.7022) = Fix(GeoPoint(lat, -72.2896), accuracyM, 160f, elapsedMs)

    // Save now or wait.

    @Test fun aPositionInHandUpToTenSecondsOldIsSavedAtOnce() {
        for (age in listOf(0L, 1L, 3_000L, 9_900L, 10_000L)) {
            val inHand = fixAt(press - age, 5f)
            assertEquals("$age ms old", inHand, positionToSaveAtOnce(inHand, press))
        }
    }

    @Test fun aPositionInHandOlderThanTenSecondsIsWaitedPast() {
        for (age in listOf(10_001L, 15_000L, 600_000L)) assertNull("$age ms old", positionToSaveAtOnce(fixAt(press - age, 5f), press))
    }

    @Test fun withNoPositionInHandTheSaveWaits() {
        assertNull(positionToSaveAtOnce(null, press))
    }

    /** Review focus: a position 9.9 seconds old and 300 m off is saved as it is, with its own accuracy, which the status says. */
    @Test fun aPoorPositionInHandIsSavedWithItsOwnAccuracyAndTheStatusSaysIt() {
        val poor = fixAt(press - 9_900, 300f)
        val used = positionToSaveAtOnce(poor, press)
        assertEquals(300f, used!!.accuracyM, 0f)
        assertEquals("Saved Waypoint 4" to "±300 m", statusText(LogStatus.Saved("Waypoint 4", used.accuracyM, used.elapsedMs), Units.METRIC))
        assertEquals("Saved Waypoint 4" to "±984 ft", statusText(LogStatus.Saved("Waypoint 4", used.accuracyM, used.elapsedMs), Units.IMPERIAL))
    }

    @Test fun aPositionTakenAfterThePressIsInHandToo() {
        val after = fixAt(press + 200, 5f)
        assertEquals(after, positionToSaveAtOnce(after, press))
    }

    @Test fun aPositionWithNoAccuracyIsNotSavedAtOnce() {
        assertNull(positionToSaveAtOnce(fixAt(press - 1_000, Float.NaN), press))
    }

    // The two places a position can be in hand.

    @Test fun theAppsOwnPositionIsInHandBeforeTheLastKnownOne() {
        val own = fixAt(press - 2_000, 5f); val known = fixAt(press - 500, 3f)
        assertEquals(own, positionInHand(own, known, press))
    }

    @Test fun theLastKnownPositionIsInHandWhenTheAppHasNoneRecentEnough() {
        val known = fixAt(press - 1_000, 7f)
        assertEquals(known, positionInHand(null, known, press))
        assertEquals(known, positionInHand(fixAt(press - 10_001, 5f), known, press))
    }

    @Test fun nothingIsInHandWhenNeitherIsRecentEnough() {
        assertNull(positionInHand(null, null, press))
        assertNull(positionInHand(fixAt(press - 20_000, 5f), fixAt(press - 10_001, 5f), press))
        assertNull(positionInHand(null, fixAt(press - 1_000, Float.NaN), press))
    }

    // Refine or not.

    @Test fun aMoreAccuratePositionWithinTenSecondsOfThePressReplacesTheOneWritten() {
        val written = fixAt(press - 4_000, 12f)
        assertTrue(refines(written, fixAt(press + 1_000, 5f), press))
        assertTrue(refines(written, fixAt(press + 10_000, 11.9f), press))
        // Taken between the written one and the press: also within the window, and newer than what was written.
        assertTrue(refines(written, fixAt(press - 1_000, 5f), press))
    }

    @Test fun aPositionTakenMoreThanTenSecondsAfterThePressRefinesNothing() {
        assertFalse(refines(fixAt(press - 4_000, 12f), fixAt(press + 10_001, 3f), press))
    }

    @Test fun anEquallyOrLessAccuratePositionRefinesNothing() {
        val written = fixAt(press - 4_000, 5f)
        assertFalse(refines(written, fixAt(press + 1_000, 5f), press))
        assertFalse(refines(written, fixAt(press + 1_000, 8f), press))
        assertFalse(refines(written, fixAt(press + 1_000, Float.NaN), press))
    }

    @Test fun aPositionOlderThanTheOneWrittenRefinesNothing() {
        assertFalse(refines(fixAt(press - 2_000, 12f), fixAt(press - 3_000, 3f), press))
    }

    @Test fun theListeningEndsTenSecondsAfterThePress() {
        assertEquals(10_000L, refineWindowLeftMs(press, now = press))
        assertEquals(1L, refineWindowLeftMs(press, now = press + 9_999))
        assertEquals(0L, refineWindowLeftMs(press, now = press + 10_000))
        assertEquals(0L, refineWindowLeftMs(press, now = press + 60_000))
    }

    // Refinement ends on a move or a delete; a change of the name or color does not end it.

    @Test fun aRefinementWritesOnlyWhileTheRowHoldsThePositionWritten() {
        val written = StoredPosition(43.7022, -72.2896, 12f)
        assertTrue(refinementMayWrite(StoredPosition(43.7022, -72.2896, 12f), written))
        // Deleted: nothing to write to, and nothing is brought back.
        assertFalse(refinementMayWrite(null, written))
        // Moved: the position or its accuracy is no longer the one written.
        assertFalse(refinementMayWrite(StoredPosition(43.7030, -72.2896, 12f), written))
        assertFalse(refinementMayWrite(StoredPosition(43.7022, -72.2800, 12f), written))
        assertFalse(refinementMayWrite(StoredPosition(43.7022, -72.2896, 4f), written))
    }

    // Location while the app is on screen.

    @Test fun locationRunsWhileTheCompassOrTheWaypointsAreOnScreenWithTheAppOnScreen() {
        assertTrue(screensWantLocation(TopScreen.PANES, compassOnScreen = true, waypointsOnScreen = false, appOnScreen = true))
        assertTrue(screensWantLocation(TopScreen.PANES, compassOnScreen = false, waypointsOnScreen = true, appOnScreen = true))
        // Passing from one to the other.
        assertTrue(screensWantLocation(TopScreen.PANES, compassOnScreen = true, waypointsOnScreen = true, appOnScreen = true))
    }

    @Test fun locationDoesNotRunForSettingsAScreenOnTopOrAnAppOffScreen() {
        // Settings at rest: neither of the two is on screen.
        assertFalse(screensWantLocation(TopScreen.PANES, compassOnScreen = false, waypointsOnScreen = false, appOnScreen = true))
        for (top in TopScreen.entries - TopScreen.PANES) for (c in listOf(true, false)) for (w in listOf(true, false)) {
            assertFalse("$top", screensWantLocation(top, c, w, appOnScreen = true))
        }
        for (top in TopScreen.entries) for (c in listOf(true, false)) for (w in listOf(true, false)) {
            assertFalse("$top off screen", screensWantLocation(top, c, w, appOnScreen = false))
        }
    }

    // True north.

    @Test fun trueNorthWithNoStoredPositionAsksForOne() {
        assertTrue(trueNorthWantsPosition(NorthRef.TRUE, hasStoredPosition = false))
        assertFalse(trueNorthWantsPosition(NorthRef.TRUE, hasStoredPosition = true))
        assertFalse(trueNorthWantsPosition(NorthRef.MAGNETIC, hasStoredPosition = false))
        assertFalse(trueNorthWantsPosition(NorthRef.MAGNETIC, hasStoredPosition = true))
    }

    @Test fun magShowsExactlyWhileTheDialIsMagnetic() {
        assertTrue(dialIsMagnetic(NorthRef.MAGNETIC, null))
        assertTrue(dialIsMagnetic(NorthRef.MAGNETIC, 14f))
        assertTrue(dialIsMagnetic(NorthRef.TRUE, null))
        assertFalse(dialIsMagnetic(NorthRef.TRUE, 14f))
        assertFalse(dialIsMagnetic(NorthRef.TRUE, 0f))
        // The same answer the heading itself is drawn with.
        for (north in NorthRef.entries) for (d in listOf(null, -3f, 0f, 14f)) {
            assertEquals("$north $d", displayHeading(10f, d, north).isMagnetic, dialIsMagnetic(north, d))
        }
    }
}
