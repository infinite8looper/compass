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

    @Test fun noTwoRowsTouchWithTheCoordinatesOn() {
        val rows = readoutRows(coordinates = true)
        check(rows, "coordinates on")
        // `Calibrate`, which has no descender, over the longitude, which is digits.
        assertTrue("Calibrate over the longitude: ${rowGap(rows.prompt, READOUT_BODY_SIZE, false, rows.longitude, COORDINATES_SIZE, false)}",
            rowGap(rows.prompt, READOUT_BODY_SIZE, false, rows.longitude, COORDINATES_SIZE, false) >= air)
        // A status's second line with a descender (`precise location`) over the longitude.
        assertTrue("the smaller row over the longitude", rowGap(rows.detail, READOUT_DETAIL_SIZE, true, rows.longitude, COORDINATES_SIZE, false) >= air)
        // The latitude, digits, over `MAG` and the trail's label, and those over the name.
        assertTrue("the latitude over MAG", rowGap(rows.latitude, COORDINATES_SIZE, false, rows.top, READOUT_DETAIL_SIZE, true) >= air)
        assertTrue("MAG over the name", rowGap(rows.top, READOUT_DETAIL_SIZE, false, rows.name, READOUT_BODY_SIZE, true) >= air)
    }

    @Test fun theRowsAboveTheHeadingStayWhereTheyWereWithTheCoordinatesOn() {
        val rows = readoutRows(coordinates = true)
        assertEquals(-0.345f, rows.latitude, 0f); assertEquals(-0.27f, rows.top, 0f); assertEquals(-0.185f, rows.name, 0f)
    }

    /** The pane ticks show under the longitude for about a second around a swipe. Their places are the ones the screen draws them at. */
    @Test fun theLongitudesRowEndsAboveThePaneTicksAndTheRowsWithTheCoordinatesOffStayWhereTheyWere() {
        val rows = readoutRows(coordinates = true)
        val ticksTop = paneTicksTopOnCompass()
        assertEquals(PANE_TICKS_ON_COMPASS - PANE_TICK_LENGTH_CURRENT * PANE_TICKS_SCALE, ticksTop, 0f)
        // The longitude itself, digits on their baseline: the same 2 px as every other pair.
        assertTrue("the longitude over the ticks", ticksTop - rowBottom(rows.longitude, COORDINATES_SIZE, descends = false) >= air)
        // `Locating…`, which stands in that row before the first fix, has a descender. There is not room for 2 px
        // under it as well as under `Calibrate` above the row; it has 1.5 px on the smaller screen and does not touch.
        val underADescender = ticksTop - rowBottom(rows.longitude, COORDINATES_SIZE, descends = true)
        assertTrue("Locating… over the ticks: $underADescender", underADescender >= 1.5f / 192f)
        val off = readoutRows(coordinates = false)
        assertEquals(-0.31f, off.top, 0f); assertEquals(-0.195f, off.name, 0f); assertEquals(0.195f, off.main, 0f)
        assertEquals(0.30f, off.detail, 0f); assertEquals(0.31f, off.prompt, 0f)
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
