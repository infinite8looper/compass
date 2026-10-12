package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class ControlsTest {
    @Test fun holdProgressRunsFromNothingToAllOverTheHold() {
        assertEquals(0f, holdProgress(0), 0f)
        assertEquals(0.5f, holdProgress(500), 1e-6f)
        assertEquals(0.8f, holdProgress(800), 1e-6f)
        assertEquals(0.999f, holdProgress(999), 1e-6f)
        assertEquals(1f, holdProgress(1000), 0f)
    }

    @Test fun holdProgressIsCompleteOnlyAtTheFullTime() {
        assertTrue(holdProgress(999) < 1f)
        assertTrue(holdProgress(1000) >= 1f)
        assertTrue(holdProgress(1499, 1500) < 1f)
        assertEquals(1f, holdProgress(1500, 1500), 0f)
    }

    @Test fun holdProgressStaysWithinItsRange() {
        assertEquals(0f, holdProgress(-1), 0f)
        assertEquals(0f, holdProgress(Long.MIN_VALUE), 0f)
        assertEquals(1f, holdProgress(1001), 0f)
        assertEquals(1f, holdProgress(Long.MAX_VALUE), 0f)
    }

    @Test fun holdProgressWithNoHoldTimeIsCompleteAtOnce() {
        assertEquals(1f, holdProgress(0, 0), 0f)
        assertEquals(1f, holdProgress(5, -10), 0f)
        assertEquals(0f, holdProgress(-5, 0), 0f)
    }

    @Test fun headerTextCountsTheTargetsAmongTheWaypoints() {
        assertEquals("3 of 4 targets", headerText(3, 4))
        assertEquals("0 of 4 targets", headerText(0, 4))
        assertEquals("4 of 4 targets", headerText(4, 4))
        assertEquals("1 of 4 targets", headerText(1, 4))
        assertEquals("30 of 30 targets", headerText(30, 30))
    }

    @Test fun headerTextWithOneWaypointSaysTarget() {
        assertEquals("1 of 1 target", headerText(1, 1))
        assertEquals("0 of 1 target", headerText(0, 1))
        // More than one waypoint is "targets" whatever the number that are set.
        assertEquals("1 of 2 targets", headerText(1, 2))
        assertEquals("0 of 2 targets", headerText(0, 2))
        assertEquals("2 of 2 targets", headerText(2, 2))
    }

    @Test fun headerTextWithNoWaypointsSaysSo() {
        assertEquals("No waypoints yet", headerText(0, 0))
    }

    @Test fun headerTextKeepsItsCountsWithinWhatThereIs() {
        assertEquals("4 of 4 targets", headerText(9, 4))
        assertEquals("0 of 4 targets", headerText(-2, 4))
        assertEquals("No waypoints yet", headerText(3, 0))
        assertEquals("No waypoints yet", headerText(0, -1))
    }

    @Test fun aHeaderTapSetsAllUnlessAllAreSet() {
        assertTrue(headerTapSetsAll(0, 4))
        assertTrue(headerTapSetsAll(3, 4))
        assertFalse(headerTapSetsAll(4, 4))
        assertFalse(headerTapSetsAll(1, 1))
        assertTrue(headerTapSetsAll(0, 1))
        // With no waypoints there is nothing to set or clear; "set" changes nothing.
        assertTrue(headerTapSetsAll(0, 0))
    }

    @Test fun aTouchStartsOnTheWheelFromItsBandOutward() {
        val r = 100f
        assertTrue(startsOnWheel(WHEEL_BAND_INNER * r, r, 0f))
        assertTrue(startsOnWheel(0.90f * r, r, 0f))
        assertTrue(startsOnWheel(WHEEL_BAND_OUTER * r, r, 0f))
        // Past the band there is only the rim of the screen, and a finger there is on the wheel too.
        assertTrue(startsOnWheel(r, r, 0f))
        assertTrue(startsOnWheel(1.3f * r, r, 0f))
        assertFalse(startsOnWheel(WHEEL_BAND_INNER * r - 0.5f, r, 0f))
        assertFalse(startsOnWheel(0f, r, 0f))
    }

    @Test fun theWheelReachesInwardByTheTolerance() {
        val r = 100f
        val edge = WHEEL_BAND_INNER * r
        assertTrue(startsOnWheel(edge - 8f, r, 8f))
        assertTrue(startsOnWheel(edge - 7.9f, r, 8f))
        assertFalse(startsOnWheel(edge - 8.1f, r, 8f))
        // The middle of the screen, where a swipe back starts, is never on the wheel.
        assertFalse(startsOnWheel(0.6f * r, r, 8f))
        assertFalse(startsOnWheel(0f, r, 8f))
        // A tolerance below nothing is none.
        assertFalse(startsOnWheel(edge - 1f, r, -5f))
        assertTrue(startsOnWheel(edge, r, -5f))
    }

    @Test fun aTouchWithNoPlaceIsNotOnTheWheel() {
        assertFalse(startsOnWheel(Float.NaN, 100f, 8f))
        assertFalse(startsOnWheel(90f, Float.NaN, 8f))
        assertFalse(startsOnWheel(90f, 0f, 8f))
        assertFalse(startsOnWheel(90f, 100f, Float.NaN))
    }

    @Test fun wheelHueIsZeroAtTwelveAndGrowsClockwise() {
        val r = 100f
        assertEquals(0f, wheelHue(0f, -90f, r)!!, 1e-3f)
        assertEquals(90f, wheelHue(90f, 0f, r)!!, 1e-3f)
        assertEquals(180f, wheelHue(0f, 90f, r)!!, 1e-3f)
        assertEquals(270f, wheelHue(-90f, 0f, r)!!, 1e-3f)
        assertEquals(45f, wheelHue(60f, -60f, r)!!, 1e-3f)
        assertEquals(315f, wheelHue(-60f, -60f, r)!!, 1e-3f)
    }

    @Test fun wheelHueStaysBelowAFullTurn() {
        val h = wheelHue(-0.0001f, -90f, 100f)!!
        assertTrue(h >= 0f && h < 360f)
    }

    @Test fun wheelHueNearTheCenterIsNone() {
        val r = 100f
        assertNull(wheelHue(0f, 0f, r))
        assertNull(wheelHue(10f, -10f, r))
        assertNull(wheelHue(0f, WHEEL_DEAD_CENTER * r - 0.5f, r))
        assertNotNull(wheelHue(0f, WHEEL_DEAD_CENTER * r + 0.5f, r))
        assertNull(wheelHue(Float.NaN, 50f, r))
        assertNull(wheelHue(50f, 50f, 0f))
    }

    @Test fun theFirstColorChangeIsWrittenAtOnce() {
        assertEquals(0L, colorWriteWaitMs(5_000, null))
    }

    @Test fun aColorChangeSoonAfterAWriteWaitsOutTheGap() {
        assertEquals(250L, colorWriteWaitMs(1_000, 1_000))
        assertEquals(249L, colorWriteWaitMs(1_001, 1_000))
        assertEquals(1L, colorWriteWaitMs(1_249, 1_000))
        assertEquals(0L, colorWriteWaitMs(1_250, 1_000))
        assertEquals(0L, colorWriteWaitMs(9_000, 1_000))
        assertEquals(60L, colorWriteWaitMs(1_040, 1_000, gapMs = 100))
    }

    @Test fun aWriteTimeAheadOfTheClockNeverWaitsLongerThanTheGap() {
        assertEquals(250L, colorWriteWaitMs(1_000, 5_000))
        assertEquals(0L, colorWriteWaitMs(1_000, 900, gapMs = 0))
        assertEquals(0L, colorWriteWaitMs(1_000, 1_000, gapMs = -5))
    }

    @Test fun writesFollowingTheWaitAreNoCloserThanTheGap() {
        // A change every 16 ms for two seconds, each written when its wait is over or dropped for the next.
        var last: Long? = null
        val writes = mutableListOf<Long>()
        var now = 0L
        while (now <= 2_000) {
            if (colorWriteWaitMs(now, last) == 0L) { writes += now; last = now }
            now += 16
        }
        assertTrue(writes.size in 7..9)
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a >= COLOR_WRITE_GAP_MS })
    }

    @Test fun noScrollArcWhenEverythingFits() {
        assertNull(scrollArc(0f, 1f))
        assertNull(scrollArc(0.5f, 1.4f))
        assertNull(scrollArc(Float.NaN, 0.5f))
        assertNull(scrollArc(0.5f, Float.NaN))
        assertNull(scrollArc(0.5f, 0f))
    }

    @Test fun theScrollArcIsAsLongAsThePartInView() {
        val top = scrollArc(0f, 0.5f)!!
        assertEquals(0f, top.start, 1e-6f); assertEquals(0.5f, top.end, 1e-6f)
        val bottom = scrollArc(1f, 0.5f)!!
        assertEquals(0.5f, bottom.start, 1e-6f); assertEquals(1f, bottom.end, 1e-6f)
        val middle = scrollArc(0.5f, 0.5f)!!
        assertEquals(0.25f, middle.start, 1e-6f); assertEquals(0.75f, middle.end, 1e-6f)
    }

    @Test fun theScrollArcIsNeverShorterThanItsLeastLength() {
        val a = scrollArc(0f, 0.01f)!!
        assertEquals(SCROLL_ARC_MIN, a.end - a.start, 1e-6f)
        val b = scrollArc(1f, 0.01f)!!
        assertEquals(1f, b.end, 1e-6f); assertEquals(1f - SCROLL_ARC_MIN, b.start, 1e-6f)
    }

    @Test fun theScrollArcStaysOnItsTrack() {
        val before = scrollArc(-0.3f, 0.25f)!!
        assertEquals(0f, before.start, 1e-6f)
        val after = scrollArc(1.7f, 0.25f)!!
        assertEquals(1f, after.end, 1e-6f)
    }

    @Test fun aRowAtTheMiddleReachesTheRing() {
        assertEquals(0f, rowInset(0f, 100f), 1e-4f)
    }

    @Test fun aRowAwayFromTheMiddleComesInAsFarAsTheRingDoes() {
        // A 3-4-5 triangle: 60 above the center of a ring of 100 the ring is 80 from the middle line.
        assertEquals(20f, rowInset(60f, 100f), 1e-3f)
        assertEquals(20f, rowInset(-60f, 100f), 1e-3f)
        assertTrue(rowInset(30f, 100f) < rowInset(50f, 100f))
    }

    @Test fun aRowNearTheTopOrBottomComesInNoFurtherThanAtTheLimit() {
        val limit = rowInset(ROW_INSET_MAX_Y * 100f, 100f)
        assertEquals(limit, rowInset(90f, 100f), 1e-4f)
        assertEquals(limit, rowInset(-100f, 100f), 1e-4f)
        assertEquals(limit, rowInset(400f, 100f), 1e-4f)
        assertTrue(limit > 0f && limit < 100f)
    }

    @Test fun aRowWithNoPlaceIsNotMoved() {
        assertEquals(0f, rowInset(Float.NaN, 100f), 0f)
        assertEquals(0f, rowInset(10f, 0f), 0f)
        assertEquals(0f, rowInset(10f, Float.NaN), 0f)
    }

    @Test fun aDragThatStartsAlongTheWheelIsTheWheels() {
        // Down at 12 o'clock, 90 above the center: along the wheel there is sideways.
        assertTrue(isAlongWheel(0f, -90f, 10f, 0f))
        assertTrue(isAlongWheel(0f, -90f, -10f, 0f))
        assertTrue(isAlongWheel(0f, -90f, 10f, 4f))
        // Down at 9 o'clock: along the wheel is up or down.
        assertTrue(isAlongWheel(-90f, 0f, 0f, 10f))
        assertTrue(isAlongWheel(-90f, 0f, 3f, -10f))
    }

    @Test fun aDragThatStartsTowardTheCenterIsNotTheWheels() {
        // The swipe that goes back, from the bezel at 9 o'clock.
        assertFalse(isAlongWheel(-90f, 0f, 10f, 0f))
        assertFalse(isAlongWheel(-90f, 0f, 10f, 4f))
        assertFalse(isAlongWheel(-90f, 0f, 10f, -9f))
        assertFalse(isAlongWheel(0f, -90f, 0f, 10f))
        assertFalse(isAlongWheel(64f, 64f, -7f, -7f))
    }

    @Test fun aDragThatStartsOutwardOrHalfWayIsTheWheels() {
        // Outward there is only the rim, which is the wheel's.
        assertTrue(isAlongWheel(-90f, 0f, -10f, 0f))
        assertTrue(isAlongWheel(-90f, 0f, -10f, 2f))
        // As much along as inward: the wheel's.
        assertTrue(isAlongWheel(-90f, 0f, 10f, 10f))
    }

    @Test fun aDragWithNoDirectionIsNotTheWheels() {
        assertFalse(isAlongWheel(0f, 0f, 10f, 0f))
        assertFalse(isAlongWheel(-90f, 0f, 0f, 0f))
        assertFalse(isAlongWheel(Float.NaN, 0f, 10f, 0f))
        assertFalse(isAlongWheel(-90f, 0f, Float.NaN, 0f))
    }

    @Test fun aDragStaysOnTheWheelInItsBandAndOnTheRim() {
        val r = 100f
        assertTrue(staysOnWheel(WHEEL_BAND_INNER * r, r, 0f))
        assertTrue(staysOnWheel(0.92f * r, r, 16f))
        assertTrue(staysOnWheel(WHEEL_BAND_OUTER * r, r, 16f))
        assertTrue(staysOnWheel(r, r, 16f))
        assertTrue(staysOnWheel(1.4f * r, r, 16f))
    }

    @Test fun aDragLeavesTheWheelInwardPastTheTolerance() {
        val r = 100f
        val edge = WHEEL_BAND_INNER * r
        assertTrue(staysOnWheel(edge - 16f, r, 16f))
        assertTrue(staysOnWheel(edge - 15.9f, r, 16f))
        assertFalse(staysOnWheel(edge - 16.1f, r, 16f))
        assertFalse(staysOnWheel(edge - 0.1f, r, 0f))
        assertFalse(staysOnWheel(0.5f * r, r, 16f))
        assertFalse(staysOnWheel(0f, r, 16f))
        // A tolerance below nothing is none.
        assertFalse(staysOnWheel(edge - 1f, r, -4f))
    }

    @Test fun aDragWithNoPlaceHasLeftTheWheel() {
        assertFalse(staysOnWheel(Float.NaN, 100f, 16f))
        assertFalse(staysOnWheel(90f, 0f, 16f))
        assertFalse(staysOnWheel(90f, Float.NaN, 16f))
        assertFalse(staysOnWheel(90f, 100f, Float.NaN))
    }

    @Test fun aTouchThatEndsMoreThanHalfTheScreenToTheRightGoesBack() {
        assertTrue(isBackSwipe(201f, 400f))
        assertTrue(isBackSwipe(390f, 400f))
        assertFalse(isBackSwipe(200f, 400f))
        assertFalse(isBackSwipe(120f, 400f))
        assertFalse(isBackSwipe(0f, 400f))
        assertFalse(isBackSwipe(-300f, 400f))
        assertFalse(isBackSwipe(Float.NaN, 400f))
        assertFalse(isBackSwipe(300f, 0f))
        assertFalse(isBackSwipe(300f, Float.NaN))
    }
}
