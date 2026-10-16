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
        assertEquals("Trail row on waypoints", without)
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

    // Taps one after another: which two make a double tap.

    private fun tap(downMs: Long, x: Float = 100f, inView: Boolean = true, heldMs: Long = 40) =
        DialTap(downMs, downMs + heldMs, x, 100f, inViewAtDown = inView, inViewAtUp = inView)
    private fun steps(vararg taps: DialTap): List<Boolean> {
        var waiting: DialTap? = null
        return taps.map { t -> nextTap(waiting, t, timeoutMs = 300, minMs = 40, slopPx = slop).also { waiting = it.waiting }.saves }
    }

    @Test fun twoTapsInsideTheWindowSaveOnceAndOneTapAloneSavesNothing() {
        assertEquals(listOf(false), steps(tap(0)))
        assertEquals(listOf(false, true), steps(tap(0), tap(140)))
    }
    @Test fun aStrayTapFarAwayDoesNotUseUpTheDoubleTapThatFollowsIt() {
        // A tap at the edge, then two quick taps on the dial: the second and third are the double tap.
        assertEquals(listOf(false, false, true), steps(tap(0, x = 10f), tap(150, x = 300f), tap(290, x = 300f)))
    }
    @Test fun aStrayTapLongBeforeDoesNotUseUpTheDoubleTapThatFollowsIt() {
        assertEquals(listOf(false, false, true), steps(tap(0), tap(500), tap(640)))
    }
    @Test fun aThirdTapCloseBehindADoubleTapIsNotASecondDoubleTap() {
        assertEquals(listOf(false, true, false), steps(tap(0), tap(140), tap(280)))
        // But a fourth makes a pair with the third.
        assertEquals(listOf(false, true, false, true), steps(tap(0), tap(140), tap(280), tap(420)))
    }
    @Test fun tapsTooCloseOrTooFarApartInTimeAreNoPair() {
        // The second went down 341 ms after the first was let go (the first is held 40 ms).
        assertEquals(listOf(false, false), steps(tap(0), tap(381)))
        // Sooner than a double tap may follow (under 40 ms after the release).
        assertEquals(listOf(false, false), steps(tap(0), tap(60)))
    }
    // The limits themselves (the first tap is held 40 ms, so it is let go at 40): the window is closed at both ends,
    // and the slop is as far apart as two taps of a pair may be.
    @Test fun aSecondTapDownExactlyAtTheDoubleTapTimeAfterTheFirstIsLetGoMakesAPairAndOneMsLaterDoesNot() {
        assertEquals(listOf(false, true), steps(tap(0), tap(40 + 300)))
        assertEquals(listOf(false, false), steps(tap(0), tap(40 + 301)))
    }
    @Test fun aSecondTapDownExactlyAtTheMinimumMakesAPairAndOneMsSoonerDoesNot() {
        assertEquals(listOf(false, true), steps(tap(0), tap(40 + 40)))
        assertEquals(listOf(false, false), steps(tap(0), tap(40 + 39)))
    }
    @Test fun twoTapsExactlyTheSlopApartMakeAPairAndFurtherDoNot() {
        assertEquals(listOf(false, true), steps(tap(0, x = 100f), tap(140, x = 100f + slop)))
        assertEquals(listOf(false, false), steps(tap(0, x = 100f), tap(140, x = 100f + slop + 0.5f)))
    }
    @Test fun aSecondTapDownBeforeTheFirstIsLetGoIsNoPairAndWaitsInItsTurn() {
        // Two fingers: the second goes down 20 ms before the first is let go (a gap of -20 ms).
        val first = DialTap(0, 60, 100f, 100f, inViewAtDown = true, inViewAtUp = true)
        val second = DialTap(40, 100, 100f, 100f, inViewAtDown = true, inViewAtUp = true)
        val step = nextTap(first, second, timeoutMs = 300, minMs = 40, slopPx = slop)
        assertFalse(step.saves)
        assertEquals(second, step.waiting)
    }
    @Test fun aPairIsJudgedByTheFirstTapsViewWhenItWentDownAndTheSecondsWhenItWasLetGo() {
        fun t(downMs: Long, atDown: Boolean, atUp: Boolean) = DialTap(downMs, downMs + 40, 100f, 100f, atDown, atUp)
        // In view where it counts, out of view where it does not: saves.
        assertTrue(nextTap(t(0, atDown = true, atUp = false), t(140, atDown = false, atUp = true), 300, 40, slop).saves)
        // Out of view where it counts: no save, and nothing left waiting either way.
        val firstOut = nextTap(t(0, atDown = false, atUp = true), t(140, atDown = true, atUp = true), 300, 40, slop)
        assertFalse(firstOut.saves); assertNull(firstOut.waiting)
        val secondOut = nextTap(t(0, atDown = true, atUp = true), t(140, atDown = true, atUp = false), 300, 40, slop)
        assertFalse(secondOut.saves); assertNull(secondOut.waiting)
    }
    @Test fun aPairWithTheCompassOutOfViewSavesNothingAndLeavesNothingWaiting() {
        val step = nextTap(tap(0, inView = false), tap(140), timeoutMs = 300, minMs = 40, slopPx = slop)
        assertFalse(step.saves)
        assertNull(step.waiting)
    }

    @Test fun theAccessibilityActionIsNamedAsTheSpecProposes() {
        assertEquals("Save waypoint here", SAVE_HERE_ACTION)
        // The dial it is an action of is named for a screen reader with the app's name.
        assertEquals("Compass", DIAL_NAME)
    }
}
