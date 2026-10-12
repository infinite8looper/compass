package com.infinite8looper.compass

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
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
import org.junit.Assume.assumeTrue
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

    @Before fun clean() { removeFiles() }
    @After fun close() {
        db?.close(); source?.close()
        removeFiles()
    }

    private fun removeFiles() {
        for (n in listOf(name, sourceName)) { ctx.deleteDatabase(n); marker(n).delete() }
        scripts().forEach { it.delete() }
    }

    /** Where the app records that the database of that name has been rebuilt; this folder is never backed up. */
    private fun marker(dbName: String) = File(ctx.noBackupFilesDir, "$dbName.sanitized")
    private fun scripts() = listOf(File(ctx.filesDir, "hot-journal.sql"), File(ctx.filesDir, "hot-journal.sh"))

    private fun isEmulator() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk_")

    /** A database of the app's schema that does none of what the app's own builder does, with deleted rows left readable. */
    private fun unprotected(dbName: String, writeAheadLog: Boolean): WaypointDatabase =
        Room.databaseBuilder(ctx, WaypointDatabase::class.java, dbName)
            .setJournalMode(if (writeAheadLog) RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING else RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA secure_delete = OFF").use { it.moveToFirst() }
                }
            })
            .build()

    /** What the backup agent does with a copy that is refused or cannot be attempted: nothing runs. */
    private fun copied(d: WaypointDatabase, dbName: String, copy: () -> Unit): Boolean =
        try { d.copyIfSettled(ctx.getDatabasePath(dbName), copy) } catch (e: SQLiteException) { false }

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

    /**
     * Runs [body] with the platform's default journal mode set to [value]. This is a device-wide debug property, so
     * it is done on an emulator only and skipped anywhere else. The property is put back whatever [body] does, and
     * if both [body] and putting it back fail, the failure of [body] is the one reported.
     */
    private fun withPlatformJournalDefault(value: String, body: suspend () -> Unit) {
        assumeTrue("skipped: changes the device-wide property $journalModeProperty, which is done on an emulator only", isEmulator())
        val original = shell("getprop $journalModeProperty")
        var failure: Throwable? = null
        try { setJournalModeProperty(value); runBlocking { body() } } catch (t: Throwable) { failure = t }
        var notRestored: Throwable? = null
        try { setJournalModeProperty(original) } catch (t: Throwable) { notRestored = t }
        failure?.let { f -> notRestored?.let { f.addSuppressed(it) }; throw f }
        notRestored?.let { throw it }
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
    @Test fun journalIsTruncatedWhereThePlatformDefaultIsPersist() = withPlatformJournalDefault("PERSIST") {
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals("truncate", pragma(d, "PRAGMA journal_mode"))
        assertEquals("1", writerPragma(d, "PRAGMA secure_delete"))
        insertThreeDeleteOne(d)
        assertEquals(0L, companions(name).getValue("-journal").length())
        assertDeletedNameGoneAndKeptNamesPresent(name, "open")
        d.close()
        assertDeletedNameGoneAndKeptNamesPresent(name, "closed")
    }

    /**
     * With the platform's default set to a write-ahead log, the platform keeps the database in that mode whatever
     * the app asks for. The database must still work and overwrite deleted rows, and what a backup would copy must
     * be free of the deleted name: the log is emptied first, and the copy runs only if it is empty.
     */
    @Test fun copyIsCleanWhereThePlatformDefaultIsAWriteAheadLog() = withPlatformJournalDefault("WAL") {
        val file = ctx.getDatabasePath(name)
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals("1", writerPragma(d, "PRAGMA secure_delete"))
        insertThreeDeleteOne(d)
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        // Control: here the log does hold the deleted name, so this is the case the check exists for.
        assertEquals("wal", pragma(d, "PRAGMA journal_mode"))
        assertTrue(found(name, deleted).toString(), found(name, deleted).getValue("-wal") > 0)
        assertTrue(WaypointDatabase.hasJournalData(file))

        assertTrue(d.sanitize())
        var seen: Map<String, Int>? = null
        assertTrue(d.copyIfSettled(file) { seen = found(name, deleted) })
        assertEquals(emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
        assertEquals(0L, companions(name).getValue("-wal").length())
        for (k in kept) assertEquals(1, found(name, k)[""])
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
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
        // Not yet rebuilt, so not yet allowed to be copied.
        assertFalse(copied(d, name) { fail("copied before the rebuild") })
        assertTrue(d.sanitize())
        // Clean before anything is written: a later write could hide the name only by landing on top of it.
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

        assertTrue(d.sanitize())
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
     * The backup agent copies without opening a database only when the app has none. A journal or log file with
     * bytes in it counts as having one even when the database file itself is missing, since it would be copied too.
     */
    @Test fun journalOrLogLeftWithoutADatabaseFileCountsAsADatabase() {
        val file = ctx.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        // Controls: nothing there at all, and the database file alone.
        assertEquals(emptyMap<String, File>(), companions(name).filterValues { it.exists() })
        assertFalse(WaypointDatabase.hasFiles(file))
        file.writeBytes(deleted.toByteArray())
        assertTrue(WaypointDatabase.hasFiles(file))
        assertTrue(file.delete())

        for (ending in listOf("-journal", "-wal")) {
            val companion = companions(name).getValue(ending)
            try {
                companion.writeBytes(deleted.toByteArray())
                assertFalse(file.exists())
                assertTrue(ending, WaypointDatabase.hasFiles(file))
                // An empty one holds nothing that could be copied.
                companion.writeBytes(ByteArray(0))
                assertFalse(ending, WaypointDatabase.hasFiles(file))
            } finally {
                companion.delete()
            }
        }
    }

    /**
     * What follows from the test above: with such a file there, the backup agent opens the database instead of
     * copying blindly. The left-behind bytes must then be gone before the copy is allowed to run.
     */
    @Test fun journalOrLogLeftWithoutADatabaseFileDoesNotReachACopy() = runBlocking {
        val file = ctx.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        for (ending in listOf("-journal", "-wal")) {
            companions(name).getValue(ending).writeBytes(deleted.toByteArray())
            assertEquals(ending, mapOf(ending to 1), found(name, deleted))

            val d = WaypointDatabase.open(ctx, name).also { db = it }
            for ((i, k) in kept.withIndex()) d.waypoints().insert(wp(k, i + 1L))
            assertTrue(ending, d.sanitize())
            var seen: Map<String, Int>? = null
            assertTrue(ending, d.copyIfSettled(file) { seen = found(name, deleted) })
            assertEquals(ending, emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
            assertDeletedNameGoneAndKeptNamesPresent(name, "open, after a left-behind $ending")
            d.close()
            removeFiles()
        }
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
        assertTrue(d.sanitize())
        var seen: Map<String, Int>? = null
        assertTrue(d.copyIfSettled(file) { seen = found(name, deleted) })
        assertEquals(emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
    }

    /**
     * A file that was never in write-ahead-log mode, from a build that left deleted rows readable: the deleted name
     * sits in freed space of the main file. It must not be copied as it is, and must be clean once rebuilt.
     */
    @Test fun rollbackFileWithDeletedContentIsRebuiltBeforeItCanBeCopied() = runBlocking {
        val file = ctx.getDatabasePath(name)
        val old = unprotected(name, writeAheadLog = false).also { source = it }
        assertEquals("0", pragma(old, "PRAGMA secure_delete"))
        insertThreeDeleteOne(old)
        old.close()
        // Controls: the name is still in the main file, and the file's header says rollback format (1), not log (2).
        assertEquals(mapOf("" to 1), found(name, deleted).filterValues { it > 0 })
        assertEquals(1, file.readBytes()[18].toInt())

        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        assertFalse(copied(d, name) { fail("copied before the rebuild") })
        assertTrue(d.sanitize())
        assertDeletedNameGoneAndKeptNamesPresent(name, "rebuilt")
        var seen: Map<String, Int>? = null
        assertTrue(d.copyIfSettled(file) { seen = found(name, deleted) })
        assertEquals(emptyMap<String, Int>(), seen!!.filterValues { it > 0 })
        d.close()
        assertDeletedNameGoneAndKeptNamesPresent(name, "closed")
    }

    /**
     * The record of a rebuild belongs to one file. A different database put in its place (as a restore, a transfer
     * or an older build's file would be) does not count as rebuilt, and neither does a rebuilt file that arrives
     * where the device has no record of it.
     */
    @Test fun rebuildRecordIsNotInheritedByAnotherFile() = runBlocking {
        val file = ctx.getDatabasePath(name)
        val first = WaypointDatabase.open(ctx, name).also { db = it }
        first.waypoints().insert(wp("Ibisfen", 1))
        assertTrue(first.sanitize())
        assertTrue(first.copyIfSettled(file) {})
        first.close()
        assertTrue(marker(name).exists())

        // Another database, with deleted content and an identifying number of its own, takes the file's place.
        val other = unprotected(sourceName, writeAheadLog = false).also { source = it }
        insertThreeDeleteOne(other)
        other.openHelper.writableDatabase.execSQL("PRAGMA application_id = 424242")
        other.close()
        ctx.getDatabasePath(sourceName).copyTo(file, overwrite = true)
        assertEquals(mapOf("" to 1), found(name, deleted).filterValues { it > 0 })

        val second = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals(kept.reversed(), second.waypoints().observeAll().first().map { it.name })
        assertFalse(copied(second, name) { fail("copied a file that was never rebuilt") })
        assertTrue(second.sanitize())
        assertDeletedNameGoneAndKeptNamesPresent(name, "rebuilt")
        assertTrue(second.copyIfSettled(file) {})
        second.close()

        // The rebuilt file, arriving where there is no record of it (a restore onto a fresh install).
        assertTrue(marker(name).delete())
        val third = WaypointDatabase.open(ctx, name).also { db = it }
        assertFalse(copied(third, name) { fail("copied without a record of the rebuild") })
        assertTrue(third.sanitize())
        assertTrue(third.copyIfSettled(file) {})
    }

    /**
     * The rebuild fails for real here: another connection holds a write lock on the file, so the rebuild cannot
     * get the lock it needs. The database must stay usable, and must not be copied.
     */
    @Test fun failedRebuildLeavesTheDatabaseUsableAndUncopied() = runBlocking {
        val file = ctx.getDatabasePath(name)
        val old = unprotected(name, writeAheadLog = false).also { source = it }
        insertThreeDeleteOne(old)
        old.close()
        assertEquals(mapOf("" to 1), found(name, deleted).filterValues { it > 0 })

        val d = WaypointDatabase.open(ctx, name).also { db = it }
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        val blocker = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            blocker.beginTransactionNonExclusive()
            try {
                assertFalse(d.sanitize())
                assertFalse(marker(name).exists())
                // Still usable: it reads, and nothing is copied.
                assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
                assertFalse(copied(d, name) { fail("copied after a failed rebuild") })
            } finally {
                blocker.endTransaction()
            }
        } finally {
            blocker.close()
        }
        // It writes again, the rebuild is retried and succeeds, and only then is it copied.
        d.waypoints().setAllTargets(false)
        assertTrue(d.waypoints().observeAll().first().none { it.isTarget })
        assertEquals(mapOf("" to 1), found(name, deleted).filterValues { it > 0 })
        assertTrue(d.sanitize())
        assertDeletedNameGoneAndKeptNamesPresent(name, "rebuilt")
        assertTrue(d.copyIfSettled(file) {})
    }

    /**
     * A real hot journal: another process (the device's sqlite3 tool, started through the shell) changes the
     * database inside a transaction large enough to be written into the main file early, and is killed before it
     * commits. What that transaction wrote, and the journal it left, must not be in the files at the time of a copy.
     */
    @Test fun hotJournalFromAKilledProcessDoesNotReachACopy() = runBlocking {
        val file = ctx.getDatabasePath(name)
        val journal = companions(name).getValue("-journal")
        val first = WaypointDatabase.open(ctx, name).also { db = it }
        insertThreeDeleteOne(first)
        assertTrue(first.sanitize())
        first.close()
        val sizeBefore = file.length()

        val (sql, script) = scripts()
        sql.writeText(
            "PRAGMA cache_size = 2;\nBEGIN;\n" +
                "UPDATE waypoints SET name = 'Hotjournalname' WHERE name = '${kept[0]}';\n" +
                "DELETE FROM waypoints WHERE name = '${kept[1]}';\n" +
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3000) " +
                "INSERT INTO waypoints(name, lat, lon, accuracyM, createdAt, hue, isTarget) " +
                "SELECT 'Spillfiller' || i, 0, 0, 0, i, 0, 0 FROM n;\n",
        )
        script.writeText("(cat ${sql.path}; sleep 30) | sqlite3 ${file.path}\n")
        val pkg = ctx.packageName
        val running = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("run-as $pkg sh ${script.path}")
        try {
            // Wait until the uncommitted changes are in the main file and the journal holds the old pages.
            val deadline = System.currentTimeMillis() + 20_000
            while ((journal.length() == 0L || file.length() <= sizeBefore) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            Thread.sleep(500)
        } finally {
            shell("run-as $pkg pkill -9 sqlite3")
            shell("run-as $pkg pkill -9 sleep")
            ParcelFileDescriptor.AutoCloseInputStream(running).use { it.readBytes() }
        }
        // Controls: the journal is there with the old content, and the main file holds uncommitted rows.
        assertTrue("journal ${journal.length()} bytes", journal.length() > 0)
        assertTrue(WaypointDatabase.hasJournalData(file))
        assertTrue(found(name, kept[0]).toString(), found(name, kept[0]).getValue("-journal") > 0)
        assertTrue(found(name, "Spillfiller").toString(), found(name, "Spillfiller").getValue("") > 0)

        val d = WaypointDatabase.open(ctx, name).also { db = it }
        // The interrupted transaction is undone: the rows are the ones from before it.
        assertEquals(kept.reversed(), d.waypoints().observeAll().first().map { it.name })
        assertTrue(d.sanitize())
        var seen: Map<String, Map<String, Int>>? = null
        assertTrue(d.copyIfSettled(file) { seen = listOf("Hotjournalname", "Spillfiller", deleted).associateWith { found(name, it).filterValues { n -> n > 0 } } })
        assertEquals(mapOf("Hotjournalname" to emptyMap(), "Spillfiller" to emptyMap<String, Int>(), deleted to emptyMap()), seen)
        assertEquals(0L, journal.length())
        assertDeletedNameGoneAndKeptNamesPresent(name, "open")
    }

    @Test fun renamedWaypointsOldNameIsInNoFile() = runBlocking {
        val d = WaypointDatabase.open(ctx, name).also { db = it }
        d.waypoints().insert(wp(kept[0], 1))
        val renamed = d.waypoints().insert(wp(deleted, 2))
        d.waypoints().insert(wp(kept[1], 3))
        assertEquals(1, found(name, deleted)[""])
        // The same call the app's rename makes. The new name is longer, so the row cannot simply be written in place.
        d.waypoints().update(d.waypoints().get(renamed)!!.copy(name = "Ibisfenmoorland by the weir"))
        assertEquals("Ibisfenmoorland by the weir", d.waypoints().get(renamed)!!.name)
        assertDeletedNameGoneAndKeptNamesPresent(name, "open")
        assertEquals(1, found(name, "Ibisfenmoorland by the weir")[""])
        // And a shorter one, which is written over the old one.
        d.waypoints().update(d.waypoints().get(renamed)!!.copy(name = "Elk"))
        assertEquals(emptyMap<String, Int>(), found(name, "Ibisfenmoorland").filterValues { it > 0 })
        d.close()
        assertDeletedNameGoneAndKeptNamesPresent(name, "closed")
        assertEquals(emptyMap<String, Int>(), found(name, "Ibisfenmoorland").filterValues { it > 0 })
    }
}
