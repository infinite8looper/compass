package com.infinite8looper.compass.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.*
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.WaypointInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.RandomAccessFile

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

@Database(entities = [Waypoint::class], version = 1, exportSchema = false)
abstract class WaypointDatabase : RoomDatabase() {
    abstract fun waypoints(): WaypointDao

    /**
     * Runs [copy] while the database is locked against changes, and only if no journal or log data lies next to
     * [databaseFile]. Returns whether [copy] ran. The lock is a transaction that writes nothing, so it adds no
     * journal data of its own.
     */
    fun copyIfSettled(databaseFile: File, copy: () -> Unit): Boolean {
        val db = openHelper.writableDatabase
        // Only matters where the database could not be kept out of write-ahead-log mode (see [open]): this writes
        // the log into the main file and empties it. Its result is not relied on; the check of the files decides.
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        db.beginTransaction()
        try {
            if (hasJournalData(databaseFile)) return false
            copy()
            return true
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val FILE_NAME = "waypoints.db"
        private const val TAG = "WaypointDatabase"

        @Volatile private var instance: WaypointDatabase? = null

        /**
         * A deleted waypoint must not stay readable in the files, since the system's backup copies them as they are.
         * So the database overwrites deleted rows with zeros and keeps no write-ahead log, with a rollback journal
         * that is emptied after every change. These are set here rather than left to the device's defaults.
         */
        fun open(context: Context, name: String): WaypointDatabase {
            val wasWriteAheadLog = isWriteAheadLogFile(context.getDatabasePath(name))
            return Room.databaseBuilder(context.applicationContext, WaypointDatabase::class.java, name)
                // Room, used without a driver, only turns the write-ahead log off here; the journal mode is then
                // the platform's default for databases without one, so the callback below sets it as well.
                .setJournalMode(JournalMode.TRUNCATE)
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
                        // Inside a transaction, so that it is set on the connection that does the writing.
                        db.beginTransaction()
                        try {
                            db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                        // A file that came from write-ahead-log mode may hold deleted rows in freed space;
                        // rebuilding the file drops them.
                        if (wasWriteAheadLog) db.execSQL("VACUUM")
                    }
                })
                .build()
        }

        fun get(context: Context): WaypointDatabase =
            instance ?: synchronized(this) { instance ?: open(context, FILE_NAME).also { instance = it } }

        /**
         * True when the rollback journal or a write-ahead log next to [databaseFile] holds any bytes. Such a file
         * can hold rows that have been deleted from the database.
         */
        fun hasJournalData(databaseFile: File): Boolean =
            listOf("-journal", "-wal").any { File(databaseFile.path + it).length() > 0 }

        /** Byte 18 of a SQLite file is 2 when it was last written in write-ahead-log mode. */
        private fun isWriteAheadLogFile(file: File): Boolean =
            file.length() >= 20 && RandomAccessFile(file, "r").use { it.seek(18); it.read() == 2 }
    }
}
