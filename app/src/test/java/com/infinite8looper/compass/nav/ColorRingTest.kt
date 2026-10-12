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
    // tertiary and error colors as `cmd overlay lookup` gave them), and the app's own colors.
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

    @Test fun theValuesNewWaypointsAreGivenLandOnDifferentColors() {
        // The stored values of the first eight waypoints, as before; on the app's own ring each has a color of its own
        // except those that fall in the red third.
        val first = (0 until 8).map { hueForIndex(it) }
        assertEquals(listOf(0f, 137.508f, 275.016f, 52.524f, 190.032f, 327.54f, 105.048f, 242.556f), first.map { (it * 1000).roundToInt() / 1000f })
        val colors = first.map { hex(ringColor(it / 360f, blue, red, red)) }
        assertEquals(listOf(1, 4), colors.indices.filter { colors[it] == hex(red) })
        assertEquals(7, colors.toSet().size)
    }

    private companion object {
        const val LOWEST_A = 8.30
        const val LOWEST_B = 13.28
        const val ROLES_A = "#B7CFFF #D57FFF #FFBEB9"
        const val ROLES_B = "#BBEFC2 #DAE974 #FFBEB9"
    }
}
