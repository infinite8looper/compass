package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/**
 * The calibration screen's rules: the regions of directions the magnetic field has pointed in, in the watch's own
 * frame (v3 spec, "Calibration": "a ring of segments: each segment stands for a region of directions that the
 * measured magnetic field has pointed in, in the watch's own frame, and lights when the field has been there"), the
 * estimated heading error, the `Calibrate` prompt's threshold and how long the screen is kept on.
 *
 * The field vectors are the ones the API 37 emulator's magnetometer reported (`dumpsys sensorservice`, the last
 * event, two decimals) after each was set through its sensor console (`adb emu sensor set magnetic-field`), on
 * 2026-10-15: an earth field of (0, 20, -45) microtesla (east, north, up) as a watch sees it in the pose named.
 * `boot` is what the emulator reports by itself after a boot, before anything is set.
 */
class CalibrationTest {
    private class Recorded(val pose: String, val x: Float, val y: Float, val z: Float, val region: Int)

    // The region each should fall in, worked out by hand from the components: the band from z over the strength
    // (below -1/3: 0, between: 1, above 1/3: 2) and the sector from the angle of (x, y) counterclockwise from x,
    // in eighths centered on x, y, -x and -y (sector 0 is -22.5 to 22.5 degrees). Region = band * 8 + sector.
    private val recorded = listOf(
        Recorded("boot", 0.00f, 9.88f, -47.75f, 2),            // z -0.98, 90 degrees
        Recorded("flat-h0", 0.00f, 20.00f, -45.00f, 2),        // z -0.91, 90
        Recorded("flat-h90", -20.00f, 0.00f, -45.00f, 4),      // z -0.91, 180
        Recorded("flat-h180", -0.00f, -20.00f, -45.00f, 6),    // z -0.91, 270
        Recorded("flat-h270", 20.00f, -0.00f, -45.00f, 0),     // z -0.91, 0
        Recorded("facedown-h0", 0.00f, 20.00f, 45.00f, 18),    // z 0.91, 90
        Recorded("facedown-h90", 20.00f, 0.00f, 45.00f, 16),   // z 0.91, 0
        Recorded("upright-h0", 0.00f, -45.00f, -20.00f, 6),    // z -0.41, 270
        Recorded("upright-h90", -20.00f, -45.00f, -0.00f, 13), // z 0, 246
        Recorded("upright-h180", -0.00f, -45.00f, 20.00f, 22), // z 0.41, 270
        Recorded("upright-h270", 20.00f, -45.00f, 0.00f, 15),  // z 0, 294
        Recorded("rollright-h0", 45.00f, 20.00f, -0.00f, 9),   // z 0, 24
        Recorded("rollleft-h0", -45.00f, 20.00f, -0.00f, 11),  // z 0, 156
        Recorded("tilt45-h45", -14.12f, -21.81f, -41.81f, 5),  // z -0.85, 237
        Recorded("tilt45-h225", 23.19f, -41.81f, -11.81f, 15), // z -0.24, 299
    )

    @Test fun thereAreTwentyFourRegionsInThreeBandsOfEight() {
        assertEquals(24, COVERAGE_REGIONS)
        assertEquals(COVERAGE_BANDS * COVERAGE_SECTORS, COVERAGE_REGIONS)
        assertEquals(3, COVERAGE_BANDS); assertEquals(8, COVERAGE_SECTORS)
    }

    @Test fun eachRecordedFieldFallsInTheRegionItsDirectionIsIn() {
        for (r in recorded) assertEquals(r.pose, r.region, coverageRegion(r.x, r.y, r.z))
    }

    @Test fun aRegionIsMadeOfItsBandAndItsSector() {
        assertEquals(1, regionBand(13)); assertEquals(5, regionSector(13))
        assertEquals(2, regionBand(22)); assertEquals(6, regionSector(22))
        assertEquals(0, regionBand(0)); assertEquals(0, regionSector(0))
    }

    @Test fun theRegionDependsOnTheDirectionNotTheStrength() {
        for (r in recorded) {
            assertEquals(r.pose, r.region, coverageRegion(r.x * 0.6f, r.y * 0.6f, r.z * 0.6f))
            assertEquals(r.pose, r.region, coverageRegion(r.x * 1.3f, r.y * 1.3f, r.z * 1.3f))
        }
    }

    @Test fun aFieldWithNoDirectionIsInNoRegion() {
        assertEquals(-1, coverageRegion(0f, 0f, 0f))
        assertEquals(-1, coverageRegion(Float.NaN, 20f, -45f))
        assertEquals(-1, coverageRegion(0f, Float.POSITIVE_INFINITY, -45f))
    }

    @Test fun theRecordedPosesCoverTwelveRegionsAndTurningThroughThemAgainAddsNothing() {
        var covered = 0
        for (r in recorded) covered = nextCoverage(covered, r.x, r.y, r.z)
        assertEquals(12, coveredCount(covered))
        assertEquals(0.5f, coveredFraction(covered), 1e-6f)
        for (region in setOf(0, 2, 4, 5, 6, 9, 11, 13, 15, 16, 18, 22)) assertTrue("region $region", isCovered(covered, region))
        for (region in setOf(1, 3, 7, 8, 10, 12, 14, 17, 19, 20, 21, 23)) assertFalse("region $region", isCovered(covered, region))
        var again = covered
        for (r in recorded.reversed()) again = nextCoverage(again, r.x, r.y, r.z)
        assertEquals(covered, again)
    }

    @Test fun nothingIsCoveredAtFirstAndEverythingIsCoveredWhenEveryRegionHasBeenVisited() {
        assertEquals(0, coveredCount(0)); assertEquals(0f, coveredFraction(0), 0f)
        var all = 0
        for (region in 0 until COVERAGE_REGIONS) all = all or (1 shl region)
        assertEquals(24, coveredCount(all)); assertEquals(1f, coveredFraction(all), 0f)
    }

    /**
     * A field whose strength the earth's cannot have (`isInterference`; 92 microtesla is what the screenshots set
     * for the interference picture) still lights the region it points into: the spec says a segment "lights when the
     * field has been there", and a watch that is badly calibrated can read such a strength while the wrist turns
     * (the screen warns of it in its own place). (46, 46, -65) is 92 microtesla through the back of the watch toward
     * its upper right, region 1; (0, 80, -45) through the back toward its top, region 2; (45, 0, 80) out of its face
     * toward its right, region 16.
     */
    @Test fun aFieldOfAStrengthTheEarthsCannotHaveStillLightsTheRegionItPointsInto() {
        assertEquals(1 shl 1, nextCoverage(0, 46f, 46f, -65f))
        assertEquals(1 shl 2, nextCoverage(0, 0f, 80f, -45f))
        assertEquals(1 shl 2, nextCoverage(0, 0f, 2f, -4f))
        val one = nextCoverage(0, 0f, 20f, -45f)
        assertEquals(one or (1 shl 16), nextCoverage(one, 45f, 0f, 80f))
    }

    @Test fun aFieldWithNoDirectionCoversNothing() {
        assertEquals(0, nextCoverage(0, Float.NaN, Float.NaN, Float.NaN))
        assertEquals(0, nextCoverage(0, 0f, 0f, 0f))
    }

    // The rotation vector's estimated heading error, values[4] in radians: "-1 if unavailable" (SensorEvent).

    @Test fun theHeadingErrorIsTheFifthValueInDegrees() {
        assertEquals(10f, headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, 0.17453292f))!!, 1e-3f)
        assertEquals(45f, headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, 0.7853982f))!!, 1e-3f)
    }

    /**
     * Not given: -1, a sensor with four values, and what is not a number. Also 0: the AOSP rotation vector the
     * emulators run copies the accelerometer's event and writes only values[0..3] (RotationVectorSensor::process,
     * `*outEvent = event`), so its values[4] is the accelerometer's unused 0, not an estimate.
     */
    @Test fun aHeadingErrorThatIsNotGivenIsNone() {
        assertNull(headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, -1f)))
        assertNull(headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, 0f)))
        assertNull(headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f)))
        assertNull(headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, Float.NaN)))
        assertNull(headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, Float.POSITIVE_INFINITY)))
        assertNull(headingErrorDeg(floatArrayOf(0.68f, 0f, 0f, 0.74f, -0.2f)))
    }

    @Test fun theHeadingErrorIsWrittenInWholeDegreesAndNeverAsZero() {
        assertEquals("±12°", headingErrorText(12.4f))
        assertEquals("±13°", headingErrorText(12.5f))
        assertEquals("±1°", headingErrorText(0.3f))
        assertEquals("±180°", headingErrorText(180f))
    }

    @Test fun theShareCoveredIsWrittenAsAPercent() {
        assertEquals("0%", coveredText(0))
        assertEquals("50%", coveredText(0xFFF))
        assertEquals("4%", coveredText(1))      // 1 of 24 is 4.2 %
        assertEquals("100%", coveredText((1 shl 24) - 1))
    }

    // The `Calibrate` prompt: "also shows when the estimated heading error is large, not only at a low magnetometer
    // level. The threshold is fixed in `nav/` and unit-tested." (v3 spec, "Calibration")

    @Test fun theLargeHeadingErrorThresholdIsThirtyDegrees() { assertEquals(30f, LARGE_HEADING_ERROR_DEG, 0f) }

    /** The prompt for a single reading with nothing shown before it, as the compass works it out (CompassScreen.kt). */
    private fun firstReadingPrompt(accuracy: Int?, errorDeg: Float?) =
        calibrationPromptDue(accuracy, largeHeadingError(wasShown = false, errorDeg = errorDeg))

    @Test fun thePromptShowsAtALowOrUnreliableMagnetometerAsBefore() {
        assertTrue(firstReadingPrompt(1, null)); assertTrue(firstReadingPrompt(0, null))
        assertTrue(firstReadingPrompt(-1, null))   // SENSOR_STATUS_NO_CONTACT is below low too
        assertFalse(firstReadingPrompt(2, null)); assertFalse(firstReadingPrompt(3, null))
        assertFalse(firstReadingPrompt(null, null))
    }

    @Test fun thePromptShowsForAHeadingErrorAboveTheThresholdWhateverTheLevel() {
        assertTrue(firstReadingPrompt(3, 30.01f)); assertTrue(firstReadingPrompt(2, 45f)); assertTrue(firstReadingPrompt(null, 90f))
        assertFalse(firstReadingPrompt(3, 30f)); assertFalse(firstReadingPrompt(3, 29.9f)); assertFalse(firstReadingPrompt(null, 5f))
        assertTrue(firstReadingPrompt(1, 5f))
    }

    // The large-error prompt has a margin: it shows above 30 degrees and goes only below 25, so an estimate that
    // hovers at 30 does not put `Calibrate` on and off at the sensor's rate.

    @Test fun theLargeErrorPromptGoesOnlyBelowTwentyFiveDegrees() { assertEquals(25f, LARGE_HEADING_ERROR_CLEAR_DEG, 0f) }

    @Test fun aLargeErrorThatWasNotShownShowsOnlyAboveThirty() {
        assertFalse(largeHeadingError(wasShown = false, errorDeg = 30f))
        assertTrue(largeHeadingError(wasShown = false, errorDeg = 30.01f))
        assertFalse(largeHeadingError(wasShown = false, errorDeg = 27f))
        assertFalse(largeHeadingError(wasShown = false, errorDeg = 25f))
        assertFalse(largeHeadingError(wasShown = false, errorDeg = null))
    }

    @Test fun aLargeErrorThatWasShownStaysUntilBelowTwentyFive() {
        assertTrue(largeHeadingError(wasShown = true, errorDeg = 30f))
        assertTrue(largeHeadingError(wasShown = true, errorDeg = 27f))
        assertTrue(largeHeadingError(wasShown = true, errorDeg = 25f))
        assertFalse(largeHeadingError(wasShown = true, errorDeg = 24.99f))
        assertFalse(largeHeadingError(wasShown = true, errorDeg = 5f))
        // An estimate the sensor stops giving is no large error.
        assertFalse(largeHeadingError(wasShown = true, errorDeg = null))
    }

    @Test fun anErrorHoveringAroundThirtyPutsThePromptOnOnceAndTakesItOffOnce() {
        var shown = false
        val seen = ArrayList<Boolean>()
        for (e in floatArrayOf(29f, 29.9f, 30.5f, 29.5f, 30.2f, 28f, 26f, 25.1f, 24.9f, 26f, 29.9f, 30.1f)) {
            shown = largeHeadingError(shown, e); seen += shown
        }
        assertEquals(listOf(false, false, true, true, true, true, true, true, false, false, false, true), seen)
    }

    @Test fun theCompassPromptIsTheLevelOrTheLargeErrorAsShown() {
        assertTrue(calibrationPromptDue(1, largeHeadingError = false))
        assertTrue(calibrationPromptDue(3, largeHeadingError = true))
        assertTrue(calibrationPromptDue(null, largeHeadingError = true))
        assertFalse(calibrationPromptDue(3, largeHeadingError = false))
        assertFalse(calibrationPromptDue(null, largeHeadingError = false))
    }

    // "The screen stays on while calibration is open, for two minutes from opening or from the last change of the
    // ring, whichever is later." (v3 spec, "Calibration")

    @Test fun theScreenIsKeptOnForTwoMinutesFromOpening() {
        assertEquals(120_000L, CALIBRATION_AWAKE_MS)
        assertEquals(121_000L, calibrationAwakeUntil(openedAtMs = 1_000L, lastNewRegionAtMs = null))
        assertTrue(calibrationKeepsAwake(nowMs = 120_999L, openedAtMs = 1_000L, lastNewRegionAtMs = null))
        assertFalse(calibrationKeepsAwake(nowMs = 121_000L, openedAtMs = 1_000L, lastNewRegionAtMs = null))
    }

    @Test fun aNewSegmentKeepsItOnForTwoMinutesFromThen() {
        assertEquals(170_000L, calibrationAwakeUntil(openedAtMs = 1_000L, lastNewRegionAtMs = 50_000L))
        assertTrue(calibrationKeepsAwake(nowMs = 169_999L, openedAtMs = 1_000L, lastNewRegionAtMs = 50_000L))
        assertFalse(calibrationKeepsAwake(nowMs = 170_000L, openedAtMs = 1_000L, lastNewRegionAtMs = 50_000L))
        // Whichever is later: a segment from before the opening does not shorten it.
        assertEquals(121_000L, calibrationAwakeUntil(openedAtMs = 1_000L, lastNewRegionAtMs = 0L))
    }

    // The ring's memory. Owner, 2026-10-16, on how long the lit segments are kept: "Until accuracy drops
    // (Recommended)": they are kept across openings, restarts and the end of the app's process, and start over only
    // when the magnetometer reports that its accuracy has fallen to low or unreliable. A sensor that has just been
    // registered may report a low level before it reports the one it had (the start-up): such a report does not
    // clear the ring.

    private val unreliable = 0; private val low = 1; private val medium = 2; private val high = 3

    @Test fun aFallToLowOrUnreliableClearsTheRing() {
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = high, reported = low, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = high, reported = unreliable, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = medium, reported = low, sinceRegisteredMs = 10_000L, settled = true))
        // Low to unreliable is a fall to unreliable.
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = low, reported = unreliable, sinceRegisteredMs = 10_000L, settled = true))
        // No level stored yet: a low report clears (an empty ring, or one lit before any level was known).
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = null, reported = low, sinceRegisteredMs = 0L, settled = true))
    }

    @Test fun aFallThatStopsAboveLowOrARiseOnlyStoresTheLevel() {
        assertEquals(RingMemoryStep.STORE_LEVEL, ringMemoryStep(storedLevel = high, reported = medium, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.STORE_LEVEL, ringMemoryStep(storedLevel = low, reported = high, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.STORE_LEVEL, ringMemoryStep(storedLevel = unreliable, reported = low, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.STORE_LEVEL, ringMemoryStep(storedLevel = null, reported = high, sinceRegisteredMs = 0L, settled = true))
    }

    @Test fun theSameLevelOrNoLevelChangesNothing() {
        assertEquals(RingMemoryStep.KEEP, ringMemoryStep(storedLevel = high, reported = high, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.KEEP, ringMemoryStep(storedLevel = low, reported = low, sinceRegisteredMs = 10_000L, settled = true))
        assertEquals(RingMemoryStep.KEEP, ringMemoryStep(storedLevel = high, reported = null, sinceRegisteredMs = 10_000L, settled = true))
    }

    @Test fun aLowReportInTheStartUpBeforeTheStoredLevelCameDoesNotClear() {
        assertEquals(3_000L, RING_STARTUP_MS)
        assertEquals(RingMemoryStep.KEEP, ringMemoryStep(storedLevel = high, reported = unreliable, sinceRegisteredMs = 0L, settled = false))
        assertEquals(RingMemoryStep.KEEP, ringMemoryStep(storedLevel = high, reported = low, sinceRegisteredMs = 2_999L, settled = false))
        // Nor is a lower level that is not low stored then: the next start-up compares with the level kept.
        assertEquals(RingMemoryStep.KEEP, ringMemoryStep(storedLevel = high, reported = medium, sinceRegisteredMs = 500L, settled = false))
    }

    @Test fun aLowReportAfterTheStoredLevelCameOrAfterTheStartUpClears() {
        // The stored level (or better) has come since the sensor was registered: a fall now is a fall.
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = high, reported = low, sinceRegisteredMs = 500L, settled = true))
        // Three seconds on, a low level is the sensor's own, whatever came before.
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = high, reported = low, sinceRegisteredMs = 3_000L, settled = false))
        assertEquals(RingMemoryStep.CLEAR, ringMemoryStep(storedLevel = medium, reported = unreliable, sinceRegisteredMs = 60_000L, settled = false))
    }

    @Test fun theStartUpIsOverWithAReportAtTheStoredLevelOrBetter() {
        assertFalse(ringMemorySettles(settled = false, storedLevel = high, reported = null))
        assertFalse(ringMemorySettles(settled = false, storedLevel = high, reported = low))
        assertFalse(ringMemorySettles(settled = false, storedLevel = high, reported = medium))
        assertTrue(ringMemorySettles(settled = false, storedLevel = high, reported = high))
        assertTrue(ringMemorySettles(settled = false, storedLevel = medium, reported = high))
        // Nothing stored: there is nothing to wait for.
        assertTrue(ringMemorySettles(settled = false, storedLevel = null, reported = null))
        // Once over, it stays over.
        assertTrue(ringMemorySettles(settled = true, storedLevel = high, reported = low))
    }

    /** One run of the sensor as the view model follows it: the level kept and whether the ring was cleared. */
    private fun run(stored: Int?, reports: List<Pair<Long, Int?>>): Pair<Int?, Boolean> {
        var level = stored; var settled = false; var cleared = false
        for ((at, reported) in reports) {
            when (ringMemoryStep(level, reported, at, settled)) {
                RingMemoryStep.CLEAR -> { cleared = true; level = reported }
                RingMemoryStep.STORE_LEVEL -> level = reported
                RingMemoryStep.KEEP -> Unit
            }
            settled = ringMemorySettles(settled, level, reported)
        }
        return level to cleared
    }

    @Test fun aStartUpThatReportsUnreliableThenLowThenItsLevelKeepsTheRing() {
        assertEquals(high to false, run(high, listOf(0L to null, 20L to unreliable, 200L to low, 900L to high, 5_000L to high)))
    }

    @Test fun aFallAfterTheStartUpClearsTheRingOnce() {
        assertEquals(low to true, run(high, listOf(20L to unreliable, 900L to high, 10_000L to low, 10_020L to low)))
        // A sensor that stays low past the start-up is taken at its word.
        assertEquals(low to true, run(high, listOf(20L to low, 1_000L to low, 3_000L to low)))
    }
}
