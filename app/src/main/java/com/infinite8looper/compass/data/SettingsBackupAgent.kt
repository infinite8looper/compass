package com.infinite8looper.compass.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import com.infinite8looper.compass.nav.BackupPass
import com.infinite8looper.compass.nav.backupPass
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the system's backup and device-to-device transfer of the files named in res/xml/data_extraction_rules.xml,
 * but only while the "Back up waypoints" setting is on. Both kinds of transfer come through [onFullBackup]; the
 * super implementation is what applies the extraction rules. No file of the wearer's is handed over with the setting
 * off, nor in a pass in which the waypoint database cannot be shown to be fit to copy: on record as rebuilt, with
 * deleted content being overwritten, and with no journal or log data next to it.
 *
 * With the setting turned off by the wearer, a backup that is stored is handed one small marker file instead of
 * nothing ([handOverMarker]). An app cannot delete its stored backup. A backup that is made takes the place of the
 * one stored before, so the marker's backup, which holds no waypoint, is what is stored from then on. Seen with the
 * system's local transport on the API 33, 34 and 35 emulators.
 */
class SettingsBackupAgent : BackupAgent() {
    // The manifest says fullBackupOnly, so the system never calls the key/value pair of methods.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        when (pass(data)) {
            BackupPass.NOTHING -> return
            BackupPass.MARKER -> { handOverMarker(data); return }
            BackupPass.COPY -> Unit
        }
        // The system copies the database file and whatever journal or log file lies next to it, as they are, and
        // those files can hold rows the wearer has deleted or changed. So the copy runs only for a database that is
        // on record as rebuilt, overwrites deleted content and has no such data next to it, with the database locked
        // so that nothing changes while the files are read. Otherwise this pass hands over nothing, the same as with
        // the setting off.
        var copying = false
        val copy = {
            // The setting is read again here, with the lock held, so that turning it off during a pass counts.
            if (backupEnabled()) { copying = true; super.onFullBackup(data) }
        }
        try {
            // No database files yet: there are no waypoints to protect, and none is created for the sake of a backup.
            if (WaypointDatabase.copyWithoutDatabase(applicationContext, copy)) return
            val db = WaypointDatabase.get(applicationContext)
            // Rebuilds the file if that has not been done for it; the outcome is checked again under the lock.
            db.sanitize()
            if (!db.copyIfSettled(getDatabasePath(WaypointDatabase.FILE_NAME), copy)) {
                Log.w(TAG, "the waypoint database could not be confirmed free of deleted content; backing up nothing")
            }
        } catch (e: Exception) {
            if (copying) throw e // the system's own copy failed: that is the system's to handle
            // Only the kind of error is logged: its message can quote file paths and statements.
            Log.w(TAG, "could not prepare the waypoint database for backup; backing up nothing (" + e.javaClass.simpleName + ")")
        }
    }

    /** What this pass hands over ([backupPass]), from the stored switch and who turned it off. */
    private fun pass(data: FullBackupDataOutput): BackupPass {
        val enabled = read("the backup setting") { it.readBackupEnabled() }
        // Asked only for a switch that is off: with it on, the answer changes nothing.
        val offByWearer = if (enabled == false) read("who turned the backup setting off") { it.readBackupOffByWearer() } else null
        return backupPass(enabled, offByWearer, deviceTransfer = data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0)
    }

    /**
     * Hands over one small file and nothing else: the backup the system makes of it holds no waypoint, crumb or
     * setting, and takes the place of the one stored before. The file is written for the pass and deleted after it.
     * A failure here is logged by its kind and hands over nothing, as with the switch off before.
     */
    private fun handOverMarker(data: FullBackupDataOutput) {
        val marker = File(filesDir, MARKER_FILE)
        try {
            marker.writeText(MARKER_TEXT)
            fullBackupFile(marker, data)
        } catch (e: Exception) {
            Log.w(TAG, "could not hand over the marker of a backup that is switched off (" + e.javaClass.simpleName + ")")
        } finally {
            marker.delete()
        }
    }

    /** A marker that a restore brought back says nothing to the app and is removed. */
    override fun onRestoreFinished() {
        try { File(filesDir, MARKER_FILE).delete() } catch (e: Exception) { Log.w(TAG, "could not remove a restored marker (" + e.javaClass.simpleName + ")") }
    }

    private fun <T> read(what: String, ask: suspend (SettingsStore) -> T?): T? = try {
        runBlocking { withTimeout(5_000) { ask(SettingsStore.get(applicationContext)) } }
    } catch (e: Exception) {
        // Only the kind of error is logged: its message can quote file paths.
        Log.w(TAG, "could not read " + what + " (" + e.javaClass.simpleName + ")")
        null
    }

    /**
     * Reads the stored setting through the app's own store (DataStore allows one instance per file). Only a positive
     * "on" allows a backup: an unreadable file, a damaged file (reset with the switch off) or a timeout all mean no.
     */
    private fun backupEnabled(): Boolean = try {
        runBlocking { withTimeout(5_000) { SettingsStore.get(applicationContext).readBackupEnabled() } } == true
    } catch (e: Exception) {
        // The wearer's choice could not be read: backing up nothing is the safe error.
        // Only the kind of error is logged: its message can quote file paths.
        Log.w(TAG, "could not read the backup setting; backing up nothing (" + e.javaClass.simpleName + ")")
        false
    }

    private companion object {
        const val TAG = "SettingsBackupAgent"
        /** The file handed over alone while the switch is off by the wearer's doing, in the app's files folder, and what is in it. */
        const val MARKER_FILE = "backup-switched-off"
        const val MARKER_TEXT = "Back up waypoints is off.\n"
    }
}
