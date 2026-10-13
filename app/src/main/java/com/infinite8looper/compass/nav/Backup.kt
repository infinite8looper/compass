package com.infinite8looper.compass.nav

// The decisions about the `Back up waypoints` switch: what a pass of the system's backup hands over, and what a
// tap on the switch does.

/** What the app hands over in one pass of the system's backup or transfer. */
enum class BackupPass {
    /** The waypoint database and the settings file, as the backup rules name them. */
    COPY,
    /** One small file that says the switch is off, and nothing else, so that the backup made holds no waypoints. */
    MARKER,
    /** Nothing at all. */
    NOTHING,
}

/**
 * What a pass hands over. [enabled] is the stored switch, or null when it could not be read; [offByWearer] is
 * whether the switch was turned off by the wearer in the app, or null when that could not be read; [deviceTransfer]
 * is whether the pass is a transfer to another device and not a backup that is stored.
 *
 * With the switch on, the files are copied. With it off because the wearer turned it off, a stored backup gets the
 * marker alone: a backup that is made takes the place of the one stored before, so the waypoints in that one go
 * with it. A pass that hands over nothing leaves the stored backup to the system, and nothing is known about what
 * the system does with it then. In every other case nothing is handed over: a switch that could not be read, a
 * switch that is off without the wearer having turned it off (a damaged settings file is reset with it off), and a
 * transfer, which stores nothing that a marker could replace.
 */
fun backupPass(enabled: Boolean?, offByWearer: Boolean?, deviceTransfer: Boolean): BackupPass = when {
    enabled == true -> BackupPass.COPY
    enabled == false && offByWearer == true && !deviceTransfer -> BackupPass.MARKER
    else -> BackupPass.NOTHING
}

/** What a tap on the `Back up waypoints` switch does. */
enum class BackupTap { TURN_ON, ASK_FIRST }

/** A tap turns the switch on at once. Turning it off also replaces the stored backup, so the wearer is asked first. */
fun backupSwitchTap(on: Boolean): BackupTap = if (on) BackupTap.ASK_FIRST else BackupTap.TURN_ON
