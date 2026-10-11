package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaypointDatabaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "test-waypoints.db"
    private lateinit var db: WaypointDatabase

    @Before fun open() { ctx.deleteDatabase(name); db = WaypointDatabase.open(ctx, name) }
    @After fun close() { db.close(); ctx.deleteDatabase(name) }

    private fun wp(n: String, t: Long, target: Boolean = true) =
        Waypoint(name = n, lat = 43.7022, lon = -72.2896, accuracyM = 8f, createdAt = t, hue = 120f, isTarget = target)

    @Test fun freshDatabaseIsEmpty() = runBlocking {
        assertTrue(db.waypoints().observeAll().first().isEmpty())
    }
    @Test fun insertReadBackExactValuesNewestFirst() = runBlocking {
        val a = db.waypoints().insert(wp("A", 1000)); val b = db.waypoints().insert(wp("B", 2000))
        val all = db.waypoints().observeAll().first()
        assertEquals(listOf(b, a), all.map { it.id })
        val got = db.waypoints().get(a)!!
        assertEquals("A", got.name); assertEquals(43.7022, got.lat, 0.0); assertEquals(-72.2896, got.lon, 0.0)
        assertEquals(8f, got.accuracyM, 0f); assertEquals(120f, got.hue, 0f); assertTrue(got.isTarget)
        assertEquals(got.id, got.toInfo().id); assertEquals(43.7022, got.toInfo().point.lat, 0.0)
    }
    @Test fun duplicateNamesGetDistinctIds() = runBlocking {
        val a = db.waypoints().insert(wp("Same", 1)); val b = db.waypoints().insert(wp("Same", 1))
        assertNotEquals(a, b); assertEquals(2, db.waypoints().observeAll().first().size)
    }
    @Test fun idsAreNotReusedAfterDelete() = runBlocking {
        val a = db.waypoints().insert(wp("A", 1)); db.waypoints().delete(a)
        assertTrue(db.waypoints().insert(wp("B", 2)) > a)
    }
    @Test fun updateRenamesAndRecolors() = runBlocking {
        val id = db.waypoints().insert(wp("A", 1))
        db.waypoints().update(db.waypoints().get(id)!!.copy(name = "Camp", hue = 300f))
        val got = db.waypoints().get(id)!!
        assertEquals("Camp", got.name); assertEquals(300f, got.hue, 0f)
    }
    @Test fun targetsToggleSinglyAndAllAtOnce() = runBlocking {
        val a = db.waypoints().insert(wp("A", 1, target = false)); db.waypoints().insert(wp("B", 2, target = false))
        db.waypoints().setTarget(a, true)
        assertEquals(1, db.waypoints().observeAll().first().count { it.isTarget })
        db.waypoints().setAllTargets(true)
        assertTrue(db.waypoints().observeAll().first().all { it.isTarget })
        db.waypoints().setAllTargets(false)
        assertTrue(db.waypoints().observeAll().first().none { it.isTarget })
    }
    @Test fun deleteMissingIdIsHarmless() = runBlocking {
        db.waypoints().delete(12345); assertNull(db.waypoints().get(12345))
    }
    @Test fun dataSurvivesReopen() = runBlocking {
        val id = db.waypoints().insert(wp("Keep", 1)); db.close()
        db = WaypointDatabase.open(ctx, name)
        assertEquals("Keep", db.waypoints().get(id)!!.name)
    }
}
