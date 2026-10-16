package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.Crumb
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

    // The crumbs of the breadcrumb trail, which are a second table of the same database.

    private fun crumb(lat: Double, t: Long) = Crumb(lat = lat, lon = -72.2896, accuracyM = 6f, createdAt = t)

    @Test fun freshDatabaseHasNoCrumbs() = runBlocking {
        assertTrue(db.crumbs().all().isEmpty())
        assertTrue(db.crumbs().observeAll().first().isEmpty())
    }
    @Test fun crumbsReadBackExactValuesNewestFirstWhateverTheirClockTimes() = runBlocking {
        // The second is stamped earlier than the first, as after the watch's clock was set back.
        val a = db.crumbs().insert(crumb(43.7022, 5000)); val b = db.crumbs().insert(crumb(43.7031, 1000)); val c = db.crumbs().insert(crumb(43.7040, 9000))
        val all = db.crumbs().all()
        assertEquals(listOf(c, b, a), all.map { it.id })
        assertEquals(all, db.crumbs().observeAll().first())
        assertEquals(Crumb(b, 43.7031, -72.2896, 6f, 1000), all[1])
        assertEquals(b, all[1].toPoint().id); assertEquals(43.7031, all[1].toPoint().point.lat, 0.0); assertEquals(-72.2896, all[1].toPoint().point.lon, 0.0)
    }
    @Test fun deletingACrumbRemovesThatOneAndItsIdIsNotReused() = runBlocking {
        val a = db.crumbs().insert(crumb(43.7022, 1)); val b = db.crumbs().insert(crumb(43.7031, 2))
        db.crumbs().delete(b)
        assertEquals(listOf(a), db.crumbs().all().map { it.id })
        // Deleting it again, or one that never was, is harmless.
        db.crumbs().delete(b); db.crumbs().delete(12345)
        assertEquals(listOf(a), db.crumbs().all().map { it.id })
        assertTrue(db.crumbs().insert(crumb(43.7040, 3)) > b)
    }
    @Test fun clearingTheCrumbsLeavesTheWaypoints() = runBlocking {
        val w = db.waypoints().insert(wp("Keep", 1))
        repeat(5) { db.crumbs().insert(crumb(43.70 + it / 1000.0, it.toLong())) }
        assertEquals(5, db.crumbs().all().size)
        db.crumbs().clear()
        assertTrue(db.crumbs().all().isEmpty())
        assertEquals(listOf(w), db.waypoints().observeAll().first().map { it.id })
        // And deleting every waypoint leaves the crumbs.
        val c = db.crumbs().insert(crumb(43.7022, 9))
        db.waypoints().delete(w)
        assertEquals(listOf(c), db.crumbs().all().map { it.id })
    }
    @Test fun crumbsSurviveReopen() = runBlocking {
        val id = db.crumbs().insert(crumb(43.7022, 1)); db.close()
        db = WaypointDatabase.open(ctx, name)
        assertEquals(listOf(Crumb(id, 43.7022, -72.2896, 6f, 1)), db.crumbs().all())
    }
}
