package com.infinite8looper.compass

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.defaultUnits
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun withStore(block: suspend (SettingsStore, String) -> Unit) = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val job = SupervisorJob()
        try { block(SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job)), file) }
        finally { job.cancelAndJoin(); ctx.preferencesDataStoreFile(file).delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }

    @Test fun defaultsWhenNoFileExists() = withStore { s, _ ->
        val v = s.settings.first()
        assertEquals(NorthRef.TRUE, v.north); assertEquals(defaultUnits(Locale.getDefault()), v.units)
        assertNull(v.focusedId); assertNull(v.lastPosition); assertNull(v.lastAltitudeM)
        assertTrue(v.tickFeedback); assertFalse(v.showCoordinates); assertTrue(v.backupEnabled)
    }
    @Test fun valuesRoundTrip() = withStore { s, _ ->
        s.setNorth(NorthRef.MAGNETIC); s.setUnits(Units.IMPERIAL); s.setFocused(7)
        s.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
        val v = s.settings.first()
        assertEquals(NorthRef.MAGNETIC, v.north); assertEquals(Units.IMPERIAL, v.units); assertEquals(7L, v.focusedId)
        assertEquals(43.7022, v.lastPosition!!.lat, 1e-9); assertEquals(-72.2896, v.lastPosition.lon, 1e-9)
        assertEquals(160f, v.lastAltitudeM!!, 0f)
    }
    @Test fun tickFeedbackAndShowCoordinatesRoundTripBothWays() = withStore { s, _ ->
        s.setTickFeedback(false); s.setShowCoordinates(true)
        var v = s.settings.first()
        assertFalse(v.tickFeedback); assertTrue(v.showCoordinates)
        s.setTickFeedback(true); s.setShowCoordinates(false)
        v = s.settings.first()
        assertTrue(v.tickFeedback); assertFalse(v.showCoordinates)
    }
    @Test fun backupEnabledRoundTripsBothWays() = withStore { s, _ ->
        s.setBackupEnabled(false)
        assertFalse(s.settings.first().backupEnabled)
        s.setBackupEnabled(true)
        assertTrue(s.settings.first().backupEnabled)
    }
    @Test fun focusCanBeCleared() = withStore { s, _ ->
        s.setFocused(7); s.setFocused(null); assertNull(s.settings.first().focusedId)
    }
    @Test fun waypointNumbersCountUpAndNeverRepeat() = withStore { s, _ ->
        assertEquals(listOf(1, 2, 3), listOf(s.nextWaypointNumber(), s.nextWaypointNumber(), s.nextWaypointNumber()))
    }
    @Test fun unitsDefaultFromLocale() {
        assertEquals(Units.IMPERIAL, defaultUnits(Locale.US)); assertEquals(Units.IMPERIAL, defaultUnits(Locale.UK))
        assertEquals(Units.METRIC, defaultUnits(Locale.FRANCE)); assertEquals(Units.METRIC, defaultUnits(Locale.CANADA))
    }
    @Test fun corruptFileYieldsDefaultsAndStaysWritable() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val f = ctx.preferencesDataStoreFile(file)
        f.parentFile!!.mkdirs()
        f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, -1, -2, 0x55, 0x01, 0x02, 0x03))
        val job = SupervisorJob()
        try {
            val s = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job))
            val v = s.settings.first()
            assertEquals(NorthRef.TRUE, v.north); assertEquals(defaultUnits(Locale.getDefault()), v.units)
            assertNull(v.focusedId); assertNull(v.lastPosition); assertNull(v.lastAltitudeM)
            // A damaged file leaves the privacy switch OFF, not at its fresh-install default of on.
            assertTrue(v.tickFeedback); assertFalse(v.showCoordinates); assertFalse(v.backupEnabled)
            s.setNorth(NorthRef.MAGNETIC)
            assertEquals(NorthRef.MAGNETIC, s.settings.first().north)
        } finally { job.cancelAndJoin(); f.delete() }
    }
    @Test fun valuesSurviveClosingAndReopeningTheStore() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val job1 = SupervisorJob(); val job2 = SupervisorJob()
        try {
            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job1))
            first.setNorth(NorthRef.MAGNETIC); first.setUnits(Units.IMPERIAL); first.setFocused(42)
            first.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
            first.setTickFeedback(false); first.setShowCoordinates(true); first.setBackupEnabled(false)
            assertEquals(listOf(1, 2), listOf(first.nextWaypointNumber(), first.nextWaypointNumber()))
            job1.cancelAndJoin()
            val second = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job2))
            val v = second.settings.first()
            assertEquals(NorthRef.MAGNETIC, v.north); assertEquals(Units.IMPERIAL, v.units); assertEquals(42L, v.focusedId)
            assertEquals(43.7022, v.lastPosition!!.lat, 1e-9); assertEquals(-72.2896, v.lastPosition.lon, 1e-9)
            assertEquals(160f, v.lastAltitudeM!!, 0f)
            assertFalse(v.tickFeedback); assertTrue(v.showCoordinates); assertFalse(v.backupEnabled)
            assertEquals(3, second.nextWaypointNumber())
        } finally { job1.cancelAndJoin(); job2.cancelAndJoin(); ctx.preferencesDataStoreFile(file).delete() }
    }

    // What the backup agent asks: true only when the switch is positively on; false or null never allows a backup.
    @Test fun agentReadIsEnabledWhenNoFileExists() = withStore { s, _ -> assertEquals(true, s.readBackupEnabled()) }
    @Test fun agentReadFollowsTheStoredValue() = withStore { s, _ ->
        s.setBackupEnabled(false); assertEquals(false, s.readBackupEnabled())
        s.setBackupEnabled(true); assertEquals(true, s.readBackupEnabled())
    }
    @Test fun agentReadIsNotEnabledForACorruptFileAndTheSwitchStaysOff() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val f = ctx.preferencesDataStoreFile(file)
        f.parentFile!!.mkdirs()
        f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, -1, -2, 0x55, 0x01, 0x02, 0x03))
        val job = SupervisorJob()
        try {
            val s = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job))
            assertNotEquals(true, s.readBackupEnabled())
            assertFalse(s.settings.first().backupEnabled)
            s.setBackupEnabled(true)
            assertEquals(true, s.readBackupEnabled())
        } finally { job.cancelAndJoin(); f.delete() }
    }
    @Test fun agentReadIsNullWhenTheFileCannotBeRead() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val f = ctx.preferencesDataStoreFile(file)
        val job1 = SupervisorJob(); val job2 = SupervisorJob()
        try {
            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job1))
            first.setBackupEnabled(true)
            job1.cancelAndJoin()
            assertTrue("could not make the file unreadable", f.setReadable(false, false))
            assertFalse("the test setup did not make the file unreadable", f.canRead())
            val second = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job2))
            assertNull(second.readBackupEnabled())
            // The settings flow does not claim the switch is on either.
            assertFalse(second.settings.first().backupEnabled)
        } finally { f.setReadable(true, false); job1.cancelAndJoin(); job2.cancelAndJoin(); f.delete() }
    }
    @Test fun lastPositionIsKeptOutOfTheSettingsFileThatIsBackedUp() = withStore { s, file ->
        s.setLastPosition(GeoPoint(43.7022, -72.2896), 160f); s.setNorth(NorthRef.MAGNETIC)
        assertEquals(43.7022, s.settings.first().lastPosition!!.lat, 1e-9)
        val main = ctx.preferencesDataStoreFile(file).readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(main.contains("magnetic_north")); assertFalse(main.contains("last_lat")); assertFalse(main.contains("last_lon"))
        assertTrue(ctx.preferencesDataStoreFile("$file-position").readBytes().toString(Charsets.ISO_8859_1).contains("last_lat"))
    }
}
