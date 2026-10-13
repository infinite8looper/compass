package com.infinite8looper.compass.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import com.infinite8looper.compass.nav.BackupPass
import com.infinite8looper.compass.nav.backupPass
import com.infinite8looper.compass.nav.switchForPass
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
 *
 * A restore of the marker's backup leaves the switch off, turned off by the wearer ([onRestoreFile],
 * [onRestoreFinished]): turning backups off holds across an install on this or another watch. A restore of any
 * other backup changes nothing about the switch, and no restore turns it on.
 */
class SettingsBackupAgent : BackupAgent() {
    // The manifest says fullBackupOnly, so the system never calls the key/value pair of methods.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        // A marker left by a pass whose process ended before it could delete it.
        File(filesDir, MARKER_FILE).delete()
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

    private val restoredOptOut by lazy { RestoredOptOut(filesDir) }

    /**
     * What this pass hands over ([backupPass]), from the stored switch and who turned it off, taken in one read. A
     * switch still owed off by a restore ([RestoredOptOut]) is written first; if it cannot be, the pass takes the
     * switch as off by the wearer whatever the file says ([switchForPass]).
     */
    private fun pass(data: FullBackupDataOutput): BackupPass {
        val owed = restoredOptOut.owed() && read("the settings to turn the switch off after a restore") { restoredOptOut.settle(it) } != true
        val (enabled, offByWearer) = switchForPass(read("the backup setting") { it.readBackupSwitch() } ?: (null to null), owed)
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

    /**
     * Every file of a restore comes through here. The marker of a backup made with the switch off by the wearer is
     * noted as it comes ([RestoredOptOut.markerCame]); the system itself restores only the files the backup rules
     * name, so the marker is not written to the app's files, and the rest is the system's as before.
     */
    override fun onRestoreFile(data: ParcelFileDescriptor?, size: Long, destination: File?, type: Int, mode: Long, mtime: Long) {
        // The system names the folder by its real path, which is not always the spelling the app is given for it.
        if (destination != null && destination.name == MARKER_FILE && destination.canonicalFile.parentFile == filesDir.canonicalFile) {
            try {
                restoredOptOut.markerCame()
            } catch (e: Exception) {
                Log.w(TAG, "could not put the restored marker on record (" + e.javaClass.simpleName + ")")
            }
        }
        super.onRestoreFile(data, size, destination, type, mode, mtime)
    }

    /**
     * A restore never turns the switch on. If it brought the marker, the switch is written off, turned off by the
     * wearer, so the next pass is a marker pass again. If that cannot be written the record of the marker's coming
     * stays, and the switch is owed off until it can ([pass]).
     */
    override fun onRestoreFinished() {
        File(filesDir, MARKER_FILE).delete()
        if (restoredOptOut.owed() && read("the settings to turn the switch off after a restore") { restoredOptOut.settle(it) } != true) {
            Log.w(TAG, "the switch could not be written off after a restore; it stays owed off")
        }
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
