package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/**
 * Which of the dial's three vibrations a frame gives (v3 spec, "Vibrations"): thin for a line every 2 degrees,
 * thick for one every 30 degrees (the cardinal points among them), and the focused target's bearing, the strongest
 * kind that applies, with the existing limits kept.
 */
class DialCrossingTest {
    private fun cell(deg: Float) = nextTickCell(TickCell.Unset, deg)
    private fun side(heading: Float, target: Float?) = nextTargetSide(TargetSide.Unset, heading, target)

    /** The kind for a move of the shown heading from [from] to [to], one frame, with the target's bearing [target]. */
    private fun kind(from: Float, to: Float, target: Float? = null, tickFeedback: Boolean = true, blindNavigation: Boolean = false): DialCrossing {
        val cellBefore = cell(from); val sideBefore = side(from, target)
        return dialCrossing(
            cellBefore, nextTickCell(cellBefore, to), sideBefore, nextTargetSide(sideBefore, to, target),
            tickFeedback = tickFeedback, blindNavigation = blindNavigation,
        )
    }

    @Test fun aThinLineGivesThinAndAThickLineGivesThick() {
        assertEquals(DialCrossing.THIN, kind(141f, 142.5f))
        assertEquals(DialCrossing.THIN, kind(142.5f, 141f))
        assertEquals(DialCrossing.THICK, kind(149f, 150.5f))
        assertEquals(DialCrossing.THICK, kind(150.5f, 149f))
        assertEquals(DialCrossing.THIN, kind(151f, 152.5f))
        assertEquals(DialCrossing.THIN, kind(148.5f, 147f))
        // North and the other cardinal points are thick lines.
        assertEquals(DialCrossing.THICK, kind(359f, 0.5f))
        assertEquals(DialCrossing.THICK, kind(0.5f, 359f))
        assertEquals(DialCrossing.THICK, kind(89f, 90.5f))
        assertEquals(DialCrossing.THICK, kind(180.5f, 179f))
        assertEquals(DialCrossing.THICK, kind(269f, 270.5f))
        assertEquals(DialCrossing.THICK, kind(29f, 30.5f))
        assertEquals(DialCrossing.THICK, kind(330.5f, 329f))
        // No line passed, or a heading at rest.
        assertEquals(DialCrossing.NONE, kind(141f, 141.9f))
        assertEquals(DialCrossing.NONE, kind(141f, 141f))
    }

    @Test fun aMoveOverSeveralLinesIsThickWhenAnyOfThemIsThick() {
        assertEquals(DialCrossing.THICK, kind(147f, 153f))
        assertEquals(DialCrossing.THICK, kind(153f, 147f))
        assertEquals(DialCrossing.THICK, kind(356f, 3f))
        assertEquals(DialCrossing.THIN, kind(151f, 159f))
        assertEquals(DialCrossing.THIN, kind(159f, 151f))
        // The shorter way round is the way the dial went.
        assertEquals(DialCrossing.THIN, kind(2.5f, 7f))
    }

    @Test fun cellsAreTheExistingOnesSoTheMarginAgainstJitterStays() {
        // Resting on a line, or 0.2 past it and back, crosses nothing.
        val c = cell(141.5f)
        assertEquals(DialCrossing.NONE, dialCrossing(c, nextTickCell(c, 142.2f), TargetSide.Unset, TargetSide.Unset, true, false))
        assertEquals(DialCrossing.THIN, dialCrossing(c, nextTickCell(c, 142.31f), TargetSide.Unset, TargetSide.Unset, true, false))
        // Arriving from no heading at all is not a crossing.
        assertEquals(DialCrossing.NONE, dialCrossing(TickCell.Unset, cell(150.5f), TargetSide.Unset, TargetSide.Unset, true, false))
    }

    @Test fun passingTheTargetsBearingGivesTargetAndOutranksTheLines() {
        assertEquals(DialCrossing.TARGET, kind(124.5f, 125.5f, target = 125f))
        assertEquals(DialCrossing.TARGET, kind(125.5f, 124.5f, target = 125f))
        // A bearing on a line, thin or thick: one vibration, the target's.
        assertEquals(DialCrossing.TARGET, kind(141f, 142.5f, target = 142f))
        assertEquals(DialCrossing.TARGET, kind(149f, 150.5f, target = 150f))
        assertEquals(DialCrossing.TARGET, kind(359.5f, 0.5f, target = 0f))
        assertEquals(DialCrossing.TARGET, kind(0.5f, 359.5f, target = 0f))
        // A fast frame that jumps the bearing and a thick line together.
        assertEquals(DialCrossing.TARGET, kind(146f, 154f, target = 148f))
        // Not reached, or not passed: a line only.
        assertEquals(DialCrossing.THIN, kind(141f, 142.5f, target = 143f))
        assertEquals(DialCrossing.NONE, kind(124.5f, 124.9f, target = 125f))
    }

    @Test fun theTargetsSideHasAMarginSoJitterOnTheBearingGivesOneCrossing() {
        var s = side(124f, 125f)
        assertEquals(TargetSide.Below, s)
        // Within the margin of the bearing the side stays what it was.
        for (h in listOf(124.8f, 125.2f, 124.9f, 125.25f, 124.75f)) {
            val next = nextTargetSide(s, h, 125f)
            assertEquals(DialCrossing.NONE, dialCrossing(cell(125f), cell(125f), s, next, true, false))
            s = next
        }
        assertEquals(TargetSide.Below, s)
        val past = nextTargetSide(s, 125.31f, 125f)
        assertEquals(TargetSide.Above, past)
        assertEquals(DialCrossing.TARGET, dialCrossing(cell(125f), cell(125f), s, past, true, false))
        // Starting on the bearing: no side yet, and taking one is not a crossing.
        val on = side(125.1f, 125f)
        assertEquals(TargetSide.Unset, on)
        assertEquals(DialCrossing.NONE, dialCrossing(cell(125f), cell(125f), on, nextTargetSide(on, 126f, 125f), true, false))
    }

    @Test fun theOppositeDirectionIsNotTheTarget() {
        // 180 degrees from the bearing the side would flip; that is not passing the target.
        assertEquals(DialCrossing.THIN, kind(304.5f, 306.5f, target = 125f))
        assertEquals(DialCrossing.NONE, kind(304.5f, 305.5f, target = 125f))
        assertEquals(TargetSide.Unset, side(305.5f, 125f))
        assertEquals(TargetSide.Unset, side(216f, 125f))
        assertEquals(TargetSide.Above, side(214f, 125f))
        assertEquals(TargetSide.Below, side(36f, 125f))
    }

    @Test fun noTargetOrABearingThatIsNotANumberGivesNoTargetCrossing() {
        assertEquals(TargetSide.Unset, side(124f, null))
        assertEquals(TargetSide.Unset, side(124f, Float.NaN))
        assertEquals(TargetSide.Unset, nextTargetSide(TargetSide.Below, 126f, null))
        // A heading that is not a number changes nothing.
        assertEquals(TargetSide.Below, nextTargetSide(TargetSide.Below, Float.NaN, 125f))
        // The target going away while the heading passes where it was is not a crossing.
        assertEquals(DialCrossing.NONE, dialCrossing(cell(124.5f), cell(124.5f), TargetSide.Below, nextTargetSide(TargetSide.Below, 126f, null), true, false))
    }

    @Test fun anotherTargetStartsWithNoSide() {
        // The focus moves from a target the wearer had on one side to one on the other: nothing was passed.
        val before = side(124f, 125f)
        val after = nextTargetSide(before, 124f, 100f, targetChanged = true)
        assertEquals(TargetSide.Unset, after)
        assertEquals(DialCrossing.NONE, dialCrossing(cell(124f), cell(124f), before, after, true, false))
        // From the next frame it has its side, and passing it counts.
        val next = nextTargetSide(after, 124f, 100f)
        assertEquals(TargetSide.Above, next)
        assertEquals(DialCrossing.NONE, dialCrossing(cell(124f), cell(124f), after, next, true, false))
        assertEquals(DialCrossing.TARGET, dialCrossing(cell(101f), cell(99f), next, nextTargetSide(next, 99f, 100f), true, false))
    }

    @Test fun withBlindNavigationOnTheTargetGivesNoCrossingVibrationOfItsOwn() {
        // The lock pulse of v2 is the target's vibration then; the lines keep theirs.
        assertEquals(DialCrossing.NONE, kind(124.5f, 125.5f, target = 125f, blindNavigation = true))
        assertEquals(DialCrossing.THIN, kind(141f, 142.5f, target = 142f, blindNavigation = true))
        assertEquals(DialCrossing.THICK, kind(149f, 150.5f, target = 150f, blindNavigation = true))
    }

    @Test fun withTickFeedbackOffTheDialGivesNothing() {
        assertEquals(DialCrossing.NONE, kind(141f, 142.5f, tickFeedback = false))
        assertEquals(DialCrossing.NONE, kind(149f, 150.5f, tickFeedback = false))
        assertEquals(DialCrossing.NONE, kind(124.5f, 125.5f, target = 125f, tickFeedback = false))
        assertEquals(DialCrossing.NONE, kind(124.5f, 125.5f, target = 125f, tickFeedback = false, blindNavigation = true))
    }

    @Test fun theExistingLimitsStayForAllThreeKinds() {
        val ms = 1_000_000L
        val t = 10_000 * ms
        for (k in listOf(DialCrossing.THIN, DialCrossing.THICK, DialCrossing.TARGET)) {
            assertTrue("$k", dialVibrationDue(k, t, lastTapNanos = 0L, lastPulse = null, lastPulseNanos = 0L, lastTargetNanos = 0L))
            // At most one per 40 ms.
            assertFalse("$k", dialVibrationDue(k, t, lastTapNanos = t - 39 * ms, lastPulse = null, lastPulseNanos = 0L, lastTargetNanos = 0L))
            assertTrue("$k", dialVibrationDue(k, t, lastTapNanos = t - 40 * ms, lastPulse = null, lastPulseNanos = 0L, lastTargetNanos = 0L))
            // Not on, or right after, a lock pulse or a heartbeat.
            assertFalse("$k", dialVibrationDue(k, t, 0L, Pulse.LOCK, t - 399 * ms, 0L))
            assertTrue("$k", dialVibrationDue(k, t, 0L, Pulse.LOCK, t - 400 * ms, 0L))
            assertFalse("$k", dialVibrationDue(k, t, 0L, Pulse.HEARTBEAT, t, 0L))
            assertTrue("$k", dialVibrationDue(k, t, 0L, Pulse.HEARTBEAT, t - 150 * ms, 0L))
        }
        assertFalse(dialVibrationDue(DialCrossing.NONE, t, 0L, null, 0L, 0L))
        // A line right after the target's vibration is dropped, so the target's is not cut short.
        assertFalse(dialVibrationDue(DialCrossing.THIN, t, t - 50 * ms, null, 0L, lastTargetNanos = t - 50 * ms))
        assertFalse(dialVibrationDue(DialCrossing.THICK, t, t - 149 * ms, null, 0L, lastTargetNanos = t - 149 * ms))
        assertTrue(dialVibrationDue(DialCrossing.THIN, t, t - 150 * ms, null, 0L, lastTargetNanos = t - TICK_QUIET_AFTER_TARGET_NANOS))
    }

    /** The compass screen's frame loop for the dial vibrations, with the same pure functions in the same order. */
    private class Frames(val target: Float?, val blindNavigation: Boolean = false, val tickFeedback: Boolean = true) {
        var nowNanos = 1_000_000_000L
        var shown = Float.NaN
        val played = mutableListOf<Pair<DialCrossing, Float>>()
        private var settling = TickSettling.Settled
        private var cell = TickCell.Unset
        private var targetCell = TickCell.Unset
        private var side = TargetSide.Unset
        private var lastTap = 0L
        private var lastTarget = 0L

        fun frame(heading: Float, dtMs: Long = 16) {
            nowNanos += dtMs * 1_000_000L
            val first = shown.isNaN()
            shown = if (first) heading else smoothAngle(shown, heading, dtMs / 1000f)
            val next = nextTickCell(cell, shown)
            targetCell = nextTickCell(targetCell, heading)
            val wasSettling = settling.active
            settling = nextTickSettling(settling, isTickDisturbance(first, false, dtMs * 1_000_000L, false, 0f), nowNanos, next, targetCell)
            val nextSide = nextTargetSide(side, shown, target)
            val k = dialCrossing(cell, next, side, nextSide, tickFeedback, blindNavigation)
            if (!wasSettling && !settling.active && dialVibrationDue(k, nowNanos, lastTap, null, 0L, lastTarget)) {
                played += k to shown
                lastTap = nowNanos
                if (k == DialCrossing.TARGET) lastTarget = nowNanos
            }
            cell = next
            side = nextSide
        }
        fun rest(heading: Float, ms: Long) { repeat((ms / 16).toInt()) { frame(heading) } }
        fun turn(from: Float, to: Float, degPerSecond: Float) {
            val step = degPerSecond * 0.016f * if (shortestDelta(from, to) < 0) -1 else 1
            var h = from
            while (kotlin.math.abs(shortestDelta(h, to)) > kotlin.math.abs(step)) { h = normalizeDeg(h + step); frame(h) }
            rest(to, 1000)
        }
        fun count(k: DialCrossing) = played.count { it.first == k }
    }

    @Test fun aTurnPastTheTargetGivesThinThickAndOneTargetVibration() {
        // 100.5 to 160.5 at 10 degrees a second: the lines 102 to 160, of which 120 and 150 are thick, and the
        // target at 125. The line at 126 comes about 0.1 s after the target and is dropped so as not to cut it short.
        val f = Frames(target = 125f)
        f.frame(100.5f); f.rest(100.5f, 2000)
        f.turn(100.5f, 160.5f, 10f)
        assertEquals(1, f.count(DialCrossing.TARGET))
        assertEquals(125f, f.played.single { it.first == DialCrossing.TARGET }.second, 0.5f)
        assertEquals(listOf(120f, 150f), f.played.filter { it.first == DialCrossing.THICK }.map { Math.round(it.second).toFloat() })
        assertEquals(27, f.count(DialCrossing.THIN))
        assertTrue(f.played.none { it.first == DialCrossing.THIN && it.second in 125.5f..126.5f })
        // And back: the target once more.
        f.turn(160.5f, 100.5f, 10f)
        assertEquals(2, f.count(DialCrossing.TARGET))
        assertEquals(4, f.count(DialCrossing.THICK))
    }

    @Test fun theSameTurnWithBlindNavigationOnGivesTheLinesOnly() {
        val f = Frames(target = 125f, blindNavigation = true)
        f.frame(100.5f); f.rest(100.5f, 2000)
        f.turn(100.5f, 160.5f, 10f)
        assertEquals(0, f.count(DialCrossing.TARGET))
        assertEquals(2, f.count(DialCrossing.THICK))
        assertEquals(28, f.count(DialCrossing.THIN))
    }

    @Test fun theCatchUpAfterTheFirstHeadingPassesTheTargetSilently() {
        // The dial swings from 100 to 141 past the target at 125 while it catches up: nothing, as for the lines.
        val f = Frames(target = 125f)
        f.frame(100f)
        f.rest(141f, 2000)
        assertTrue(f.played.toString(), f.played.isEmpty())
    }

    @Test fun theConfirmingVibrationWaitsForThePressToPlayOut() {
        // A save that finishes 40 ms after its press: the confirming vibration comes when the press has played.
        assertEquals(SAVE_CONFIRM_AFTER_PRESS_MS - 40, confirmDelayMs(nowMs = 10_040, pressAtMs = 10_000))
        assertEquals(SAVE_CONFIRM_AFTER_PRESS_MS, confirmDelayMs(nowMs = 10_000, pressAtMs = 10_000))
        assertEquals(0L, confirmDelayMs(nowMs = 10_000 + SAVE_CONFIRM_AFTER_PRESS_MS, pressAtMs = 10_000))
        assertEquals(0L, confirmDelayMs(nowMs = 25_000, pressAtMs = 10_000))
        // No press felt (the one-handed gesture, whose library plays its own): at once.
        assertEquals(0L, confirmDelayMs(nowMs = 10_000, pressAtMs = NO_PRESS))
        // A clock that has stepped back gives no wait longer than the gap.
        assertEquals(SAVE_CONFIRM_AFTER_PRESS_MS, confirmDelayMs(nowMs = 9_000, pressAtMs = 10_000))
        assertTrue(SAVE_CONFIRM_AFTER_PRESS_MS in 100L..250L)
    }
}
