package com.infinite8looper.compass.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the system's backup and device-to-device transfer of the files named in res/xml/data_extraction_rules.xml,
 * but only while the "Back up waypoints" setting is on. Both kinds of transfer come through [onFullBackup]; the
 * super implementation is what applies the extraction rules. With the setting off, nothing is handed over; the same
 * holds for a pass in which the waypoint database cannot be shown to be free of journal data.
 */
class SettingsBackupAgent : BackupAgent() {
    // The manifest says fullBackupOnly, so the system never calls the key/value pair of methods.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (!backupEnabled()) return
        // The system copies the database file and whatever journal or log file lies next to it, as they are, and
        // those files can hold rows the wearer has deleted. The copy runs only if they hold no bytes, with the
        // database locked so that nothing changes while the files are read. Otherwise this pass hands over
        // nothing, the same as with the setting off.
        var copying = false
        val copied = try {
            WaypointDatabase.get(applicationContext)
                .copyIfSettled(getDatabasePath(WaypointDatabase.FILE_NAME)) { copying = true; super.onFullBackup(data) }
        } catch (e: Exception) {
            if (copying) throw e // the system's own copy failed: that is the system's to handle
            // Only the kind of error is logged: its message can quote file paths and statements.
            Log.w(TAG, "could not prepare the waypoint database for backup; backing up nothing (" + e.javaClass.simpleName + ")")
            return
        }
        if (!copied) Log.w(TAG, "the waypoint database has journal data next to it; backing up nothing")
    }

    /**
     * Reads the stored setting through the app's own store (DataStore allows one instance per file). Only a positive
     * "on" allows a backup: an unreadable file, a damaged file (reset with the switch off) or a timeout all mean no.
     */
    private fun backupEnabled(): Boolean = try {
        runBlocking { withTimeout(5_000) { SettingsStore.get(applicationContext).readBackupEnabled() } } == true
    } catch (e: Exception) {
        // The wearer's choice could not be read: backing up nothing is the safe error.
        Log.w(TAG, "could not read the backup setting; backing up nothing", e)
        false
    }

    private companion object { const val TAG = "SettingsBackupAgent" }
}
