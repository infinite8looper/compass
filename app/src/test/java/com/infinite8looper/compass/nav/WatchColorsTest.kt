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

    private fun roles(primary: Int, tertiary: Int, error: Int = paleRed) = roleColors(WatchScheme(primary, tertiary, error))

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
        assertEquals(RoleColors(0xB8FEC4, 0xFF7AA8, paleRed), roles(0xB8FEC4, 0xFF7AA8))
        assertEquals(RoleColors(paleBlue, 0xFF6B4A, paleRed), roles(paleBlue, 0xFF6B4A))
    }
    @Test fun onlyTheRedGreenAndBlueOfASchemeColorCount() {
        assertEquals(roles(0xB8FEC4, 0xFF7AA8), roleColors(WatchScheme(0xFFB8FEC4.toInt(), 0xFFFF7AA8.toInt(), 0xFFFFBEB9.toInt())))
        assertEquals(RoleColors(0xB8FEC4, 0xFF7AA8, paleRed), roleColors(WatchScheme(0x00B8FEC4, 0x80FF7AA8.toInt(), 0x10FFBEB9)))
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
        // Lightened, never darkened: no channel went down.
        assertTrue((accent shr 16 and 0xFF) >= 0x00 && (accent shr 8 and 0xFF) >= 0x00 && (accent and 0xFF) >= 0x80)
        assertTrue(saturationOf(accent) >= MIN_SATURATION)
    }
    @Test fun pureBlueIsTooDarkAndIsLightened() {
        val accent = roles(0x0000FF, paleRed).accent
        assertTrue(contrastOnBlack(accent) >= MIN_CONTRAST)
        assertEquals(240f, hueOf(accent), 1.5f)
        assertEquals(0xFF, accent and 0xFF)
    }
    @Test fun aColorThatAlreadyReadsIsNotTouched() {
        assertEquals(paleBlue, roles(paleBlue, paleRed).accent)
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
    @Test fun aPaleColorThatIsStillAColorIsKept() {
        assertEquals(0.15f, MIN_SATURATION, 0f)
        assertEquals(paleViolet, roles(paleViolet, paleRed).accent)
        // Just under and just over the least saturation, at full brightness.
        assertEquals(blue, roles(0xDBDBFF, paleRed).accent)      // 0.14
        assertEquals(0xD6D6FF, roles(0xD6D6FF, paleRed).accent)  // 0.16
    }
    @Test fun aGrayTertiaryGivesTheRedAndAGrayErrorGivesTheRed() {
        assertEquals(RoleColors(paleBlue, red, red), roles(paleBlue, 0x808080, error = 0x909090))
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
            assertEquals(roles.toString(), same, roles.accent)
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
        assertEquals(paleBlue, roles.accent)
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
        assertEquals(0xFF8080, roles(paleBlue, 0xFF8080).north)   // exactly the least
    }
    @Test fun theAccentAndTheErrorColorAreNotDeepened() {
        val roles = roles(paleBlue, paleRed, error = paleRed)
        assertEquals(paleBlue, roles.accent)
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
    @Test fun whenTheBlueStandsInForAGrayPrimaryNorthIsKeptApartFromTheBlue() {
        val roles = roles(0x808080, 0x2F6BFF)
        assertEquals(blue, roles.accent)
        assertEquals(MIN_HUE_APART, hueApart(blue, roles.north), 1.5f)
    }
    @Test fun theErrorColorIsNotMovedForNorthOrForThePointer() {
        // The scheme's error color is used as it is, whether or not it matches north or the pointer.
        assertEquals(paleRed, roles(paleBlue, paleRed, error = paleRed).error)
        assertEquals(paleRed, roles(0xFFBEC7, 0xFDC3EF, error = paleRed).error)
    }
}
