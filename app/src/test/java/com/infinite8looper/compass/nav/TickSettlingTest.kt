package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/**
 * The taps over whole timelines. [Frames] steps the same pure functions the compass screen's frame loop calls,
 * in the same order: smooth the shown heading, decide whether the dial is settling, follow the tick cell, tap.
 */
class TickSettlingTest {
    private class Frames {
        var nowNanos = 1_000_000_000L
        var shown = Float.NaN
        var taps = 0
        var settling = TickSettling.Settled
        private var cell = TickCell.Unset
        private var targetCell = TickCell.Unset
        private var lastTap = 0L
        private var hold = false

        /** One display frame [dtMs] after the last, with the sensor's heading at [target]. */
        fun frame(target: Float, dtMs: Long = 16, holdFlat: Boolean = false, referenceShiftDeg: Float = 0f, stale: Boolean = false) {
            nowNanos += dtMs * 1_000_000L
            val first = shown.isNaN()
            val leftHoldFlat = hold && !holdFlat
            hold = holdFlat
            if (!holdFlat) shown = if (first) target else smoothAngle(shown, target, dtMs / 1000f)
            val disturbed = isTickDisturbance(first, stale, dtMs * 1_000_000L, leftHoldFlat, referenceShiftDeg)
            val next = nextTickCell(cell, shown)
            targetCell = nextTickCell(targetCell, target)
            val wasSettling = settling.active
            settling = nextTickSettling(settling, disturbed, nowNanos, next, targetCell)
            // The frame on which the dial arrives is still part of the catch-up.
            if (isTickCrossing(cell, next) && !holdFlat && !wasSettling && !settling.active && tickTapDue(nowNanos, lastTap)) {
                taps++; lastTap = nowNanos
            }
            cell = next
        }
        fun rest(target: Float, ms: Long, holdFlat: Boolean = false) { repeat((ms / 16).toInt()) { frame(target, holdFlat = holdFlat) } }
        /** A real turn at [degPerSecond] from [from] to [to], then a second at rest so the dial arrives. */
        fun turn(from: Float, to: Float, degPerSecond: Float) {
            val step = degPerSecond * 0.016f * if (shortestDelta(from, to) < 0) -1 else 1
            var h = from
            while (kotlin.math.abs(shortestDelta(h, to)) > kotlin.math.abs(step)) { h = normalizeDeg(h + step); frame(h) }
            rest(to, 1000)
        }
    }

    @Test fun firstReadingThenASwingOfFortyOneDegreesGivesNoTapsAndTapsResumeOnceCaughtUp() {
        val f = Frames()
        f.frame(100f)
        f.rest(141f, 2000)
        assertEquals(0, f.taps)
        assertFalse(f.settling.active)
        assertEquals(141f, f.shown, 0.1f)
        // The lines at 142, 144, 146, 148 and 150.
        f.turn(141f, 150.5f, 10f)
        assertEquals(5, f.taps)
    }
    @Test fun leavingHoldFlatAfterTheHeadingChangedBySixtyOneDegreesGivesNoneDuringTheCatchUp() {
        val f = Frames()
        f.frame(100f); f.rest(100f, 2000)
        f.rest(161f, 1000, holdFlat = true)
        assertEquals(100f, f.shown, 0.01f)
        f.rest(161f, 2000)
        assertEquals(0, f.taps)
        assertEquals(161f, f.shown, 0.1f)
        f.turn(161f, 170.5f, 10f)
        assertEquals(5, f.taps)
    }
    @Test fun frameGapOfFourHundredMillisecondsFollowedByAJumpGivesNone() {
        val f = Frames()
        f.frame(100f); f.rest(100f, 2000)
        f.frame(130f, dtMs = 400)
        f.rest(130f, 2000)
        assertEquals(0, f.taps)
        // The reading that is already there after a pause is the old one; the new one comes a few frames later.
        val g = Frames()
        g.frame(100f); g.rest(100f, 2000)
        g.frame(100f, dtMs = 5000)
        g.rest(100f, 96)
        g.rest(250f, 2000)
        assertEquals(0, g.taps)
        assertEquals(250f, g.shown, 0.1f)
        // However late the new reading comes, the dial is quiet until it has come and been caught up with.
        val h = Frames()
        h.frame(100f); h.rest(100f, 2000)
        h.frame(100f, dtMs = 5000, stale = true)
        repeat(200) { h.frame(100f, stale = true) }
        assertTrue(h.settling.active)
        h.rest(251f, 2000)
        assertEquals(0, h.taps)
        assertFalse(h.settling.active)
        h.turn(251f, 260.5f, 10f)
        assertEquals(5, h.taps)
    }
    @Test fun declinationChangeOfThirteenPointSixDegreesGivesNone() {
        val f = Frames()
        f.frame(100f); f.rest(100f, 2000)
        f.frame(113.6f, referenceShiftDeg = 13.6f)
        f.rest(113.6f, 2000)
        assertEquals(0, f.taps)
        val g = Frames()
        g.frame(100f); g.rest(100f, 2000)
        g.frame(86.4f, referenceShiftDeg = -13.6f)
        g.rest(86.4f, 2000)
        assertEquals(0, g.taps)
    }
    @Test fun afterSettlingARealTenDegreeTurnGivesItsTaps() {
        val f = Frames()
        f.frame(100f); f.rest(100f, 2000)
        assertFalse(f.settling.active)
        f.turn(100f, 110.5f, 10f)
        assertEquals(5, f.taps)
        f.turn(110.5f, 100.5f, 10f)
        assertEquals(10, f.taps)
    }
    @Test fun slowContinuousTurnThatBeginsBeforeSettlingCompletesDoesNotStaySilent() {
        // 5 degrees a second from the very first reading, for six seconds: 30 degrees, 15 lines. The dial is
        // never more than a degree behind, so the quiet time ends at its shortest and only the first line or two are lost.
        val f = Frames()
        f.frame(100f)
        f.turn(100f, 130.5f, 5f)
        assertFalse(f.settling.active)
        assertTrue("taps: ${f.taps}", f.taps in 13..15)
    }
    @Test fun fastContinuousTurnThatBeginsBeforeSettlingCompletesIsQuietForAtMostTheLongestSettlingTime() {
        // 60 degrees a second keeps the dial about 7 degrees behind, which never counts as caught up.
        val f = Frames()
        f.frame(100f)
        var h = 100f
        val start = f.nowNanos
        while (f.nowNanos - start < TICK_SETTLE_MAX_NANOS - 20_000_000L) { h = normalizeDeg(h + 0.96f); f.frame(h) }
        assertEquals(0, f.taps)
        assertTrue(f.settling.active)
        repeat(63) { h = normalizeDeg(h + 0.96f); f.frame(h) }
        assertFalse(f.settling.active)
        assertTrue("taps: ${f.taps}", f.taps >= 10)
    }
    @Test fun halfTurnJumpIsCaughtUpBeforeTheLongestSettlingTime() {
        // The longest catch-up there is must fit, or its last lines would tap.
        val f = Frames()
        f.frame(0f); f.rest(0f, 2000)
        f.frame(179f, dtMs = 300)
        val start = f.nowNanos
        while (f.settling.active) f.frame(179f)
        assertTrue(f.nowNanos - start < TICK_SETTLE_MAX_NANOS)
        assertEquals(179f, f.shown, 1f)
        f.rest(179f, 2000)
        assertEquals(0, f.taps)
    }

    @Test fun arrivingNextToATickAfterACatchUpGivesNoTapForTheLastStretch() {
        // The dial ends 0.4 past the line at 140. It is "caught up" only once it is in the cell it will stay in.
        for (target in listOf(140.4f, 140.9f, 139.6f, 141.9f, 138.1f, 0.4f, 359.6f)) {
            val f = Frames()
            f.frame(100f); f.rest(100f, 2000)
            f.frame(target, dtMs = 300)
            f.rest(target, 3000)
            assertEquals("target $target", 0, f.taps)
            assertFalse(f.settling.active)
        }
    }

    @Test fun disturbances() {
        val frame = 16_000_000L
        fun disturbed(first: Boolean = false, stale: Boolean = false, gap: Long = frame, leftHold: Boolean = false, shift: Float = 0f) =
            isTickDisturbance(first, stale, gap, leftHold, shift)
        assertFalse(disturbed())
        assertTrue(disturbed(first = true))
        assertTrue(disturbed(stale = true))
        assertTrue(disturbed(leftHold = true))
        assertTrue(disturbed(gap = 251_000_000L))
        assertFalse(disturbed(gap = 100_000_000L))
        assertTrue(disturbed(shift = 13.6f))
        assertTrue(disturbed(shift = -13.6f))
        // The declination moves by hundredths of a degree with every new position; that is not a disturbance.
        assertFalse(disturbed(shift = 0.05f))
        assertFalse(disturbed(shift = Float.NaN))
    }
    @Test fun settlingStartsOnADisturbanceAndEndsWhenTheDialIsInTheHeadingsCell() {
        val t0 = 5_000_000_000L
        val ms = 1_000_000L
        fun cell(deg: Float) = nextTickCell(TickCell.Unset, deg)
        assertFalse(TickSettling.Settled.active)
        assertEquals(TickSettling.Settled, nextTickSettling(TickSettling.Settled, false, t0, cell(10f), cell(50f)))
        val s = nextTickSettling(TickSettling.Settled, true, t0, cell(10f), cell(50f))
        assertTrue(s.active)
        // In the right cell, but too soon after the disturbance to trust the reading: still quiet.
        assertTrue(nextTickSettling(s, false, t0 + 100 * ms, cell(50.5f), cell(51f)).active)
        assertFalse(nextTickSettling(s, false, t0 + TICK_SETTLE_MIN_NANOS, cell(50.5f), cell(51f)).active)
        assertTrue(nextTickSettling(s, false, t0 + TICK_SETTLE_MIN_NANOS, cell(49.5f), cell(50.5f)).active)
        assertTrue(nextTickSettling(s, false, t0 + TICK_SETTLE_MIN_NANOS, cell(359f), cell(1f)).active)
        assertFalse(nextTickSettling(s, false, t0 + TICK_SETTLE_MIN_NANOS, cell(0.1f), cell(1.5f)).active)
        assertFalse(nextTickSettling(s, false, t0 + TICK_SETTLE_MAX_NANOS, cell(10f), cell(50f)).active)
        // A second disturbance starts the time again.
        val again = nextTickSettling(s, true, t0 + 600 * ms, cell(50f), cell(50f))
        assertTrue(again.active)
        assertTrue(nextTickSettling(again, false, t0 + 900 * ms, cell(50f), cell(50f)).active)
        assertFalse(nextTickSettling(again, false, t0 + 1000 * ms, cell(50f), cell(50f)).active)
        // Without a heading there is no cell to arrive in, and the longest time still ends it.
        assertTrue(nextTickSettling(s, false, t0 + TICK_SETTLE_MIN_NANOS, TickCell.Unset, TickCell.Unset).active)
        assertFalse(nextTickSettling(s, false, t0 + TICK_SETTLE_MAX_NANOS, TickCell.Unset, TickCell.Unset).active)
    }
}
