package com.infinite8looper.compass.nav

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    private val kc = GeoPoint(39.099912, -94.581213)   // Kansas City
    private val stl = GeoPoint(38.627089, -90.200203)  // St. Louis

    @Test fun normalizeWraps() {
        assertEquals(350f, normalizeDeg(-10f), 1e-4f)
        assertEquals(10f, normalizeDeg(370f), 1e-4f)
        assertEquals(0f, normalizeDeg(360f), 1e-4f)
    }
    @Test fun shortestDeltaCrossesZero() {
        assertEquals(20f, shortestDelta(350f, 10f), 1e-4f)
        assertEquals(-20f, shortestDelta(10f, 350f), 1e-4f)
        assertEquals(180f, kotlin.math.abs(shortestDelta(0f, 180f)), 1e-4f)
    }
    @Test fun smoothingGoesTheShortWay() {
        val s = smoothAngle(350f, 10f, 0.05f)
        assertTrue("expected between 350 and 10 through 0, got $s", s > 350f || s < 10f)
    }
    @Test fun smoothingConvergesAndHolds() {
        assertEquals(10f, smoothAngle(350f, 10f, 10f), 0.01f)
        assertEquals(350f, smoothAngle(350f, 10f, 0f), 1e-4f)      // zero elapsed time
        assertEquals(350f, smoothAngle(350f, Float.NaN, 0.02f), 1e-4f) // NaN sensor value
        assertEquals(350f, smoothAngle(350f, 10f, -1f), 1e-4f)
    }
    @Test fun bearingCardinals() {
        assertEquals(0f, bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 0.01f)
        assertEquals(90f, bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 0.01f)
        assertEquals(180f, bearingDeg(GeoPoint(1.0, 0.0), GeoPoint(0.0, 0.0)), 0.01f)
        assertEquals(270f, bearingDeg(GeoPoint(0.0, 1.0), GeoPoint(0.0, 0.0)), 0.01f)
    }
    @Test fun bearingAndDistanceReferencePair() {
        assertEquals(96.51f, bearingDeg(kc, stl), 0.1f)
        assertEquals(382_900.0, distanceMeters(kc, stl), 1000.0)
    }
    @Test fun oneDegreeOfLatitude() {
        assertEquals(111_195.0, distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1.0)
    }
    @Test fun samePointIsFiniteAndArrived() {
        val p = GeoPoint(43.7022, -72.2896)
        assertEquals(0.0, distanceMeters(p, p), 1e-6)
        assertFalse(bearingDeg(p, p).isNaN())
        assertTrue(hasArrived(distanceMeters(p, p), Units.METRIC))
    }
    @Test fun antimeridianTakesShortWay() {
        val a = GeoPoint(0.0, 179.5); val b = GeoPoint(0.0, -179.5)
        assertEquals(111_195.0, distanceMeters(a, b), 5.0)
        assertEquals(90f, bearingDeg(a, b), 0.01f)
    }
    @Test fun nearPoleIsFinite() {
        val d = distanceMeters(GeoPoint(89.9, 0.0), GeoPoint(89.9, 180.0))
        assertEquals(2 * 0.1 * 111_195.0, d, 50.0)
        assertFalse(bearingDeg(GeoPoint(90.0, 0.0), GeoPoint(0.0, 0.0)).isNaN())
    }
    @Test fun displayHeadingAppliesDeclination() {
        assertEquals(DisplayHeading(346f, false), displayHeading(0f, -14f, NorthRef.TRUE))
        assertEquals(DisplayHeading(0f, true), displayHeading(0f, -14f, NorthRef.MAGNETIC))
        assertEquals(DisplayHeading(0f, true), displayHeading(0f, null, NorthRef.TRUE))
    }
    @Test fun dialAngleMatchesReference() {
        assertEquals(90f, dialAngle(90f, -14f, NorthRef.TRUE), 1e-4f)
        assertEquals(104f, dialAngle(90f, -14f, NorthRef.MAGNETIC), 1e-4f)
        assertEquals(90f, dialAngle(90f, null, NorthRef.TRUE), 1e-4f)
    }
    @Test fun distanceFormatting() {
        assertEquals("420 m", formatDistance(420.4, Units.METRIC))
        assertEquals("999 m", formatDistance(999.4, Units.METRIC))
        assertEquals("1.0 km", formatDistance(1000.0, Units.METRIC))
        assertEquals("12.3 km", formatDistance(12_345.0, Units.METRIC))
        assertEquals("328 ft", formatDistance(100.0, Units.IMPERIAL))
        assertEquals("0.2 mi", formatDistance(305.0, Units.IMPERIAL))   // 1000.7 ft
        assertEquals("1.0 mi", formatDistance(1609.344, Units.IMPERIAL))
    }
    @Test fun arrivalThresholds() {
        assertTrue(hasArrived(9.99, Units.METRIC)); assertFalse(hasArrived(10.0, Units.METRIC))
        assertTrue(hasArrived(9.0, Units.IMPERIAL)); assertFalse(hasArrived(9.2, Units.IMPERIAL)) // 30 ft = 9.144 m
    }
    @Test fun huesStayApartForManyWaypoints() {
        val hues = (0 until 500).map { hueForIndex(it) }
        assertTrue(hues.all { it >= 0f && it < 360f })
        for (i in 1 until hues.size) {
            assertTrue(kotlin.math.abs(shortestDelta(hues[i - 1], hues[i])) > 100f)
        }
        val first50 = hues.take(50).sorted()
        val minGap = first50.zipWithNext { a, b -> b - a }.min()
        assertTrue("min gap $minGap", minGap > 1f)
    }
    @Test fun sensorHelpers() {
        assertTrue(shouldHoldFlat(75f)); assertFalse(shouldHoldFlat(40f))
        assertTrue(isFieldNormal(48f)); assertFalse(isFieldNormal(120f)); assertFalse(isFieldNormal(10f))
        assertEquals("High", accuracyLabel(3)); assertEquals("Medium", accuracyLabel(2))
        assertEquals("Low", accuracyLabel(1)); assertEquals("Unreliable", accuracyLabel(0))
        assertEquals("Unknown", accuracyLabel(null))
        assertTrue(isStale(30_000, 5_000)); assertFalse(isStale(30_000, 15_000)); assertFalse(isStale(30_000, null))
    }
    private fun fix(acc: Float) = Fix(GeoPoint(1.0, 2.0), acc, 0f, 0L)

    @Test fun acquireReturnsFirstGoodFix() = runTest {
        val seen = mutableListOf<Float>()
        val got = acquireFix(flow { emit(fix(40f)); delay(1000); emit(fix(12f)); delay(1000); emit(fix(3f)) }) { seen += it.accuracyM }
        assertEquals(12f, got!!.accuracyM, 0f)
        assertEquals(listOf(40f, 12f), seen)
    }
    @Test fun acquireFallsBackToBestAfterTimeout() = runTest {
        val got = acquireFix(flow { emit(fix(40f)); delay(5000); emit(fix(25f)); delay(60_000); emit(fix(5f)) })
        assertEquals(25f, got!!.accuracyM, 0f)
    }
    @Test fun acquireReturnsNullWithNoFix() = runTest {
        assertNull(acquireFix(flow { delay(60_000) }))
        assertNull(acquireFix(flow { }))
    }

    @Test fun holdFlatEntersAboveSeventy() {
        assertFalse(nextHoldFlat(false, 0f)); assertFalse(nextHoldFlat(false, 59f))
        assertFalse(nextHoldFlat(false, 70f))
        assertTrue(nextHoldFlat(false, 70.5f)); assertTrue(nextHoldFlat(false, 90f))
    }
    @Test fun holdFlatDoesNotEnterBetweenSixtyAndSeventyFromLevel() {
        var hold = false
        for (pitch in listOf(10f, 55f, 60f, 62f, 65f, 69.9f, 66f, 61f, 30f)) {
            hold = nextHoldFlat(hold, pitch)
            assertFalse("entered at $pitch", hold)
        }
    }
    @Test fun holdFlatStaysBetweenSixtyAndSeventyOnceEntered() {
        var hold = nextHoldFlat(false, 72f)
        assertTrue(hold)
        for (pitch in listOf(69f, 65f, 60.5f, 60f, 64f, 69.9f, 61f)) {
            hold = nextHoldFlat(hold, pitch)
            assertTrue("left at $pitch", hold)
        }
    }
    @Test fun holdFlatLeavesBelowSixty() {
        assertFalse(nextHoldFlat(true, 59.9f)); assertFalse(nextHoldFlat(true, 0f))
        // Hovering either side of one threshold changes the state once, not on every reading.
        var hold = false; var changes = 0
        for (pitch in listOf(65f, 72f, 68f, 71f, 66f, 72f, 61f, 58f, 63f, 69f, 59f, 65f)) {
            val next = nextHoldFlat(hold, pitch)
            if (next != hold) changes++
            hold = next
        }
        assertEquals(2, changes)
        assertFalse(hold)
    }
    @Test fun holdFlatKeepsItsStateOnNaN() {
        assertTrue(nextHoldFlat(true, Float.NaN)); assertFalse(nextHoldFlat(false, Float.NaN))
    }

    /** Feeds [headings] through the tick function one per frame and counts the frames that tap. */
    private fun taps(headings: List<Float>): Int {
        var cell = TickCell.Unset; var count = 0
        for (h in headings) {
            val next = nextTickCell(cell, h)
            if (isTickCrossing(cell, next)) count++
            cell = next
        }
        return count
    }
    /** From [from] to [to] degrees in steps of 0.1, the short way, both ends included. */
    private fun sweep(from: Float, to: Float): List<Float> {
        val tenths = Math.round(shortestDelta(from, to) * 10f)
        val step = if (tenths < 0) -1 else 1
        return (0..kotlin.math.abs(tenths)).map { normalizeDeg(from + it * step / 10f) }
    }

    @Test fun tickSlowSweepZeroToTenGivesFiveTaps() {
        // The lines at 2, 4, 6, 8 and 10. Each counts once the heading is 0.3 past it, so the tap for the line
        // at 10 comes at 10.3: stopping exactly on the line has not crossed it yet.
        assertEquals(5, taps(sweep(0f, 10.5f)))
        assertEquals(4, taps(sweep(0f, 10f)))
        assertEquals(5, taps(sweep(10.5f, 0.5f)))
    }
    @Test fun tickSweepAcrossTheWrapBothWays() {
        // 358, 0, 2 and 4 going up; 4, 2, 0 and 358 coming back.
        assertEquals(4, taps(sweep(356f, 4.5f)))
        assertEquals(3, taps(sweep(356f, 4f)))
        assertEquals(4, taps(sweep(4.5f, 356f)))
        assertEquals(1, taps(listOf(359.5f, 359.9f, 0.2f, 0.4f)))
        assertEquals(1, taps(listOf(0.4f, 0.1f, 359.9f, 359.6f)))
    }
    @Test fun tickJitterAroundALineGivesNone() {
        assertEquals(0, taps(List(200) { if (it % 2 == 0) 10.2f else 9.8f }))
        assertEquals(0, taps(listOf(10f) + List(200) { if (it % 2 == 0) 10.2f else 9.8f }))
        assertEquals(0, taps(List(200) { if (it % 2 == 0) 359.8f else 0.2f }))
        // Arriving at the line from below and then resting on it: still nothing.
        assertEquals(0, taps(sweep(9f, 10f) + List(200) { if (it % 2 == 0) 10.2f else 9.8f }))
    }
    @Test fun tickReturnAcrossTheSameLineNeedsTheMarginBack() {
        assertEquals(1, taps(listOf(9f, 10.4f)))
        assertEquals(1, taps(listOf(9f, 10.4f, 10.1f, 9.9f, 9.8f)))
        assertEquals(2, taps(listOf(9f, 10.4f, 10.1f, 9.9f, 9.6f)))
        // Rocking a full degree either side of a line taps on every pass.
        assertEquals(6, taps(listOf(9f, 11f, 9f, 11f, 9f, 11f, 9f)))
    }
    @Test fun tickJumpOfThirtyDegreesInOneStepGivesOne() {
        assertEquals(1, taps(listOf(10f, 40f)))
        assertEquals(1, taps(listOf(350f, 20f)))
        assertEquals(1, taps(listOf(5f, 185f)))
    }
    @Test fun tickReversingMidCellGivesNone() {
        assertEquals(0, taps(listOf(4.5f, 5.5f, 4.5f, 5.9f, 4.1f, 5f)))
        assertEquals(0, taps(sweep(4.2f, 5.8f) + sweep(5.8f, 4.2f)))
    }
    @Test fun tickIgnoresNaN() {
        assertEquals(TickCell.Unset, nextTickCell(TickCell.Unset, Float.NaN))
        val armed = nextTickCell(TickCell.Unset, 7f)
        assertEquals(armed, nextTickCell(armed, Float.NaN))
        // A NaN frame neither taps nor loses the place; the first real reading only arms.
        assertEquals(0, taps(listOf(Float.NaN, 7f, Float.NaN, 7.5f)))
        assertEquals(1, taps(listOf(7f, Float.NaN, 9f)))
        assertFalse(isTickCrossing(TickCell.Unset, armed))
    }
    @Test fun tickTapsAreAtLeastFortyMillisecondsApart() {
        val ms = 1_000_000L
        assertTrue(tickTapDue(nowNanos = 40 * ms, lastTapNanos = 0L))
        assertFalse(tickTapDue(nowNanos = 39 * ms, lastTapNanos = 0L))
        assertTrue(tickTapDue(nowNanos = 1_000 * ms, lastTapNanos = 960 * ms))
        assertFalse(tickTapDue(nowNanos = 1_000 * ms, lastTapNanos = 984 * ms))
        // A spin crossing a tick on every 60 Hz frame taps on every third frame; the rest are dropped.
        var last = 0L; var count = 0
        for (frame in 1..60) {
            val now = frame * 16_666_667L
            if (tickTapDue(now, last)) { count++; last = now }
        }
        assertEquals(20, count)
    }
}
