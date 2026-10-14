package com.infinite8looper.compass

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.StoredTrail
import com.infinite8looper.compass.data.defaultUnits
import com.infinite8looper.compass.nav.ColorMode
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        // Blind navigation and the breadcrumb trail are both on until they are turned off. The trail was off by default
        // in v2; the v3 spec: "`Breadcrumb trail` is on by default, also for an install that never touched the switch."
        assertTrue(v.blindNavigation); assertTrue(v.breadcrumbTrail)
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
    @Test fun blindNavigationAndBreadcrumbTrailRoundTripBothWays() = withStore { s, file ->
        s.setBlindNavigation(false); s.setBreadcrumbTrail(true)
        var v = s.settings.first()
        assertFalse(v.blindNavigation); assertTrue(v.breadcrumbTrail)
        s.setBlindNavigation(true); s.setBreadcrumbTrail(false)
        v = s.settings.first()
        assertTrue(v.blindNavigation); assertFalse(v.breadcrumbTrail)
        // Off is stored, so it stays off: the default of on is for a switch that was never touched.
        assertTrue(ctx.preferencesDataStoreFile(file).readBytes().toString(Charsets.ISO_8859_1).contains("breadcrumb_trail"))
        s.setBreadcrumbTrail(true)
        assertTrue(s.settings.first().breadcrumbTrail)
    }
    @Test fun theTwoNewSettingsChangeNoOtherSetting() = withStore { s, _ ->
        s.setBackupEnabled(false); s.setTickFeedback(false); s.setShowCoordinates(true)
        s.setBlindNavigation(false); s.setBreadcrumbTrail(true)
        val v = s.settings.first()
        assertFalse(v.backupEnabled); assertFalse(v.tickFeedback); assertTrue(v.showCoordinates)
        assertEquals(false, s.readBackupEnabled())
    }
    @Test fun theTwoNewSettingsAreKeptInTheSettingsFileAndNotThePositionFile() = withStore { s, file ->
        s.setBlindNavigation(false); s.setBreadcrumbTrail(true); s.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
        assertFalse(s.settings.first().blindNavigation)
        val main = ctx.preferencesDataStoreFile(file).readBytes().toString(Charsets.ISO_8859_1)
        val pos = ctx.preferencesDataStoreFile("$file-position").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(main.contains("blind_navigation")); assertTrue(main.contains("breadcrumb_trail"))
        assertFalse(pos.contains("blind_navigation")); assertFalse(pos.contains("breadcrumb_trail"))
    }
    @Test fun aSettingsFileFromBeforeTheTwoNewSettingsReadsThemAsTheirDefaults() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val main = ctx.preferencesDataStoreFile(file)
        val oldJob = SupervisorJob(); val job = SupervisorJob()
        try {
            // An install from before this build: a settings file with the keys it had then and neither new one.
            val old = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + oldJob)) { main }
            old.edit {
                it[booleanPreferencesKey("magnetic_north")] = true; it[booleanPreferencesKey("tick_feedback")] = false
                it[booleanPreferencesKey("backup_enabled")] = false
            }
            oldJob.cancelAndJoin()
            val text = main.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("tick_feedback")); assertFalse(text.contains("blind_navigation")); assertFalse(text.contains("breadcrumb_trail"))

            // Both are on for an install that never stored them (v3: "`Breadcrumb trail` is on by default, also for
            // an install that never touched the switch"), and the stored trail a press reads says the same.
            val store = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job))
            val v = store.settings.first()
            assertTrue(v.blindNavigation); assertTrue(v.breadcrumbTrail)
            assertEquals(StoredTrail(TrailMode.OFF, settingOn = true), store.storedTrail())
            // What it did hold is as it was.
            assertEquals(NorthRef.MAGNETIC, v.north); assertFalse(v.tickFeedback); assertFalse(v.backupEnabled)
        } finally { oldJob.cancelAndJoin(); job.cancelAndJoin(); main.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
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
            // The two newer settings come back as their defaults: blind navigation on, and the trail on (v3:
            // "`Breadcrumb trail` is on by default").
            assertTrue(v.blindNavigation); assertTrue(v.breadcrumbTrail)
            s.setNorth(NorthRef.MAGNETIC)
            assertEquals(NorthRef.MAGNETIC, s.settings.first().north)
        } finally { job.cancelAndJoin(); f.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }
    @Test fun valuesSurviveClosingAndReopeningTheStore() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val job1 = SupervisorJob(); val job2 = SupervisorJob()
        try {
            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job1))
            first.setNorth(NorthRef.MAGNETIC); first.setUnits(Units.IMPERIAL); first.setFocused(42)
            first.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
            first.setTickFeedback(false); first.setShowCoordinates(true); first.setBackupEnabled(false)
            first.setBlindNavigation(false); first.setBreadcrumbTrail(true)
            assertEquals(listOf(1, 2), listOf(first.nextWaypointNumber(), first.nextWaypointNumber()))
            job1.cancelAndJoin()
            val second = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job2))
            val v = second.settings.first()
            assertEquals(NorthRef.MAGNETIC, v.north); assertEquals(Units.IMPERIAL, v.units); assertEquals(42L, v.focusedId)
            assertEquals(43.7022, v.lastPosition!!.lat, 1e-9); assertEquals(-72.2896, v.lastPosition.lon, 1e-9)
            assertEquals(160f, v.lastAltitudeM!!, 0f)
            assertFalse(v.tickFeedback); assertTrue(v.showCoordinates); assertFalse(v.backupEnabled)
            assertFalse(v.blindNavigation); assertTrue(v.breadcrumbTrail)
            assertEquals(3, second.nextWaypointNumber())
        } finally { job1.cancelAndJoin(); job2.cancelAndJoin(); ctx.preferencesDataStoreFile(file).delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
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
        } finally { job.cancelAndJoin(); f.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
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
            // The two newer settings read as their defaults while the file cannot be read.
            assertTrue(second.settings.first().blindNavigation); assertTrue(second.settings.first().breadcrumbTrail)
        } finally { f.setReadable(true, false); job1.cancelAndJoin(); job2.cancelAndJoin(); f.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }
    // Who turned the switch off. The backup agent puts a backup without waypoints in the place of the stored one
    // only for a switch the wearer turned off in the app.
    @Test fun aSwitchTurnedOffInTheAppIsOnRecordAsTurnedOffByTheWearer() = withStore { s, file ->
        assertEquals(false, s.readBackupOffByWearer())
        s.setBackupEnabled(false)
        assertEquals(false, s.readBackupEnabled()); assertEquals(true, s.readBackupOffByWearer())
        // It is in the settings file, so it is there for the agent in another process's lifetime.
        assertTrue(ctx.preferencesDataStoreFile(file).readBytes().toString(Charsets.ISO_8859_1).contains("backup_off_by_wearer"))
        s.setBackupEnabled(true)
        assertEquals(true, s.readBackupEnabled()); assertEquals(false, s.readBackupOffByWearer())
        s.setBackupEnabled(false); s.setTickFeedback(false)
        assertEquals(true, s.readBackupOffByWearer())
    }
    @Test fun aSwitchThatIsOffInADamagedFileIsNotOnRecordAsTurnedOffByTheWearer() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val f = ctx.preferencesDataStoreFile(file)
        f.parentFile!!.mkdirs()
        f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, -1, -2, 0x55, 0x01, 0x02, 0x03))
        val job = SupervisorJob()
        try {
            val s = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job))
            assertEquals(false, s.readBackupEnabled())
            assertEquals(false, s.readBackupOffByWearer())
        } finally { job.cancelAndJoin(); f.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }
    @Test fun aSwitchThatIsOffInAFileFromBeforeTheRecordWasKeptIsNotOnRecordAsTurnedOffByTheWearer() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val main = ctx.preferencesDataStoreFile(file)
        val oldJob = SupervisorJob(); val job = SupervisorJob()
        try {
            val old = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + oldJob)) { main }
            old.edit { it[booleanPreferencesKey("backup_enabled")] = false }
            oldJob.cancelAndJoin()
            val s = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job))
            assertEquals(false, s.readBackupEnabled())
            assertEquals(false, s.readBackupOffByWearer())
        } finally { oldJob.cancelAndJoin(); job.cancelAndJoin(); main.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }
    @Test fun whoTurnedTheSwitchOffIsNotKnownWhenTheFileCannotBeRead() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val f = ctx.preferencesDataStoreFile(file)
        val job1 = SupervisorJob(); val job2 = SupervisorJob()
        try {
            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job1))
            first.setBackupEnabled(false)
            job1.cancelAndJoin()
            assertTrue("could not make the file unreadable", f.setReadable(false, false))
            assertFalse("the test setup did not make the file unreadable", f.canRead())
            val second = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job2))
            assertNull(second.readBackupEnabled())
            assertNull(second.readBackupOffByWearer())
        } finally { f.setReadable(true, false); job1.cancelAndJoin(); job2.cancelAndJoin(); f.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }
    // One read gives the agent the switch and who turned it off, as they stood together.
    @Test fun theSwitchAndWhoTurnedItOffAreReadTogether() = withStore { s, _ ->
        assertEquals(true to false, s.readBackupSwitch())
        s.setBackupEnabled(false)
        assertEquals(false to true, s.readBackupSwitch())
        s.setBackupEnabled(true)
        assertEquals(true to false, s.readBackupSwitch())
    }
    @Test fun lastPositionIsKeptOutOfTheSettingsFileThatIsBackedUp() = withStore { s, file ->
        s.setLastPosition(GeoPoint(43.7022, -72.2896), 160f); s.setNorth(NorthRef.MAGNETIC)
        assertEquals(43.7022, s.settings.first().lastPosition!!.lat, 1e-9)
        val main = ctx.preferencesDataStoreFile(file).readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(main.contains("magnetic_north")); assertFalse(main.contains("last_lat")); assertFalse(main.contains("last_lon")); assertFalse(main.contains("last_alt"))
        assertTrue(ctx.preferencesDataStoreFile("$file-position").readBytes().toString(Charsets.ISO_8859_1).contains("last_lat"))
        val pos = ctx.preferencesDataStoreFile("$file-position").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(pos.contains("last_lon")); assertTrue(pos.contains("last_alt"))
        assertEquals(160f, s.settings.first().lastAltitudeM!!, 0f)
    }
    @Test fun legacyPositionKeysMoveOutOfTheSettingsFileOnce() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val main = ctx.preferencesDataStoreFile(file); val pos = ctx.preferencesDataStoreFile("$file-position")
        fun text(f: java.io.File) = f.readBytes().toString(Charsets.ISO_8859_1)
        val legacyJob = SupervisorJob(); val job1 = SupervisorJob(); val job2 = SupervisorJob()
        try {
            // An old install: the position sits in the settings file next to the other settings.
            val legacy = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + legacyJob)) { main }
            legacy.edit {
                it[doublePreferencesKey("last_lat")] = 43.7022; it[doublePreferencesKey("last_lon")] = -72.2896
                it[floatPreferencesKey("last_alt")] = 160f
                it[booleanPreferencesKey("magnetic_north")] = true; it[booleanPreferencesKey("imperial")] = true
                it[booleanPreferencesKey("backup_enabled")] = false; it[longPreferencesKey("focused_id")] = 9L
            }
            legacyJob.cancelAndJoin()
            // Positive control: the legacy names really are in the file before the store opens it.
            assertTrue(text(main).contains("last_lat") && text(main).contains("last_lon") && text(main).contains("last_alt"))

            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job1))
            val v = first.settings.first()
            assertEquals(43.7022, v.lastPosition!!.lat, 1e-9); assertEquals(-72.2896, v.lastPosition.lon, 1e-9)
            assertEquals(160f, v.lastAltitudeM!!, 0f)
            assertEquals(NorthRef.MAGNETIC, v.north); assertEquals(Units.IMPERIAL, v.units)
            assertEquals(9L, v.focusedId); assertFalse(v.backupEnabled)
            assertEquals(false, first.readBackupEnabled())
            job1.cancelAndJoin()
            val afterFirst = main.readBytes()
            for (k in listOf("last_lat", "last_lon", "last_alt")) assertFalse("$k left in the settings file", text(main).contains(k))
            for (k in listOf("magnetic_north", "imperial", "backup_enabled", "focused_id")) assertTrue("$k lost", text(main).contains(k))
            val posAfterFirst = pos.readBytes()

            // Opening it again changes nothing.
            val second = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job2))
            val w = second.settings.first()
            assertEquals(v, w)
            job2.cancelAndJoin()
            assertArrayEquals(afterFirst, main.readBytes()); assertArrayEquals(posAfterFirst, pos.readBytes())
        } finally {
            legacyJob.cancelAndJoin(); job1.cancelAndJoin(); job2.cancelAndJoin(); main.delete(); pos.delete()
        }
    }

    // The breadcrumb trail's mode, which is kept with the settings.

    @Test fun theTrailsModeIsOffUntilItIsSetAndRoundTrips() = withStore { s, _ ->
        assertEquals(TrailMode.OFF, s.settings.first().trailMode)
        s.setBreadcrumbTrail(true)
        assertEquals(TrailMode.OFF, s.settings.first().trailMode)
        for (mode in listOf(TrailMode.LAYING, TrailMode.RETRACING, TrailMode.OFF, TrailMode.LAYING)) {
            s.setTrailMode(mode)
            assertEquals(mode, s.settings.first().trailMode)
        }
        assertTrue(s.settings.first().breadcrumbTrail)
    }
    /**
     * A press reads the trail's stored mode and switch as it is made. The answer is what the file held when it was
     * asked, even if the one who asked gets to take it only after the file has changed: here it is asked on the main
     * thread, which is then kept busy while the switch is turned off.
     */
    @Test fun theStoredTrailIsWhatTheFileHeldWhenItWasAskedHoweverLateTheAnswerIsTaken() = withStore { s, _ ->
        // Before anything is stored the switch is on (v3: "`Breadcrumb trail` is on by default").
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = true), s.storedTrail())
        s.setBreadcrumbTrail(false)
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = false), s.storedTrail())
        s.setBreadcrumbTrail(true)
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = true), s.storedTrail())
        for (mode in listOf(TrailMode.RETRACING, TrailMode.LAYING)) {
            s.setTrailMode(mode)
            assertEquals(StoredTrail(mode, settingOn = true), s.storedTrail())
        }
        val asked = withContext(Dispatchers.Main) {
            val answer = async(start = CoroutineStart.UNDISPATCHED) { s.storedTrail() }
            // The file's contents are fetched for the one who asked on another thread; the main thread stays busy.
            Thread.sleep(150)
            runBlocking(Dispatchers.IO) { s.setBreadcrumbTrail(false) }
            Thread.sleep(150)
            answer
        }
        assertEquals(StoredTrail(TrailMode.LAYING, settingOn = true), asked.await())
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = false), s.storedTrail())
        assertEquals(TrailMode.OFF, s.settings.first().trailMode)
    }

    /**
     * Once the store has read or written the trail's mode or switch, it answers what the trail is without waiting:
     * the answer is there before the one who asked can be overtaken by a later change.
     */
    @Test fun theStoredTrailIsAnsweredWithoutWaitingOnceTheStoreHasIt() = withStore { s, _ ->
        suspend fun answeredAtOnce(): StoredTrail = withContext(Dispatchers.Main) {
            val answer = async(start = CoroutineStart.UNDISPATCHED) { s.storedTrail() }
            assertTrue(answer.isCompleted)
            answer.await()
        }
        // The first answer is read from the file, which holds nothing yet: the switch is on by default.
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = true), s.storedTrail())
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = true), answeredAtOnce())
        s.setBreadcrumbTrail(false)
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = false), answeredAtOnce())
        s.setBreadcrumbTrail(true)
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = true), answeredAtOnce())
        for (mode in listOf(TrailMode.LAYING, TrailMode.RETRACING, TrailMode.OFF, TrailMode.LAYING)) {
            s.setTrailMode(mode)
            assertEquals(StoredTrail(mode, settingOn = true), answeredAtOnce())
        }
        s.setBreadcrumbTrail(false)
        assertEquals(StoredTrail(TrailMode.OFF, settingOn = false), answeredAtOnce())
        // Changes made from several threads at once leave the answer equal to what the file holds.
        coroutineScope {
            repeat(40) { i ->
                launch(Dispatchers.IO) { if (i % 2 == 0) s.setBreadcrumbTrail(i % 4 == 0) else s.setTrailMode(TrailMode.entries[i % 3]) }
            }
        }
        val held = s.settings.first()
        assertEquals(StoredTrail(held.trailMode, held.breadcrumbTrail), answeredAtOnce())
    }

    /**
     * A change to the trail's switch whose caller is cancelled while the change is under way: whether or not the
     * write was made, the answer from memory is what the file holds afterwards. The caller is cancelled at a
     * different moment each round, from before the write begins to after it has ended, and in some rounds the file
     * has changed although the caller was cancelled.
     */
    @Test fun aChangeWhoseCallerIsCancelledLeavesTheStoredTrailWhatTheFileHolds() = withStore { s, _ ->
        s.setBreadcrumbTrail(false)
        var writtenThoughCancelled = 0
        coroutineScope { repeat(480) { round ->
            val before = s.settings.first().breadcrumbTrail
            val change = launch(Dispatchers.IO) { s.setBreadcrumbTrail(!before) }
            val until = System.nanoTime() + (round % 80) * 50_000L
            while (System.nanoTime() < until) Thread.onSpinWait()
            change.cancelAndJoin()
            val file = s.settings.first()
            if (change.isCancelled && file.breadcrumbTrail != before) writtenThoughCancelled++
            assertEquals("round $round", StoredTrail(file.trailMode, file.breadcrumbTrail), s.storedTrail())
        } }
        assertTrue("no round had a cancelled caller and a changed file", writtenThoughCancelled > 0)
    }

    @Test fun theTrailsModeSurvivesANewStoreOnTheSameFile() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        try {
            val firstJob = SupervisorJob()
            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + firstJob))
            first.setBreadcrumbTrail(true); first.setTrailMode(TrailMode.RETRACING)
            firstJob.cancelAndJoin()
            val secondJob = SupervisorJob()
            try {
                val v = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + secondJob)).settings.first()
                assertEquals(TrailMode.RETRACING, v.trailMode); assertTrue(v.breadcrumbTrail)
            } finally { secondJob.cancelAndJoin() }
        } finally {
            ctx.preferencesDataStoreFile(file).delete(); ctx.preferencesDataStoreFile("$file-position").delete()
        }
    }
    @Test fun switchingTheBreadcrumbTrailOffPutsTheModeBackToOffAndSwitchingItOnDoesNotBringItBack() = withStore { s, _ ->
        s.setBreadcrumbTrail(true); s.setTrailMode(TrailMode.RETRACING)
        s.setBreadcrumbTrail(false)
        var v = s.settings.first()
        assertFalse(v.breadcrumbTrail); assertEquals(TrailMode.OFF, v.trailMode)
        s.setBreadcrumbTrail(true)
        v = s.settings.first()
        assertTrue(v.breadcrumbTrail); assertEquals(TrailMode.OFF, v.trailMode)
    }
    @Test fun theTrailsModeIsInTheSettingsFileAndChangesNoOtherSetting() = withStore { s, file ->
        s.setBackupEnabled(false); s.setTickFeedback(false); s.setBlindNavigation(false); s.setBreadcrumbTrail(true); s.setFocused(7)
        s.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
        s.setTrailMode(TrailMode.LAYING)
        val v = s.settings.first()
        assertFalse(v.backupEnabled); assertFalse(v.tickFeedback); assertFalse(v.blindNavigation); assertTrue(v.breadcrumbTrail)
        assertEquals(7L, v.focusedId); assertEquals(TrailMode.LAYING, v.trailMode)
        assertEquals(false, s.readBackupEnabled())
        val main = ctx.preferencesDataStoreFile(file).readBytes().decodeToString()
        val pos = ctx.preferencesDataStoreFile("$file-position").readBytes().decodeToString()
        assertTrue(main.contains("trail_mode")); assertTrue(main.contains("LAYING"))
        assertFalse(pos.contains("trail_mode"))
    }

    // The `Colors` setting (v3 spec, "Colors"): "A setting `Colors`: `Standard` or `Watch face`. Default `Standard`."

    @Test fun colorsAreStandardUntilSetAndRoundTripBothWays() = withStore { s, file ->
        assertEquals(ColorMode.STANDARD, s.settings.first().colors)
        assertEquals(ColorMode.STANDARD, s.colorMode.first())
        s.setColors(ColorMode.WATCH_FACE)
        assertEquals(ColorMode.WATCH_FACE, s.settings.first().colors)
        assertEquals(ColorMode.WATCH_FACE, s.colorMode.first())
        // In the settings file, the one that is backed up, and not in the position file.
        assertTrue(ctx.preferencesDataStoreFile(file).readBytes().toString(Charsets.ISO_8859_1).contains("colors"))
        assertFalse(ctx.preferencesDataStoreFile("$file-position").let { it.exists() && it.readBytes().toString(Charsets.ISO_8859_1).contains("colors") })
        s.setColors(ColorMode.STANDARD)
        assertEquals(ColorMode.STANDARD, s.settings.first().colors)
        assertEquals(ColorMode.STANDARD, s.colorMode.first())
    }
    @Test fun colorsChangeNoOtherSettingAndNoOtherSettingChangesThem() = withStore { s, _ ->
        s.setBackupEnabled(false); s.setTickFeedback(false); s.setBlindNavigation(false); s.setNorth(NorthRef.MAGNETIC)
        s.setColors(ColorMode.WATCH_FACE)
        val v = s.settings.first()
        assertFalse(v.backupEnabled); assertFalse(v.tickFeedback); assertFalse(v.blindNavigation); assertEquals(NorthRef.MAGNETIC, v.north)
        assertEquals(false, s.readBackupEnabled()); assertEquals(true, s.readBackupOffByWearer())
        s.setBackupEnabled(true); s.setTickFeedback(true); s.setBreadcrumbTrail(false)
        assertEquals(ColorMode.WATCH_FACE, s.settings.first().colors)
    }
    @Test fun aValueTheColorsCannotBeReadFromIsStandard() = runBlocking {
        for (write in listOf<(androidx.datastore.preferences.core.MutablePreferences) -> Unit>(
            { it[stringPreferencesKey("colors")] = "watch face" },
            { it[stringPreferencesKey("colors")] = "" },
            { it[booleanPreferencesKey("colors")] = true },
            { it[longPreferencesKey("colors")] = 1L },
        )) {
            val file = "test-settings-${UUID.randomUUID()}"
            val main = ctx.preferencesDataStoreFile(file)
            val oldJob = SupervisorJob(); val job = SupervisorJob()
            try {
                val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + oldJob)) { main }
                raw.edit { it[booleanPreferencesKey("tick_feedback")] = false; write(it) }
                oldJob.cancelAndJoin()
                val store = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job))
                val v = store.settings.first()
                assertEquals(ColorMode.STANDARD, v.colors)
                assertEquals(ColorMode.STANDARD, store.colorMode.first())
                // The rest of the file is read as it is.
                assertFalse(v.tickFeedback)
                // And it can be set again.
                store.setColors(ColorMode.WATCH_FACE)
                assertEquals(ColorMode.WATCH_FACE, store.settings.first().colors)
            } finally { oldJob.cancelAndJoin(); job.cancelAndJoin(); main.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
        }
    }
    @Test fun colorsAreStandardInADamagedFileAndInOneThatCannotBeRead() = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        val f = ctx.preferencesDataStoreFile(file)
        val job1 = SupervisorJob(); val job2 = SupervisorJob(); val job3 = SupervisorJob()
        try {
            val first = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job1))
            first.setColors(ColorMode.WATCH_FACE)
            job1.cancelAndJoin()
            assertTrue("could not make the file unreadable", f.setReadable(false, false))
            val second = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job2))
            assertEquals(ColorMode.STANDARD, second.settings.first().colors)
            assertEquals(ColorMode.STANDARD, second.colorMode.first())
            job2.cancelAndJoin()
            f.setReadable(true, false)
            f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, -1, -2, 0x55, 0x01, 0x02, 0x03))
            val third = SettingsStore(ctx, file, CoroutineScope(Dispatchers.IO + job3))
            assertEquals(ColorMode.STANDARD, third.colorMode.first())
            assertEquals(ColorMode.STANDARD, third.settings.first().colors)
        } finally { f.setReadable(true, false); job1.cancelAndJoin(); job2.cancelAndJoin(); job3.cancelAndJoin(); f.delete(); ctx.preferencesDataStoreFile("$file-position").delete() }
    }
}
