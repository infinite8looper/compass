package com.infinite8looper.compass.nav

// Words the wearer sees that were added after the owner approved the rest, kept together so that changing one is a
// change to this file alone.

/** The row of the waypoints screen that drops a crumb while a trail is being laid. */
const val DROP_CRUMB = "Drop crumb"

/** What an accessibility service is told the dial is: the app's name. The dial needs a name for a screen reader to stop on it and offer [SAVE_HERE_ACTION]. */
const val DIAL_NAME = "Compass"

/** The name an accessibility service is given for the compass's action that saves the position: what a double tap on the dial does. */
const val SAVE_HERE_ACTION = "Save waypoint here"

/**
 * The smaller line of the `Breadcrumb trail` row in settings. On a watch with a button the app can act on it says
 * what the button does; on one without, it says where the setting shows, and speaks of no press.
 */
fun trailSettingDetail(assignableButton: Boolean): String =
    if (assignableButton) "Double press starts a trail" else "Trail row on waypoints screen"

// The question asked before `Back up waypoints` is turned off: what is asked, what turning it off does, and the two answers.

/** What is asked. */
const val BACKUP_OFF_QUESTION = "Turn off backup?"
/** What turning it off does to the backup that is stored. It says nothing about when: the system decides when a backup runs. */
const val BACKUP_OFF_DETAIL = "The next backup replaces the stored one, without waypoints."
/** The answer that turns it off, shown in the error color. */
const val BACKUP_OFF_CONFIRM = "Turn off"
/** The answer that leaves it on; Back and a right swipe do the same. */
const val BACKUP_OFF_KEEP = "Keep on"
