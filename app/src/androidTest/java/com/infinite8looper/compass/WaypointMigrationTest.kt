package com.infinite8looper.compass

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.Crumb
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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

    private val freshName = "migration-test-fresh.db"

    @Before fun clean() { removeFiles() }
    @After fun close() { db?.close(); removeFiles() }

    private fun removeFiles() {
        for (n in listOf(name, freshName)) { ctx.deleteDatabase(n); File(ctx.noBackupFilesDir, "$n.sanitized").delete() }
    }

    /** The schema Room exported for [version] (app/schemas, which the build puts in this test's assets). */
    private fun exportedSchema(version: Int): JSONObject =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.infinite8looper.compass.data.WaypointDatabase/$version.json").use { JSONObject(it.readBytes().decodeToString()) }
            .getJSONObject("database")

    /** The statement that creates each table of an exported schema, by table name, as SQLite keeps it. */
    private fun createStatements(schema: JSONObject): Map<String, String> {
        val entities = schema.getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }.associate { e ->
            e.getString("tableName") to asStored(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
        }
    }

    /** SQLite keeps a table's statement without the words that let it be run twice. */
    private fun asStored(createSql: String) = createSql.replace("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE ")

    /** What a database file's schema is: every table and index with the statement that made it, and each table's columns. */
    private fun schemaOf(file: File): Pair<Map<String, String>, Map<String, List<String>>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            val objects = raw.rawQuery(
                "SELECT type || ' ' || name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null,
            ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to (c.getString(1) ?: "") else null }.toMap() }
            val columns = objects.keys.filter { it.startsWith("table ") }.map { it.removePrefix("table ") }.associateWith { table ->
                raw.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                    generateSequence { if (c.moveToNext()) (0 until c.columnCount).joinToString("|") { i -> c.getString(i) ?: "null" } else null }.toList()
                }
            }
            objects to columns
        }

    private fun identityHash(file: File): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
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

    /**
     * A version 1 database is built here from the schema Room exported for version 1, with rows as version 1 stored
     * them, and opened the way the app opens it. Three things are then checked of the migrated file:
     * - Room accepts it: on opening, Room compares every table of the file (columns, types, which may be empty, the
     *   primary key, indices) with the entities this build is compiled from, and refuses a file that differs;
     * - it is the same, statement for statement and column for column, as a database Room makes anew at version 2,
     *   with no table more or less, and its tables' statements are the ones in the exported version 2 schema;
     * - it carries version 2's identity, and the rows are as they were.
     */
    @Test fun aDatabaseBuiltFromTheExportedVersion1SchemaMigratesToExactlyTheSchemaOfVersion2() = runBlocking {
        val v1 = exportedSchema(1)
        assertEquals(1, v1.getInt("version"))
        file().parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file(), null).use { raw ->
            for (sql in createStatements(v1).values) raw.execSQL(sql)
            val setup = v1.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.execSQL("INSERT INTO waypoints(name, lat, lon, accuracyM, createdAt, hue, isTarget) VALUES('Summit', 43.7022, -72.2896, 8.5, 1000, 120.25, 1)")
            raw.execSQL("INSERT INTO waypoints(name, lat, lon, accuracyM, createdAt, hue, isTarget) VALUES('Car', -33.8568, 151.2153, 4.0, 2000, 300.0, 0)")
            raw.version = 1
        }
        // Controls: it is a version 1 file with version 1's identity and no crumbs table.
        assertEquals(1, readRaw().first)
        assertEquals(v1.getString("identityHash"), identityHash(file()))
        assertFalse("crumbs" in tables())

        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals(
            listOf(
                Waypoint(2, "Car", -33.8568, 151.2153, 4.0f, 2000, 300f, false),
                Waypoint(1, "Summit", 43.7022, -72.2896, 8.5f, 1000, 120.25f, true),
            ),
            d.waypoints().observeAll().first(),
        )
        assertEquals(emptyList<Crumb>(), d.crumbs().all())
        d.close(); db = null

        // A database made anew by this build, to compare with.
        val fresh = WaypointDatabase.open(ctx, freshName)
        try { assertTrue(fresh.crumbs().all().isEmpty()) } finally { fresh.close() }
        val migrated = schemaOf(file())
        val anew = schemaOf(ctx.getDatabasePath(freshName))
        assertEquals(setOf("table waypoints", "table crumbs", "table room_master_table"), anew.first.keys)
        assertEquals(anew.first, migrated.first)
        assertEquals(anew.second, migrated.second)
        val v2 = exportedSchema(2)
        assertEquals(2, v2.getInt("version"))
        for ((table, sql) in createStatements(v2)) assertEquals(sql, migrated.first["table $table"])
        assertEquals(setOf("waypoints", "crumbs"), createStatements(v2).keys)
        assertEquals(v2.getString("identityHash"), identityHash(file()))
        assertEquals(2, readRaw().first)
    }

    /** The check above can fail: a file whose crumbs table is not the one this build expects is refused on opening. */
    @Test fun aMigratedFileWithAWrongCrumbsTableIsRefusedByRoom() = runBlocking {
        putVersion1FileInPlace()
        SQLiteDatabase.openDatabase(file().path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            // As a wrong migration would leave it: the table is there, a column is missing, and the version says 2.
            raw.execSQL("CREATE TABLE `crumbs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `createdAt` INTEGER NOT NULL)")
            raw.version = 2
        }
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        val failure = runCatching { d.waypoints().observeAll().first() }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException)
        d.close(); db = null
        // And it was not emptied.
        assertEquals(3, readRaw().second.size)
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
