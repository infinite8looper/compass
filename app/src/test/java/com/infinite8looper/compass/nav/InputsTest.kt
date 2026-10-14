package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class InputsTest {
    // Whether the watch has a button an app can use, from what the watch says of its buttons.

    @Test fun aWatchWithNothingButItsPowerButtonHasNoAssignableButton() {
        assertFalse(hasAssignableButton(buttonCount = 1, sideKeysKnown = 0))
        assertFalse(hasAssignableButton(buttonCount = 0, sideKeysKnown = 0))
    }
    @Test fun aWatchWithASecondButtonThatIsOneOfTheSideKeysHasOne() {
        assertTrue(hasAssignableButton(buttonCount = 2, sideKeysKnown = 1))
        assertTrue(hasAssignableButton(buttonCount = 4, sideKeysKnown = 3))
    }
    @Test fun moreButtonsOfWhichNoneIsASideKeyIsNoAssignableButton() {
        // The app acts on the three side keys and on nothing else: a button with another key is not one of its own.
        assertFalse(hasAssignableButton(buttonCount = 2, sideKeysKnown = 0))
        assertFalse(hasAssignableButton(buttonCount = 3, sideKeysKnown = 0))
    }
    @Test fun aCountOfOneIsBelievedOverASideKeyTheWatchDescribes() {
        // The count includes the power button, so a count of one says there is no other.
        assertFalse(hasAssignableButton(buttonCount = 1, sideKeysKnown = 1))
        assertFalse(hasAssignableButton(buttonCount = 0, sideKeysKnown = 2))
    }
    @Test fun aWatchThatCannotSayHowManyButtonsItHasIsJudgedByItsSideKeysAlone() {
        assertTrue(hasAssignableButton(buttonCount = BUTTON_COUNT_UNKNOWN, sideKeysKnown = 1))
        assertFalse(hasAssignableButton(buttonCount = BUTTON_COUNT_UNKNOWN, sideKeysKnown = 0))
    }

    // The settings row of the breadcrumb trail.

    @Test fun theTrailsSettingSpeaksOfAPressOnlyOnAWatchWithAButton() {
        assertEquals("Double press starts a trail", trailSettingDetail(assignableButton = true))
        val without = trailSettingDetail(assignableButton = false)
        assertEquals("Trail row on waypoints screen", without)
        assertFalse(without.contains("press", ignoreCase = true))
    }

    // Two taps on the dial.

    private val slop = 100f

    @Test fun twoTapsCloseTogetherWithTheCompassInViewSave() {
        assertTrue(doubleTapSaves(firstInView = true, secondInView = true, apartPx = 0f, slopPx = slop))
        assertTrue(doubleTapSaves(firstInView = true, secondInView = true, apartPx = 100f, slopPx = slop))
    }
    @Test fun twoTapsFurtherApartThanTheSlopSaveNothing() {
        assertFalse(doubleTapSaves(firstInView = true, secondInView = true, apartPx = 100.5f, slopPx = slop))
        assertFalse(doubleTapSaves(firstInView = true, secondInView = true, apartPx = 400f, slopPx = slop))
    }
    @Test fun aTapThatLandedWhileTheCompassWasNotInViewMakesNoDoubleTap() {
        // The first tap stopped a screen that was still sliding, or the second landed after the screens had begun to move.
        assertFalse(doubleTapSaves(firstInView = false, secondInView = true, apartPx = 0f, slopPx = slop))
        assertFalse(doubleTapSaves(firstInView = true, secondInView = false, apartPx = 0f, slopPx = slop))
        assertFalse(doubleTapSaves(firstInView = false, secondInView = false, apartPx = 0f, slopPx = slop))
    }
    @Test fun aDistanceThatIsNotANumberSavesNothing() {
        assertFalse(doubleTapSaves(firstInView = true, secondInView = true, apartPx = Float.NaN, slopPx = slop))
    }

    @Test fun theAccessibilityActionIsNamedAsTheSpecProposes() {
        assertEquals("Save waypoint here", SAVE_HERE_ACTION)
        // The dial it is an action of is named for a screen reader with the app's name.
        assertEquals("Compass", DIAL_NAME)
    }
}
