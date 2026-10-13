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
    private val file = "test-settings-${UUID.randomUUID()}"
    private val job = SupervisorJob()
    private val store by lazy { SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job)) }

    @Before fun setUp() { dir.mkdirs() }
    @After fun tearDown() = runBlocking {
        job.cancelAndJoin()
        ctx.preferencesDataStoreFile(file).apply { setReadable(true, false); setWritable(true, false); delete() }
        ctx.preferencesDataStoreFile("$file-position").delete()
        dir.deleteRecursively(); Unit
    }

    @Test fun aRestoreThatBringsTheMarkerLeavesTheSwitchOffByTheWearer() = runBlocking {
        // A new install: no settings file, so the switch reads on.
        assertEquals(true to false, store.readBackupSwitch())
        val restored = RestoredOptOut(dir)
        assertFalse(restored.owed())
        restored.markerCame()
        assertTrue("the marker's coming is not on record", restored.owed())
        assertTrue(restored.settle(store))
        assertEquals(false to true, store.readBackupSwitch())
        assertFalse(store.settings.first().backupEnabled)
        assertFalse("still owed after it was settled", restored.owed())
        assertTrue("something was left in the folder: ${dir.list()?.toList()}", dir.list().isNullOrEmpty())
    }

    @Test fun aRestoreThatBringsNoMarkerChangesNothing() = runBlocking {
        // A normal backup's settings file says on.
        store.setBackupEnabled(true); store.setTickFeedback(false)
        val restored = RestoredOptOut(dir)
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
        val restored = RestoredOptOut(dir)
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
}
