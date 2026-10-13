package com.infinite8looper.compass.data

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Carries the wearer's turning off of `Back up waypoints` across a restore. A backup made with the switch off holds
 * the marker file and no settings file, so a restore of it would otherwise start the app at its defaults, with the
 * switch on. When the marker comes in a restore, that is put on record in a file of its own in [folder] (the app's
 * files folder; the backup rules do not name it, so it is never backed up), and [settle] then writes the switch off,
 * turned off by the wearer, to the settings file and removes the record. While the settings file cannot be written
 * the record stays, and the switch is owed off ([owed]): the backup agent then hands over the marker alone again.
 * A restore that brings no marker changes nothing, and nothing here ever turns the switch on.
 */
class RestoredOptOut(folder: File) {
    private val record = File(folder, RECORD_FILE)
    /** The marker came in this process's restore, kept also in memory in case the record cannot be written. */
    @Volatile private var came = false

    /** A restore brought the marker. May throw if the record cannot be written; the coming is kept in memory even so. */
    fun markerCame() {
        came = true
        record.writeText(RECORD_TEXT)
    }

    /** Whether the switch is owed off: the marker came and the settings file has not been written since. */
    fun owed(): Boolean = came || record.exists()

    /**
     * Writes what is owed to the settings file through [store]. True when nothing is owed any more; false when the
     * file could not be written, in which case the record stays. Throws only to pass a cancellation on.
     */
    suspend fun settle(store: SettingsStore): Boolean {
        if (!owed()) return true
        return try {
            store.setBackupEnabled(false)
            came = false
            record.delete()
            !record.exists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val RECORD_FILE = "backup-off-owed"
        const val RECORD_TEXT = "A restore brought a backup made with Back up waypoints off.\n"
    }
}
