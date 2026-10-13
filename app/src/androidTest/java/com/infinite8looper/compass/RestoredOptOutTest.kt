package com.infinite8looper.compass

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.RestoredOptOut
import com.infinite8looper.compass.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * What a restore does to the `Back up waypoints` switch, with real files and the real settings store: a restore that
 * brings the marker of a backup made with the switch off leaves the switch off and on record as turned off by the
 * wearer; any other restore changes nothing; and when the settings file cannot be written the switch is still
 * owed off, on a record of its own, until it can be.
 */
@RunWith(AndroidJUnit4::class)
class RestoredOptOutTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.cacheDir, "restored-opt-out-${UUID.randomUUID()}")
    private val second = File(ctx.cacheDir, "restored-opt-out-second-${UUID.randomUUID()}")
    private val file = "test-settings-${UUID.randomUUID()}"
    private val job = SupervisorJob()
    private val store by lazy { SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job)) }

    @Before fun setUp() { dir.mkdirs(); second.mkdirs() }
    @After fun tearDown() = runBlocking {
        job.cancelAndJoin()
        ctx.preferencesDataStoreFile(file).apply { setReadable(true, false); setWritable(true, false); delete() }
        ctx.preferencesDataStoreFile("$file-position").delete()
        dir.setWritable(true, false); second.setWritable(true, false)
        dir.deleteRecursively(); second.deleteRecursively(); Unit
    }

    @Test fun aRestoreThatBringsTheMarkerLeavesTheSwitchOffByTheWearer() = runBlocking {
        // A new install: no settings file, so the switch reads on.
        assertEquals(true to false, store.readBackupSwitch())
        val restored = RestoredOptOut(dir, second)
        assertFalse(restored.owed())
        restored.markerCame()
        assertTrue("the marker's coming is not on record", restored.owed())
        assertTrue(restored.settle(store))
        assertEquals(false to true, store.readBackupSwitch())
        assertFalse(store.settings.first().backupEnabled)
        assertFalse("still owed after it was settled", restored.owed())
        assertTrue("something was left in the folders: ${dir.list()?.toList()} ${second.list()?.toList()}", dir.list().isNullOrEmpty() && second.list().isNullOrEmpty())
    }

    @Test fun aRestoreThatBringsNoMarkerChangesNothing() = runBlocking {
        // A normal backup's settings file says on.
        store.setBackupEnabled(true); store.setTickFeedback(false)
        val restored = RestoredOptOut(dir, second)
        assertTrue(restored.settle(store))
        assertEquals(true to false, store.readBackupSwitch())
        assertFalse(store.settings.first().tickFeedback)
        // And a settings file that says off stays off: a restore never turns the switch on.
        store.setBackupEnabled(false)
        assertTrue(restored.settle(store))
        assertEquals(false to true, store.readBackupSwitch())
    }

    @Test fun whenTheSettingsFileCannotBeWrittenTheSwitchStaysOwedOffUntilItCan() = runBlocking {
        store.setBackupEnabled(true)
        val f = ctx.preferencesDataStoreFile(file)
        val folder = f.parentFile!!
        val restored = RestoredOptOut(dir, second)
        restored.markerCame()
        // The store's folder takes no new file, so the store's write, which is made beside the file and moved over it, fails.
        assertTrue("could not make the folder unwritable", folder.setWritable(false, false))
        try {
            assertFalse("the write went through an unwritable folder: the test setup did not hold", restored.settle(store))
            assertTrue("no longer owed although the settings file was not written", restored.owed())
        } finally { folder.setWritable(true, false) }
        // Once it can be written it is, and nothing is owed.
        assertTrue(restored.settle(store))
        assertEquals(false to true, store.readBackupSwitch())
        assertFalse(restored.owed())
    }

    @Test fun theRecordIsKeptInTwoPlacesSoOneThatTakesNoWriteDoesNotLoseIt() = runBlocking {
        store.setBackupEnabled(true)
        for ((dead, live) in listOf(dir to second, second to dir)) {
            assertTrue("could not make ${dead.name} unwritable", dead.setWritable(false, false))
            try {
                // The marker comes while one folder takes no write: the coming may be reported as an error, and is on record all the same.
                try { RestoredOptOut(dir, second).markerCame() } catch (e: java.io.IOException) { }
                assertTrue("nothing was written to ${live.name}", !live.list().isNullOrEmpty())
                // The process ends after a restore: what is in memory is gone, and the record is still there.
                assertTrue("the record did not outlive the process", RestoredOptOut(dir, second).owed())
            } finally { dead.setWritable(true, false) }
            assertTrue(RestoredOptOut(dir, second).settle(store))
            assertFalse(RestoredOptOut(dir, second).owed())
            assertEquals(false to true, store.readBackupSwitch())
            store.setBackupEnabled(true)
        }
    }

    @Test fun theWearerTurningTheSwitchOnClearsTheRecordAndItStaysOn() = runBlocking {
        store.setBackupEnabled(true)
        val restored = RestoredOptOut(dir, second)
        restored.markerCame()
        assertTrue(restored.owed())
        // The wearer taps the switch, which shows off while it is owed off.
        restored.wearerSet(store, on = true)
        assertEquals(true to false, store.readBackupSwitch())
        assertFalse("still owed after the wearer turned the switch on", restored.owed())
        // Nothing writes it off again: not this instance at the next pass, and not a new one at the next start.
        assertTrue(restored.settle(store)); assertTrue(RestoredOptOut(dir, second).settle(store))
        assertEquals(true to false, store.readBackupSwitch())
        // And off by the wearer is off by the wearer, with nothing owed.
        restored.markerCame(); restored.wearerSet(store, on = false)
        assertEquals(false to true, store.readBackupSwitch()); assertFalse(restored.owed())
    }

    @Test fun theWearersChoiceThatCannotBeWrittenLeavesWhatIsOwed() = runBlocking {
        store.setBackupEnabled(true)
        val restored = RestoredOptOut(dir, second)
        restored.markerCame()
        val folder = ctx.preferencesDataStoreFile(file).parentFile!!
        assertTrue(folder.setWritable(false, false))
        try {
            var failed = false
            // Off is a change to the file, which has the switch on, so it has to be written.
            try { restored.wearerSet(store, on = false) } catch (e: java.io.IOException) { failed = true }
            assertTrue("the write went through an unwritable folder: the test setup did not hold", failed)
            assertTrue("no longer owed although the switch was not written", restored.owed())
        } finally { folder.setWritable(true, false) }
    }
}
