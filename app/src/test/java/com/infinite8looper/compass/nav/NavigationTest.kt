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
    @Test fun accuracyFillsTheCalibrationFrameByQuarters() {
        // Unreliable and unknown fill nothing, low one quarter, medium three, high all four.
        assertEquals(0, accuracyQuarters(null)); assertEquals(0, accuracyQuarters(0))
        assertEquals(1, accuracyQuarters(1)); assertEquals(3, accuracyQuarters(2)); assertEquals(4, accuracyQuarters(3))
    }
    @Test fun anAccuracyTheSensorDoesNotDefineFillsNothing() {
        // -1 is the system's "no contact"; nothing above 3 is defined.
        assertEquals(0, accuracyQuarters(-1)); assertEquals(0, accuracyQuarters(4)); assertEquals(0, accuracyQuarters(Int.MAX_VALUE))
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

    /** A fix taken at [elapsedMs] on the clock the press is timed on. */
    private fun fixAt(elapsedMs: Long, acc: Float) = Fix(GeoPoint(1.0, 2.0), acc, 0f, elapsedMs)

    @Test fun aFixCountsOnlyFromThePressOn() {
        assertFalse(isFixSincePress(fixAt(99_999, 3f), 100_000))
        assertFalse(isFixSincePress(fixAt(97_000, 3f), 100_000))
        assertTrue(isFixSincePress(fixAt(100_000, 3f), 100_000))
        assertTrue(isFixSincePress(fixAt(100_250, 3f), 100_000))
    }
    @Test fun acquireNeverReturnsAFixFromBeforeThePress() = runTest {
        // A good fix from 3 s before the press and one from 1 ms before it are passed over, and are not progress.
        val seen = mutableListOf<Long>()
        val got = acquireFix(
            flow { emit(fixAt(97_000, 3f)); emit(fixAt(99_999, 4f)); delay(1000); emit(fixAt(100_800, 40f)); delay(1000); emit(fixAt(101_800, 12f)) },
            notBeforeMs = 100_000,
        ) { seen += it.elapsedMs }
        assertEquals(101_800L, got!!.elapsedMs)
        assertEquals(listOf(100_800L, 101_800L), seen)
    }
    @Test fun acquireFallsBackOnlyToAFixSinceThePress() = runTest {
        // After the 20 s the best fix since the press is used, although an earlier one was more accurate.
        val got = acquireFix(flow { emit(fixAt(99_000, 2f)); delay(5000); emit(fixAt(105_000, 30f)); delay(60_000) }, notBeforeMs = 100_000)
        assertEquals(105_000L, got!!.elapsedMs)
        // With nothing but earlier fixes there is no fix at all.
        assertNull(acquireFix(flow { emit(fixAt(99_000, 2f)); emit(fixAt(99_999, 2f)); delay(60_000) }, notBeforeMs = 100_000))
        assertNull(acquireFix(flow { emit(fixAt(99_000, 2f)) }, notBeforeMs = 100_000))
    }

    @Test fun interferenceIsAFieldTheEarthsCannotBeAndNeverAnUnknownOne() {
        assertFalse(isInterference(null))
        assertFalse(isInterference(48f))
        assertFalse(isInterference(25f)); assertFalse(isInterference(65f))
        assertTrue(isInterference(24.9f)); assertTrue(isInterference(65.1f))
        assertTrue(isInterference(0f)); assertTrue(isInterference(400f))
        assertFalse(isInterference(Float.NaN))
    }
    @Test fun theWordsForMissingPreciseLocationAreInOnePlaceAndTheStatusUsesThem() {
        assertEquals("Needs access to" to "precise location", NEEDS_PRECISE_LOCATION)
        assertEquals(NEEDS_PRECISE_LOCATION, statusText(LogStatus.Failed(LogFailure.NO_PERMISSION), Units.METRIC))
        assertEquals(NEEDS_PRECISE_LOCATION, statusText(LogStatus.Failed(LogFailure.NO_PERMISSION), Units.IMPERIAL))
    }
    @Test fun theSecondLineCarriesOnTheFirstOnlyForTheMessageThatIsBrokenInTwo() {
        // The owner's words, whole, on two lines.
        val (first, second) = statusText(LogStatus.Failed(LogFailure.NO_PERMISSION), Units.METRIC)
        assertEquals("Needs access to precise location", "$first $second")
        assertTrue(statusDetailContinues(LogStatus.Failed(LogFailure.NO_PERMISSION)))
        // Everywhere else the second line is a note under the status, or there is none.
        assertFalse(statusDetailContinues(LogStatus.Failed(LogFailure.NO_FIX)))
        assertFalse(statusDetailContinues(LogStatus.Failed(LogFailure.LOCATION_OFF)))
        assertFalse(statusDetailContinues(LogStatus.Failed(LogFailure.SAVE_FAILED)))
        assertFalse(statusDetailContinues(LogStatus.Saving(12f)))
        assertFalse(statusDetailContinues(LogStatus.Saving(null)))
        assertFalse(statusDetailContinues(LogStatus.Saved("Waypoint 5", 5f, 100_000)))
        assertFalse(statusDetailContinues(LogStatus.Idle))
    }
    @Test fun withoutInterferenceTheRingIsGreenAndAsFullAsTheAccuracy() {
        assertEquals(CalibrationRing(0f, RingRole.CONFIRM), calibrationRing(null, interference = false))
        assertEquals(CalibrationRing(0f, RingRole.CONFIRM), calibrationRing(0, interference = false))
        assertEquals(CalibrationRing(0.25f, RingRole.CONFIRM), calibrationRing(1, interference = false))
        assertEquals(CalibrationRing(0.75f, RingRole.CONFIRM), calibrationRing(2, interference = false))
        assertEquals(CalibrationRing(1f, RingRole.CONFIRM), calibrationRing(3, interference = false))
        // A level that is not one of the four the sensor defines fills nothing.
        assertEquals(CalibrationRing(0f, RingRole.CONFIRM), calibrationRing(7, interference = false))
        assertEquals(CalibrationRing(0f, RingRole.CONFIRM), calibrationRing(-1, interference = false))
    }
    @Test fun duringInterferenceTheWholeRingIsInTheErrorColorAtEveryAccuracy() {
        for (accuracy in listOf(null, 0, 1, 2, 3, 7, -1)) {
            assertEquals("accuracy $accuracy", CalibrationRing(1f, RingRole.ERROR), calibrationRing(accuracy, interference = true))
        }
    }
    @Test fun whenInterferenceEndsTheRingIsWhatTheAccuracyAloneGives() {
        for (accuracy in listOf(null, 0, 1, 2, 3)) {
            assertEquals(accuracyQuarters(accuracy) / 4f, calibrationRing(accuracy, interference = false).fraction, 0f)
            assertEquals(RingRole.CONFIRM, calibrationRing(accuracy, interference = false).role)
        }
    }
    @Test fun aSensorThatHasJustStartedKeepsWhatTheMagnetometerLastSaidUntilItSaysSomethingNew() {
        val before = MagnetReport(accuracy = 3, fieldMicroTesla = 49f)
        // The first headings after a start come before the magnetometer's first event.
        assertEquals(before, carriedOver(before, MagnetReport(null, null)))
        // Each of the two is replaced when it is reported, and not before.
        assertEquals(MagnetReport(1, 49f), carriedOver(before, MagnetReport(1, null)))
        assertEquals(MagnetReport(3, 52f), carriedOver(before, MagnetReport(null, 52f)))
        assertEquals(MagnetReport(2, 31f), carriedOver(before, MagnetReport(2, 31f)))
        // Unreliable is a report like any other.
        assertEquals(MagnetReport(0, 49f), carriedOver(before, MagnetReport(0, null)))
    }
    @Test fun beforeAnyReportThereIsNothingToKeep() {
        assertEquals(MagnetReport(null, null), carriedOver(null, MagnetReport(null, null)))
        assertEquals(MagnetReport(2, null), carriedOver(null, MagnetReport(2, null)))
        assertEquals(MagnetReport(2, 40f), carriedOver(MagnetReport(null, null), MagnetReport(2, 40f)))
    }
    @Test fun interferenceFromBeforeAStopLastsOnlyUntilTheFirstNewFieldStrength() {
        val before = MagnetReport(accuracy = 3, fieldMicroTesla = 148f)
        val held = carriedOver(before, MagnetReport(null, null))
        assertTrue(isInterference(held.fieldMicroTesla))
        val first = carriedOver(held, MagnetReport(3, 49f))
        assertFalse(isInterference(first.fieldMicroTesla))
        // And the other way: a strong field is shown from its first report.
        assertTrue(isInterference(carriedOver(MagnetReport(3, 49f), MagnetReport(null, 148f)).fieldMicroTesla))
    }
    @Test fun statusTextGivesTheTwoLinesForEveryState() {
        assertEquals("" to null, statusText(LogStatus.Idle, Units.METRIC))
        assertEquals("Saving…" to null, statusText(LogStatus.Saving(null), Units.METRIC))
        assertEquals("Saving…" to "±12 m", statusText(LogStatus.Saving(12.4f), Units.METRIC))
        assertEquals("Saved Waypoint 5" to "±5 m", statusText(LogStatus.Saved("Waypoint 5", 5f, 100_000), Units.METRIC))
        assertEquals("No GPS fix" to "Try again outdoors", statusText(LogStatus.Failed(LogFailure.NO_FIX), Units.METRIC))
        assertEquals("Location is off" to null, statusText(LogStatus.Failed(LogFailure.LOCATION_OFF), Units.METRIC))
        assertEquals("Needs access to" to "precise location", statusText(LogStatus.Failed(LogFailure.NO_PERMISSION), Units.METRIC))
        assertEquals("Couldn't save" to null, statusText(LogStatus.Failed(LogFailure.SAVE_FAILED), Units.METRIC))
    }
    @Test fun statusTextGivesTheAccuracyInTheChosenUnits() {
        assertEquals("Saving…" to "±39 ft", statusText(LogStatus.Saving(12f), Units.IMPERIAL))
        assertEquals("Saved Waypoint 5" to "±16 ft", statusText(LogStatus.Saved("Waypoint 5", 5f, 100_000), Units.IMPERIAL))
        // The words of a failure do not depend on the units.
        assertEquals(statusText(LogStatus.Failed(LogFailure.NO_FIX), Units.METRIC), statusText(LogStatus.Failed(LogFailure.NO_FIX), Units.IMPERIAL))
    }
    @Test fun aResultIsShownForItsTimeAndAnythingElseForNone() {
        assertEquals(2_000L, logStatusShowMs(LogStatus.Saved("Waypoint 5", 5f, 100_000)))
        for (reason in LogFailure.entries) assertEquals(3_000L, logStatusShowMs(LogStatus.Failed(reason)))
        assertNull(logStatusShowMs(LogStatus.Idle))
        assertNull(logStatusShowMs(LogStatus.Saving(null)))
        assertNull(logStatusShowMs(LogStatus.Saving(8f)))
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

    @Test fun tickSlowSweepFromZeroPastTheLineAtTenGivesFiveTapsAndFourWhenStoppingOnIt() {
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
    @Test fun tickIgnoresInfiniteHeadings() {
        assertEquals(TickCell.Unset, nextTickCell(TickCell.Unset, Float.POSITIVE_INFINITY))
        assertEquals(TickCell.Unset, nextTickCell(TickCell.Unset, Float.NEGATIVE_INFINITY))
        val armed = nextTickCell(TickCell.Unset, 7f)
        assertEquals(armed, nextTickCell(armed, Float.POSITIVE_INFINITY))
        assertEquals(armed, nextTickCell(armed, Float.NEGATIVE_INFINITY))
        assertEquals(0, taps(listOf(7f, Float.POSITIVE_INFINITY, 7.5f, Float.NEGATIVE_INFINITY, 7f)))
        assertEquals(1, taps(listOf(7f, Float.POSITIVE_INFINITY, 9f)))
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

    @Test fun facingIsEnteredWithinTwoDegrees() {
        assertTrue(nextFacing(false, 100f, 101.9f))
        assertTrue(nextFacing(false, 100f, 98.1f))
        assertTrue(nextFacing(false, 100f, 100f))
        assertFalse(nextFacing(false, 100f, 102.1f))
        assertFalse(nextFacing(false, 100f, 97.9f))
        assertFalse(nextFacing(false, 100f, 280f))
    }
    @Test fun facingIsLeftOnlyBeyondThreeDegrees() {
        assertTrue(nextFacing(true, 100f, 102.9f))
        assertTrue(nextFacing(true, 100f, 97.1f))
        assertFalse(nextFacing(true, 100f, 103.1f))
        assertFalse(nextFacing(true, 100f, 96.9f))
        // Between the two limits the answer is whatever it was.
        assertTrue(nextFacing(true, 100f, 102.5f))
        assertFalse(nextFacing(false, 100f, 102.5f))
    }
    @Test fun facingWorksAcrossNorth() {
        assertTrue(nextFacing(false, 359f, 0.9f))
        assertTrue(nextFacing(false, 0.9f, 359f))
        assertFalse(nextFacing(false, 359f, 1.1f))
        assertFalse(nextFacing(false, 1.1f, 359f))
        assertTrue(nextFacing(true, 358f, 0.9f))
        assertTrue(nextFacing(true, 0.9f, 358f))
        assertFalse(nextFacing(true, 358f, 1.1f))
        assertFalse(nextFacing(true, 1.1f, 358f))
        assertTrue(nextFacing(false, 0f, 360f))
    }
    @Test fun facingNeedsATargetAndRealNumbers() {
        for (current in listOf(false, true)) {
            assertFalse(nextFacing(current, 100f, null))
            assertFalse(nextFacing(current, Float.NaN, 100f))
            assertFalse(nextFacing(current, 100f, Float.NaN))
            assertFalse(nextFacing(current, Float.POSITIVE_INFINITY, 100f))
            assertFalse(nextFacing(current, 100f, Float.NEGATIVE_INFINITY))
        }
    }
    @Test fun facingIsOffWhileTheShownHeadingIsNotLive() {
        // Held flat too steeply, or catching up after a pause, the dial does not show where the wearer points.
        assertFalse(nextFacing(false, 100f, 100f, headingLive = false))
        assertFalse(nextFacing(true, 100f, 100f, headingLive = false))
        assertFalse(nextFacing(true, 100f, 102.5f, headingLive = false))
        // Once it is live again, facing is entered afresh, by the entering limit.
        assertTrue(nextFacing(nextFacing(true, 100f, 101f, headingLive = false), 100f, 101f, headingLive = true))
        assertFalse(nextFacing(nextFacing(true, 100f, 102.5f, headingLive = false), 100f, 102.5f, headingLive = true))
    }
    @Test fun facingThroughASweepSwitchesOnceEachWay() {
        // The heading turns through a target at 90 and on, with a little noise at each limit.
        val headings = listOf(80f, 87.9f, 88.1f, 87.95f, 88.2f, 90f, 92.9f, 92.4f, 92.95f, 93.1f, 92.9f, 100f)
        var facing = false; var switches = 0
        for (h in headings) { val next = nextFacing(facing, h, 90f); if (next != facing) switches++; facing = next }
        assertEquals(2, switches)
        assertFalse(facing)
    }

    @Test fun facingStartsAgainWhenTheFocusMovesToAnotherTarget() {
        // Facing target A at 0; the focus moves to B. B is faced only if it is within the entering limit.
        assertFalse(nextFacing(true, 0f, 2.5f, targetChanged = true))
        assertTrue(nextFacing(true, 0f, 1.5f, targetChanged = true))
        assertFalse(nextFacing(false, 0f, 2.5f, targetChanged = true))
        // On the frames after the change, B is an ordinary target again.
        assertTrue(nextFacing(nextFacing(true, 0f, 1.5f, targetChanged = true), 0f, 2.5f))
        assertFalse(nextFacing(nextFacing(true, 0f, 2.5f, targetChanged = true), 0f, 2.5f))
        // The same target keeps the leaving limit.
        assertTrue(nextFacing(true, 0f, 2.5f, targetChanged = false))
    }

    /**
     * Steps the blind navigation pulses the way the compass screen's frame loop does: decide facing, then the
     * pulses, then whether a tick crossing on the same frame may tap.
     */
    private class BlindFrames(var facing: Boolean = false) {
        var nowNanos = 5_000_000_000L
        var state = BlindPulses.Silent
        val pulses = mutableListOf<Pulse>()
        var lastPulse: Pulse? = null
        var lastPulseNanos = 0L
        var ticks = 0

        fun frame(facing: Boolean = this.facing, enabled: Boolean = true, inView: Boolean = true, dtMs: Long = 16, tickCrossing: Boolean = false): Pulse? {
            nowNanos += dtMs * 1_000_000L
            this.facing = facing
            val next = nextBlindPulses(state, facing, enabled, inView, nowNanos)
            val pulse = pulseBetween(state, next)
            state = next
            if (pulse != null) { pulses += pulse; lastPulse = pulse; lastPulseNanos = nowNanos }
            if (tickCrossing && tickClearOfPulse(nowNanos, lastPulse, lastPulseNanos)) ticks++
            return pulse
        }
        /** Frames 16 ms apart for [ms]; the pulses they gave. */
        fun hold(ms: Long, facing: Boolean = this.facing, enabled: Boolean = true, inView: Boolean = true): List<Pulse> {
            val before = pulses.size
            repeat((ms / 16).toInt()) { frame(facing, enabled, inView) }
            return pulses.subList(before, pulses.size).toList()
        }
    }

    @Test fun turningOntoTheTargetGivesOneLockThenAHeartbeatEachSecondAndALockAgainAfterTurningAway() {
        val f = BlindFrames()
        assertEquals(emptyList<Pulse>(), f.hold(2_000, facing = false))
        // Turn onto the target: the lock comes on that frame, and only on that frame.
        assertEquals(Pulse.LOCK, f.frame(facing = true))
        assertNull(f.frame())
        // Held for 3.5 s from the lock: a heartbeat at 1, 2 and 3 seconds.
        assertEquals(listOf(Pulse.HEARTBEAT, Pulse.HEARTBEAT, Pulse.HEARTBEAT), f.hold(3_500 - 16))
        // Turned away: nothing, however long.
        assertEquals(emptyList<Pulse>(), f.hold(5_000, facing = false))
        // Back onto it: a lock again, then the heartbeats count from that lock.
        assertEquals(Pulse.LOCK, f.frame(facing = true))
        assertEquals(emptyList<Pulse>(), f.hold(976))
        assertEquals(listOf(Pulse.HEARTBEAT), f.hold(32))
        assertEquals(listOf(Pulse.LOCK, Pulse.HEARTBEAT, Pulse.HEARTBEAT, Pulse.HEARTBEAT, Pulse.LOCK, Pulse.HEARTBEAT), f.pulses)
    }

    @Test fun heartbeatsAreOneSecondApartAndKeepTheirBeatBetweenFrames() {
        // Frames 16 ms apart do not divide a second: the beat stays on the lock's seconds and does not drift later.
        val f = BlindFrames()
        f.frame(facing = true)
        val lockAt = f.nowNanos
        val beats = mutableListOf<Long>()
        repeat(60_000 / 16) { if (f.frame() == Pulse.HEARTBEAT) beats += f.nowNanos - lockAt }
        assertEquals(60, beats.size)
        beats.forEachIndexed { i, at ->
            val due = (i + 1) * BLIND_HEARTBEAT_NANOS
            assertTrue("heartbeat ${i + 1} at $at", at >= due && at < due + 16_000_000L)
        }
    }

    @Test fun jitterAtTheFacingLimitGivesNoRepeatedLocks() {
        // The heading wavers at the entering limit of a target at 90, comes on, wavers at the leaving limit.
        val headings = listOf(80f, 87.9f, 88.1f, 87.95f, 88.2f, 87.9f, 88.05f, 90f, 92.9f, 92.4f, 92.95f, 87.1f, 92.99f, 90f)
        val f = BlindFrames()
        var facing = false
        for (h in headings) { facing = nextFacing(facing, h, 90f); f.frame(facing) }
        assertEquals(listOf(Pulse.LOCK), f.pulses)
        // Beyond the leaving limit and back: that is facing lost, and a lock again.
        for (h in listOf(93.1f, 92.5f, 91.9f)) { facing = nextFacing(facing, h, 90f); f.frame(facing) }
        assertEquals(listOf(Pulse.LOCK, Pulse.LOCK), f.pulses)
    }

    @Test fun aLongGapBetweenFramesGivesOneHeartbeatAndNoBurst() {
        val f = BlindFrames()
        assertEquals(Pulse.LOCK, f.frame(facing = true))
        // 30.4 s without a frame, still facing: one heartbeat on the frame that ends the gap, none owed after it.
        assertEquals(Pulse.HEARTBEAT, f.frame(dtMs = 30_400))
        assertNull(f.frame())
        assertNull(f.frame())
        // The beat is still the lock's: the next one is at 31 s from it.
        assertEquals(emptyList<Pulse>(), f.hold(560))
        assertEquals(listOf(Pulse.HEARTBEAT), f.hold(48))
        // A gap in which facing was lost gives nothing, and no lock until facing again.
        assertNull(f.frame(facing = false, dtMs = 600_000))
        assertNull(f.frame(facing = false))
        assertEquals(Pulse.LOCK, f.frame(facing = true, dtMs = 600_000))
        assertEquals(listOf(Pulse.LOCK, Pulse.HEARTBEAT, Pulse.HEARTBEAT, Pulse.LOCK), f.pulses)
    }

    @Test fun aClockThatStepsBackGivesNoPulse() {
        var state = nextBlindPulses(BlindPulses.Silent, facing = true, enabled = true, inView = true, nowNanos = 10_000_000_000L)
        val earlier = nextBlindPulses(state, facing = true, enabled = true, inView = true, nowNanos = 3_000_000_000L)
        assertNull(pulseBetween(state, earlier))
        state = earlier
        assertEquals(
            Pulse.HEARTBEAT,
            pulseBetween(state, nextBlindPulses(state, facing = true, enabled = true, inView = true, nowNanos = 11_000_000_000L)),
        )
    }

    @Test fun pulsesAreSilentWithTheSettingOff() {
        val f = BlindFrames()
        assertEquals(emptyList<Pulse>(), f.hold(4_000, facing = true, enabled = false))
        assertEquals(BlindPulses.Silent, f.state)
        // Switched on while facing: the wearer is told so with a lock, and the heartbeats follow it.
        assertEquals(listOf(Pulse.LOCK, Pulse.HEARTBEAT), f.hold(1_100, facing = true))
        // Switched off: silent at once.
        assertEquals(emptyList<Pulse>(), f.hold(4_000, facing = true, enabled = false))
    }

    @Test fun pulsesAreSilentWhileTheCompassIsNotInViewAndComingBackWhileFacingIsALock() {
        val f = BlindFrames()
        assertEquals(emptyList<Pulse>(), f.hold(4_000, facing = true, inView = false))
        assertEquals(Pulse.LOCK, f.frame(facing = true))
        assertEquals(listOf(Pulse.HEARTBEAT), f.hold(1_100))
        // The compass leaves the view (a swipe starts, a screen opens over it, the app is left) while still facing.
        assertEquals(emptyList<Pulse>(), f.hold(300, inView = false))
        // Back in view and still facing: a fresh lock, not a heartbeat picked up where it stopped.
        assertEquals(Pulse.LOCK, f.frame())
        // Away for a long time with no frame at all in between: the same, and one pulse only.
        f.frame(inView = false)
        assertEquals(Pulse.LOCK, f.frame(dtMs = 45_000))
        assertNull(f.frame())
        assertEquals(listOf(Pulse.LOCK, Pulse.HEARTBEAT, Pulse.LOCK, Pulse.LOCK), f.pulses)
    }

    @Test fun noPulseWithoutFacingWhateverElseHolds() {
        for (enabled in listOf(false, true)) for (inView in listOf(false, true)) {
            val f = BlindFrames()
            assertEquals(emptyList<Pulse>(), f.hold(3_000, facing = false, enabled = enabled, inView = inView))
            assertEquals(BlindPulses.Silent, f.state)
        }
        assertNull(pulseBetween(BlindPulses.Silent, BlindPulses.Silent))
    }

    @Test fun theFocusMovingStraightToAnotherFacedTargetIsALockForThatTarget() {
        // Two targets close together, the wearer facing both: stepping the focus from one to the other.
        val t = 10_000_000_000L
        val onA = nextBlindPulses(BlindPulses.Silent, facing = true, enabled = true, inView = true, nowNanos = t)
        val onB = nextBlindPulses(onA, facing = true, enabled = true, inView = true, nowNanos = t + 300_000_000L, targetChanged = true)
        assertEquals(Pulse.LOCK, pulseBetween(onA, onB, targetChanged = true))
        // The heartbeats then count from that lock, not from the first target's.
        val soon = nextBlindPulses(onB, facing = true, enabled = true, inView = true, nowNanos = t + 1_000_000_000L)
        assertNull(pulseBetween(onB, soon))
        val later = nextBlindPulses(soon, facing = true, enabled = true, inView = true, nowNanos = t + 1_300_000_000L)
        assertEquals(Pulse.HEARTBEAT, pulseBetween(soon, later))
        // The focus moving to a target that is not faced is silence, and so is one moving while nothing pulses.
        val off = nextBlindPulses(onA, facing = false, enabled = true, inView = true, nowNanos = t + 300_000_000L, targetChanged = true)
        assertNull(pulseBetween(onA, off, targetChanged = true))
        assertEquals(BlindPulses.Silent, off)
        // A lock on the very frame the last pulse was due is still a lock.
        val same = nextBlindPulses(onA, facing = true, enabled = true, inView = true, nowNanos = t, targetChanged = true)
        assertEquals(Pulse.LOCK, pulseBetween(onA, same, targetChanged = true))
    }

    @Test fun pulsesAreSilentWhileThePositionIsOldAndAFreshFixWhileFacingIsALock() {
        fun step(state: BlindPulses, atMs: Long, stale: Boolean, facing: Boolean = true) =
            nextBlindPulses(state, facing, enabled = true, inView = true, nowNanos = atMs * 1_000_000L, positionStale = stale)
        // Facing a bearing worked out from an old position: nothing, for as long as it stays old.
        var state = BlindPulses.Silent
        val pulses = mutableListOf<Pulse>()
        var t = 10_000L
        fun run(ms: Long, stale: Boolean, facing: Boolean = true) {
            repeat((ms / 16).toInt()) {
                t += 16
                val next = step(state, t, stale, facing)
                pulseBetween(state, next)?.let { pulses += it }
                state = next
            }
        }
        run(5_000, stale = true)
        assertEquals(emptyList<Pulse>(), pulses)
        assertEquals(BlindPulses.Silent, state)
        // A fresh fix arrives while still facing: that is becoming facing. One lock, then the heartbeats.
        run(3_500, stale = false)
        assertEquals(listOf(Pulse.LOCK, Pulse.HEARTBEAT, Pulse.HEARTBEAT, Pulse.HEARTBEAT), pulses)
        // The position grows old while facing: the heartbeats stop.
        pulses.clear()
        run(30_000, stale = true)
        assertEquals(emptyList<Pulse>(), pulses)
        // Fresh again after that long: one lock, and nothing made up for the time in between.
        t += 16; var next = step(state, t, stale = false)
        assertEquals(Pulse.LOCK, pulseBetween(state, next)); state = next
        t += 16; next = step(state, t, stale = false)
        assertNull(pulseBetween(state, next)); state = next
        run(960, stale = false)
        assertEquals(emptyList<Pulse>(), pulses)
        run(32, stale = false)
        assertEquals(listOf(Pulse.HEARTBEAT), pulses)
        // Old and not facing, then fresh and still not facing: nothing.
        pulses.clear()
        run(2_000, stale = true, facing = false)
        run(2_000, stale = false, facing = false)
        assertEquals(emptyList<Pulse>(), pulses)
    }

    @Test fun aTickOnAPulsesFrameOrRightAfterItIsDropped() {
        // No pulse yet: ticks are free.
        assertTrue(tickClearOfPulse(1_000L, null, 0L))
        val f = BlindFrames()
        f.frame(facing = false, tickCrossing = true)
        assertEquals(1, f.ticks)
        // The lock's own frame, and every frame until the lock has had its time: no tick.
        assertEquals(Pulse.LOCK, f.frame(facing = true, tickCrossing = true))
        assertEquals(1, f.ticks)
        val lockAt = f.nowNanos
        while (f.nowNanos + 16_000_000L - lockAt < TICK_QUIET_AFTER_LOCK_NANOS) f.frame(tickCrossing = true)
        assertEquals(1, f.ticks)
        f.frame(tickCrossing = true)
        assertEquals(2, f.ticks)
        // The heartbeat's frame and the shorter time after it.
        while (f.frame() != Pulse.HEARTBEAT) Unit
        val beatAt = f.nowNanos
        assertFalse(tickClearOfPulse(beatAt, Pulse.HEARTBEAT, beatAt))
        assertFalse(tickClearOfPulse(beatAt + TICK_QUIET_AFTER_HEARTBEAT_NANOS - 1, Pulse.HEARTBEAT, beatAt))
        assertTrue(tickClearOfPulse(beatAt + TICK_QUIET_AFTER_HEARTBEAT_NANOS, Pulse.HEARTBEAT, beatAt))
        assertFalse(tickClearOfPulse(beatAt + TICK_QUIET_AFTER_HEARTBEAT_NANOS, Pulse.LOCK, beatAt))
        // Neither quiet time reaches the next heartbeat, so ticks work alongside the pulses.
        assertTrue(TICK_QUIET_AFTER_HEARTBEAT_NANOS < TICK_QUIET_AFTER_LOCK_NANOS)
        assertTrue(TICK_QUIET_AFTER_LOCK_NANOS < BLIND_HEARTBEAT_NANOS / 2)
    }
}
