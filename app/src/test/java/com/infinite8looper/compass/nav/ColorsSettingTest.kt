package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * The `Colors` setting (v3 spec, "Colors"): `Standard`, the default, and `Watch face`.
 *
 * The three schemes here are the ones the emulators gave, as `WatchColorsDeviceTest` logs them (primary, tertiary,
 * error, onSurface, onSurfaceVariant, outline), with the system's setting `dynamic_color_theme_enabled` put to 1
 * through adb, on 2026-10-14.
 */
class ColorsSettingTest {
    /** Wear OS 5.1 (API 35): the image's own fixed colors. Its primary has a saturation of 0.149. */
    private val api35 = WatchScheme(0xD9E2FF, 0xFDD7FA, 0xF2B8B5, 0xE2E2E9, 0xC5C6D0, 0x8F9099)
    /** Wear OS 7.0 (API 37) with the seed 2F8BFF written to `theme_customization_overlay_packages`. */
    private val api37Blue = WatchScheme(0xB7CFFF, 0xD3C8FF, 0xFFBEB9, 0xE4E8F4, 0xCACEDA, 0xA2A5B1)
    /** Wear OS 7.0 (API 37) with the seed 00AA55. */
    private val api37Green = WatchScheme(0xBBEFC2, 0xDCE9A1, 0xFFBEB9, 0xE0EBDE, 0xC6D1C4, 0x9EA99C)
    private val recorded = listOf(api35, api37Blue, api37Green)

    private val standardRoles = RoleColors(accent = 0x2F8BFF, north = 0xFF3B30, error = 0xFF3B30)

    // The stored value

    @Test fun nothingStoredIsStandard() {
        assertEquals(ColorMode.STANDARD, colorModeOf(null))
    }
    @Test fun theStoredNamesReadBack() {
        assertEquals(ColorMode.STANDARD, colorModeOf(ColorMode.STANDARD.name))
        assertEquals(ColorMode.WATCH_FACE, colorModeOf(ColorMode.WATCH_FACE.name))
    }
    @Test fun anUnreadableValueIsStandard() {
        for (bad in listOf("", "watch_face", "WATCH FACE", "Watch face", "true", "1", "STANDARD ", "\u0000")) {
            assertEquals(bad, ColorMode.STANDARD, colorModeOf(bad))
        }
    }

    // Standard

    @Test fun standardIsNorthRedAndThePointerBlue() {
        assertEquals(standardRoles, STANDARD_PALETTE.roles)
        assertEquals(WaypointColors.WHEEL, STANDARD_PALETTE.waypoints)
        assertEquals(Grays(ink = 0xE8EAED, dim = 0x9E9E9E, faint = 0x6E6E6E, label = 0x7C7C7C), STANDARD_PALETTE.grays)
    }
    @Test fun standardReadsNothingFromTheWatch() {
        assertEquals(STANDARD_PALETTE, paletteFor(ColorMode.STANDARD, null))
        for (scheme in recorded) assertEquals(STANDARD_PALETTE, paletteFor(ColorMode.STANDARD, scheme))
        assertEquals(STANDARD_PALETTE, paletteFor(ColorMode.STANDARD, WatchScheme(0x00FF00, 0xFF00FF, 0x00FFFF, 0x102030, 0x405060, 0x708090)))
    }
    @Test fun watchFaceWithNoSchemeIsStandard() {
        // "Where the watch gives no scheme (the library returns nothing), `Watch face` cannot be chosen."
        assertEquals(STANDARD_PALETTE, paletteFor(ColorMode.WATCH_FACE, null))
    }

    // The full wheel

    @Test fun theWheelIsEveryHueAtTheSaturationWaypointsHadInV2() {
        assertEquals(0.65f, WHEEL_SATURATION, 0f)
        assertEquals(0xFF5959, wheelColor(0f))
        assertEquals(0x59FF59, wheelColor(120f))
        assertEquals(0x5959FF, wheelColor(240f))
        assertEquals(0xFFFF59, wheelColor(60f))
        for (deg in 0 until 360) {
            val c = wheelColor(deg.toFloat())
            assertEquals("$deg", deg.toFloat(), hueOf(c), 1f)
            assertEquals("$deg", 0xFF, maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF))
            assertEquals("$deg", WHEEL_SATURATION, saturationOf(c), 0.005f)
        }
    }
    @Test fun theWheelWrapsAndAPlaceThatIsNotANumberIsZero() {
        assertEquals(wheelColor(0f), wheelColor(360f))
        assertEquals(wheelColor(240f), wheelColor(-120f))
        assertEquals(wheelColor(137.5f), wheelColor(497.5f))
        assertEquals(wheelColor(0f), wheelColor(Float.NaN))
        assertEquals(wheelColor(0f), wheelColor(Float.POSITIVE_INFINITY))
        assertEquals(0, wheelColor(359.99f) shr 24)
    }
    @Test fun everyWheelColorReadsAsALineAndItsSoftLineToo() {
        for (twentieth in 0 until 7200) {
            val c = wheelColor(twentieth / 20f)
            assertTrue("%06X".format(c), contrastOnBlack(c) >= MIN_LINE_CONTRAST)
            val soft = softLineColor(c)
            assertTrue("%06X".format(soft), contrastOnBlack(softTintOverBlack(soft)) >= MIN_LINE_CONTRAST)
        }
    }
    @Test fun aWaypointKeepsItsStoredPlaceInBothModes() {
        // The ring's third anchor is the palette's (the owner, 2026-10-14: "for themed colors make sure the wheel
        // has *3* anchorpoints"); until then it was always the error color. The ring is read turned so that the first
        // new waypoint's place is its third anchor (the owner, 2026-10-14: "new waypoint should be near some other
        // color"); until then a place was read straight, which put the first place near north.
        for (scheme in recorded) {
            val watchFace = paletteFor(ColorMode.WATCH_FACE, scheme)
            assertEquals(WaypointColors.RING, watchFace.waypoints)
            for (place in listOf(0f, 52.5f, 137.5f, 190f, 275f, 359f)) {
                assertEquals(wheelColor(place), waypointRgb(STANDARD_PALETTE, place))
                val r = watchFace.roles
                val turns = (place - FIRST_WAYPOINT_PLACE) / 360f + 2f / 3f
                assertEquals(ringColor(turns, r.accent, r.north, watchFace.ringThird), waypointRgb(watchFace, place))
            }
        }
    }

    // Where the first new waypoint's color is. The owner (2026-10-14): "new waypoint should be near some other
    // color-- when unthemed, how about near blue/green or yellow/green?"

    @Test fun inStandardTheFirstNewWaypointIsAYellowGreenOrGreen() {
        val first = waypointRgb(STANDARD_PALETTE, hueForIndex(0))
        assertEquals("%06X".format(first), 0x7AFF59, first)
        // Hue 108: between yellow-green (90) and green (120), far from the red (3) and the blue (213).
        assertEquals(108.3f, hueOf(first), 0.5f)
    }
    @Test fun inWatchFaceTheFirstNewWaypointIsTheRingsThirdAnchorNotNorthOrTheAccent() {
        fun check(what: String, scheme: WatchScheme) {
            val p = paletteFor(ColorMode.WATCH_FACE, scheme)
            val first = waypointRgb(p, hueForIndex(0))
            val all = "$what -> first %06X, accent %06X, north %06X, third %06X".format(first, p.roles.accent, p.roles.north, p.ringThird)
            assertEquals(all, p.ringThird, first)
            if (isGrayScheme(scheme)) {
                // A gray scheme (owner, 2026-10-16: "the marker colors and other elements should be gray or
                // gray-red"): the third anchor is a gray of the scheme, told from north and the gray-red pointer by
                // having almost no color.
                assertTrue(all, saturationOf(p.roles.north) - saturationOf(first) >= NORTH_POINTER_SATURATION_APART)
                assertTrue(all, saturationOf(p.roles.accent) - saturationOf(first) >= ACCENT_INK_SATURATION_APART)
            } else {
                assertTrue(all, hueApart(first, p.roles.north) >= MIN_HUE_APART)
                assertTrue(all, hueApart(first, p.roles.accent) >= MIN_HUE_APART)
            }
        }
        for (scheme in recorded) check("$scheme", scheme)
        val random = java.util.Random(20261017L)
        repeat(20_000) {
            val scheme = WatchScheme(random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000))
            check("$scheme", scheme)
        }
    }
    @Test fun inWatchFaceTheAccentAndNorthAreAThirdOfATurnEitherSideOfTheFirstPlace() {
        // The ring keeps its order (accent, north, third) and its thirds; only where it is read from moves.
        for (scheme in recorded) {
            val p = paletteFor(ColorMode.WATCH_FACE, scheme)
            assertEquals(p.roles.accent, waypointRgb(p, FIRST_WAYPOINT_PLACE + 120f))
            assertEquals(p.roles.north, waypointRgb(p, FIRST_WAYPOINT_PLACE + 240f))
        }
    }

    // Where new waypoints start. The owner (2026-10-14): "in standard (non-theme) colors use the full color wheel
    // and start with something away from the existing red/blue colors."

    @Test fun theFirstNewWaypointStartsInTheMiddleOfTheWidestGapBetweenNorthsRedAndThePointersBlue() {
        // The red's hue is 3.2 degrees and the blue's 213.5: the gap through yellow and green is 210.3 wide, and its
        // middle is 108.3.
        assertEquals(3.19f, hueOf(FALLBACK_NORTH), 0.01f)
        assertEquals(213.46f, hueOf(FALLBACK_ACCENT), 0.01f)
        assertEquals(108.33f, widestGapMiddle(FALLBACK_NORTH, FALLBACK_ACCENT), 0.01f)
        assertEquals(widestGapMiddle(FALLBACK_NORTH, FALLBACK_ACCENT), hueForIndex(0), 0.001f)
        val first = wheelColor(hueForIndex(0))
        assertTrue("%06X".format(first), hueApart(first, FALLBACK_NORTH) >= 100f)
        assertTrue("%06X".format(first), hueApart(first, FALLBACK_ACCENT) >= 100f)
    }
    @Test fun afterTheFirstTheWaypointsGoOnRoundTheFullWheelByTheGoldenAngle() {
        for (i in 1 until 50) assertEquals("$i", normalizeDeg(hueForIndex(0) + i * 137.508f), hueForIndex(i), 0.01f)
        // Every hue is reached: the first 50 leave no gap of the wheel wider than 15 degrees.
        val sorted = (0 until 50).map { hueForIndex(it) }.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a } + (sorted.first() + 360f - sorted.last())
        assertTrue("$gaps", gaps.max() < 15f)
    }
    @Test fun theWidestGapIsFoundEitherWayRoundAndForOneHue() {
        // 0 and 90: the gap from 90 round to 360 is the wider; 300 and 30 (across the top): the gap from 30 to 300.
        assertEquals(225f, widestGapMiddle(0xFF0000, fromHsv(90f, 1f, 1f)), 0.5f)
        assertEquals(165f, widestGapMiddle(fromHsv(300f, 1f, 1f), fromHsv(30f, 1f, 1f)), 0.5f)
        assertEquals(165f, widestGapMiddle(fromHsv(30f, 1f, 1f), fromHsv(300f, 1f, 1f)), 0.5f)
        // One hue twice: the whole wheel is the gap, and its middle is opposite.
        assertEquals(180f, widestGapMiddle(0xFF0000, 0xFF0000), 0.5f)
    }

    // The ring's three anchors. The owner (2026-10-14): "for themed colors make sure the wheel has *3* anchorpoints--
    // otherwise the wheel will have repetitions (i.e., not actually a wheel)".

    private fun assertThreeAnchorsApart(what: String, p: Palette) {
        val a = p.roles.accent; val n = p.roles.north; val t = p.ringThird
        val all = "$what -> %06X %06X %06X".format(a, n, t)
        assertTrue(all, hueApart(a, n) >= MIN_HUE_APART)
        assertTrue(all, hueApart(a, t) >= MIN_HUE_APART)
        assertTrue(all, hueApart(n, t) >= MIN_HUE_APART)
        // The third reads on black and is a color, as the error color it stands in for has to.
        assertTrue(all, contrastOnBlack(t) >= MIN_CONTRAST)
        assertTrue(all, saturationOf(t) >= MIN_SATURATION)
    }
    @Test fun theRingOfEveryRecordedSchemeHasThreeAnchorsApartInHue() {
        for (scheme in recorded) assertThreeAnchorsApart("$scheme", paletteFor(ColorMode.WATCH_FACE, scheme))
    }
    @Test fun theRingOfManyRandomSchemesHasThreeAnchorsApartInHue() {
        // Where the rule applies: a gray scheme (owner, 2026-10-16) has a gray-red ring instead
        // (theRingOfAGraySchemeIsGraysAndGrayReds).
        val random = java.util.Random(20261015L)
        repeat(20_000) {
            val scheme = WatchScheme(random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000))
            if (!isGrayScheme(scheme)) assertThreeAnchorsApart("$scheme", paletteFor(ColorMode.WATCH_FACE, scheme))
        }
    }
    @Test fun theSchemesErrorColorIsTheThirdAnchorWhereItIsApartFromBoth() {
        // Seed 2F8BFF: the error color 0xFFBEB9 (hue 4) is 144 degrees from the accent and 84 from north.
        val p = paletteFor(ColorMode.WATCH_FACE, api37Blue)
        assertEquals(0xFFBEB9, p.roles.error)
        assertEquals(0xFFBEB9, p.ringThird)
        assertEquals(0xFFBEB9, paletteFor(ColorMode.WATCH_FACE, api37Green).ringThird)
    }
    @Test fun whereTheErrorColorIsNotApartTheThirdIsMadeInTheWidestGapAndTheErrorColorIsUnchanged() {
        // API 35: the error color 0xF2B8B5 (hue 3) is only 58 degrees from north (hue 305).
        val p = paletteFor(ColorMode.WATCH_FACE, api35)
        assertTrue(hueApart(p.roles.error, p.roles.north) < MIN_HUE_APART)
        assertEquals(0xF2B8B5, p.roles.error)
        assertNotEquals(p.roles.error, p.ringThird)
        assertEquals(widestGapMiddle(p.roles.accent, p.roles.north), hueOf(p.ringThird), 1.5f)
        assertThreeAnchorsApart("api35", p)
    }
    @Test fun aSchemeWhosePrimaryGivesWayStillHasAThirdAnchorAndNotARepeatedRed() {
        // A scheme of grays: the roles are the app's own blue, red and red; the ring is not blue, red, red. (Until
        // 2026-10-16 a gray primary beside a color, 0x808080 with 0xFF7AA8, gave way too; the owner: "For a color
        // scheme like mine, North should be the red from the watch face, and the marker colors and other elements
        // should be gray or gray-red".)
        val p = paletteFor(ColorMode.WATCH_FACE, WatchScheme(0x808080, 0x9E9E9E, 0x6E6E6E))
        assertEquals(RoleColors(FALLBACK_ACCENT, FALLBACK_NORTH, FALLBACK_ERROR), p.roles)
        assertEquals(widestGapMiddle(FALLBACK_ACCENT, FALLBACK_NORTH), hueOf(p.ringThird), 1.5f)
        assertThreeAnchorsApart("grays", p)
    }

    // A gray scheme's ring. Owner, 2026-10-16: "For a color scheme like mine, North should be the red from the watch
    // face, and the marker colors and other elements should be gray or gray-red. I.e. North is the dominant theme
    // color and the waypoints and other UI elements come from other theme colors with interpolation".

    private val owner = WatchScheme(0xFFF9EB, 0xFFC0B7, 0xFFBFB0, 0xEDE8DA, 0xD3CEC1, 0xAAA699)

    /** The largest difference of a red, green or blue channel between [a] and [b]. */
    private fun channelsApart(a: Int, b: Int) = (0..2).maxOf { abs((a shr (8 * it) and 0xFF) - (b shr (8 * it) and 0xFF)) }

    /** Gray or gray-red: under [GRAY_SATURATION], or within 40 degrees of hue of north's red. */
    private fun grayOrGrayRed(rgb: Int, north: Int) = saturationOf(rgb) < GRAY_SATURATION || hueApart(rgb, north) <= 40f

    @Test fun theOwnersRingIsThePointerNorthAndAGrayOfTheScheme() {
        val p = paletteFor(ColorMode.WATCH_FACE, owner)
        val what = "%06X %06X %06X".format(p.roles.accent, p.roles.north, p.ringThird)
        // The third anchor is the scheme's outline, a gray, made to read on black.
        assertTrue(what, saturationOf(p.ringThird) < GRAY_SATURATION)
        assertTrue(what, contrastOnBlack(p.ringThird) >= MIN_CONTRAST)
        assertEquals(what, hueOf(owner.outline!!), hueOf(p.ringThird), 3f)
        // The ink and the grays are the scheme's, as for any scheme.
        assertEquals(graysFor(owner), p.grays)
    }
    @Test fun theOwnersFirstWaypointsAreGrayOrGrayRedAndEachItsOwnColor() {
        val p = paletteFor(ColorMode.WATCH_FACE, owner)
        val taken = ArrayList<Float>()
        val colors = ArrayList<Int>()
        repeat(8) {
            val place = nextWaypointPlace(taken.toFloatArray(), p)
            taken += place
            colors += waypointRgb(p, place)
        }
        val what = colors.joinToString { "%06X".format(it) } + " north %06X".format(p.roles.north)
        for (c in colors) {
            assertTrue(what, grayOrGrayRed(c, p.roles.north))
            assertTrue(what, contrastOnBlack(c) >= MIN_CONTRAST)
            // Not north's red; and not the pointer: the places skipped for the red and the blue keep a new waypoint at
            // least a twelfth of the way from the pointer to north, as on any ring.
            assertTrue(what, channelsApart(c, p.roles.north) >= 24)
            assertTrue(what, channelsApart(c, p.roles.accent) >= channelsApart(p.roles.accent, p.roles.north) / 12)
        }
        for (i in colors.indices) for (j in i + 1 until colors.size) assertTrue("$what: $i $j", channelsApart(colors[i], colors[j]) >= 12)
    }
    @Test fun theOwnersNorthIsRedderThanEveryNewWaypoint() {
        // Owner, 2026-10-16: "North is the dominant theme color". Watch-fix review 1, Important 1: north was a salmon
        // 0xFF7865 (0.60); it is the watch face's red now, and no waypoint of the first fifty is as red.
        val p = paletteFor(ColorMode.WATCH_FACE, owner)
        val taken = ArrayList<Float>()
        repeat(50) {
            val place = nextWaypointPlace(taken.toFloatArray(), p)
            taken += place
            val c = waypointRgb(p, place)
            assertTrue("$it: %06X north %06X".format(c, p.roles.north), saturationOf(c) < saturationOf(p.roles.north))
        }
        assertTrue("%06X".format(p.roles.north), saturationOf(p.roles.north) >= NORTH_GRAY_SATURATION)
    }

    // Watch-fix re-review 2, Minors 1 and 2: on a gray scheme the error color (#FF7C65 on the owner's) lies on the
    // ring between the pointer and north, and Waypoint 2 was #FF7F68, 3 apart in a channel. New waypoints now never
    // take a place whose color is within WAYPOINT_ROLE_CHANNELS_APART in a channel of the error color or the pointer.

    /** The places [count] new waypoints are given one after another in [p]. */
    private fun givenIn(p: Palette, count: Int): List<Float> {
        val taken = ArrayList<Float>()
        repeat(count) { taken += nextWaypointPlace(taken.toFloatArray(), p) }
        return taken
    }
    private fun assertAwayFromErrorAndPointer(what: String, p: Palette, places: List<Float>) {
        places.forEachIndexed { i, place ->
            val c = waypointRgb(p, place)
            val text = "$what: ${i + 1} at $place %06X, error %06X, pointer %06X".format(c, p.roles.error, p.roles.accent)
            assertTrue(text, channelsApart(c, p.roles.error) >= WAYPOINT_ROLE_CHANNELS_APART)
            assertTrue(text, channelsApart(c, p.roles.accent) >= WAYPOINT_ROLE_CHANNELS_APART)
        }
    }
    @Test fun aGraySchemesPaletteSaysSoAndNoOtherDoes() {
        assertTrue(paletteFor(ColorMode.WATCH_FACE, owner).gray)
        assertFalse(STANDARD_PALETTE.gray)
        assertFalse(paletteFor(ColorMode.STANDARD, owner).gray)
        for (scheme in recorded) assertFalse("$scheme", paletteFor(ColorMode.WATCH_FACE, scheme).gray)
    }
    @Test fun noneOfTheOwnersFirstFiftyNewWaypointsIsNearTheErrorColorOrThePointer() {
        assertEquals(24, WAYPOINT_ROLE_CHANNELS_APART)
        val p = paletteFor(ColorMode.WATCH_FACE, owner)
        assertEquals("%06X".format(p.roles.error), 0xFF7C65, p.roles.error)
        val places = givenIn(p, 50)
        assertAwayFromErrorAndPointer("owner", p, places)
        // Still each its own place and its own color.
        for (i in places.indices) for (j in i + 1 until places.size) {
            assertTrue("$i $j: ${places[i]} ${places[j]}", abs(shortestDelta(places[i], places[j])) >= 0.01f)
        }
        val colors = places.map { waypointRgb(p, it) }
        assertEquals(colors.joinToString { "%06X".format(it) }, colors.size, colors.toSet().size)
    }
    @Test fun onManyRandomGraySchemesNoNewWaypointIsNearTheErrorColorOrThePointer() {
        // Random schemes whose primary is a light gray (the owner's is #FFF9EB), so that most of them are gray ones.
        val random = java.util.Random(20261017L)
        var gray = 0
        repeat(2_000) {
            val v = 0x9F + random.nextInt(0x61)
            val primary = (v shl 16) or ((v - random.nextInt(10)) shl 8) or (v - random.nextInt(10))
            val scheme = WatchScheme(
                primary, random.nextInt(0x1000000), random.nextInt(0x1000000),
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
            )
            if (!isGrayScheme(scheme)) return@repeat
            gray++
            val p = paletteFor(ColorMode.WATCH_FACE, scheme)
            assertAwayFromErrorAndPointer("$scheme", p, givenIn(p, 20))
        }
        assertTrue("$gray", gray > 1_000)
    }
    @Test fun standardAndColorfulSchemesGiveTheSamePlacesAsBefore() {
        // Only a gray scheme passes over places for the error color and the pointer: the others give the places the
        // numbering gives with the red and the blue skipped, as nextWaypointPlace(taken) does.
        for (p in listOf(STANDARD_PALETTE) + recorded.map { paletteFor(ColorMode.WATCH_FACE, it) }) {
            val taken = ArrayList<Float>()
            repeat(50) {
                val place = nextWaypointPlace(taken.toFloatArray(), p)
                assertEquals(nextWaypointPlace(taken.toFloatArray()), place, 0f)
                taken += place
            }
        }
    }

    /**
     * The first 50 places new waypoints were given one after another by `nextWaypointPlace(taken)` at 5a10810, the
     * code before gray schemes passed over places for the error color and the pointer (watch-fix re-review 3,
     * Minor 1). Printed by that commit's own code; the palette took no part in the choice then.
     */
    private val placesBeforeTheGrayRule = floatArrayOf(
        108.32498f, 298.35693f, 180.91284f, 43.404907f, 245.83301f, 75.86499f, 148.45288f, 265.89697f, 318.4209f,
        128.38892f, 160.849f, 31.00879f, 95.928955f, 285.96094f, 63.468994f, 51.073242f, 136.05713f, 306.0249f,
        83.53296f, 273.56494f, 326.08887f, 115.99292f, 168.51685f, 253.50073f, 38.676758f, 241.10498f, 293.6289f,
        261.16895f, 313.69287f, 281.2329f, 156.1211f, 333.75684f, 103.59717f, 71.13721f, 123.66113f, 176.18506f,
        91.20117f, 143.7251f, 58.74121f, 131.3291f, 111.26514f, 321.36133f, 98.86914f, 163.78906f, 288.90137f,
        66.40918f, 248.77295f, 301.29688f, 33.94922f, 171.45703f,
    )
    @Test fun standardAndColorfulSchemesGiveThePlacesTheCodeGaveBeforeTheGrayRule() {
        for (p in listOf(STANDARD_PALETTE) + recorded.map { paletteFor(ColorMode.WATCH_FACE, it) }) {
            assertFalse(p.gray)
            val given = givenIn(p, placesBeforeTheGrayRule.size)
            placesBeforeTheGrayRule.forEachIndexed { i, before ->
                assertEquals("%06X %06X place ${i + 1}".format(p.roles.accent, p.roles.north), before, given[i], 0f)
            }
        }
    }

    /**
     * A gray palette whose ring is one gray and whose error color and pointer are that gray: every place is within
     * WAYPOINT_ROLE_CHANNELS_APART of them, so none is left to choose after passing over those (watch-fix re-review
     * 3, Minor 2). New waypoints are then given the places they would be given without passing over any for the
     * colors, each its own place, not the first place again and again.
     */
    @Test fun whereEveryPlaceIsNearTheErrorColorOrThePointerTheChoiceIsMadeWithoutPassingOverThem() {
        val oneGray = 0x8A8580
        val p = Palette(
            RoleColors(accent = oneGray, north = oneGray, error = oneGray),
            Grays(STANDARD_INK, STANDARD_DIM, STANDARD_FAINT, STANDARD_LABEL),
            WaypointColors.RING, ringThird = oneGray, gray = true,
        )
        for (i in 0 until 4096) assertTrue("place ${hueForIndex(i)}", nearTheRoles(p, hueForIndex(i)))
        val given = givenIn(p, placesBeforeTheGrayRule.size)
        placesBeforeTheGrayRule.forEachIndexed { i, before -> assertEquals("place ${i + 1}", before, given[i], 0f) }
        assertEquals(given.size, given.toSet().size)
    }
    @Test fun theRingOfAGraySchemeIsGraysAndGrayReds() {
        val random = java.util.Random(20261016L)
        var gray = 0
        repeat(20_000) {
            val scheme = WatchScheme(
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
            )
            if (!isGrayScheme(scheme)) return@repeat
            gray++
            val p = paletteFor(ColorMode.WATCH_FACE, scheme)
            val what = "$scheme -> %06X %06X %06X".format(p.roles.accent, p.roles.north, p.ringThird)
            assertTrue(what, saturationOf(p.ringThird) < GRAY_SATURATION)
            assertTrue(what, contrastOnBlack(p.ringThird) >= MIN_CONTRAST)
            assertTrue(what, hueApart(p.roles.accent, p.roles.north) <= 40f)
        }
        assertTrue("$gray", gray > 100)
    }

    // The accent and the ink. The owner (2026-10-14), on a pale accent: "Deepen to ~0.3 saturation".

    /**
     * The measure: the accent's saturation is at least [ACCENT_INK_SATURATION_APART] more than the ink's. Both are
     * near white where the scheme is pale, and the ink takes its hue from the scheme, so they can share a hue: what
     * tells them apart is how much color each has. The accent has at least 0.30 and the ink under 0.09.
     */
    private fun assertAccentApartFromInk(what: String, p: Palette) {
        val apart = saturationOf(p.roles.accent) - saturationOf(p.grays.ink)
        assertTrue("$what -> %06X %06X: %.3f".format(p.roles.accent, p.grays.ink, apart), apart >= ACCENT_INK_SATURATION_APART)
    }
    @Test fun theAccentIsToldApartFromTheInkOnTheRecordedSchemes() {
        assertEquals(0.2f, ACCENT_INK_SATURATION_APART, 0f)
        for (scheme in recorded) assertAccentApartFromInk("$scheme", paletteFor(ColorMode.WATCH_FACE, scheme))
        assertAccentApartFromInk("standard", STANDARD_PALETTE)
    }
    @Test fun theAccentIsToldApartFromTheInkOnManyRandomSchemes() {
        val random = java.util.Random(20261016L)
        repeat(20_000) {
            val scheme = WatchScheme(
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
            )
            assertAccentApartFromInk("$scheme", paletteFor(ColorMode.WATCH_FACE, scheme))
        }
        // The palest a primary can be and still be used, with an ink of its own hue.
        assertAccentApartFromInk("pale", paletteFor(ColorMode.WATCH_FACE, WatchScheme(0xE5E5FF, 0xFDD7FA, 0xF2B8B5, onSurface = 0xE5E5FF)))
    }

    // Watch face: the roles

    @Test fun watchFaceTakesTheRolesFromTheScheme() {
        for (scheme in recorded) assertEquals(roleColors(scheme), paletteFor(ColorMode.WATCH_FACE, scheme).roles)
        // The primary 0xB7CFFF has 0.28 and is deepened to 0.30 (the owner, 2026-10-14: "Deepen to ~0.3
        // saturation"); until then it was the accent as it was.
        val r = paletteFor(ColorMode.WATCH_FACE, api37Blue).roles
        assertEquals(RoleColors(r.accent, roleColors(api37Blue).north, 0xFFBEB9), r)
        assertEquals(hueOf(0xB7CFFF), hueOf(r.accent), 1.5f)
        assertEquals(ACCENT_MIN_SATURATION, saturationOf(r.accent), 0.01f)
    }
    @Test fun thePalePrimaryOfTheApi35ImageIsDeepenedAndKept() {
        // v3 spec: "A pale primary is deepened and kept, not thrown away." How deep, the owner (2026-10-14):
        // "Deepen to ~0.3 saturation"; until then to 0.15.
        val roles = paletteFor(ColorMode.WATCH_FACE, api35).roles
        assertNotEquals(standardRoles, roles)
        assertTrue("%06X".format(roles.accent), saturationOf(roles.accent) >= ACCENT_MIN_SATURATION)
        assertTrue("%06X".format(roles.accent), saturationOf(roles.accent) < ACCENT_MIN_SATURATION + 0.01f)
        assertEquals(hueOf(0xD9E2FF), hueOf(roles.accent), 1.5f)
        assertEquals(0xFF, roles.accent and 0xFF)
        assertTrue(contrastOnBlack(roles.accent) >= MIN_CONTRAST)
    }
    @Test fun everyRoleRuleHoldsOnTheRecordedSchemes() {
        for (scheme in recorded) {
            val r = paletteFor(ColorMode.WATCH_FACE, scheme).roles
            val what = "$scheme -> $r"
            assertTrue(what, contrastOnBlack(r.accent) >= MIN_CONTRAST)
            assertTrue(what, contrastOnBlack(r.north) >= MIN_CONTRAST)
            assertTrue(what, contrastOnBlack(r.error) >= MIN_CONTRAST)
            assertTrue(what, saturationOf(r.accent) >= MIN_SATURATION)
            assertTrue(what, saturationOf(r.north) >= NORTH_MIN_SATURATION)
            assertTrue(what, hueApart(r.accent, r.north) >= MIN_HUE_APART)
        }
    }
    @Test fun theRingOfEveryRecordedSchemeReadsAsLines() {
        for (scheme in recorded) {
            val p = paletteFor(ColorMode.WATCH_FACE, scheme)
            for (twentieth in 0 until 7200) {
                val c = waypointRgb(p, twentieth / 20f)
                assertTrue("%06X".format(c), contrastOnBlack(c) >= MIN_LINE_CONTRAST)
                assertTrue(contrastOnBlack(softTintOverBlack(softLineColor(c))) >= MIN_LINE_CONTRAST)
            }
        }
    }

    // Watch face: the ink and the grays

    @Test fun watchFaceTakesTheInkAndTheGraysFromTheSchemeWhereItGivesThem() {
        for (scheme in recorded) {
            val g = paletteFor(ColorMode.WATCH_FACE, scheme).grays
            assertNotEquals(STANDARD_PALETTE.grays.ink, g.ink)
            assertNotEquals(STANDARD_PALETTE.grays.dim, g.dim)
            assertNotEquals(STANDARD_PALETTE.grays.faint, g.faint)
            assertNotEquals(STANDARD_PALETTE.grays.label, g.label)
            // Each has the hue of the scheme's color it comes from.
            assertEquals("$scheme ink", hueOf(scheme.onSurface!!), hueOf(g.ink), 6f)
            assertEquals("$scheme dim", hueOf(scheme.onSurfaceVariant!!), hueOf(g.dim), 6f)
            assertEquals("$scheme faint", hueOf(scheme.outline!!), hueOf(g.faint), 6f)
            assertEquals("$scheme label", hueOf(scheme.outline), hueOf(g.label), 6f)
        }
    }
    @Test fun aSchemeWithoutTheInkAndTheGraysKeepsTheAppsOwn() {
        assertEquals(STANDARD_PALETTE.grays, graysFor(WatchScheme(0xB7CFFF, 0xD3C8FF, 0xFFBEB9)))
        assertEquals(STANDARD_PALETTE.grays, graysFor(null))
        val onlyInk = graysFor(WatchScheme(0xB7CFFF, 0xD3C8FF, 0xFFBEB9, onSurface = 0xE4E8F4))
        assertNotEquals(STANDARD_PALETTE.grays.ink, onlyInk.ink)
        assertEquals(STANDARD_PALETTE.grays.copy(ink = onlyInk.ink), onlyInk)
    }
    @Test fun eachGrayReadsOnBlackAtLeastAsWellAsTheAppsOwnAndStaysAGray() {
        for (scheme in recorded) assertGraysHold(scheme, graysFor(scheme))
    }
    @Test fun theGraysHoldForManyRandomSchemes() {
        val random = java.util.Random(20261014L)
        repeat(20_000) {
            val scheme = WatchScheme(
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
                random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000),
            )
            assertGraysHold(scheme, graysFor(scheme))
        }
    }
    @Test fun aGrayFromTheSchemeIsAsLightAsTheAppsOwnAndNoLighter() {
        // The brightness is the app's own for that role, so the order ink, dim, label, faint is kept.
        for (scheme in recorded) {
            val g = graysFor(scheme)
            val own = STANDARD_PALETTE.grays
            for ((mine, theirs) in listOf(g.ink to own.ink, g.dim to own.dim, g.faint to own.faint, g.label to own.label)) {
                assertEquals("%06X".format(mine), contrastOnBlack(theirs), contrastOnBlack(mine), 0.25)
            }
        }
    }

    private fun assertGraysHold(scheme: WatchScheme, g: Grays) {
        val own = STANDARD_PALETTE.grays
        val what = "$scheme -> $g"
        assertTrue(what, contrastOnBlack(g.ink) >= contrastOnBlack(own.ink))
        assertTrue(what, contrastOnBlack(g.dim) >= contrastOnBlack(own.dim))
        assertTrue(what, contrastOnBlack(g.faint) >= contrastOnBlack(own.faint))
        assertTrue(what, contrastOnBlack(g.label) >= contrastOnBlack(own.label))
        assertTrue(what, contrastOnBlack(g.ink) >= MIN_CONTRAST)
        assertTrue(what, contrastOnBlack(g.dim) >= MIN_CONTRAST)
        // A gray is never taken for a color: under the saturation below which a primary counts as gray.
        for (c in listOf(g.ink, g.dim, g.faint, g.label)) assertTrue(what, saturationOf(c) < GRAY_SATURATION)
        assertTrue(what, contrastOnBlack(g.ink) > contrastOnBlack(g.dim))
        assertTrue(what, contrastOnBlack(g.dim) > contrastOnBlack(g.label))
        assertTrue(what, contrastOnBlack(g.label) > contrastOnBlack(g.faint))
    }

    // The settings row

    @Test fun theRowsWordsAreTheSpecsProposal() {
        assertEquals("Colors", COLORS_LABEL)
        assertEquals("Standard", colorsRow(ColorMode.STANDARD, available = true).detail)
        assertEquals("Watch face", colorsRow(ColorMode.WATCH_FACE, available = true).detail)
        assertEquals("Not available on this watch", colorsRow(ColorMode.STANDARD, available = false).detail)
        assertEquals("Not available on this watch", colorsRow(ColorMode.WATCH_FACE, available = false).detail)
    }
    @Test fun theRowsClickLabelSaysWhatATapDoesAsTheOwnerApproved() {
        // The owner (2026-10-14) approved `Use watch face colors` / `Use standard colors`.
        assertEquals("Use watch face colors", colorsRow(ColorMode.STANDARD, available = true).clickLabel)
        assertEquals("Use standard colors", colorsRow(ColorMode.WATCH_FACE, available = true).clickLabel)
        // A row that takes no tap offers no click.
        assertNull(colorsRow(ColorMode.STANDARD, available = false).clickLabel)
        assertNull(colorsRow(ColorMode.WATCH_FACE, available = false).clickLabel)
    }
    @Test fun aTapOnTheRowGivesTheOtherChoice() {
        assertEquals(ColorMode.WATCH_FACE, colorsRow(ColorMode.STANDARD, available = true).tapGives)
        assertEquals(ColorMode.STANDARD, colorsRow(ColorMode.WATCH_FACE, available = true).tapGives)
    }
    @Test fun withNoSchemeTheRowTakesNoTap() {
        // "Where the watch gives no scheme (the library returns nothing), `Watch face` cannot be chosen and the row says so."
        assertNull(colorsRow(ColorMode.STANDARD, available = false).tapGives)
        assertNull(colorsRow(ColorMode.WATCH_FACE, available = false).tapGives)
    }
}
