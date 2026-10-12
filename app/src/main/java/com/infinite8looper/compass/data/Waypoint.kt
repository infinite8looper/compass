package com.infinite8looper.compass.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.*
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.WaypointInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.security.SecureRandom

@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val createdAt: Long,
    val hue: Float,
    val isTarget: Boolean,
) {
    fun toInfo() = WaypointInfo(id, name, GeoPoint(lat, lon), hue, isTarget)
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<Waypoint>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun get(id: Long): Waypoint?

    @Insert suspend fun insert(w: Waypoint): Long
    @Update suspend fun update(w: Waypoint)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE waypoints SET isTarget = :isTarget WHERE id = :id")
    suspend fun setTarget(id: Long, isTarget: Boolean)

    @Query("UPDATE waypoints SET isTarget = :isTarget")
    suspend fun setAllTargets(isTarget: Boolean)
}

/**
 * Version 1 held the waypoints. Version 2 adds the crumbs of the breadcrumb trail ([MIGRATION_1_2]). The schema of
 * each version is exported to app/schemas.
 */
@Database(entities = [Waypoint::class, Crumb::class], version = 2, exportSchema = true)
abstract class WaypointDatabase : RoomDatabase() {
    abstract fun waypoints(): WaypointDao
    abstract fun crumbs(): CrumbDao

    /** The file a rebuild is recorded in; set by [open]. It lies in the folder the system never backs up. */
    private var record: File? = null

    /**
     * Makes sure this database file has been rebuilt, so that it holds nothing of rows deleted or changed before
     * deleted content was being overwritten, and that this is on record for this file. Returns whether it is.
     *
     * The record has two halves that must agree: a random number written into the file's header (the SQLite
     * "application id", which Room does not use) and the same number in a file of the app's no-backup folder. A
     * database that arrives by restore or transfer, comes from an older build or is created anew either has no
     * number, or one this device has no record of, so it is rebuilt before it counts. The number is written only
     * after the rebuild has succeeded. A failed rebuild leaves the database as usable as it was, and without a
     * record, so the next call tries again. Not for the main thread.
     */
    fun sanitize(): Boolean {
        val record = record ?: return false
        return try {
            val db = openHelper.writableDatabase
            if (isRecorded(db, record)) return true
            if (!overwritesDeletedContent(db)) return false
            db.execSQL("VACUUM")
            val id = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
            db.execSQL("PRAGMA application_id = $id")
            val draft = File(record.path + ".new")
            draft.writeText(id.toString())
            if (!draft.renameTo(record)) throw IOException()
            isRecorded(db, record)
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not rebuild the waypoint database (" + e.javaClass.simpleName + ")")
            false
        } catch (e: IOException) {
            Log.w(TAG, "could not record the rebuild of the waypoint database (" + e.javaClass.simpleName + ")")
            false
        }
    }

    /**
     * Runs [copy] while the database is locked against changes, and only if the files are fit to be copied as they
     * are: the file is on record as rebuilt (see [sanitize]), deleted content is being overwritten, and no journal
     * or log data lies next to [databaseFile]. Returns whether [copy] ran. The lock is a transaction that writes
     * nothing, so it adds no journal data of its own.
     */
    fun copyIfSettled(databaseFile: File, copy: () -> Unit): Boolean {
        val record = record ?: return false
        val db = openHelper.writableDatabase
        // Only matters where the database could not be kept out of write-ahead-log mode (see [open]): this writes
        // the log into the main file and empties it. Its result is not relied on; the check of the files decides.
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        db.beginTransaction()
        try {
            if (!isRecorded(db, record) || !overwritesDeletedContent(db) || hasJournalData(databaseFile)) return false
            copy()
            return true
        } finally {
            db.endTransaction()
        }
    }

    private fun isRecorded(db: SupportSQLiteDatabase, record: File): Boolean {
        val inFile = db.query("PRAGMA application_id").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val onRecord = try { record.readText().toIntOrNull() } catch (e: IOException) { null }
        return inFile != 0 && inFile == onRecord
    }

    /** Reads the setting inside a transaction, which is where the platform uses the connection that writes. */
    private fun overwritesDeletedContent(db: SupportSQLiteDatabase): Boolean {
        val own = !db.inTransaction()
        if (own) db.beginTransaction()
        try {
            return db.query("PRAGMA secure_delete").use { it.moveToFirst() && it.getInt(0) == 1 }
        } finally {
            if (own) db.endTransaction()
        }
    }

    companion object {
        const val FILE_NAME = "waypoints.db"
        private const val TAG = "WaypointDatabase"

        /**
         * From version 1 to 2: adds the empty table of crumbs and touches nothing else, so every waypoint is as it
         * was. The statement is the one Room itself creates the table with (app/schemas, version 2).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crumbs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracyM` REAL NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        @Volatile private var instance: WaypointDatabase? = null

        /**
         * A deleted waypoint must not stay readable in the files, since the system's backup copies them as they are.
         * So the database is set to overwrite deleted rows with zeros and to keep no write-ahead log, with a
         * rollback journal that is emptied after every change, rather than leaving these to the device's defaults.
         */
        fun open(context: Context, name: String): WaypointDatabase =
            Room.databaseBuilder(context.applicationContext, WaypointDatabase::class.java, name)
                // Room, used without a driver, only turns the write-ahead log off here; the journal mode is then
                // the platform's default for databases without one, so the callback below sets it as well.
                .setJournalMode(JournalMode.TRUNCATE)
                // A file of an earlier version is migrated. There is no fallback that would empty it: a file this
                // build has no migration for is refused, and stays as it is.
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        try {
                            db.query("PRAGMA journal_mode = TRUNCATE").use { it.moveToFirst() }
                        } catch (e: SQLiteException) {
                            // Seen where the platform's default is itself a write-ahead log, or while another
                            // connection holds the file in that mode: the mode cannot be changed. The database
                            // still works, and [copyIfSettled] keeps a non-empty log out of any backup.
                            Log.w(TAG, "could not set the waypoint database's journal mode (" + e.javaClass.simpleName + ")")
                        }
                        // The platform runs this on every connection it has for the database and on every one it
                        // opens later, so a replaced or added connection overwrites deleted content too. Android 13,
                        // 14 and 15 all accept a pragma that answers with a row here. A statement that failed would
                        // not be reported by this call: the platform logs it, closes the connection and keeps the
                        // statement, so every later use of the database would throw. There is nothing to catch
                        // here, and the database would not work at all; a backup pass would then hand over nothing,
                        // through the backup agent's handling of that exception. Separately, [copyIfSettled] reads
                        // the setting back before any copy.
                        db.execPerConnectionSQL("PRAGMA secure_delete = ON", null)
                    }
                })
                .build()
                .also { it.record = File(context.applicationContext.noBackupFilesDir, "$name.sanitized") }

        fun get(context: Context): WaypointDatabase =
            instance ?: synchronized(this) { instance ?: open(context, FILE_NAME).also { instance = it } }

        /**
         * Runs [copy] only if the app has no waypoint database at all, neither files (see [hasFiles]) nor one opened
         * in this process, and keeps one from being opened meanwhile. Returns whether that was the case.
         */
        fun copyWithoutDatabase(context: Context, copy: () -> Unit): Boolean = synchronized(this) {
            if (instance != null || hasFiles(context.getDatabasePath(FILE_NAME))) return false
            copy()
            true
        }

        /**
         * True when anything of a database that a backup would copy is at [databaseFile]: the file itself, or a
         * journal or log with bytes in it that was left behind without one. An empty journal or log and a "-shm"
         * file do not count, since neither holds rows.
         */
        fun hasFiles(databaseFile: File): Boolean = databaseFile.exists() || hasJournalData(databaseFile)

        /**
         * True when the rollback journal or a write-ahead log next to [databaseFile] holds any bytes. Such a file
         * can hold rows that have been deleted from the database.
         */
        fun hasJournalData(databaseFile: File): Boolean =
            listOf("-journal", "-wal").any { File(databaseFile.path + it).length() > 0 }
    }
}
