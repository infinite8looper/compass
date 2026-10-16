package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * The place a new waypoint is given ([nextWaypointPlace]).
 *
 * The owner (2026-10-14), choosing "Skip red and blue (Recommended)": "New waypoints never land within about ±25° of
 * north's red or the pointer's blue; a place that would is moved on to the next one. Existing waypoints keep their
 * colors." The red and blue are the Standard wheel's: north `#FF3B30` (hue 3.2) and the pointer `#2F8BFF` (hue 213.5).
 *
 * And the re-review (Minor 5): on an install that already has waypoints, a new place could land 23 degrees from an
 * old one, since the numbering starts at 108.3 and older installs' places were numbered from 0.
 */
class WaypointPlacesTest {
    private val red = hueOf(FALLBACK_NORTH)
    private val blue = hueOf(FALLBACK_ACCENT)

    private fun apart(a: Float, b: Float) = abs(shortestDelta(a, b))
    private fun leastApart(place: Float, taken: List<Float>) = taken.minOfOrNull { apart(place, it) } ?: Float.MAX_VALUE

    /** The places [count] waypoints are given one after another, from [taken]. */
    private fun given(count: Int, taken: List<Float> = emptyList()): List<Float> {
        val all = taken.toMutableList()
        repeat(count) { all += nextWaypointPlace(all.toFloatArray()) }
        return all.drop(taken.size)
    }

    private fun assertAwayFromRedAndBlue(what: String, place: Float) {
        assertTrue("$what: $place is ${apart(place, red)} from the red", apart(place, red) >= SKIP_HUE_APART)
        assertTrue("$what: $place is ${apart(place, blue)} from the blue", apart(place, blue) >= SKIP_HUE_APART)
        // The color drawn for it, too: the wheel's color at the place has the place's hue.
        val color = wheelColor(place)
        assertTrue("$what: %06X".format(color), hueApart(color, FALLBACK_NORTH) >= SKIP_HUE_APART - 0.5f)
        assertTrue("$what: %06X".format(color), hueApart(color, FALLBACK_ACCENT) >= SKIP_HUE_APART - 0.5f)
    }

    @Test fun theRedAndBlueAreTheStandardWheelsNorthAndPointerAnd25DegreesAreSkippedEitherSide() {
        assertEquals(25f, SKIP_HUE_APART)
        assertEquals(3.19f, red, 0.01f)
        assertEquals(213.46f, blue, 0.01f)
    }

    @Test fun withNoWaypointsTheFirstPlaceIsStill108() {
        assertEquals(FIRST_WAYPOINT_PLACE, nextWaypointPlace(FloatArray(0)))
        assertEquals(hueForIndex(0), nextWaypointPlace(FloatArray(0)))
    }

    @Test fun manyNewWaypointsNeverLandNearTheRedOrTheBlue() {
        val places = given(500)
        places.forEachIndexed { i, place -> assertAwayFromRedAndBlue("waypoint ${i + 1}", place) }
    }

    @Test fun thePlacesOfTheNumberingNearTheRedOrTheBlueAreNeverGiven() {
        // 23.3, 213.4 and 350.9 are the third, seventh and eighth places of the numbering.
        val near = (0 until 500).map { hueForIndex(it) }.filter { apart(it, red) < SKIP_HUE_APART || apart(it, blue) < SKIP_HUE_APART }
        assertEquals(listOf(23.341f, 213.373f, 350.881f), near.take(3).map { Math.round(it * 1000) / 1000f })
        val places = given(500).toSet()
        for (skipped in near) assertFalse("$skipped", skipped in places)
    }

    @Test fun whateverIsStoredANewPlaceIsAwayFromTheRedAndTheBlue() {
        // Older installs, and waypoints whose color was set by hand, hold places anywhere, the red and blue included.
        val random = java.util.Random(20261014L)
        repeat(5_000) { round ->
            val taken = List(random.nextInt(60)) { random.nextFloat() * 360f }
            assertAwayFromRedAndBlue("round $round", nextWaypointPlace(taken.toFloatArray()))
        }
        // Every place of the wheel taken, one a degree: still not the red or the blue.
        assertAwayFromRedAndBlue("a degree apart", nextWaypointPlace(FloatArray(360) { it.toFloat() }))
    }

    @Test fun noPlaceIsGivenTwice() {
        val places = given(500)
        assertEquals(places.size, places.toSet().size)
    }

    @Test fun theFirstEightPlacesOfAFreshInstall() {
        // Each the place, of the next eight of the numbering that are not near the red or the blue, furthest from
        // the places already given.
        assertEquals(listOf(108.3f, 298.4f, 180.9f, 43.4f, 245.8f, 75.9f, 148.5f, 265.9f), given(8).map { Math.round(it * 10) / 10f })
    }

    @Test fun theFirstThirtyPlacesOfAFreshInstallAreTheOnesSeedTargetsManyGives() {
        // scripts/seed-targets.sh many seeds these, to two decimals.
        val many = "108.32 298.36 180.91 43.40 245.83 75.86 148.45 265.90 318.42 128.39 160.85 31.01 95.93 285.96 63.47 51.07 136.06 306.02 83.53 273.56 326.09 115.99 168.52 253.50 38.68 241.10 293.63 261.17 313.69 281.23"
        assertEquals(many, given(30).joinToString(" ") { "%.2f".format(java.util.Locale.ROOT, it) })
    }

    @Test fun theNewPlacesStayApartOnAFreshInstall() {
        val places = given(20)
        for (n in 2..20) {
            val first = places.take(n).sorted()
            val gaps = first.zipWithNext { a, b -> b - a } + (first.first() + 360f - first.last())
            assertTrue("$n: $gaps", gaps.min() >= 7f)
        }
    }

    @Test fun onAnInstallWithOlderWaypointsANewPlaceIsFarFromThem() {
        // The re-review's case: places numbered from 0 (before 2026-10-14), eight of them: 0, 137.5, 275.0, 52.5,
        // 190.0, 327.5, 105.0, 242.6. The numbering's next places from 108.3 are 108.3 and 245.8, 3.3 degrees from 105
        // and 242.6.
        // The next three are each at least 23 degrees from every place taken, old and new; the fourth has to be
        // closer, eleven places being on the wheel by then.
        val older = (0 until 8).map { normalizeDeg(it * 137.508f) }
        val next = given(3, older)
        next.forEachIndexed { i, place -> assertTrue("$place in $next", leastApart(place, older + next.take(i)) >= 23f) }
    }

    @Test fun aNewPlaceIsAsFarFromTheTakenOnesAsTheNextPlaceOfTheNumberingWouldBe() {
        // So the choice never does worse than taking the numbering in turn, skipping the red and the blue.
        fun plain(taken: List<Float>): Float = (0 until 4096).map { hueForIndex(it) }.first { p ->
            apart(p, red) >= SKIP_HUE_APART && apart(p, blue) >= SKIP_HUE_APART && taken.none { apart(p, it) < 0.01f }
        }
        val random = java.util.Random(7L)
        repeat(2_000) { round ->
            val taken = List(1 + random.nextInt(40)) { if (random.nextBoolean()) hueForIndex(random.nextInt(60)) else random.nextFloat() * 360f }
            val chosen = nextWaypointPlace(taken.toFloatArray())
            assertTrue("round $round", leastApart(chosen, taken) >= leastApart(plain(taken), taken))
        }
    }

    @Test fun inWatchFaceANewPlaceIsReadAtLeast10DegreesFromTheRingsAccentAndNorth() {
        // The ring is read so that 108.3 is its third anchor, the accent is at 228.3 and north at 348.3: inside the
        // blue's skipped degrees (188.5 to 238.5) and the red's (338.2 to 28.2), 10.1 degrees from their edges.
        val accentAt = normalizeDeg(FIRST_WAYPOINT_PLACE + 120f)
        val northAt = normalizeDeg(FIRST_WAYPOINT_PLACE + 240f)
        assertEquals(10.13f, normalizeDeg(blue + SKIP_HUE_APART) - accentAt, 0.01f)
        assertEquals(10.14f, northAt - normalizeDeg(red - SKIP_HUE_APART), 0.01f)
        for (place in given(500)) {
            assertTrue("$place", apart(place, accentAt) >= 10f)
            assertTrue("$place", apart(place, northAt) >= 10f)
        }
    }
}
