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
     * for the interference picture) points somewhere other than the earth's field, so it covers nothing.
     */
    @Test fun aFieldOfAStrengthTheEarthsCannotHaveCoversNothing() {
        assertEquals(0, nextCoverage(0, 0f, 80f, -45f))
        assertEquals(0, nextCoverage(0, 0f, 2f, -4f))
        val one = nextCoverage(0, 0f, 20f, -45f)
        assertEquals(one, nextCoverage(one, 45f, 0f, 80f))
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

    @Test fun thePromptShowsAtALowOrUnreliableMagnetometerAsBefore() {
        assertTrue(calibrationPromptDue(1, null)); assertTrue(calibrationPromptDue(0, null))
        assertTrue(calibrationPromptDue(-1, null))   // SENSOR_STATUS_NO_CONTACT is below low too
        assertFalse(calibrationPromptDue(2, null)); assertFalse(calibrationPromptDue(3, null))
        assertFalse(calibrationPromptDue(null, null))
    }

    @Test fun thePromptShowsForAHeadingErrorAboveTheThresholdWhateverTheLevel() {
        assertTrue(calibrationPromptDue(3, 30.01f)); assertTrue(calibrationPromptDue(2, 45f)); assertTrue(calibrationPromptDue(null, 90f))
        assertFalse(calibrationPromptDue(3, 30f)); assertFalse(calibrationPromptDue(3, 29.9f)); assertFalse(calibrationPromptDue(null, 5f))
        assertTrue(calibrationPromptDue(1, 5f))
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
}
