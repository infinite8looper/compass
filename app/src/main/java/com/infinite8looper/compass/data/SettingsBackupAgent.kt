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
 * super implementation is what applies the extraction rules. With the setting off, nothing is handed over.
 */
class SettingsBackupAgent : BackupAgent() {
    // The manifest says fullBackupOnly, so the system never calls the key/value pair of methods.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (!backupEnabled()) return
        // Write the database's log into its main file and empty the log, so the copied file is complete on its own
        // and no old log frames (rows the wearer has since deleted) are left to be copied.
        try {
            WaypointDatabase.get(applicationContext).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "could not checkpoint the waypoint database before backup", e)
        }
        super.onFullBackup(data)
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
