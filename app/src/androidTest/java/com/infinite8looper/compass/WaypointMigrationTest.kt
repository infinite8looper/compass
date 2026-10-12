package com.infinite8looper.compass

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.Crumb
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Version 2 of the database adds the crumbs. A file of version 1 must come through with every waypoint as it was.
 *
 * The file `waypoints-v1.db` in the test's assets is a real one: the database of the build before the crumbs
 * (commit 05ff0d2), taken from an API 35 emulator after three waypoints were logged there with the side button at
 * three fed positions. Nothing in it was written by hand.
 */
@RunWith(AndroidJUnit4::class)
class WaypointMigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private var db: WaypointDatabase? = null

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WaypointDatabase::class.java)

    @Before fun clean() { removeFiles() }
    @After fun close() { db?.close(); removeFiles() }

    private fun removeFiles() {
        ctx.deleteDatabase(name)
        File(ctx.noBackupFilesDir, "$name.sanitized").delete()
    }

    private fun file(): File = ctx.getDatabasePath(name)

    private fun putVersion1FileInPlace() {
        file().parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("waypoints-v1.db").use { input ->
            file().outputStream().use { input.copyTo(it) }
        }
    }

    /** The waypoints and the schema version of the file as it lies there, read without Room. */
    private fun readRaw(): Pair<Int, List<Waypoint>> =
        SQLiteDatabase.openDatabase(file().path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            val rows = raw.rawQuery("SELECT id, name, lat, lon, accuracyM, createdAt, hue, isTarget FROM waypoints ORDER BY createdAt DESC, id DESC", null).use { c ->
                generateSequence { if (c.moveToNext()) c else null }.map {
                    Waypoint(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getFloat(4), c.getLong(5), c.getFloat(6), c.getInt(7) != 0)
                }.toList()
            }
            raw.version to rows
        }

    private fun tables(): Set<String> =
        SQLiteDatabase.openDatabase(file().path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
            }
        }

    @Test fun aVersion1FileFromTheEarlierBuildKeepsEveryWaypointUnchanged() = runBlocking {
        putVersion1FileInPlace()
        val (versionBefore, before) = readRaw()
        // Controls: it is a version 1 file, it holds the three waypoints, and it has no crumbs table.
        assertEquals(1, versionBefore)
        assertEquals(listOf("Waypoint 3", "Waypoint 2", "Waypoint 1"), before.map { it.name })
        assertTrue(before.all { it.isTarget && it.lat in 43.69..43.71 && it.lon in -72.30..-72.28 })
        assertEquals(3, before.map { it.hue }.distinct().size)
        assertFalse("crumbs" in tables())

        // Opened the way the app opens it.
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals(before, d.waypoints().observeAll().first())
        assertEquals(emptyList<Crumb>(), d.crumbs().all())
        // Both tables work afterwards, and the waypoint ids go on from where they were.
        val added = d.waypoints().insert(Waypoint(name = "After", lat = 1.5, lon = 2.5, accuracyM = 3f, createdAt = 4, hue = 5f, isTarget = false))
        assertEquals(before.maxOf { it.id } + 1, added)
        val crumb = d.crumbs().insert(Crumb(lat = 43.7022, lon = -72.2896, accuracyM = 5f, createdAt = 10))
        assertEquals(listOf(Crumb(crumb, 43.7022, -72.2896, 5f, 10)), d.crumbs().all())
        d.waypoints().delete(added)
        // A migrated file can be made fit to be copied for a backup like any other.
        assertTrue(d.sanitize())
        var copied = false
        assertTrue(d.copyIfSettled(file()) { copied = true })
        assertTrue(copied)
        d.close()

        val (versionAfter, after) = readRaw()
        assertEquals(2, versionAfter)
        assertEquals(before, after)
        assertTrue("crumbs" in tables())
    }

    @Test fun theMigratedFileHasExactlyTheSchemaOfVersion2() {
        // A version 1 database built from the exported schema, with rows put in as version 1 stored them.
        helper.createDatabase(name, 1).use { v1 ->
            v1.execSQL("INSERT INTO waypoints(name, lat, lon, accuracyM, createdAt, hue, isTarget) VALUES('Summit', 43.7022, -72.2896, 8.5, 1000, 120.25, 1)")
            v1.execSQL("INSERT INTO waypoints(name, lat, lon, accuracyM, createdAt, hue, isTarget) VALUES('Car', -33.8568, 151.2153, 4.0, 2000, 300.0, 0)")
        }
        // Room compares every table of the migrated file with the exported version 2 schema and fails on a difference.
        helper.runMigrationsAndValidate(name, 2, true, WaypointDatabase.MIGRATION_1_2).use { v2 ->
            v2.query("SELECT name, lat, lon, accuracyM, createdAt, hue, isTarget FROM waypoints ORDER BY id").use { c ->
                assertTrue(c.moveToNext())
                assertEquals("Summit", c.getString(0)); assertEquals(43.7022, c.getDouble(1), 0.0); assertEquals(-72.2896, c.getDouble(2), 0.0)
                assertEquals(8.5f, c.getFloat(3), 0f); assertEquals(1000L, c.getLong(4)); assertEquals(120.25f, c.getFloat(5), 0f); assertEquals(1, c.getInt(6))
                assertTrue(c.moveToNext())
                assertEquals("Car", c.getString(0)); assertEquals(-33.8568, c.getDouble(1), 0.0); assertEquals(151.2153, c.getDouble(2), 0.0)
                assertEquals(4.0f, c.getFloat(3), 0f); assertEquals(2000L, c.getLong(4)); assertEquals(300f, c.getFloat(5), 0f); assertEquals(0, c.getInt(6))
                assertFalse(c.moveToNext())
            }
            v2.query("SELECT count(*) FROM crumbs").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        }
    }

    /**
     * Without the migration, Room refuses a version 1 file instead of emptying it: this is the same database class
     * built the plain way, with nothing added. The app's own builder adds the migration and no destructive fallback.
     */
    @Test fun withoutTheMigrationAVersion1FileIsRefusedAndLeftAsItWas() = runBlocking {
        putVersion1FileInPlace()
        val (_, before) = readRaw()
        val bytesBefore = file().readBytes()
        val plain = Room.databaseBuilder(ctx, WaypointDatabase::class.java, name).build()
        try {
            val failure = runCatching { plain.waypoints().observeAll().first() }.exceptionOrNull()
            assertTrue("$failure", failure is IllegalStateException)
        } finally {
            plain.close()
        }
        assertEquals(1 to before, readRaw())
        assertArrayEquals(bytesBefore, file().readBytes())
    }

    /** A file from a version this build does not know is refused by the app's own builder and is not emptied. */
    @Test fun aFileFromALaterVersionIsRefusedByTheAppsBuilderAndLeftAsItWas() = runBlocking {
        putVersion1FileInPlace()
        SQLiteDatabase.openDatabase(file().path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        val (_, before) = readRaw()
        assertEquals(3, before.size)
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        val failure = runCatching { d.waypoints().observeAll().first() }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException)
        d.close(); db = null
        assertEquals(99 to before, readRaw())
    }
}
