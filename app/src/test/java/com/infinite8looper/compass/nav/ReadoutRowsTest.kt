package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/** Where the readout's rows stand ([readoutRows]) and what the heading says to a screen reader ([headingSpoken]). */
class ReadoutRowsTest {
    /** The least air between two lines, in dial radii: 2 px on the smaller round screen (radius 192 px). */
    private val air = 2f / 192f

    /** The radii, in pixels, of the two round screens the pictures are taken on. */
    private val SCREENS = listOf(192, 227)

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
        // Counted in whole rows of pixels, as the pictures are: at 2.5 px of air above, `Hold flat` over `MAG` left one
        // clear row on the smaller round screen (v3 Task 8 review). Two on both screens.
        for (radius in SCREENS) {
            val clear = clearRows(rowBottom(rows.prompt, READOUT_BODY_SIZE, descends = false), rows.top - TALL_LETTER_TOP * READOUT_DETAIL_SIZE, radius)
            assertTrue("the prompt over MAG on a screen of radius $radius px: $clear clear rows", clear >= 2)
        }
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

    /**
     * The owner, 2026-10-16: "Keep together when free (Recommended)". When the coordinates are not showing, a status's
     * second line goes in the latitude's row, right under the first line (`Needs access to` / `precise location`, and
     * the failures with no position); when they are showing (`Saved Waypoint 1` with `±16 ft`), it keeps the top.
     */
    @Test fun aStatusSecondLineStandsRightUnderItsFirstWhenNoCoordinateRowShows() {
        val rows = readoutRows(coordinates = true)
        fun lines(status: LogStatus, position: GeoPoint?, noPosition: LogFailure? = null, stale: Boolean = false) = readout(
            status = status, units = Units.IMPERIAL, focus = FocusKind.NONE, distanceM = null, arrived = false, noTarget = false,
            position = position, stale = stale, fixAccuracyM = 5f, noPosition = noPosition, showCoordinates = true, holdFlat = false,
        )
        val here = GeoPoint(43.7022, -72.2896)
        // Not showing: the permission is not held, or a press failed with no position known. Under the first line.
        val needsAccess = lines(LogStatus.Failed(LogFailure.NO_PERMISSION), null, LogFailure.NO_PERMISSION)
        assertNull(needsAccess.latitude); assertNull(needsAccess.longitude); assertNotNull(needsAccess.detail)
        assertEquals(rows.detailAlone, detailRow(rows, needsAccess), 0f)
        val noFix = lines(LogStatus.Failed(LogFailure.NO_FIX), null)
        assertNull(noFix.latitude); assertNull(noFix.longitude)
        assertEquals(rows.detailAlone, detailRow(rows, noFix), 0f)
        // Showing: a save with its accuracy, and a failure while an old position is known. At the top.
        val saved = lines(LogStatus.Saved("Waypoint 1", 5f, 0L), here)
        assertNotNull(saved.latitude)
        assertEquals(rows.detail, detailRow(rows, saved), 0f)
        val noFixOld = lines(LogStatus.Failed(LogFailure.NO_FIX), here, stale = true)
        assertNotNull(noFixOld.latitude)
        assertEquals(rows.detail, detailRow(rows, noFixOld), 0f)
        // `Locating…` alone in the longitude's row is showing too: the second line keeps the top, clear of it. The
        // state is real: a save that takes the location provider's last known position, or a fix that arrives for
        // the press, shows `Saving…` with its accuracy (CompassViewModel) before the compass has a position of its own.
        val savingLocating = lines(LogStatus.Saving(5f), null)
        assertNull(savingLocating.latitude); assertNotNull(savingLocating.longitude)
        assertEquals("the second line there", "±16 ft", savingLocating.detail?.words)
        assertEquals(rows.detail, detailRow(rows, savingLocating), 0f)

        // The latitude's row: within 2 px of it on the larger round screen, right under the first line.
        assertEquals("the second line in the latitude's row", rows.latitude, rows.detailAlone, 2f / 227f)
        assertTrue("under the row under the heading", rows.detailAlone > rows.main)
        for ((name, row) in listOf("top" to rows.top, "name" to rows.name, "prompt" to rows.prompt, "detail" to rows.detail))
            assertFalse("$name stands between the two lines", row > rows.main && row < rows.detailAlone)
        val under = rowGap(rows.main, READOUT_BODY_SIZE, true, rows.detailAlone, READOUT_DETAIL_SIZE, true)
        assertTrue("the first line over the second: $under", under >= air)
        val overTicks = paneTicksTopOnCompass() - rowBottom(rows.detailAlone, READOUT_DETAIL_SIZE, descends = true)
        assertTrue("the second line over the pane ticks: $overTicks", overTicks >= air)
        for (radius in SCREENS) {
            val clear = clearRows(rowBottom(rows.main, READOUT_BODY_SIZE, descends = true), rows.detailAlone - TALL_LETTER_TOP * READOUT_DETAIL_SIZE, radius)
            assertTrue("the first line over the second on a screen of radius $radius px: $clear clear rows", clear >= 2)
        }
        // With the coordinates switched off nothing changes: under the row under the heading, as in v2.
        val off = readoutRows(coordinates = false)
        assertEquals(0.30f, off.detailAlone, 0f)
        assertEquals(0.30f, detailRow(off, needsAccess), 0f); assertEquals(0.30f, detailRow(off, saved), 0f)
    }

    /**
     * A status's second line, `Try again outdoors` the widest (the full width of its slot), stays inside the circle the
     * cardinal letters sweep wherever it stands: at the top, where that circle is narrow, its slot is narrower, and
     * the words are drawn smaller to fit it (down to three quarters) before they are cut short.
     */
    @Test fun aSecondLineStaysInsideTheReadoutCircleInEveryPlace() {
        val margin = 1f / 192f
        for (coordinates in listOf(false, true)) {
            val rows = readoutRows(coordinates)
            for (row in listOf(rows.detail, rows.detailAlone)) {
                val width = detailWidth(row)
                assertTrue("no wider than the slot under the heading: $width", width <= DETAIL_WIDTH + 1e-6f)
                // The slot's corners at the top of a tall letter and at the bottom of a descender.
                for (y in listOf(row - TALL_LETTER_TOP * READOUT_DETAIL_SIZE, row + DESCENDER_BOTTOM * READOUT_DETAIL_SIZE)) {
                    val corner = kotlin.math.sqrt((width / 2) * (width / 2) + y * y)
                    assertTrue("coordinates $coordinates, row $row: a corner at ${corner} of the radius", corner <= READOUT_RADIUS - margin)
                }
            }
        }
        // Under the heading the slot is as wide as before.
        assertEquals(DETAIL_WIDTH, detailWidth(readoutRows(false).detail), 0f)
        assertEquals(DETAIL_WIDTH, detailWidth(readoutRows(true).detailAlone), 0f)
        // `Try again outdoors` is 0.615 of the radius wide at full size (118 px on the smaller round screen): drawn at
        // three quarters it fits the top slot.
        assertTrue(0.615f * 0.75f <= detailWidth(readoutRows(true).detail))
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

    /**
     * A clear row is a whole row of pixels with no ink: on a screen of radius 192 px whose middle is at 192 px, ink that
     * ends at 131.3 px reaches into row 131, and ink that begins at 133.8 px is in row 133, so only row 132 is clear
     * (2.5 px of air, one clear row: `Hold flat` over `MAG` in the v3 Task 8 pictures).
     */
    @Test fun clearRowsAreTheWholeRowsOfPixelsBetweenTwoLinesInk() {
        assertEquals(1, clearRows((131.3f - 192f) / 192f, (133.8f - 192f) / 192f, 192))
        // Ink ending exactly on a row's edge leaves that row clear.
        assertEquals(2, clearRows((131f - 192f) / 192f, (133.5f - 192f) / 192f, 192))
        assertEquals(2, clearRows((131.5f - 227f) / 227f, (134.5f - 227f) / 227f, 227))
        // Touching or overlapping: none.
        assertTrue(clearRows(0.1f, 0.1f, 192) <= 0); assertTrue(clearRows(0.2f, 0.1f, 192) < 0)
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
