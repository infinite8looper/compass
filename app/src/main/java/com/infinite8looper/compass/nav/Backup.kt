package com.infinite8looper.compass.nav

// The decisions about the `Back up waypoints` switch: what a pass of the system's backup hands over, and what a
// tap on the switch does.

/** What the app hands over in one pass of the system's backup or transfer. */
enum class BackupPass {
    /** The waypoint database and the settings file, as the backup rules name them. */
    COPY,
    /** One small file that says the switch is off, and nothing else, so that the backup or transfer made holds no waypoints. */
    MARKER,
    /** Nothing at all. */
    NOTHING,
}

/**
 * What a pass hands over. [enabled] is the stored switch, or null when it could not be read; [offByWearer] is
 * whether the switch was turned off by the wearer in the app, or null when that could not be read; [canWrite] is
 * whether the app's storage takes a write at this moment.
 *
 * With the switch on, the files are copied. With it off because the wearer turned it off, the pass gets the marker
 * alone, whether it is a backup that is stored or a transfer to another watch: a backup that is made takes the
 * place of the one stored before, so the waypoints in that one go with it, and the marker is what tells the app on
 * the watch it is restored to that the wearer turned backups off. A pass that hands over nothing leaves the stored
 * backup to the system, and nothing is known about what the system does with it then. Nothing is handed over for a
 * switch that could not be read and for a switch that is off without the wearer having turned it off (a damaged
 * settings file is reset with it off).
 *
 * While the storage takes no write, nothing is handed over whatever the switch says: that the wearer turned backups
 * off is kept in files, and storage that takes no write may not have kept it.
 */
fun backupPass(enabled: Boolean?, offByWearer: Boolean?, canWrite: Boolean): BackupPass = when {
    !canWrite -> BackupPass.NOTHING
    enabled == true -> BackupPass.COPY
    enabled == false && offByWearer == true -> BackupPass.MARKER
    else -> BackupPass.NOTHING
}

/**
 * Whether the settings screen shows the switch on: the stored switch, and off for as long as the switch is owed off
 * by a restore whose settings file has not been written yet. What the screen shows is then what the passes do.
 */
fun backupShownOn(storedOn: Boolean, offOwedByRestore: Boolean): Boolean = storedOn && !offOwedByRestore

/** What a tap on the `Back up waypoints` switch does. */
enum class BackupTap { TURN_ON, ASK_FIRST }

/** A tap turns the switch on at once. Turning it off also replaces the stored backup, so the wearer is asked first. */
fun backupSwitchTap(on: Boolean): BackupTap = if (on) BackupTap.ASK_FIRST else BackupTap.TURN_ON

/**
 * The switch and who turned it off, as a pass takes them. [read] is what the settings file gives (each null when it
 * cannot be read). [offOwedByRestore] is set when a restore brought the marker of a backup made with the switch off
 * by the wearer and the settings file has not been written since: the switch then counts as off by the wearer,
 * whatever the file says or fails to say, so the pass is a marker pass again and hands over no waypoint.
 */
fun switchForPass(read: Pair<Boolean?, Boolean?>, offOwedByRestore: Boolean): Pair<Boolean?, Boolean?> =
    if (offOwedByRestore) false to true else read
