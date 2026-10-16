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

    // A touch on the wheel, sample by sample. The screen's radius is 100, the wheel's band starts at 86, a drag
    // begins 8 from where the finger came down, and a drag may stray 16 inside the band.
    private fun step(touch: WheelTouch, downX: Float, downY: Float, x: Float, y: Float) =
        nextWheelTouch(touch, downX, downY, x, y, screenRadius = 100f, slop = 8f, leaveTolerance = 16f)
    private fun follow(downX: Float, downY: Float, vararg path: Pair<Float, Float>): List<WheelTouch> {
        var touch = WheelTouch.UNDECIDED
        return path.map { (x, y) -> step(touch, downX, downY, x, y).also { touch = it } }
    }
    private fun onRing(degrees: Float, radius: Float = 92f): Pair<Float, Float> {
        val a = Math.toRadians(degrees.toDouble())
        return (radius * Math.sin(a)).toFloat() to (-radius * Math.cos(a)).toFloat()
    }

    @Test fun aTouchThatHasNotMovedFarIsUndecided() {
        assertEquals(WheelTouch.UNDECIDED, step(WheelTouch.UNDECIDED, 0f, -92f, 0f, -92f))
        assertEquals(WheelTouch.UNDECIDED, step(WheelTouch.UNDECIDED, 0f, -92f, 7.9f, -92f))
        assertEquals(WheelTouch.UNDECIDED, step(WheelTouch.UNDECIDED, 0f, -92f, 8f, -92f))
        assertEquals(WheelTouch.RECOLOR, step(WheelTouch.UNDECIDED, 0f, -92f, 8.1f, -92f))
    }

    @Test fun aDragRoundTheWheelRecolorsAllTheWay() {
        val (dx, dy) = onRing(30f)
        val states = follow(dx, dy, *(31..120).map { onRing(it.toFloat()) }.toTypedArray())
        assertEquals(WheelTouch.UNDECIDED, states.first())
        assertEquals(WheelTouch.RECOLOR, states.last())
        assertTrue(states.dropWhile { it == WheelTouch.UNDECIDED }.all { it == WheelTouch.RECOLOR })
    }

    @Test fun aSwipeInFromTheEdgeIsNeverTheWheels() {
        val states = follow(-95f, 0f, -90f to 0f, -80f to 0f, -40f to 0f, 0f to 0f, 60f to 0f, 92f to 0f)
        assertEquals(WheelTouch.UNDECIDED, states[0])
        assertTrue(states.drop(1).all { it == WheelTouch.NOT_THE_WHEELS })
    }

    @Test fun aDragThatLeavesTheWheelInwardIsGivenUpAndStaysSoOnComingBack() {
        // Along the wheel from 9 o'clock, then in to the middle, then out to the band again and round it.
        val states = follow(-92f, 0f, -92f to -12f, -90f to -20f, -71f to -15f, -68f to -10f, -30f to 0f, -92f to 0f, -92f to 20f, -65f to 65f)
        assertEquals(listOf(WheelTouch.RECOLOR, WheelTouch.RECOLOR, WheelTouch.RECOLOR), states.take(3))
        // 70 from the center is the limit (86 - 16); just inside it the drag has left.
        assertEquals(WheelTouch.GIVEN_UP, states[3])
        assertTrue(states.drop(3).all { it == WheelTouch.GIVEN_UP })
    }

    @Test fun aDragThatGoesOutOverTheRimStillRecolors() {
        val states = follow(0f, -92f, 12f to -92f, 30f to -100f, 60f to -120f, 80f to -140f)
        assertTrue(states.all { it == WheelTouch.RECOLOR })
    }

    @Test fun aTouchThatIsNotTheWheelsStaysSo() {
        assertEquals(WheelTouch.NOT_THE_WHEELS, step(WheelTouch.NOT_THE_WHEELS, -95f, 0f, -92f, 30f))
        assertEquals(WheelTouch.NOT_THE_WHEELS, step(WheelTouch.NOT_THE_WHEELS, -95f, 0f, 0f, -92f))
    }

    @Test fun aSampleWithNoPlaceChangesNothing() {
        assertEquals(WheelTouch.UNDECIDED, step(WheelTouch.UNDECIDED, 0f, -92f, Float.NaN, -92f))
        assertEquals(WheelTouch.RECOLOR, step(WheelTouch.RECOLOR, 0f, -92f, Float.NaN, Float.NaN))
    }

    @Test fun theColorFollowsTheFingerOnlyWhileTheDragRecolors() {
        val (x, y) = onRing(120f)
        assertEquals(120f, wheelTouchHue(WheelTouch.RECOLOR, hueAtDown = 30f, hueNow = 90f, dx = x, dy = y, screenRadius = 100f), 1e-2f)
        assertEquals(90f, wheelTouchHue(WheelTouch.UNDECIDED, 30f, 90f, x, y, 100f), 0f)
        assertEquals(90f, wheelTouchHue(WheelTouch.NOT_THE_WHEELS, 30f, 90f, x, y, 100f), 0f)
    }

    @Test fun liftedBeyondTheRimTheColorIsKept() {
        // The drag went round to 120 and out over the rim there: the last color drawn stands.
        val (x, y) = onRing(120f, radius = 130f)
        assertEquals(WheelTouch.RECOLOR, step(WheelTouch.RECOLOR, 0f, -92f, x, y))
        assertEquals(120f, wheelTouchHue(WheelTouch.RECOLOR, hueAtDown = 30f, hueNow = 118f, dx = x, dy = y, screenRadius = 100f), 1e-2f)
    }

    @Test fun onceGivenUpTheColorIsWhatItWasWhenTheFingerCameDown() {
        val (x, y) = onRing(200f)
        assertEquals(30f, wheelTouchHue(WheelTouch.GIVEN_UP, hueAtDown = 30f, hueNow = 150f, dx = x, dy = y, screenRadius = 100f), 0f)
        assertEquals(30f, wheelTouchHue(WheelTouch.GIVEN_UP, 30f, 150f, 0f, 0f, 100f), 0f)
        // Lifted in the middle after a drag that left the wheel: the whole touch ends on the color it began with.
        var touch = WheelTouch.UNDECIDED
        var hue = 30f
        for ((px, py) in listOf(-92f to -12f, -90f to -20f, -60f to -10f, 0f to 0f)) {
            touch = step(touch, -92f, 0f, px, py)
            hue = wheelTouchHue(touch, 30f, hue, px, py, 100f)
        }
        assertEquals(WheelTouch.GIVEN_UP, touch)
        assertEquals(30f, hue, 0f)
    }

    @Test fun aRecoloringFingerNearTheCenterKeepsTheLastColor() {
        assertEquals(90f, wheelTouchHue(WheelTouch.RECOLOR, 30f, 90f, 5f, 5f, 100f), 0f)
    }

    @Test fun onlyAQuickTouchThatNeverMovedIsATap() {
        assertTrue(wheelTapPicks(WheelTouch.UNDECIDED, heldMs = 120, longPressMs = 400))
        assertTrue(wheelTapPicks(WheelTouch.UNDECIDED, 399, 400))
        assertFalse(wheelTapPicks(WheelTouch.UNDECIDED, 400, 400))
        assertFalse(wheelTapPicks(WheelTouch.UNDECIDED, 900, 400))
        assertFalse(wheelTapPicks(WheelTouch.RECOLOR, 120, 400))
        assertFalse(wheelTapPicks(WheelTouch.GIVEN_UP, 120, 400))
        assertFalse(wheelTapPicks(WheelTouch.NOT_THE_WHEELS, 120, 400))
        assertFalse(wheelTapPicks(WheelTouch.UNDECIDED, -5, 400))
    }

    @Test fun aCrownTurnStepsTheFocusOnceItHasGoneFurtherThanTheStep() {
        // Small scrolls add up; nothing steps until they have gone further than the step.
        assertEquals(CrownTurn(0, 30f), crownTurn(0f, 30f, 80f))
        assertEquals(CrownTurn(0, 80f), crownTurn(30f, 50f, 80f))
        assertEquals(CrownTurn(1, 0f), crownTurn(80f, 1f, 80f))
        assertEquals(CrownTurn(-1, 0f), crownTurn(-60f, -30f, 80f))
        // One click of a crown with detents is more than a step and is one step, with nothing left over for the next.
        assertEquals(CrownTurn(1, 0f), crownTurn(0f, 128f, 80f))
        assertEquals(CrownTurn(-1, 0f), crownTurn(0f, -128f, 80f))
        assertEquals(CrownTurn(1, 0f), crownTurn(0f, 500f, 80f))
    }

    @Test fun aCrownTurnedBackStartsOverFromWhereItTurned() {
        // 70 one way and then back: what was gathered the first way is dropped, so the turn back steps after going
        // further than one step, not after undoing the 70 first.
        assertEquals(CrownTurn(0, -30f), crownTurn(70f, -30f, 80f))
        assertEquals(CrownTurn(-1, 0f), crownTurn(-30f, -51f, 80f))
        assertEquals(CrownTurn(0, 20f), crownTurn(-79f, 20f, 80f))
        assertEquals(CrownTurn(-1, 0f), crownTurn(70f, -81f, 80f))
        // A scroll of nothing changes nothing.
        assertEquals(CrownTurn(0, 70f), crownTurn(70f, 0f, 80f))
        assertEquals(CrownTurn(0, -70f), crownTurn(-70f, 0f, 80f))
    }

    @Test fun aCrownTurnThatIsNotANumberIsDropped() {
        assertEquals(CrownTurn(0, 40f), crownTurn(40f, Float.NaN, 80f))
        assertEquals(CrownTurn(0, 10f), crownTurn(Float.NaN, 10f, 80f))
    }

    @Test fun theTwoPartsOfACrownTurnAreWhatTheTurnIs() {
        for (carried in listOf(0f, 30f, 79f, 80f, -60f, -79f, Float.NaN)) for (scrolled in listOf(0f, 1f, 51f, 128f, -30f, -81f, -500f, Float.NaN)) {
            val turn = crownTurn(carried, scrolled, 80f)
            assertEquals("$carried, $scrolled", turn.steps, crownSteps(carried, scrolled, 80f))
            assertEquals("$carried, $scrolled", turn.carried, crownCarried(carried, scrolled, 80f), 0f)
        }
        // A scroll of exactly a step is not further than a step.
        assertEquals(0, crownSteps(0f, 80f, 80f)); assertEquals(80f, crownCarried(0f, 80f, 80f), 0f)
    }
}
