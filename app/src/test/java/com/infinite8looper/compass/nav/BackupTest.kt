package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    @Test fun withTheSwitchOnTheFilesAreCopied() {
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = false, canWrite = true))
        // On is on, whatever is left of an earlier turning off, and when that cannot be read.
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = true, canWrite = true))
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = null, canWrite = true))
    }

    @Test fun turnedOffByTheWearerAPassHoldsTheMarkerAlone() {
        // A backup that is stored and a transfer to another watch alike: the pass does not ask which it is.
        assertEquals(BackupPass.MARKER, backupPass(enabled = false, offByWearer = true, canWrite = true))
    }

    @Test fun offForAnyOtherReasonHandsOverNothingAndReplacesNothing() {
        // A damaged settings file is reset with the switch off, and a file from before this was built has the switch
        // off and no record of who turned it: neither is the wearer asking for the stored backup to go.
        assertEquals(BackupPass.NOTHING, backupPass(enabled = false, offByWearer = false, canWrite = true))
        assertEquals(BackupPass.NOTHING, backupPass(enabled = false, offByWearer = null, canWrite = true))
        // The switch could not be read.
        for (by in listOf(true, false, null)) assertEquals(BackupPass.NOTHING, backupPass(enabled = null, offByWearer = by, canWrite = true))
    }

    @Test fun whileTheAppsStorageTakesNoWriteAPassHandsOverNothing() {
        // Storage that takes no write may have lost the record that the wearer turned backups off: nothing leaves.
        for (enabled in listOf(true, false, null)) for (by in listOf(true, false, null)) {
            assertEquals("$enabled, $by", BackupPass.NOTHING, backupPass(enabled, by, canWrite = false))
        }
    }

    @Test fun aTapOnTheSwitchTurnsItOnAtOnceAndAsksBeforeTurningItOff() {
        assertEquals(BackupTap.TURN_ON, backupSwitchTap(on = false))
        assertEquals(BackupTap.ASK_FIRST, backupSwitchTap(on = true))
    }

    @Test fun aSwitchOwedOffByARestoreCountsAsTurnedOffByTheWearerWhateverTheFileSays() {
        // A restore brought the marker and the settings file could not be written since: the pass is a marker pass.
        for (read in listOf(true to false, false to false, false to true, null to null, true to null)) {
            assertEquals(false to true, switchForPass(read, offOwedByRestore = true))
            assertEquals(BackupPass.MARKER, switchForPass(read, offOwedByRestore = true).let { backupPass(it.first, it.second, canWrite = true) })
        }
        // Nothing owed: the file is what counts.
        for (read in listOf(true to false, false to false, false to true, null to null)) assertEquals(read, switchForPass(read, offOwedByRestore = false))
    }

    @Test fun theSwitchIsShownOffWhileItIsOwedOff() {
        assertTrue(backupShownOn(storedOn = true, offOwedByRestore = false))
        assertFalse(backupShownOn(storedOn = true, offOwedByRestore = true))
        assertFalse(backupShownOn(storedOn = false, offOwedByRestore = false))
        assertFalse(backupShownOn(storedOn = false, offOwedByRestore = true))
        // So a tap on a switch that is owed off turns it on at once, as a tap on any switch that shows off does.
        assertEquals(BackupTap.TURN_ON, backupSwitchTap(backupShownOn(storedOn = true, offOwedByRestore = true)))
    }
}
