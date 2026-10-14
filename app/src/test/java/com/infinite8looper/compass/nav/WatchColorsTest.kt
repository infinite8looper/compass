package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class WatchColorsTest {
    private val blue = 0x2F8BFF
    private val red = 0xFF3B30
    // What the Wear OS 7.0 emulator's system made of a blue seed: its primary, its tertiary and its error color.
    private val paleBlue = 0xB7CFFF
    private val paleViolet = 0xD3C8FF
    private val paleRed = 0xFFBEB9
    // paleBlue and 0xB8FEC4 as accents: their saturations, 0.28 and 0.27, are raised to 0.30. The owner (2026-10-14),
    // on a pale accent: "Deepen to ~0.3 saturation". Until then each was the accent as it was.
    private val paleBlueAccent = 0xB2CCFF
    private val paleGreenAccent = 0xB1FEBE

    private fun roles(primary: Int, tertiary: Int, error: Int = paleRed) = roleColors(WatchScheme(primary, tertiary, error))
    private fun brightest(rgb: Int) = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)

    // Without a scheme, and with the app's own pair

    @Test fun aWatchWithNoSchemeGetsTheBlueAndTheRed() {
        assertEquals(RoleColors(accent = 0x2F8BFF, north = 0xFF3B30, error = 0xFF3B30), roleColors(null))
        assertEquals(0x2F8BFF, FALLBACK_ACCENT)
        assertEquals(0xFF3B30, FALLBACK_NORTH)
        assertEquals(0xFF3B30, FALLBACK_ERROR)
    }
    @Test fun theBlueAndTheRedPassThroughUnchangedWhenASchemeHoldsThem() {
        assertEquals(RoleColors(blue, red, red), roles(blue, red, red))
    }
    @Test fun aSchemeThatReadsAndIsWellApartIsUsedAsItIs() {
        assertEquals(RoleColors(paleGreenAccent, 0xFF7AA8, paleRed), roles(0xB8FEC4, 0xFF7AA8))
        assertEquals(RoleColors(paleBlueAccent, 0xFF6B4A, paleRed), roles(paleBlue, 0xFF6B4A))
        assertEquals(RoleColors(0x9FC0FF, 0xFF6B4A, paleRed), roles(0x9FC0FF, 0xFF6B4A))
    }
    @Test fun onlyTheRedGreenAndBlueOfASchemeColorCount() {
        assertEquals(roles(0xB8FEC4, 0xFF7AA8), roleColors(WatchScheme(0xFFB8FEC4.toInt(), 0xFFFF7AA8.toInt(), 0xFFFFBEB9.toInt())))
        assertEquals(RoleColors(paleGreenAccent, 0xFF7AA8, paleRed), roleColors(WatchScheme(0x00B8FEC4, 0x80FF7AA8.toInt(), 0x10FFBEB9)))
    }

    // Contrast against black

    @Test fun contrastOnBlackIsTheStandardRatio() {
        assertEquals(21.0, contrastOnBlack(0xFFFFFF), 0.001)
        assertEquals(1.0, contrastOnBlack(0x000000), 0.001)
        assertEquals(6.26, contrastOnBlack(blue), 0.01)
        assertEquals(5.92, contrastOnBlack(red), 0.01)
        assertEquals(5.32, contrastOnBlack(0x808080), 0.01)
        assertEquals(2.44, contrastOnBlack(0x0000FF), 0.01)
    }
    @Test fun theLeastContrastIsWhatSmallTextNeedsAndTheAppsOwnColorsHaveIt() {
        assertEquals(4.5, MIN_CONTRAST, 0.0)
        assertTrue(contrastOnBlack(FALLBACK_ACCENT) >= MIN_CONTRAST)
        assertTrue(contrastOnBlack(FALLBACK_NORTH) >= MIN_CONTRAST)
    }
    @Test fun aDarkColorIsLightenedUntilItReadsAndKeepsItsHue() {
        val navy = 0x000080
        val accent = roles(navy, paleRed).accent
        assertNotEquals(navy, accent)
        assertTrue(contrastOnBlack(accent) >= MIN_CONTRAST)
        // Just enough and no more: it stops within a step of the least contrast.
        assertTrue(contrastOnBlack(accent) < MIN_CONTRAST + 0.15)
        assertEquals(240f, hueOf(accent), 1.5f)
        assertTrue(saturationOf(accent) >= MIN_SATURATION)
    }
    @Test fun lighteningNeverTakesAChannelDown() {
        // Dark colors with something in every channel, so each of the three comparisons can fail.
        for (dark in listOf(0x402010, 0x103050, 0x083A12, 0x30103A, 0x000080)) {
            val accent = roles(dark, paleRed).accent
            assertNotEquals(dark, accent)
            assertTrue("%06X red".format(dark), (accent shr 16 and 0xFF) > (dark shr 16 and 0xFF))
            assertTrue("%06X green".format(dark), (accent shr 8 and 0xFF) > (dark shr 8 and 0xFF))
            assertTrue("%06X blue".format(dark), (accent and 0xFF) >= (dark and 0xFF))
            assertTrue(contrastOnBlack(accent) >= MIN_CONTRAST)
        }
    }
    @Test fun pureBlueIsTooDarkAndIsLightened() {
        val accent = roles(0x0000FF, paleRed).accent
        assertTrue(contrastOnBlack(accent) >= MIN_CONTRAST)
        assertEquals(240f, hueOf(accent), 1.5f)
        assertEquals(0xFF, accent and 0xFF)
    }
    @Test fun aColorThatAlreadyReadsIsNotTouched() {
        // paleBlue itself is now deepened (see paleBlueAccent); what it is deepened to is not touched again.
        assertEquals(paleBlueAccent, roles(paleBlueAccent, paleRed).accent)
        assertEquals(0x9FC0FF, roles(0x9FC0FF, paleRed).accent)
        // A color exactly as light as it has to be.
        val edge = roles(0x000080, paleRed).accent
        assertEquals(edge, roles(edge, paleRed).accent)
    }
    @Test fun aNearBlackPrimaryHasNoColorToKeepAndGivesTheBlue() {
        assertEquals(blue, roles(0x101010, paleRed).accent)
        assertEquals(blue, roles(0x000000, paleRed).accent)
        // Lightened until it reads, this one is a gray with no color left in it.
        assertEquals(blue, roles(0x0A0808, paleRed).accent)
    }
    @Test fun aDarkErrorColorIsLightenedToo() {
        val error = roles(paleBlue, paleViolet, error = 0x8C1D18).error
        assertTrue(contrastOnBlack(error) >= MIN_CONTRAST)
        assertEquals(hueOf(0x8C1D18), hueOf(error), 1.5f)
    }

    // Gray

    @Test fun saturationIsTheShareOfTheBrightestChannelThatIsColor() {
        assertEquals(0f, saturationOf(0x808080), 0f)
        assertEquals(0f, saturationOf(0x000000), 0f)
        assertEquals(1f, saturationOf(0xFF0000), 0f)
        assertEquals(0.28f, saturationOf(paleBlue), 0.005f)
        assertEquals(0.02f, saturationOf(0xE8EAED), 0.005f)
    }
    @Test fun aGrayPrimaryGivesTheBlue() {
        assertEquals(blue, roles(0x808080, paleRed).accent)
        assertEquals(blue, roles(0xFFFFFF, paleRed).accent)
        assertEquals(blue, roles(0xE8EAED, paleRed).accent)
        // A gray with a little warmth is still a gray among the ink and the dim text.
        assertEquals(blue, roles(0x8A8580, paleRed).accent)
    }
    @Test fun aPrimaryWithTheAccentsLeastSaturationIsUsedAsItIs() {
        // The owner (2026-10-14), on a pale accent: "Deepen to ~0.3 saturation". Until then a primary of 0.15 or
        // more was used as it was (0xD6D6FF, 0.16, and paleViolet, 0.22); now one of 0.30 or more is.
        assertEquals(0.15f, MIN_SATURATION, 0f)
        assertEquals(0.30f, ACCENT_MIN_SATURATION, 0f)
        assertEquals(blue, roles(blue, paleRed).accent)
        assertNotEquals(0xB3B3FF, roles(0xB3B3FF, paleRed).accent)   // 0.298: deepened
        for (kept in listOf(0xB2B2FF, 0x9FC0FF, 0xFF9999)) {    // 0.30, 0.38, 0.40
            assertTrue(saturationOf(kept) >= ACCENT_MIN_SATURATION)
            assertEquals("%06X".format(kept), kept, roles(kept, paleRed).accent)
        }
    }
    @Test fun aPalePrimaryIsDeepenedToTheAccentsLeastSaturationAndKept() {
        // v3 spec ("Colors"): "A pale primary is deepened and kept, not thrown away." Until v3 0xDBDBFF (0.14) and
        // the Wear OS 5.1 image's fixed primary 0xD9E2FF (0.149) gave the blue. How deep, the owner (2026-10-14):
        // "Deepen to ~0.3 saturation". So every primary from the saturation of a gray up to 0.30 is raised to 0.30:
        // until then these were raised only to 0.15 (the first two) or used as they were (the others).
        for (pale in listOf(0xDBDBFF, 0xD9E2FF, 0xE5E5FF, 0xD6D6FF, paleViolet, paleBlue, 0xBBEFC2)) {
            val accent = roles(pale, paleRed).accent
            val what = "%06X -> %06X".format(pale, accent)
            assertNotEquals(what, blue, accent)
            // Deepened just to that saturation: the same hue and the same brightness.
            assertTrue(what, saturationOf(accent) >= ACCENT_MIN_SATURATION)
            assertTrue(what, saturationOf(accent) < ACCENT_MIN_SATURATION + 0.01f)
            assertEquals(what, hueOf(pale), hueOf(accent), 1.5f)
            assertEquals(what, brightest(pale), brightest(accent))
            assertTrue(what, contrastOnBlack(accent) >= MIN_CONTRAST)
        }
    }
    @Test fun aPrimaryBelowTheSaturationOfAGrayIsStillNotUsed() {
        // The least saturation a primary needs to be deepened rather than taken for a gray: the grays of the
        // schemes the emulators gave have up to 0.085.
        assertEquals(0.10f, GRAY_SATURATION, 0f)
        assertEquals(blue, roles(0xE6E6FF, paleRed).accent)      // 0.098
        assertNotEquals(blue, roles(0xE5E5FF, paleRed).accent)   // 0.102
    }
    @Test fun aGrayTertiaryGivesTheRedAndAGrayErrorGivesTheRed() {
        assertEquals(RoleColors(paleBlueAccent, red, red), roles(paleBlue, 0x808080, error = 0x909090))
        assertEquals(red, roles(paleBlue, 0x101010).north)
    }
    @Test fun aSchemeOfGraysGivesTheBlueAndTheRed() {
        assertEquals(roleColors(null), roles(0x808080, 0x9E9E9E, 0x6E6E6E))
    }

    // North apart from the pointer

    @Test fun hueApartIsTheShorterWayRoundTheWheel() {
        assertEquals(149.7f, hueApart(blue, red), 0.1f)
        assertEquals(0f, hueApart(paleBlue, paleBlue), 0f)
        assertEquals(10f, hueApart(0xFF0015, 0xFF0000) + hueApart(0xFF0000, 0xFF1500), 0.2f)   // 355 to 0 to 5
        assertEquals(10f, hueApart(0xFF0015, 0xFF1500), 0.2f)
        assertEquals(180f, hueApart(0xFF0000, 0x00FFFF), 0.01f)
        assertEquals(hueApart(blue, red), hueApart(red, blue), 0f)
    }
    @Test fun aTertiaryEqualToThePrimaryIsTurnedAwayFromIt() {
        for (same in listOf(0x76FD9D, paleBlue, blue, red, 0x00A0C8, 0xFFD000)) {
            val roles = roles(same, same)
            assertEquals(roles.toString(), if (same == paleBlue) paleBlueAccent else same, roles.accent)
            assertEquals(roles.toString(), MIN_HUE_APART, hueApart(roles.accent, roles.north), 1.5f)
            assertTrue(roles.toString(), contrastOnBlack(roles.north) >= MIN_CONTRAST)
            assertTrue(roles.toString(), saturationOf(roles.north) >= MIN_SATURATION)
        }
    }
    @Test fun aTertiaryAFewDegreesFromThePrimaryIsTurnedFurtherTheWayItAlreadyLeans() {
        val primary = 0x76FD9D                 // hue 137
        val before = 0x76FDA6                  // hue 141, a little further round
        val behind = 0x76FD94                  // hue 133
        val up = roles(primary, before).north
        val down = roles(primary, behind).north
        assertEquals(primary, roles(primary, before).accent)
        assertEquals(MIN_HUE_APART, hueApart(primary, up), 1.5f)
        assertEquals(MIN_HUE_APART, hueApart(primary, down), 1.5f)
        assertEquals(hueOf(primary) + MIN_HUE_APART, hueOf(up), 1.5f)
        assertEquals(hueOf(primary) - MIN_HUE_APART, hueOf(down), 1.5f)
        // It keeps how pale and how bright it was.
        assertEquals(saturationOf(before), saturationOf(up), 0.01f)
        assertTrue(contrastOnBlack(up) >= MIN_CONTRAST)
    }
    @Test fun turningAwayWorksAcrossTheTopOfTheWheel() {
        val primary = 0xFF0015                 // hue 355
        val tertiary = 0xFF1500                // hue 5
        val north = roles(primary, tertiary).north
        assertEquals(MIN_HUE_APART, hueApart(roles(primary, tertiary).accent, north), 1.5f)
        assertEquals((355f + MIN_HUE_APART) % 360f, hueOf(north), 1.5f)
        val back = roles(tertiary, primary).north
        assertEquals((5f - MIN_HUE_APART + 360f) % 360f, hueOf(back), 1.5f)
    }
    @Test fun aTertiaryAtLeastTheLeastAngleAwayIsLeftAlone() {
        assertEquals(60f, MIN_HUE_APART, 0f)
        assertEquals(0xFFFF76, roles(0x76FF76, 0xFFFF76).north)   // 120 and 60
        assertEquals(0x76FFFF, roles(0x76FF76, 0x76FFFF).north)   // 120 and 180
        assertNotEquals(0xD8FF76, roles(0x76FF76, 0xD8FF76).north) // 120 and 77 is 43 apart
    }
    @Test fun theEmulatorsOwnBlueAndVioletAreTooCloseAndNorthIsTurned() {
        // 32 degrees apart, both pale: the pair the system made from a blue seed.
        assertEquals(32f, hueApart(paleBlue, paleViolet), 0.5f)
        val roles = roles(paleBlue, paleViolet)
        assertEquals(paleBlueAccent, roles.accent)
        assertEquals(MIN_HUE_APART, hueApart(roles.accent, roles.north), 1.5f)
        assertEquals(hueOf(paleBlue) + MIN_HUE_APART, hueOf(roles.north), 1.5f)
        assertEquals(NORTH_MIN_SATURATION, saturationOf(roles.north), 0.01f)
    }

    // North among the ink ticks

    @Test fun aPaleNorthIsDeepenedSoItStandsOutFromTheInkTicksAndKeepsItsHueAndBrightness() {
        assertEquals(0.5f, NORTH_MIN_SATURATION, 0f)
        for (pale in listOf(paleRed, 0xFFBCD7, 0xDCE9A1, 0xFDC3EF)) {
            val north = roles(paleBlue, pale).north
            assertEquals(hueOf(pale), hueOf(north), 1.5f)
            assertEquals(NORTH_MIN_SATURATION, saturationOf(north), 0.01f)
            assertEquals(maxOf(pale shr 16 and 0xFF, pale shr 8 and 0xFF, pale and 0xFF), maxOf(north shr 16 and 0xFF, north shr 8 and 0xFF, north and 0xFF))
            assertTrue(contrastOnBlack(north) >= MIN_CONTRAST)
        }
    }
    @Test fun aNorthThatIsDeepEnoughIsNotTouched() {
        assertEquals(red, roles(paleBlue, red).north)
        assertEquals(0xFF7AA8, roles(paleBlue, 0xFF7AA8).north)
        assertEquals(0xFF7F7F, roles(paleBlue, 0xFF7F7F).north)   // 128 of 255: the first whole value at or over the least
        // 127 of 255 is 0.498, a hair under, and is moved the one step.
        assertEquals(0xFF7F7F, roles(paleBlue, 0xFF8080).north)
    }
    @Test fun theAccentAndTheErrorColorAreNotDeepened() {
        // The accent is deepened only to its own least saturation, not to north's.
        val roles = roles(paleBlue, paleRed, error = paleRed)
        assertEquals(paleBlueAccent, roles.accent)
        assertEquals(paleRed, roles.error)
        assertNotEquals(paleRed, roles.north)
    }
    @Test fun aDeepenedNorthThatIsThenTooDarkIsLightenedLast() {
        // A dull blue-violet next to a green pointer: deepened it is a dark blue, and reading on black comes first.
        val north = roles(0x76FD9D, 0x5A5A80).north
        assertTrue(contrastOnBlack(north) >= MIN_CONTRAST)
        assertTrue(contrastOnBlack(north) < MIN_CONTRAST + 0.15)
        assertEquals(240f, hueOf(north), 1.5f)
        assertTrue(saturationOf(north) > saturationOf(0x5A5A80))
    }
    @Test fun aNorthTurnedIntoADarkPartOfTheWheelIsLightenedAgain() {
        // A strong cyan: 60 degrees on is a strong blue, which is too dark on black as it stands.
        val roles = roles(0x00B4FF, 0x00B4FF)
        assertEquals(0x00B4FF, roles.accent)
        assertTrue(contrastOnBlack(roles.north) >= MIN_CONTRAST)
        assertEquals(MIN_HUE_APART, hueApart(roles.accent, roles.north), 1.5f)
    }
    @Test fun whenTheRedStandsInForAGrayTertiaryItIsKeptApartFromAReddishPointerToo() {
        val roles = roles(0xFF5040, 0x808080)
        assertEquals(0xFF5040, roles.accent)
        assertEquals(MIN_HUE_APART, hueApart(roles.accent, roles.north), 1.5f)
    }
    // A primary that cannot be used: all three are the app's own

    @Test fun aSchemeWhosePrimaryIsRejectedGivesTheWholeFallbackSet() {
        val own = roleColors(null)
        // A gray primary beside a tertiary and an error color that could both have been used.
        assertEquals(own, roles(0x808080, 0x2F6BFF, paleRed))
        assertEquals(own, roles(0x808080, 0xFF7AA8, 0xFFBEB9))
        // A near-black one, and a pale one that is a gray (v3: a pale primary above that is deepened and kept,
        // in aPalePrimaryJustUnderTheLeastSaturationIsDeepenedAndKept).
        assertEquals(own, roles(0x101010, 0xFF7AA8, paleRed))
        assertEquals(own, roles(0xE6E6FF, 0xFF7AA8, paleRed))
    }
    @Test fun theFixedColorsOfTheApi35ImageAreNowUsed() {
        // Until v3 the whole scheme gave way to the app's own colors, its primary having 0.149. v3 spec: "A pale
        // primary is deepened and kept, not thrown away."
        val roles = roles(0xD9E2FF, 0xFDD7FA, 0xF2B8B5)
        assertNotEquals(roleColors(null), roles)
        assertEquals(0xF2B8B5, roles.error)
        assertTrue(saturationOf(roles.north) >= NORTH_MIN_SATURATION)
        assertTrue(hueApart(roles.accent, roles.north) >= MIN_HUE_APART)
    }
    @Test fun aPrimaryThatOnlyHadToBeLightenedKeepsTheScheme() {
        val roles = roles(0x000080, 0xFF7AA8, paleRed)
        assertEquals(240f, hueOf(roles.accent), 1.5f)
        assertEquals(0xFF7AA8, roles.north)
        assertEquals(paleRed, roles.error)
    }
    @Test fun aRejectedTertiaryOrErrorColorAloneCostsOnlyItsOwnRole() {
        assertEquals(RoleColors(paleBlueAccent, red, paleRed), roles(paleBlue, 0x808080, paleRed))
        assertEquals(paleBlueAccent, roles(paleBlue, 0xFF7AA8, 0x909090).accent)
        assertEquals(0xFF7AA8, roles(paleBlue, 0xFF7AA8, 0x909090).north)
        assertEquals(red, roles(paleBlue, 0xFF7AA8, 0x909090).error)
    }

    // The error color apart from the accent

    @Test fun anErrorColorThatLooksLikeTheAccentGivesTheRed() {
        assertEquals(60f, ERROR_MIN_HUE_APART, 0f)
        assertEquals(0.3f, ERROR_MIN_SATURATION_APART, 0f)
        // The pink scheme: 13 degrees of hue and 0.02 of saturation apart.
        assertEquals(13f, hueApart(0xFFBEC7, 0xFFBEB9), 1f)
        val pink = roles(0xFFBEC7, 0xFDC3EF, 0xFFBEB9)
        // 0xFFBEC7 has 0.25 and is deepened to 0.30 (the owner, 2026-10-14: "Deepen to ~0.3 saturation").
        assertEquals(0xFFB2BD, pink.accent)
        assertEquals(red, pink.error)
        // Close in hue and as pale: 43 degrees apart at the same saturation.
        assertEquals(red, roles(0x76FF76, 0xFF7AA8, 0xD8FF76).error)
        // The same color.
        assertEquals(red, roles(0xFFBEB9, 0x7AA8FF, 0xFFBEB9).error)
    }
    @Test fun anErrorColorFarFromTheAccentInHueIsKept() {
        assertEquals(paleRed, roles(paleBlue, paleViolet, paleRed).error)
        assertEquals(paleRed, roles(0xB8FEC4, 0xFFBCD7, paleRed).error)
        // Exactly the least angle away, at the same saturation: 120 and 60 degrees.
        assertEquals(0xFFFF76, roles(0x76FF76, 0xFF7AA8, 0xFFFF76).error)
    }
    @Test fun anErrorColorCloseInHueButFarInSaturationIsKept() {
        // A strong red pointer and the pale red: 1 degree apart, 0.48 of saturation apart.
        assertEquals(paleRed, roles(0xFF5040, 0x808080, paleRed).error)
        // A pale pink pointer and a strong red: 0.31 apart. The pointer is 0xFFB2BD, 0.30, since the owner's
        // "Deepen to ~0.3 saturation" (2026-10-14); until then it was 0xFFBEC7, 0.25, and these two were 0xFF7070
        // and 0xFF7575, as far from that.
        assertEquals(0.302f, saturationOf(roles(0xFFBEC7, 0xFDC3EF).accent), 0.001f)
        assertEquals(0xFF6363, roles(0xFFBEC7, 0xFDC3EF, 0xFF6363).error)
        // 0.29 apart is not enough.
        assertEquals(red, roles(0xFFBEC7, 0xFDC3EF, 0xFF6868).error)
    }
    @Test fun theErrorColorIsNotComparedWithNorth() {
        // North is made from a tertiary equal to the error color, and both stand.
        val roles = roles(paleBlue, 0xFF7070, 0xFF7070)
        assertEquals(0xFF7070, roles.north)
        assertEquals(0xFF7070, roles.error)
    }
    @Test fun theAppsOwnSetIsUnchangedByEveryRule() {
        assertEquals(RoleColors(blue, red, red), roleColors(null))
        assertEquals(RoleColors(blue, red, red), roles(blue, red, red))
        assertTrue(hueApart(blue, red) >= ERROR_MIN_HUE_APART)
    }

    // North keeps its depth

    @Test fun aNorthThatHadToBeBrightenedStillHasItsLeastSaturation() {
        // A green pointer and a tertiary that is nearly black: lightened, turned and deepened it was too dark, and
        // mixing it with white once more left it at 0.33.
        val north = roles(0x35BF99, 0x18072E).north
        assertTrue("%06X".format(north), saturationOf(north) >= NORTH_MIN_SATURATION)
        assertTrue(contrastOnBlack(north) >= MIN_CONTRAST)
        assertTrue(hueApart(roles(0x35BF99, 0x18072E).accent, north) >= MIN_HUE_APART)
    }
    @Test fun everySchemeGivesANorthThatReadsIsDeepAndIsApartFromThePointer() {
        val channel = listOf(0x00, 0x18, 0x35, 0x6A, 0x99, 0xBF, 0xFF)
        val colors = channel.flatMap { r -> channel.flatMap { g -> channel.map { b -> (r shl 16) or (g shl 8) or b } } }
        for (primary in listOf(0x35BF99, paleBlue, 0xFFBEC7, 0x000080, 0xFFD000, blue, 0x808080)) {
            for (tertiary in colors) {
                val roles = roles(primary, tertiary)
                val what = "%06X %06X -> %s".format(primary, tertiary, roles)
                assertTrue(what, contrastOnBlack(roles.north) >= MIN_CONTRAST)
                assertTrue(what, contrastOnBlack(roles.accent) >= MIN_CONTRAST)
                assertTrue(what, saturationOf(roles.north) >= NORTH_MIN_SATURATION)
                assertTrue(what, hueApart(roles.accent, roles.north) >= MIN_HUE_APART)
            }
        }
    }
    @Test fun theBoundsHoldOnTheColorThatIsReturnedForTheReviewersWorstCase() {
        // Found by the reviewer: the saturation and hue were read from a rounded dark color on the way, and the
        // color returned was 657C3F at 0.4919.
        val roles = roles(0xD51589, 0x717964)
        val what = "%06X".format(roles.north)
        assertTrue(what, saturationOf(roles.north) >= NORTH_MIN_SATURATION)
        assertTrue(what, hueApart(roles.accent, roles.north) >= MIN_HUE_APART)
        assertTrue(what, contrastOnBlack(roles.north) >= MIN_CONTRAST)
    }
    @Test fun theBoundsHoldExactlyOnTheReturnedColorsOfManyRandomSchemes() {
        val random = java.util.Random(20261012L)
        var used = 0
        repeat(150_000) {
            val scheme = WatchScheme(random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000))
            val roles = roleColors(scheme)
            val what = "%06X %06X %06X -> %06X %06X %06X".format(scheme.primary, scheme.tertiary, scheme.error, roles.accent, roles.north, roles.error)
            assertTrue(what, contrastOnBlack(roles.accent) >= MIN_CONTRAST)
            assertTrue(what, contrastOnBlack(roles.north) >= MIN_CONTRAST)
            assertTrue(what, contrastOnBlack(roles.error) >= MIN_CONTRAST)
            assertTrue(what, saturationOf(roles.accent) >= MIN_SATURATION)
            assertTrue(what, saturationOf(roles.accent) >= ACCENT_MIN_SATURATION)
            assertTrue(what, saturationOf(roles.north) >= NORTH_MIN_SATURATION)
            assertTrue(what, hueApart(roles.accent, roles.north) >= MIN_HUE_APART)
            if (roles != roleColors(null)) used++
        }
        // Most random schemes are used, so the bounds were tried on colors from schemes and not only on the app's own.
        assertTrue("$used", used > 100_000)
    }
}
