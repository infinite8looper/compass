package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/** Where the readout's rows stand ([readoutRows]) and what the heading says to a screen reader ([headingSpoken]). */
class ReadoutRowsTest {
    /** The least air between two lines, in dial radii: 2 px on the smaller round screen (radius 192 px). */
    private val air = 2f / 192f

    private fun check(rows: ReadoutRows, name: String) {
        // The heading's digits end above the tallest letter of the row under them.
        assertTrue("$name: heading over the row under it: ${headingGap(rows.main, READOUT_BODY_SIZE)}", headingGap(rows.main, READOUT_BODY_SIZE) >= air)
        // A row with a descender (`No target`, `Arrived at target`, `Locating…`, `Saving…`) over `Hold flat` or `Calibrate`.
        assertTrue("$name: a descender over the prompt: ${rowGap(rows.main, READOUT_BODY_SIZE, true, rows.prompt, READOUT_BODY_SIZE, true)}",
            rowGap(rows.main, READOUT_BODY_SIZE, true, rows.prompt, READOUT_BODY_SIZE, true) >= air)
        // The same row over a status's second line (`Saved Waypoint 3` over `±16 ft`, `Needs access to` over `precise location`).
        assertTrue("$name: a descender over the smaller row: ${rowGap(rows.main, READOUT_BODY_SIZE, true, rows.detail, READOUT_DETAIL_SIZE, true)}",
            rowGap(rows.main, READOUT_BODY_SIZE, true, rows.detail, READOUT_DETAIL_SIZE, true) >= air)
    }

    @Test fun noTwoRowsTouchWithTheCoordinatesOff() = check(readoutRows(coordinates = false), "coordinates off")

    /**
     * v3 spec, "Readout with coordinates": "Latitude and longitude are two adjacent lines, at four decimal places and
     * no smaller than today, in the lower half of the readout." and "The prompt (`Calibrate`, `Hold flat`) and a
     * status's second line take the place the latitude had above." From the top: the prompt or a status's second
     * line, `MAG` and the trail's label, the name, the heading, the row under it, the latitude, the longitude.
     */
    @Test fun noTwoRowsTouchWithTheCoordinatesOn() {
        val rows = readoutRows(coordinates = true)
        // The heading's digits over the row under them.
        assertTrue("heading over the row under it: ${headingGap(rows.main, READOUT_BODY_SIZE)}", headingGap(rows.main, READOUT_BODY_SIZE) >= air)
        // A row with a descender (`No target`, `Saving…`) over the latitude, whose words can be `43.7022° N (old)`:
        // tall letters, and brackets that reach as low as a descender.
        val mainOverLatitude = rowGap(rows.main, READOUT_BODY_SIZE, true, rows.latitude, COORDINATES_SIZE, true)
        assertTrue("the row under the heading over the latitude: $mainOverLatitude", mainOverLatitude >= air)
        // The latitude (` (old)`) over the longitude's row, which can say `Locating…` or `Location is off`.
        val latitudeOverLongitude = rowGap(rows.latitude, COORDINATES_SIZE, true, rows.longitude, COORDINATES_SIZE, true)
        assertTrue("the latitude over the longitude: $latitudeOverLongitude", latitudeOverLongitude >= air)
        // `Calibrate` or `Hold flat` (no descender) over `MAG` and the trail's label (`Trail 12`: a tall letter).
        val promptOverTop = rowGap(rows.prompt, READOUT_BODY_SIZE, false, rows.top, READOUT_DETAIL_SIZE, true)
        assertTrue("the prompt over MAG: $promptOverTop", promptOverTop >= air)
        // A status's second line with a descender (`precise location`, `Try again outdoors`) over the same row.
        val detailOverTop = rowGap(rows.detail, READOUT_DETAIL_SIZE, true, rows.top, READOUT_DETAIL_SIZE, true)
        assertTrue("the smaller row over MAG: $detailOverTop", detailOverTop >= air)
        // `MAG` over the name, as before.
        assertTrue("MAG over the name", rowGap(rows.top, READOUT_DETAIL_SIZE, false, rows.name, READOUT_BODY_SIZE, true) >= air)
    }

    @Test fun theCoordinatesStandTogetherInTheLowerHalfAtTheirSizeAndThePromptsTakeTheLatitudesPlace() {
        val rows = readoutRows(coordinates = true)
        // In the lower half, under the row under the heading, the latitude first.
        assertTrue("latitude in the lower half: ${rows.latitude}", rows.latitude > rows.main)
        assertTrue("longitude under the latitude: ${rows.longitude}", rows.longitude > rows.latitude)
        // Adjacent: no other row stands between them, and their ink is no further apart than the air between rows needs
        // and a little more (under 2 px more on the smaller round screen).
        for ((name, row) in listOf("top" to rows.top, "name" to rows.name, "main" to rows.main, "detail" to rows.detail, "prompt" to rows.prompt))
            assertFalse("$name stands between the coordinates", row > rows.latitude && row < rows.longitude)
        val between = rowGap(rows.latitude, COORDINATES_SIZE, true, rows.longitude, COORDINATES_SIZE, true)
        assertTrue("the coordinates are not adjacent: $between", between < air + 2f / 192f)
        // No smaller than the coordinates were (v2: 0.070 of the radius).
        assertTrue("the coordinates' size: $COORDINATES_SIZE", COORDINATES_SIZE >= 0.070f)
        // The prompt and a status's second line stand above `MAG` and the trail's label, where the latitude was (-0.345),
        // within 2 px on the larger round screen (radius 227 px) either way.
        assertTrue("the prompt above MAG: ${rows.prompt}", rows.prompt < rows.top)
        assertTrue("the smaller row above MAG: ${rows.detail}", rows.detail < rows.top)
        assertEquals("the prompt in the latitude's place", -0.345f, rows.prompt, 4f / 227f)
        assertEquals("the smaller row in the latitude's place", -0.345f, rows.detail, 4f / 227f)
        // `MAG` and the trail's label, and the name, stay where they were.
        assertEquals(-0.27f, rows.top, 0f); assertEquals(-0.185f, rows.name, 0f)
    }

    /** The pane ticks show under the longitude for about a second around a swipe. Their places are the ones the screen draws them at. */
    @Test fun theLongitudesRowEndsAboveThePaneTicksAndTheRowsWithTheCoordinatesOffStayWhereTheyWere() {
        val rows = readoutRows(coordinates = true)
        val ticksTop = paneTicksTopOnCompass()
        // The current screen's tick is drawn with a round cap, half its width beyond each end: its ink begins that
        // much higher than the line's end (seen in the pictures: 2 px above it on the larger round screen).
        assertEquals(PANE_TICKS_ON_COMPASS - PANE_TICK_LENGTH_CURRENT * PANE_TICKS_SCALE - PANE_TICK_WIDTH * PANE_TICKS_SCALE / 2, ticksTop, 1e-6f)
        // The longitude itself, digits on their baseline: the same 2 px as every other pair.
        assertTrue("the longitude over the ticks", ticksTop - rowBottom(rows.longitude, COORDINATES_SIZE, descends = false) >= air)
        // `Locating…`, which stands in that row before the first fix, has a descender: it has the same 2 px.
        val underADescender = ticksTop - rowBottom(rows.longitude, COORDINATES_SIZE, descends = true)
        assertTrue("Locating… over the ticks: $underADescender", underADescender >= air)
        val off = readoutRows(coordinates = false)
        assertEquals(-0.31f, off.top, 0f); assertEquals(-0.195f, off.name, 0f); assertEquals(0.195f, off.main, 0f)
        assertEquals(0.30f, off.detail, 0f); assertEquals(0.31f, off.prompt, 0f)
    }

    /**
     * The calibrate prompt's tap area covers its word and reaches away from the middle of the dial: under the heading
     * it starts at the top of the word and reaches down; above it (the coordinates on) it starts at the bottom of the
     * word and reaches up. Either way it leaves out the heading's digits and the middle, where a double tap saves.
     */
    @Test fun theCalibratePromptsTapAreaCoversItsWordAndLeavesOutTheMiddle() {
        for (coordinates in listOf(false, true)) {
            val rows = readoutRows(coordinates)
            val area = promptTapArea(rows)
            val wordTop = rows.prompt - 0.42f * READOUT_BODY_SIZE
            val wordBottom = rows.prompt + 0.40f * READOUT_BODY_SIZE
            assertTrue("coordinates $coordinates: the area does not cover the word: ${area.top}..${area.bottom}", area.top <= wordTop && area.bottom >= wordBottom)
            assertEquals("coordinates $coordinates: its height", PROMPT_TAP_HEIGHT, area.bottom - area.top, 1e-6f)
            val digits = HEADING_DIGIT_TOP * READOUT_HEADING_SIZE
            assertTrue("coordinates $coordinates: the area reaches the heading: ${area.top}..${area.bottom}", area.bottom < -digits || area.top > digits)
        }
        // With the coordinates off, as in v2: from 0.055 above the word's middle, 0.52 down.
        val off = promptTapArea(readoutRows(false))
        assertEquals(0.31f - 0.055f, off.top, 1e-6f)
        val on = promptTapArea(readoutRows(true))
        assertEquals(readoutRows(true).prompt + 0.055f, on.bottom, 1e-6f)
        assertTrue("reaches up", on.reachesUp); assertFalse("reaches down", off.reachesUp)
    }

    @Test fun aGapIsTheDistanceFromTheUpperRowsLowestInkToTheLowerRowsHighest() {
        // Two rows of size 0.1, one radius apart: the upper one's descender reaches 0.058 below its middle and
        // the lower one's tallest letter 0.042 above its own.
        assertEquals(1f - 0.058f - 0.042f, rowGap(0f, 0.1f, true, 1f, 0.1f, true), 1e-6f)
        // Without a descender the upper row ends at its baseline; digits are a little lower than a tall letter.
        assertEquals(1f - 0.040f - 0.0375f, rowGap(0f, 0.1f, false, 1f, 0.1f, false), 1e-6f)
    }

    @Test fun theShownDegreeIsTheHeadingRoundedAndThereIsNoneWithoutAHeading() {
        assertEquals(NO_DEGREE, shownDegree(Float.NaN))
        assertEquals(0, shownDegree(0f)); assertEquals(0, shownDegree(0.49f)); assertEquals(1, shownDegree(0.51f))
        assertEquals(236, shownDegree(236.4f)); assertEquals(359, shownDegree(359.49f))
        // The last half degree before north is north: the number never reads 360.
        assertEquals(0, shownDegree(359.5f)); assertEquals(0, shownDegree(359.99f)); assertEquals(0, shownDegree(360f))
        // No degree that can be shown is taken for none.
        for (tenth in 0..3600) assertTrue("${tenth / 10f}", shownDegree(tenth / 10f) in 0..359)
    }

    @Test fun theHeadingsTextIsTheDegreeWithItsSignAndTwoDashesWithoutOne() {
        assertEquals("--", headingText(NO_DEGREE))
        assertEquals("0°", headingText(0)); assertEquals("7°", headingText(7)); assertEquals("236°", headingText(236)); assertEquals("359°", headingText(359))
        // What is shown is what is spoken.
        assertEquals("Heading 236 degrees", headingSpoken(headingText(shownDegree(236.4f)), magnetic = false))
        assertEquals("Heading unknown", headingSpoken(headingText(shownDegree(Float.NaN)), magnetic = false))
    }

    @Test fun theHeadingIsSpokenWithItsNameAndItsUnit() {
        assertEquals("Heading 236 degrees", headingSpoken("236°", magnetic = false))
        assertEquals("Heading 0 degrees", headingSpoken("0°", magnetic = false))
        assertEquals("Heading 1 degree", headingSpoken("1°", magnetic = false))
    }

    @Test fun aMagneticHeadingSaysSo() {
        assertEquals("Heading 314 degrees, magnetic", headingSpoken("314°", magnetic = true))
        assertEquals("Heading 1 degree, magnetic", headingSpoken("1°", magnetic = true))
    }

    @Test fun aHeadingThatIsNotKnownIsSpokenAsUnknown() {
        assertEquals("Heading unknown", headingSpoken("--", magnetic = false))
        assertEquals("Heading unknown", headingSpoken("--", magnetic = true))
    }
}
