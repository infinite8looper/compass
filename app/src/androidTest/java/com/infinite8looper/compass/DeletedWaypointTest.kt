package com.infinite8looper.compass

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A deleted waypoint must leave no trace in the database file or in any file SQLite keeps next to it, because the
 * system's backup copies those files as they are. These tests read the raw bytes of the real files on the device.
 */
@RunWith(AndroidJUnit4::class)
class DeletedWaypointTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "deleted-waypoint-test.db"
    private val sourceName = "deleted-waypoint-source.db"
    private val journalModeProperty = "debug.sqlite.journalmode"
    private var db: WaypointDatabase? = null
    private var source: WaypointDatabase? = null

    private val kept = listOf("Zebrafinch", "Narwhalcove")
    private val deleted = "Quokkaspur"

    @Before fun clean() { ctx.deleteDatabase(name); ctx.deleteDatabase(sourceName) }
    @After fun close() {
        db?.close(); source?.close()
        ctx.deleteDatabase(name); ctx.deleteDatabase(sourceName)
    }

    private fun wp(n: String, t: Long) =
        Waypoint(name = n, lat = 43.7022, lon = -72.2896, accuracyM = 8f, createdAt = t, hue = 120f, isTarget = true)

    private fun companions(dbName: String): Map<String, File> =
        listOf("", "-wal", "-shm", "-journal").associateWith { File(ctx.getDatabasePath(dbName).path + it) }

    /** How often [text] occurs in [bytes] in any of the three encodings SQLite can store text in. */
    private fun occurrences(bytes: ByteArray, text: String): Int =
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).sumOf { charset ->
            val needle = text.toByteArray(charset)
            (0..bytes.size - needle.size).count { i -> needle.indices.all { j -> bytes[i + j] == needle[j] } }
        }

    /** Occurrences of [text] in each file that exists for [dbName], keyed by the file name's ending ("" is the database). */
    private fun found(dbName: String, text: String): Map<String, Int> =
        companions(dbName).filterValues { it.exists() }.mapValues { occurrences(it.value.readBytes(), text) }

    private fun assertDeletedNameGoneAndKeptNamesPresent(dbName: String, moment: String) {
        val files = companions(dbName).filterValues { it.exists() }.mapValues { it.value.length() }
        assertEquals("$moment: the deleted name in $files", emptyMap<String, Int>(), found(dbName, deleted).filterValues { it > 0 })
        // Positive control: the search finds text that is stored, so the encoding searched for is the one in the file.
        for (k in kept) assertEquals("$moment: '$k' in the database file ($files)", 1, found(dbName, k)[""])
    }

    private fun pragma(d: WaypointDatabase, sql: String): String =
        d.openHelper.writableDatabase.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    /** Reads a pragma inside a transaction, which the platform runs on the connection that does the writing. */
    private fun writerPragma(d: WaypointDatabase, sql: String): String {
        val w = d.openHelper.writableDatabase
        w.beginTransaction()
        try { return w.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getString(0) } } finally { w.endTransaction() }
    }

    private fun setJournalModeProperty(value: String) {
        // The shell call splits on spaces without a shell's quoting, so the shell's IFS variable stands in for them.
        shell("sh -c setprop\${IFS}$journalModeProperty\${IFS}'$value'")
        assertEquals(value, shell("getprop $journalModeProperty"))
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes().decodeToString().trim() }

    private suspend fun insertThreeDeleteOne(d: WaypointDatabase) {
        d.waypoints().insert(wp(kept[0], 1))
        val gone = d.waypoints().insert(wp(deleted, 2))
        d.waypoints().insert(wp(kept[1], 3))
        assertEquals(3, d.waypoints().observeAll().first().size)
        d.waypoints().delete(gone)
    }

    @Test fun deletedNameIsInNoFileWhileOpenOrAfterClosing() = runBlocking {
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        d.waypoints().insert(wp(kept[0], 1))
        val gone = d.waypoints().insert(wp(deleted, 2))
        d.waypoints().insert(wp(kept[1], 3))
        // Control: before the delete the same search does find the name, in the database file.
        assertEquals(1, found(name, deleted)[""])
        d.waypoints().delete(gone)
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        assertDeletedNameGoneAndKeptNamesPresent(name, "open")
        d.close()
        assertDeletedNameGoneAndKeptNamesPresent(name, "closed")
    }

    @Test fun journalIsTruncatedAndDeletedDataIsOverwritten() = runBlocking {
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals("truncate", pragma(d, "PRAGMA journal_mode"))
        assertEquals("1", pragma(d, "PRAGMA secure_delete"))
        assertEquals("1", writerPragma(d, "PRAGMA secure_delete"))
        insertThreeDeleteOne(d)
        // Still so after use, and no write-ahead log was made.
        assertEquals("truncate", pragma(d, "PRAGMA journal_mode"))
        assertEquals("1", pragma(d, "PRAGMA secure_delete"))
        assertFalse(companions(name).getValue("-wal").exists())
        assertEquals(0L, companions(name).getValue("-journal").length())
    }

    /**
     * The platform's own default for databases without a write-ahead log is a device setting. This test sets it to
     * "persist", which keeps old page contents in the -journal file after each change, and checks that the app's
     * database does not follow it. The setting is put back in every case.
     */
    @Test fun journalIsTruncatedWhereThePlatformDefaultIsPersist() = runBlocking {
        val original = shell("getprop $journalModeProperty")
        try {
            setJournalModeProperty("PERSIST")
            val d = WaypointDatabase.open(ctx, name).also { db = it }
            assertEquals("truncate", pragma(d, "PRAGMA journal_mode"))
            assertEquals("1", writerPragma(d, "PRAGMA secure_delete"))
            insertThreeDeleteOne(d)
            assertEquals(0L, companions(name).getValue("-journal").length())
            assertDeletedNameGoneAndKeptNamesPresent(name, "open")
            d.close()
            assertDeletedNameGoneAndKeptNamesPresent(name, "closed")
        } finally {
            setJournalModeProperty(original)
        }
    }

    /**
     * With the platform's default set to a write-ahead log, the platform keeps the database in that mode whatever
     * the app asks for. The database must still work and overwrite deleted rows, and what a backup would copy must
     * be free of the deleted name: the log is emptied first, and the copy runs only if it is empty.
     */
    @Test fun copyIsCleanWhereThePlatformDefaultIsAWriteAheadLog() = runBlocking {
        val original = shell("getprop $journalModeProperty")
        try {
            setJournalModeProperty("WAL")
            val file = ctx.getDatabasePath(name)
            val d = WaypointDatabase.open(ctx, name).also { db = it }
            assertEquals("1", writerPragma(d, "PRAGMA secure_delete"))
            insertThreeDeleteOne(d)
            assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
            // Control: here the log does hold the deleted name, so this is the case the check exists for.
            assertEquals("wal", pragma(d, "PRAGMA journal_mode"))
            assertTrue(found(name, deleted).toString(), found(name, deleted).getValue("-wal") > 0)
            assertTrue(WaypointDatabase.hasJournalData(file))

            var seen: Map<String, Int>? = null
            assertTrue(d.copyIfSettled(file) { seen = found(name, deleted) })
            assertEquals(emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
            assertEquals(0L, companions(name).getValue("-wal").length())
            for (k in kept) assertEquals(1, found(name, k)[""])
            assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        } finally {
            setJournalModeProperty(original)
        }
    }

    /**
     * A database left behind in write-ahead-log mode, with the log still holding the deleted waypoint and the main
     * file holding it in freed space (the way a build without these protections, stopped without closing, leaves it).
     */
    @Test fun databaseLeftInWalModeIsConvertedAndCleaned() = runBlocking {
        val src = Room.databaseBuilder(ctx, WaypointDatabase::class.java, sourceName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA secure_delete = OFF").use { it.moveToFirst() }
                }
            })
            .build().also { source = it }
        assertEquals("wal", pragma(src, "PRAGMA journal_mode"))
        assertEquals("0", pragma(src, "PRAGMA secure_delete"))
        insertThreeDeleteOne(src)
        // Write the log into the main file without emptying the log, so both hold the deleted name.
        src.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() }
        // Copy the files as they are while the source is open: closing it normally would remove the log.
        for ((ending, file) in companions(sourceName)) if (file.exists()) file.copyTo(companions(name).getValue(ending))
        src.close()

        val before = found(name, deleted)
        assertTrue("the left-behind log holds the deleted name: $before", before.getValue("-wal") > 0)
        assertTrue("the left-behind main file holds the deleted name: $before", before.getValue("") > 0)
        assertTrue(WaypointDatabase.hasJournalData(ctx.getDatabasePath(name)))

        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        assertEquals("truncate", pragma(d, "PRAGMA journal_mode"))
        assertEquals("1", pragma(d, "PRAGMA secure_delete"))
        // Already clean before anything is written: a later write could hide the name only by landing on top of it.
        assertEquals(0L, companions(name).getValue("-wal").length())
        assertDeletedNameGoneAndKeptNamesPresent(name, "opened")
        val extra = d.waypoints().insert(wp("Ibisfen", 4))
        assertEquals("Ibisfen", d.waypoints().get(extra)!!.name)
        assertEquals(0L, companions(name).getValue("-wal").length())
        assertFalse(WaypointDatabase.hasJournalData(ctx.getDatabasePath(name)))
        assertDeletedNameGoneAndKeptNamesPresent(name, "open")
        d.close()
        assertEquals(0L, companions(name).getValue("-wal").length())
        assertDeletedNameGoneAndKeptNamesPresent(name, "closed")
    }

    @Test fun copyRunsOnlyWhenNoJournalOrLogDataLiesNextToTheDatabase() = runBlocking {
        val file = ctx.getDatabasePath(name)
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        insertThreeDeleteOne(d)

        // Clean: the copy runs, and what it reads (as a backup would) has no trace of the deleted name.
        var seen: Map<String, Int>? = null
        assertTrue(d.copyIfSettled(file) { seen = found(name, deleted) })
        assertEquals(emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
        assertFalse(d.openHelper.writableDatabase.inTransaction())

        // Bytes in a log file next to the database: the copy must not run. Then the same for a journal file.
        for (ending in listOf("-wal", "-journal")) {
            val companion = companions(name).getValue(ending)
            val had = companion.exists()
            companion.writeBytes(deleted.toByteArray())
            try {
                assertTrue(ending, WaypointDatabase.hasJournalData(file))
                var ran = false
                assertFalse(ending, d.copyIfSettled(file) { ran = true })
                assertFalse(ending, ran)
                assertFalse(d.openHelper.writableDatabase.inTransaction())
            } finally {
                if (had) companion.writeBytes(ByteArray(0)) else companion.delete()
            }
        }

        // Clean again: it runs again, and the database still works afterwards.
        var ran = false
        assertTrue(d.copyIfSettled(file) { ran = true })
        assertTrue(ran)
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
    }

    /**
     * A second connection holds the file in write-ahead-log mode with data in its log and is reading from it, as
     * nothing in the app does. The app's database cannot leave that mode or empty the log while that goes on, so
     * nothing is copied. Once the other connection is gone, the log is emptied and the copy runs.
     */
    @Test fun copyDoesNotRunWhileAnotherConnectionKeepsALogInUse() = runBlocking {
        val file = ctx.getDatabasePath(name)
        val other = Room.databaseBuilder(ctx, WaypointDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also { source = it }
        insertThreeDeleteOne(other)
        assertTrue(found(name, deleted).getValue("-wal") > 0)
        assertTrue(WaypointDatabase.hasJournalData(file))
        // An open cursor in another thread's transaction keeps the log's contents in use.
        val reader = other.openHelper.writableDatabase
        val holding = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            reader.beginTransactionNonExclusive()
            try {
                reader.query("SELECT count(*) FROM waypoints").use { it.moveToFirst(); holding.countDown(); release.await() }
            } finally {
                reader.endTransaction()
            }
        }.also { it.start() }
        try {
            assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val d = WaypointDatabase.open(ctx, name).also { db = it }
            var ran = false
            val copied = try { d.copyIfSettled(file) { ran = true } } catch (e: SQLiteException) { false }
            assertFalse(copied)
            assertFalse(ran)
            assertTrue(WaypointDatabase.hasJournalData(file))
        } finally {
            release.countDown(); thread.join(10_000)
        }
        other.close()

        val d = db!!
        var seen: Map<String, Int>? = null
        assertTrue(d.copyIfSettled(file) { seen = found(name, deleted) })
        assertEquals(emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
    }
}
