package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

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
        val watchFace = paletteFor(ColorMode.WATCH_FACE, api37Blue)
        assertEquals(WaypointColors.RING, watchFace.waypoints)
        for (place in listOf(0f, 52.5f, 137.5f, 190f, 275f, 359f)) {
            assertEquals(wheelColor(place), waypointRgb(STANDARD_PALETTE, place))
            val r = watchFace.roles
            assertEquals(ringColor(place / 360f, r.accent, r.north, r.error), waypointRgb(watchFace, place))
        }
    }

    // Watch face: the roles

    @Test fun watchFaceTakesTheRolesFromTheScheme() {
        for (scheme in recorded) assertEquals(roleColors(scheme), paletteFor(ColorMode.WATCH_FACE, scheme).roles)
        assertEquals(RoleColors(0xB7CFFF, roleColors(api37Blue).north, 0xFFBEB9), paletteFor(ColorMode.WATCH_FACE, api37Blue).roles)
    }
    @Test fun thePalePrimaryOfTheApi35ImageIsDeepenedAndKept() {
        // v3 spec: "A pale primary is deepened and kept, not thrown away."
        val roles = paletteFor(ColorMode.WATCH_FACE, api35).roles
        assertNotEquals(standardRoles, roles)
        assertTrue("%06X".format(roles.accent), saturationOf(roles.accent) >= MIN_SATURATION)
        assertTrue("%06X".format(roles.accent), saturationOf(roles.accent) < MIN_SATURATION + 0.01f)
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
