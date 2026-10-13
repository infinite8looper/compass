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
        assertTrue("the latitude over MAG", rowGap(rows.latitude, COORDINATES_SIZE, false, rows.top, READOUT_DETAIL_SIZE, true) > 0f)
        assertTrue("MAG over the name", rowGap(rows.top, READOUT_DETAIL_SIZE, false, rows.name, READOUT_BODY_SIZE, true) > 0f)
    }

    @Test fun theRowsAboveTheHeadingStayWhereTheyWereAndTheLongitudeIsInsideTheReadout() {
        val rows = readoutRows(coordinates = true)
        assertEquals(-0.345f, rows.latitude, 0f); assertEquals(-0.27f, rows.top, 0f); assertEquals(-0.185f, rows.name, 0f)
        // The longitude ends above the pane ticks: the tallest one starts 0.401 of the radius below the middle (0.465 - 0.085 * 0.75).
        assertTrue(rows.longitude + 0.5f * COORDINATES_SIZE < 0.401f - 2f / 192f)
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
