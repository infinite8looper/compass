package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    @Test fun withTheSwitchOnTheFilesAreCopied() {
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = false, deviceTransfer = false))
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = false, deviceTransfer = true))
        // On is on, whatever is left of an earlier turning off, and when that cannot be read.
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = true, deviceTransfer = false))
        assertEquals(BackupPass.COPY, backupPass(enabled = true, offByWearer = null, deviceTransfer = false))
    }

    @Test fun turnedOffByTheWearerABackupHoldsTheMarkerAlone() {
        assertEquals(BackupPass.MARKER, backupPass(enabled = false, offByWearer = true, deviceTransfer = false))
    }

    @Test fun aTransferToAnotherDeviceWithTheSwitchOffHandsOverNothing() {
        // A transfer stores nothing that a marker could take the place of.
        assertEquals(BackupPass.NOTHING, backupPass(enabled = false, offByWearer = true, deviceTransfer = true))
    }

    @Test fun offForAnyOtherReasonHandsOverNothingAndReplacesNothing() {
        // A damaged settings file is reset with the switch off, and a file from before this was built has the switch
        // off and no record of who turned it: neither is the wearer asking for the stored backup to go.
        assertEquals(BackupPass.NOTHING, backupPass(enabled = false, offByWearer = false, deviceTransfer = false))
        assertEquals(BackupPass.NOTHING, backupPass(enabled = false, offByWearer = null, deviceTransfer = false))
        // The switch could not be read.
        for (by in listOf(true, false, null)) for (transfer in listOf(true, false)) {
            assertEquals(BackupPass.NOTHING, backupPass(enabled = null, offByWearer = by, deviceTransfer = transfer))
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
            assertEquals(BackupPass.MARKER, switchForPass(read, offOwedByRestore = true).let { backupPass(it.first, it.second, deviceTransfer = false) })
        }
        // Nothing owed: the file is what counts.
        for (read in listOf(true to false, false to false, false to true, null to null)) assertEquals(read, switchForPass(read, offOwedByRestore = false))
    }
}
