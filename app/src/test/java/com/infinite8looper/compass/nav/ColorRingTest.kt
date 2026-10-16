package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ColorRingTest {
    private val blue = FALLBACK_ACCENT
    private val red = FALLBACK_NORTH

    // Three colors that share no channel value, so a mix-up of anchors or of channels shows.
    private val a = 0x102030
    private val b = 0xF0A050
    private val c = 0x40E0C0

    private fun channels(rgb: Int) = listOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    private fun hex(rgb: Int) = "#%06X".format(rgb)

    /** The three role colors of a watch scheme, as the app makes them. */
    private fun rolesOf(primary: Int, tertiary: Int, error: Int) = roleColors(WatchScheme(primary, tertiary, error))

    // The two schemes the Wear OS 7.0 emulator's system made from the seeds 2F8BFF and 00AA55 (its primary,
    // tertiary and error colors as `cmd overlay lookup` gave them), and the app's own colors. The tests here are of
    // the ring with the three role colors as its anchors; the app's waypoints take the third from the palette
    // (`Palette.ringThird`), which is the error color only where that is apart in hue from both others, so the app
    // no longer draws a ring of the blue, the red and the red (ColorsSettingTest).
    private val own = roleColors(null)
    private val schemeA = rolesOf(0xB7CFFF, 0xD3C8FF, 0xFFBEB9)
    private val schemeB = rolesOf(0xBBEFC2, 0xDCE9A1, 0xFFBEB9)

    private fun ring(position: Float, roles: RoleColors) = ringColor(position, roles.accent, roles.north, roles.error)

    @Test fun theThreeAnchorsComeBackExactlyAtNoughtAThirdAndTwoThirds() {
        assertEquals(hex(a), hex(ringColor(0f, a, b, c)))
        assertEquals(hex(b), hex(ringColor(1f / 3f, a, b, c)))
        assertEquals(hex(c), hex(ringColor(2f / 3f, a, b, c)))
        // As the app asks: the stored value, 0 to 360, over 360.
        assertEquals(hex(a), hex(ringColor(0f / 360f, a, b, c)))
        assertEquals(hex(b), hex(ringColor(120f / 360f, a, b, c)))
        assertEquals(hex(c), hex(ringColor(240f / 360f, a, b, c)))
    }

    @Test fun halfwayBetweenTwoAnchorsIsTheirEvenMixChannelByChannel() {
        fun even(x: Int, y: Int) = channels(x).zip(channels(y)).map { (p, q) -> (p + q) / 2.0 }
        fun assertEven(x: Int, y: Int, got: Int) {
            // The even mix of two whole channel values can end in a half, which is drawn as one of the two whole values beside it.
            channels(got).zip(even(x, y)).forEach { (g, want) -> assertTrue("${hex(got)} for $want", abs(g - want) <= 0.5) }
        }
        assertEven(a, b, ringColor(1f / 6f, a, b, c))
        assertEven(b, c, ringColor(0.5f, a, b, c))
        assertEven(c, a, ringColor(5f / 6f, a, b, c))
        // The app's blue and red: half of each channel's way, which is the mix the owner chose from.
        assertEquals(hex(0x976398), hex(ringColor(60f / 360f, blue, red, red)))
        // A quarter of the way from the first anchor to the second.
        assertEquals(hex(0x48402C), hex(ringColor(1f / 12f, 0x102030, 0xF0A020, c)))
    }

    @Test fun theFadeIsStraightInEachChannel() {
        // From black to white the channels rise evenly: a quarter of the way is a quarter of 255, not a quarter of the light.
        assertEquals(hex(0x404040), hex(ringColor(1f / 12f, 0x000000, 0xFFFFFF, 0x000000)))
        assertEquals(hex(0x808080), hex(ringColor(1f / 6f, 0x000000, 0xFFFFFF, 0x000000)))
        assertEquals(hex(0xBFBFBF), hex(ringColor(3f / 12f, 0x000000, 0xFFFFFF, 0x000000)))
        // Each channel goes its own way: one rising while another falls.
        assertEquals(hex(0x40BF00), hex(ringColor(1f / 12f, 0x00FF00, 0xFF0000, 0x0000FF)))
    }

    @Test fun theRingIsContinuousAllTheWayRoundAndAcrossTheWrap() {
        assertEquals(hex(a), hex(ringColor(1f, a, b, c)))
        assertEquals(hex(a), hex(ringColor(360f / 360f, a, b, c)))
        // Just short of the full turn it is all but the first anchor again.
        channels(ringColor(359.9f / 360f, a, b, c)).zip(channels(a)).forEach { (g, want) -> assertTrue(abs(g - want) <= 1) }
        // A tenth of a degree never moves a channel by more than a step: the widest channel gap between anchors
        // here is 224 over 120 degrees.
        var before = ringColor(-0.1f / 360f, a, b, c)
        for (tenth in 0..3600) {
            val now = ringColor(tenth / 3600f, a, b, c)
            channels(now).zip(channels(before)).forEach { (p, q) -> assertTrue("at ${tenth / 10f}: ${hex(before)} then ${hex(now)}", abs(p - q) <= 1) }
            before = now
        }
    }

    @Test fun equalAnchorsGiveTheirColorForTheWholeThirdBetweenThem() {
        // The app's own set: blue, red, red. From a third to two thirds of the way round, one red.
        for (tenth in 1200..2400) assertEquals("at ${tenth / 10f}", hex(red), hex(ringColor(tenth / 3600f, blue, red, red)))
        // And it leaves the red on both sides of that third.
        assertNotEquals(hex(red), hex(ringColor(119f / 360f, blue, red, red)))
        assertNotEquals(hex(red), hex(ringColor(241f / 360f, blue, red, red)))
        // Three equal anchors: one color everywhere.
        for (deg in 0..360) assertEquals(hex(a), hex(ringColor(deg / 360f, a, a, a)))
    }

    @Test fun theAppsOwnRingHasTheSameColorAtTheSameDistanceEitherSideOfItsRedThird() {
        // Blue, red, red: the way from the blue to the red is the way back from the red to the blue. Of eight
        // places evenly spaced round it, three are the red and two pairs share a color: five colors in all.
        val eight = (0 until 8).map { hex(ringColor(it * 45f / 360f, blue, red, red)) }
        assertEquals(listOf("#2F8BFF", "#7D6DB1", "#CB4F64", "#FF3B30", "#FF3B30", "#FF3B30", "#CB4F64", "#7D6DB1"), eight)
        // Everywhere else the two sides agree to within one step of a channel, which is the rounding of a half.
        for (deg in 1..119) {
            val out = ringColor(deg / 360f, blue, red, red)
            val back = ringColor((360 - deg) / 360f, blue, red, red)
            channels(out).zip(channels(back)).forEach { (p, q) -> assertTrue("$deg: ${hex(out)} and ${hex(back)}", abs(p - q) <= 1) }
        }
        // With three different anchors the eight places have eight colors.
        assertEquals(8, (0 until 8).map { ring(it * 45f / 360f, schemeA) }.toSet().size)
        assertEquals(8, (0 until 8).map { ring(it * 45f / 360f, schemeB) }.toSet().size)
    }

    @Test fun positionsOutsideTheTurnWrap() {
        for (deg in listOf(0f, 10f, 119.5f, 120f, 200f, 240f, 359f)) {
            val want = hex(ringColor(deg / 360f, a, b, c))
            assertEquals("$deg + 360", want, hex(ringColor((deg + 360f) / 360f, a, b, c)))
            assertEquals("$deg - 360", want, hex(ringColor((deg - 360f) / 360f, a, b, c)))
            assertEquals("$deg + 720", want, hex(ringColor((deg + 720f) / 360f, a, b, c)))
        }
        assertEquals(hex(ringColor(0.75f, a, b, c)), hex(ringColor(-0.25f, a, b, c)))
        assertEquals(hex(c), hex(ringColor(-1f / 3f, a, b, c)))
        // A position that is not a number is the start of the ring.
        assertEquals(hex(a), hex(ringColor(Float.NaN, a, b, c)))
        assertEquals(hex(a), hex(ringColor(Float.POSITIVE_INFINITY, a, b, c)))
        assertEquals(hex(a), hex(ringColor(Float.NEGATIVE_INFINITY, a, b, c)))
    }

    @Test fun onlyTheRedGreenAndBlueOfAnAnchorCount() {
        assertEquals(hex(a), hex(ringColor(0f, 0xFF000000.toInt() or a, b, c)))
        assertEquals(ringColor(0.2f, a, b, c), ringColor(0.2f, 0xFF000000.toInt() or a, 0x7F000000 or b, c))
        assertEquals(0, ringColor(0.2f, 0xFF000000.toInt() or a, b, c) ushr 24)
    }

    /** The lowest contrast with black anywhere on the ring of [roles], looked at every twentieth of a degree, and where it is. */
    private fun lowestContrast(roles: RoleColors): Pair<Double, Float> =
        (0 until 7200).map { contrastOnBlack(ring(it / 7200f, roles)) to it / 20f }.minBy { it.first }

    @Test fun everyColorOnTheRingReadsAsALineOnBlack() {
        // 3 to 1 is what the Web Content Accessibility Guidelines ask of a line or a mark that carries meaning.
        val lowestOwn = lowestContrast(own)
        val lowestA = lowestContrast(schemeA)
        val lowestB = lowestContrast(schemeB)
        for ((name, lowest) in listOf("own" to lowestOwn, "A" to lowestA, "B" to lowestB)) assertTrue("$name: $lowest", lowest.first >= 3.0)
        // The numbers themselves. The app's own set is darkest a little past half way from the blue to the red, on
        // either side of the red third (4.52 to 1; the blue has 6.26 and the red 5.92). The two schemes are pale
        // all the way round and are darkest beside their north (A) and beside their error color (B).
        assertEquals(4.52, lowestOwn.first, 0.01)
        assertTrue("${lowestOwn.second}", abs(lowestOwn.second - 65.3f) < 1f || abs(lowestOwn.second - 294.7f) < 1f)
        assertEquals(LOWEST_A, lowestA.first, 0.01)
        assertEquals(LOWEST_B, lowestB.first, 0.01)
        // The role colors those two schemes give, which the numbers above are for.
        assertEquals(listOf(ROLES_A, ROLES_B), listOf(schemeA, schemeB).map { "${hex(it.accent)} ${hex(it.north)} ${hex(it.error)}" })
    }

    @Test fun aRingOfAnyThreeColorsThatReadOnBlackNeverFallsFarBelowThem() {
        // Between two anchors the mix can be darker than both, as half way between the app's blue and red. Over
        // strong colors of every kind, each lightened as a role color is until it has 4.5 to 1, the ring stays above 3 to 1.
        val edge = listOf(0x0000FF, 0xFF0000, 0x00FF00, 0xFF00FF, 0x00FFFF, 0xFFFF00, 0x2F8BFF, 0xFF3B30).map { rgb ->
            // The same lightening the role colors get: toward white until 4.5 to 1.
            roleColors(WatchScheme(rgb, rgb, rgb)).accent
        }
        var lowest = Double.MAX_VALUE
        for (x in edge) for (y in edge) for (step in 0..120) lowest = minOf(lowest, contrastOnBlack(ringColor(step / 360f, x, y, y)))
        assertTrue("$lowest", lowest >= 3.0)
    }

    // The soft tint.

    private fun valueOf(rgb: Int) = channels(rgb).max()

    @Test fun theSoftTintKeepsTheHueAndTheBrightnessAndHasLessSaturation() {
        for (roles in listOf(own, schemeA, schemeB)) for (deg in 0 until 360 step 5) {
            val full = ring(deg / 360f, roles)
            val soft = softTintOf(full)
            assertEquals("${hex(full)} -> ${hex(soft)}", valueOf(full), valueOf(soft))
            assertEquals("${hex(full)} -> ${hex(soft)}", saturationOf(full) * SOFT_TINT_SATURATION_KEPT, saturationOf(soft), 0.01f)
            // Whole channel values move the hue of a pale color a little; the hue of the tint is the color's own.
            if (saturationOf(soft) > 0.1f) assertTrue("${hex(full)} -> ${hex(soft)}: ${hueApart(full, soft)}", hueApart(full, soft) <= 3f)
        }
    }

    @Test fun theSoftTintOfTheColorsWaypointsHadIsTheTintTheyHad() {
        // A waypoint's color was hue, 0.65, 1 and its tint hue, 0.40, 1: the share of the saturation that is kept is 0.40 of 0.65.
        assertEquals(0.40f / 0.65f, SOFT_TINT_SATURATION_KEPT, 0f)
        fun hsv(h: Float, s: Float): Int {
            fun channel(n: Float): Int { val k = (n + h / 60f) % 6f; return ((1f - s * maxOf(0f, minOf(k, 4f - k, 1f))) * 255f).roundToInt() }
            return (channel(5f) shl 16) or (channel(3f) shl 8) or channel(1f)
        }
        for (deg in 0 until 360 step 15) {
            val was = hsv(deg.toFloat(), 0.40f)
            val now = softTintOf(hsv(deg.toFloat(), 0.65f))
            channels(now).zip(channels(was)).forEach { (p, q) -> assertTrue("$deg: ${hex(now)} for ${hex(was)}", abs(p - q) <= 1) }
        }
    }

    @Test fun theSoftTintOfAGrayIsThatGrayAndOnlyRedGreenAndBlueCount() {
        for (gray in listOf(0x000000, 0x6E6E6E, 0xFFFFFF)) assertEquals(hex(gray), hex(softTintOf(gray)))
        assertEquals(softTintOf(0x2F8BFF), softTintOf(0xFF000000.toInt() or 0x2F8BFF))
        assertEquals(0, softTintOf(0xFF2F8BFF.toInt()) ushr 24)
        // The app's blue: the brightest channel stays and the others come 0.615 of their way down from it.
        assertEquals(hex(0x7FB8FF), hex(softTintOf(0x2F8BFF)))
    }

    // The soft tint as it is drawn: a line at 0.6 of full strength on black.

    /** The contrast with black of [rgb] drawn at the soft tint's strength. */
    private fun drawnContrast(rgb: Int) = contrastOnBlack(softTintOverBlack(rgb))

    /** The lowest contrast of the soft line anywhere on the ring of [roles], every twentieth of a degree, made by [tint]. */
    private fun lowestSoft(roles: RoleColors, tint: (Int) -> Int): Double = (0 until 7200).minOf { drawnContrast(tint(ring(it / 7200f, roles))) }

    @Test fun drawnAtItsStrengthOverBlackEachChannelIsSixTenths() {
        assertEquals(hex(0x999999), hex(softTintOverBlack(0xFFFFFF)))
        assertEquals(hex(0x000000), hex(softTintOverBlack(0x000000)))
        assertEquals(hex(0x996000), hex(softTintOverBlack(0xFFA000)))
        assertEquals(softTintOverBlack(0x2F8BFF), softTintOverBlack(0xFF2F8BFF.toInt()))
    }

    @Test fun theSoftLineReadsOnBlackEverywhereOnTheRing() {
        // The tint by itself, before it is made to read: with the app's own colors it is under 3 to 1 for a stretch
        // either side of the red third, where the ring is at its darkest; with the two schemes it is not.
        assertEquals(2.50, lowestSoft(own, ::softTintOf), 0.01)
        assertEquals(LOWEST_SOFT_A, lowestSoft(schemeA, ::softTintOf), 0.01)
        assertEquals(LOWEST_SOFT_B, lowestSoft(schemeB, ::softTintOf), 0.01)
        val under = (0 until 3600).count { drawnContrast(softTintOf(ring(it / 3600f, own))) < MIN_LINE_CONTRAST } / 10f
        assertEquals(109f, under, 2f)
        // The line as it is drawn has 3 to 1 or more everywhere.
        for ((name, roles) in listOf("own" to own, "A" to schemeA, "B" to schemeB)) {
            val lowest = lowestSoft(roles, ::softLineColor)
            assertTrue("$name: $lowest", lowest >= MIN_LINE_CONTRAST)
        }
        assertEquals(3.0, lowestSoft(own, ::softLineColor), 0.02)
        assertEquals(LOWEST_SOFT_A, lowestSoft(schemeA, ::softLineColor), 0.01)
        assertEquals(LOWEST_SOFT_B, lowestSoft(schemeB, ::softLineColor), 0.01)
    }

    @Test fun aSoftLineThatAlreadyReadsIsTheTintUnchanged() {
        var unchanged = 0
        for (roles in listOf(own, schemeA, schemeB)) for (twentieth in 0 until 7200) {
            val tint = softTintOf(ring(twentieth / 7200f, roles))
            if (drawnContrast(tint) >= MIN_LINE_CONTRAST) { assertEquals(hex(tint), hex(softLineColor(ring(twentieth / 7200f, roles)))); unchanged++ }
        }
        // All of the two schemes' rings and about 251 of the 360 degrees of the app's own.
        assertTrue("$unchanged", unchanged > 7200 * 2 + 7200 * 240 / 360)
        assertEquals(hex(softTintOf(0xFF3B30)), hex(softLineColor(0xFF3B30)))
        assertEquals(hex(softTintOf(0x2F8BFF)), hex(softLineColor(0x2F8BFF)))
    }

    @Test fun aSoftLineMadeToReadKeepsTheHueAndTheSaturationAndIsOnlyAsBrightAsItNeeds() {
        var changed = 0
        for (twentieth in 0 until 7200) {
            val full = ring(twentieth / 7200f, own)
            val tint = softTintOf(full)
            val line = softLineColor(full)
            if (line == tint) continue
            changed++
            val at = "at ${twentieth / 20f}: ${hex(full)} tint ${hex(tint)} line ${hex(line)}"
            // The same hue as the ring color's and as the tint's, to within what whole channel values allow.
            assertTrue("$at: ${hueApart(full, line)}", hueApart(full, line) <= SOFT_LINE_HUE_TOLERANCE)
            assertTrue("$at: ${hueApart(tint, line)}", hueApart(tint, line) <= SOFT_LINE_HUE_TOLERANCE)
            assertEquals(at, saturationOf(tint), saturationOf(line), 0.02f)
            // Brighter than the tint, and no brighter than reading needs: one step of brightness less would not read.
            assertTrue(at, valueOf(line) > valueOf(tint))
            assertTrue("$at: ${drawnContrast(line)}", drawnContrast(line) >= MIN_LINE_CONTRAST && drawnContrast(line) < MIN_LINE_CONTRAST + 0.12)
        }
        assertTrue("$changed", changed in 2000..2400)
    }

    @Test fun aSoftLineReadsForAnyColorHoweverDark() {
        // Colors no ring of role colors has, down to black: brightened as far as they go and then, if that is not
        // enough, mixed with white. A black line would be invisible; it comes out a gray that reads.
        for (rgb in listOf(0x000000, 0x000001, 0x100000, 0x0000FF, 0x000080, 0x300030, 0x102030, 0x808080, 0xFFFFFF, 0xFF0000)) {
            val line = softLineColor(rgb)
            assertTrue("${hex(rgb)} -> ${hex(line)}: ${drawnContrast(line)}", drawnContrast(line) >= MIN_LINE_CONTRAST)
            assertEquals(0, line ushr 24)
        }
        // A full blue cannot read at this strength however bright: it is made paler, and stays blue.
        val blueLine = softLineColor(0x0000FF)
        assertEquals(240f, hueOf(blueLine), 1f)
        assertTrue(hex(blueLine), saturationOf(blueLine) < saturationOf(softTintOf(0x0000FF)))
        // A random sweep of colors.
        val random = java.util.Random(12)
        repeat(20_000) {
            val rgb = random.nextInt(0x1000000)
            assertTrue(hex(rgb), drawnContrast(softLineColor(rgb)) >= MIN_LINE_CONTRAST)
        }
    }

    @Test fun theValuesNewWaypointsAreGivenLandOnDifferentColors() {
        // The first eight places of the numbering new waypoints' places are chosen from (nextWaypointPlace, which
        // passes over those near the red or the blue: WaypointPlacesTest). They start at 108.325, the middle of the widest gap
        // between the app's red and blue: the owner (2026-10-14), "in standard (non-theme) colors use the full color
        // wheel and start with something away from the existing red/blue colors". Until then they started at 0. On
        // a ring of the blue, the red and the red each has a color of its own except those that fall in the red third.
        val first = (0 until 8).map { hueForIndex(it) }
        assertEquals(listOf(108.325f, 245.833f, 23.341f, 160.849f, 298.357f, 75.865f, 213.373f, 350.881f), first.map { (it * 1000).roundToInt() / 1000f })
        val colors = first.map { hex(ringColor(it / 360f, blue, red, red)) }
        assertEquals(listOf(3, 6), colors.indices.filter { colors[it] == hex(red) })
        assertEquals(7, colors.toSet().size)
    }

    private companion object {
        const val LOWEST_SOFT_A = 4.55
        // The accents of the two schemes are deepened to 0.30 (the owner, 2026-10-14: "Deepen to ~0.3
        // saturation"); until then they were 0xB7CFFF and 0xBBEFC2, and LOWEST_SOFT_B was 5.41 and LOWEST_A 8.30.
        const val LOWEST_SOFT_B = 5.24
        /** Degrees of hue between a soft line that was brightened and the color it was made from. */
        const val SOFT_LINE_HUE_TOLERANCE = 2f
        const val LOWEST_A = 8.27
        const val LOWEST_B = 13.28
        const val ROLES_A = "#B2CCFF #D47FFF #FFBEB9"
        const val ROLES_B = "#A7EFB1 #D9E974 #FFBEB9"
    }
}
