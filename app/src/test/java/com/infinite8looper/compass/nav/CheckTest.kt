package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/**
 * The check screen's rules (v3 spec, "Check screen"): it is reached from the calibration screen "only in builds made
 * with the diagnostics switch on (the debug build and the watch build; never a build for a store)"; it shows live
 * "the three headings, the estimated heading error, the magnetometer's level and field strength"; and "a row
 * switches the compass's heading source for the running app".
 */
class CheckTest {
    // The route, and whether there is one.

    @Test fun aBuildWithTheDiagnosticsSwitchOnHasTheCheckScreensRoute() {
        assertEquals(CHECK_ROUTE, checkRoute(diagnostics = true))
        assertEquals("check", CHECK_ROUTE)
    }
    @Test fun aBuildWithTheDiagnosticsSwitchOffHasNoRouteToTheCheckScreen() {
        assertNull(checkRoute(diagnostics = false))
    }
    @Test fun theCheckScreensRouteIsItsOwnScreenOnTop() {
        assertEquals(TopScreen.CHECK, topScreen(CHECK_ROUTE))
        // No other route is taken for it.
        for (route in listOf(PANES_ROUTE, EDIT_ROUTE, CALIBRATE_ROUTE, BACKUP_OFF_ROUTE, null)) assertNotEquals(TopScreen.CHECK, topScreen(route))
    }
    @Test fun theHeadingRunsForTheCheckScreenWhileTheAppIsOnScreen() {
        // It shows the three headings live, whatever the row of screens under it.
        assertTrue(headingWanted(TopScreen.CHECK, compassOnScreen = false, appOnScreen = true))
        assertTrue(headingWanted(TopScreen.CHECK, compassOnScreen = true, appOnScreen = true))
        assertFalse(headingWanted(TopScreen.CHECK, compassOnScreen = true, appOnScreen = false))
    }
    @Test fun underTheCheckScreenEveryPageIsHiddenAndTheScreensAreNoReasonForLocation() {
        for (view in PaneView.entries) assertEquals(PaneView.HIDDEN, pageViewUnder(TopScreen.CHECK, view))
        for (compass in listOf(true, false)) for (waypoints in listOf(true, false)) {
            assertFalse(screensWantLocation(TopScreen.CHECK, compass, waypoints, appOnScreen = true))
        }
    }

    // The row that switches the heading source.

    @Test fun theRowGoesRoundTheThreeSourcesFromFused() {
        assertEquals(HeadingKind.MAGNETIC, nextHeadingKind(HeadingKind.FUSED))
        assertEquals(HeadingKind.BLEND, nextHeadingKind(HeadingKind.MAGNETIC))
        assertEquals(HeadingKind.FUSED, nextHeadingKind(HeadingKind.BLEND))
        // Three taps from any source come back to it, and each source is reached on the way.
        for (kind in HeadingKind.entries) {
            val seen = generateSequence(kind, ::nextHeadingKind).take(3).toSet()
            assertEquals(HeadingKind.entries.toSet(), seen)
            assertEquals(kind, nextHeadingKind(nextHeadingKind(nextHeadingKind(kind))))
        }
    }
    @Test fun eachSourceHasItsName() {
        assertEquals("Fused", headingKindName(HeadingKind.FUSED))
        assertEquals("Magnetic", headingKindName(HeadingKind.MAGNETIC))
        assertEquals("Blend", headingKindName(HeadingKind.BLEND))
    }

    // What it shows of a reading.

    @Test fun theThreeHeadingsAreWholeDegreesAsTheCompassShowsThem() {
        val shown = checkReadout(fusedDeg = 40.4f, magneticDeg = 359.6f, blendDeg = 39.5f, errorDeg = null, accuracy = 3, fieldMicroTesla = 49.4f)
        assertEquals("40°", shown.fused)
        assertEquals("0°", shown.magnetic)
        assertEquals("40°", shown.blend)
    }
    @Test fun aHeadingThatIsNotWorkedOutReadsAsNone() {
        val shown = checkReadout(Float.NaN, Float.NaN, Float.NaN, null, null, null)
        assertEquals("--", shown.fused)
        assertEquals("--", shown.magnetic)
        assertEquals("--", shown.blend)
    }
    @Test fun theErrorIsTheEstimateInWholeDegreesOrNone() {
        assertEquals("±12°", checkReadout(0f, 0f, 0f, 12.3f, 3, 49f).error)
        assertEquals("--", checkReadout(0f, 0f, 0f, null, 3, 49f).error)
    }
    @Test fun theLevelIsTheWordCalibrationShows() {
        for (accuracy in listOf(null, -1, 0, 1, 2, 3, 4)) assertEquals(accuracyLabel(accuracy), checkReadout(0f, 0f, 0f, null, accuracy, 49f).level)
    }
    @Test fun theFieldIsInWholeMicrotesla() {
        assertEquals("49 µT", checkReadout(0f, 0f, 0f, null, 3, 49.4f).field)
        assertEquals("92 µT", checkReadout(0f, 0f, 0f, null, 3, 91.6f).field)
        assertEquals("--", checkReadout(0f, 0f, 0f, null, 3, null).field)
        assertEquals("--", checkReadout(0f, 0f, 0f, null, 3, Float.NaN).field)
    }
    @Test fun aReadoutIsEqualToAnotherThatShowsTheSame() {
        // The screen is redrawn only when what it shows changes, not with each reading.
        assertEquals(checkReadout(40.1f, 41.2f, 40.3f, null, 3, 49.2f), checkReadout(39.9f, 40.8f, 39.7f, null, 3, 48.9f))
        assertNotEquals(checkReadout(40.1f, 41.2f, 40.3f, null, 3, 49.2f), checkReadout(40.1f, 42.2f, 40.3f, null, 3, 49.2f))
    }

    // What the watch answered.

    @Test fun aColorIsWrittenAsItsSixHexDigits() {
        assertEquals("#D9E2FF", colorHex(0xFFD9E2FF.toInt()))
        assertEquals("#00AA55", colorHex(0x0000AA55))
        assertEquals("#000000", colorHex(0xFF000000.toInt()))
    }
    @Test fun aYesOrNo() {
        assertEquals("Yes", yesNo(true))
        assertEquals("No", yesNo(false))
    }
    @Test fun theCountOfButtonsIsANumberOrUnknown() {
        assertEquals("2", buttonCountText(2))
        assertEquals("0", buttonCountText(0))
        assertEquals("Unknown", buttonCountText(BUTTON_COUNT_UNKNOWN))
        assertEquals("Unknown", buttonCountText(-5))
    }
}
