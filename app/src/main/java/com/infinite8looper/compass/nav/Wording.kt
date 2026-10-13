package com.infinite8looper.compass.nav

// Words the wearer sees that were added after the owner approved the rest, kept together so that changing one is a
// change to this file alone.

/** The row of the waypoints screen that drops a crumb while a trail is being laid. */
const val DROP_CRUMB = "Drop crumb"

// The question asked before `Back up waypoints` is turned off: what is asked, what turning it off does, and the two answers.

/** What is asked. */
const val BACKUP_OFF_QUESTION = "Turn off backup?"
/** What turning it off does to the backup that is stored. It says nothing about when: the system decides when a backup runs. */
const val BACKUP_OFF_DETAIL = "The next backup replaces the stored one, without waypoints."
/** The answer that turns it off, shown in the error color. */
const val BACKUP_OFF_CONFIRM = "Turn off"
/** The answer that leaves it on; Back and a right swipe do the same. */
const val BACKUP_OFF_KEEP = "Keep on"
