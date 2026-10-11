package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class TargetsTest {
    private val here = GeoPoint(0.0, 0.0)
    private fun wp(id: Long, lat: Double, lon: Double, target: Boolean) =
        WaypointInfo(id, "W$id", GeoPoint(lat, lon), hueForIndex(id.toInt()), target)
    private val all = listOf(wp(3, 0.0, 1.0, true), wp(2, 1.0, 0.0, false), wp(1, -1.0, 0.0, true))

    @Test fun onlyTargetsAreBuiltAndFocusIsMarked() {
        val t = buildTargets(all, focusedId = 1, position = here, declination = -14f, north = NorthRef.TRUE)
        assertEquals(listOf(3L, 1L), t.map { it.id })
        assertEquals(listOf(false, true), t.map { it.focused })
        assertEquals(90f, t[0].dialAngleDeg!!, 0.01f)
        assertEquals(180f, t[1].dialAngleDeg!!, 0.01f)
        assertEquals(111_195.0, t[0].distanceM!!, 1.0)
    }
    @Test fun magneticDialShiftsMarkers() {
        val t = buildTargets(all, 3, here, -14f, NorthRef.MAGNETIC)
        assertEquals(104f, t[0].dialAngleDeg!!, 0.01f)
    }
    @Test fun noPositionMeansNoAngleOrDistance() {
        val t = buildTargets(all, 3, null, null, NorthRef.TRUE)
        assertEquals(2, t.size); assertNull(t[0].dialAngleDeg); assertNull(t[0].distanceM)
    }
    @Test fun focusFallsBackWhenMissing() {
        val t = buildTargets(all, focusedId = 2, position = here, declination = null, north = NorthRef.TRUE)
        assertTrue(t[0].focused); assertFalse(t[1].focused)
        assertEquals(3L, resolveFocus(listOf(3, 1), 99)); assertEquals(1L, resolveFocus(listOf(3, 1), 1))
        assertNull(resolveFocus(emptyList(), 1))
    }
    @Test fun allWaypointsCanBeTargets() {
        val many = (1L..300L).map { wp(it, it / 1000.0, 0.0, true) }
        assertEquals(300, buildTargets(many, 1, here, 0f, NorthRef.TRUE).size)
    }
    @Test fun steppingWrapsBothWays() {
        val ids = listOf(5L, 4L, 2L)
        assertEquals(4L, stepFocus(ids, 5, 1)); assertEquals(5L, stepFocus(ids, 2, 1))
        assertEquals(2L, stepFocus(ids, 5, -1)); assertEquals(5L, stepFocus(ids, null, 1))
        assertEquals(5L, stepFocus(ids, 99, -1)); assertNull(stepFocus(emptyList(), 5, 1))
    }
    @Test fun namesAreCleaned() {
        assertEquals("Camp", cleanName("  Camp  ", "Old"))
        assertEquals("Old", cleanName("", "Old")); assertEquals("Old", cleanName("   ", "Old"))
        assertEquals("Old", cleanName(null, "Old"))
        assertEquals("Trail head", cleanName("Trail \n  head", "Old"))
        assertEquals(40, cleanName("x".repeat(200), "Old").length)
    }
}
