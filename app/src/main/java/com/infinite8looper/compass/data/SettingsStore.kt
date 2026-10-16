package com.infinite8looper.compass.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import java.io.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import com.infinite8looper.compass.nav.ColorMode
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.colorModeOf
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.trailModeOf
import com.infinite8looper.compass.nav.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** `Breadcrumb trail` where nothing is stored for it: on. Every place that needs the default takes it from here. */
const val BREADCRUMB_TRAIL_DEFAULT = true

data class Settings(
    val north: NorthRef, val units: Units, val focusedId: Long?,
    val lastPosition: GeoPoint?, val lastAltitudeM: Float?,
    val tickFeedback: Boolean = true, val showCoordinates: Boolean = false,
    val backupEnabled: Boolean = true,
    val blindNavigation: Boolean = true, val breadcrumbTrail: Boolean = BREADCRUMB_TRAIL_DEFAULT,
    /** The breadcrumb trail's mode as it is stored. What the trail is doing also depends on [breadcrumbTrail] and on the crumbs. */
    val trailMode: TrailMode = TrailMode.OFF,
    /** The `Colors` setting. */
    val colors: ColorMode = ColorMode.STANDARD,
)

/**
 * Calibration's ring as it is kept between openings, restarts and the end of the app's process: the regions lit
 * (one bit each, nav/Calibration.kt) and the level the magnetometer last reported with them (a SensorManager
 * accuracy; null where none is kept). See [com.infinite8looper.compass.nav.ringMemoryStep].
 */
data class CalibrationMemory(val covered: Int, val level: Int?) {
    companion object { val NONE = CalibrationMemory(0, null) }
}

/** The breadcrumb trail as the settings file holds it: the stored mode and the Breadcrumb trail switch. */
data class StoredTrail(val mode: TrailMode, val settingOn: Boolean)

fun defaultUnits(locale: Locale): Units =
    if (locale.country in setOf("US", "GB", "LR", "MM")) Units.IMPERIAL else Units.METRIC

class SettingsStore(
    context: Context,
    fileName: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val app: Context = context.applicationContext
    /**
     * The last known position lives in its own file, which the backup rules do not include: where the wearer last
     * was is not part of what is backed up. The file is named after the settings file.
     */
    private val positionStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) { app.preferencesDataStoreFile("$fileName-position") }

    /**
     * Calibration's ring lives in its own file, which the backup rules do not include: it describes this watch's
     * magnetometer, not the wearer's settings. The file is named after the settings file. A damaged file is reset to
     * no region lit and no level.
     */
    private val calibrationStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) { app.preferencesDataStoreFile("$fileName-calibration") }

    /** Calibration's ring as kept. A file that cannot be read gives none lit and no level. */
    val calibration: Flow<CalibrationMemory> = calibrationStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { CalibrationMemory(it[COVERED] ?: 0, it[LEVEL]) }
        .distinctUntilChanged()

    /** Keeps calibration's ring as [memory] says, in one write. */
    suspend fun setCalibration(memory: CalibrationMemory) {
        calibrationStore.edit {
            if (memory.covered == 0) it.remove(COVERED) else it[COVERED] = memory.covered
            if (memory.level == null) it.remove(LEVEL) else it[LEVEL] = memory.level
        }
    }

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // A damaged file is reset to defaults except the backup switch, which is the one setting that fails closed.
        corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(BACKUP_ENABLED to false) },
        scope = scope,
        migrations = listOf(legacyPositionMigration()),
    ) { app.preferencesDataStoreFile(fileName) }

    /** The settings file's contents. A file that cannot be read stands as defaults with the backup switch off. */
    private val settingsData: Flow<Preferences> =
        store.data.catch { if (it is IOException) emit(mutablePreferencesOf(BACKUP_ENABLED to false)) else throw it }

    /**
     * The position file's contents, read only once the settings file has been read for the first time. That first
     * read is when DataStore runs the migration below, which may write the position file: read side by side, the
     * position file could give its earlier, empty contents first, and an upgraded install would see no last
     * position for a moment.
     */
    private val positionData: Flow<Preferences> = flow {
        settingsData.first()
        emitAll(positionStore.data)
    }.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val settings: Flow<Settings> = combine(
        settingsData,
        positionData,
    ) { p, q -> Pair(p, q) }.map { (p, q) ->
        val lat = q[LAT]; val lon = q[LON]
        Settings(
            north = if (p[MAGNETIC] == true) NorthRef.MAGNETIC else NorthRef.TRUE,
            units = when (p[IMPERIAL]) { true -> Units.IMPERIAL; false -> Units.METRIC; null -> defaultUnits(Locale.getDefault()) },
            focusedId = p[FOCUSED],
            lastPosition = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
            lastAltitudeM = q[ALT],
            tickFeedback = p[TICK_FEEDBACK] ?: true,
            showCoordinates = p[SHOW_COORDINATES] ?: false,
            backupEnabled = p[BACKUP_ENABLED] ?: true,
            // Blind navigation is on wherever nothing is stored for it: on a fresh install, on an install that was
            // upgraded from a build without it, and in a file that was reset or cannot be read.
            blindNavigation = p[BLIND_NAVIGATION] ?: true,
            // The breadcrumb trail is on wherever nothing is stored for it, as blind navigation is: an install that
            // never touched the switch and a restored backup with no value for it included.
            breadcrumbTrail = p[BREADCRUMB_TRAIL] ?: BREADCRUMB_TRAIL_DEFAULT,
            trailMode = trailModeOf(p[TRAIL_MODE]),
            colors = colorsIn(p),
        )
    }

    /**
     * The `Colors` setting alone, for the activity, which colors the screens from it. A new value only when it
     * changes. A file that cannot be read gives `Standard`, as does a value that cannot be read ([colorsIn]).
     */
    val colorMode: Flow<ColorMode> = settingsData.map { colorsIn(it) }.distinctUntilChanged()

    /**
     * The `Colors` setting in [p]: `Standard` where nothing is stored, and where what is stored is not one of the two
     * names, or is not text at all (reading a key as text where something else is stored throws).
     */
    private fun colorsIn(p: Preferences): ColorMode = try {
        colorModeOf(p[COLORS])
    } catch (e: ClassCastException) {
        ColorMode.STANDARD
    }

    // The breadcrumb trail's stored mode and its switch are also held here, in memory, as this store last read or
    // wrote them. The two are changed only by [setBreadcrumbTrail] and [setTrailMode], which are run one at a time
    // and each put what the file holds after its write here before it returns ([writeTrail]).
    private val trailWrites = Mutex()
    @Volatile private var trailHeld: StoredTrail? = null

    private fun trailIn(p: Preferences) = StoredTrail(trailModeOf(p[TRAIL_MODE]), p[BREADCRUMB_TRAIL] ?: BREADCRUMB_TRAIL_DEFAULT)

    /**
     * The breadcrumb trail's stored mode and its switch. It is for a press, which acts on what the trail was doing
     * when it was made.
     *
     * Once this store has read or written the two, the answer comes from memory without waiting, so whoever asks on
     * the main thread has it before anything else can run there. The answer is what this store last read from the
     * file or wrote to it. Every change ([setBreadcrumbTrail], [setTrailMode]) that has returned to its caller is in
     * it. A change that is under way when this is asked is in it or not: its value is put here just before it
     * returns. A change that has begun its write is not stopped by its caller being cancelled, so the file and the
     * value here do not part. [settings] gives no answer at once: it joins two files and gives its reader the newest
     * contents there are by the time the reader gets to run.
     *
     * Before the first read or write this reads the settings file and waits for it. A file that cannot be read
     * answers off, and the answer is not kept, so the next call reads again.
     */
    suspend fun storedTrail(): StoredTrail = trailHeld ?: trailWrites.withLock {
        trailHeld ?: try {
            trailIn(store.data.first()).also { trailHeld = it }
        } catch (e: IOException) {
            StoredTrail(TrailMode.OFF, settingOn = false)
        }
    }

    /**
     * Older installs kept the last position in the settings file, which is backed up. On first use this moves it to
     * the position file (unless that already has one) and removes the three keys. It touches nothing else, so a
     * stored backup switch keeps its value, and it does nothing once the keys are gone. DataStore runs it before the
     * first read or write, so the backup agent's read of the switch cleans the file before any backup can copy it.
     */
    private fun legacyPositionMigration() = object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) =
            LAT in currentData || LON in currentData || ALT in currentData
        override suspend fun migrate(currentData: Preferences): Preferences {
            val lat = currentData[LAT]; val lon = currentData[LON]; val alt = currentData[ALT]
            positionStore.edit {
                if (LAT !in it && LON !in it && lat != null && lon != null) {
                    it[LAT] = lat; it[LON] = lon
                    if (alt != null) it[ALT] = alt
                }
            }
            return currentData.toMutablePreferences().apply { remove(LAT); remove(LON); remove(ALT) }
        }
        override suspend fun cleanUp() = Unit
    }

    suspend fun setNorth(n: NorthRef) { store.edit { it[MAGNETIC] = n == NorthRef.MAGNETIC } }
    suspend fun setUnits(u: Units) { store.edit { it[IMPERIAL] = u == Units.IMPERIAL } }
    suspend fun setTickFeedback(on: Boolean) { store.edit { it[TICK_FEEDBACK] = on } }
    suspend fun setShowCoordinates(on: Boolean) { store.edit { it[SHOW_COORDINATES] = on } }
    suspend fun setBlindNavigation(on: Boolean) { store.edit { it[BLIND_NAVIGATION] = on } }
    suspend fun setColors(mode: ColorMode) { store.edit { it[COLORS] = mode.name } }
    /** Switching the breadcrumb trail off also ends laying or retracing, in the same write. The crumbs are not touched. */
    suspend fun setBreadcrumbTrail(on: Boolean) {
        writeTrail { it[BREADCRUMB_TRAIL] = on; if (!on) it.remove(TRAIL_MODE) }
    }
    /**
     * Whether a trail is being laid or retraced. It is kept in the settings file, with the switch it depends on, so
     * it survives closing the app and is backed up and restored with the settings.
     */
    suspend fun setTrailMode(mode: TrailMode) {
        writeTrail { if (mode == TrailMode.OFF) it.remove(TRAIL_MODE) else it[TRAIL_MODE] = mode.name }
    }

    /**
     * One change to the trail's stored mode or switch: the write to the file and the value held in memory, as one
     * step that a cancellation of the caller does not divide. DataStore writes on its own scope and the caller only
     * waits for the result (`DataStoreImpl.updateData`, 1.2.1), so a caller cancelled while its write was being
     * made would otherwise leave with the file changed and [trailHeld] as it was.
     */
    private suspend fun writeTrail(change: (MutablePreferences) -> Unit) {
        trailWrites.withLock { withContext(NonCancellable) { trailHeld = trailIn(store.edit { change(it) }) } }
    }
    /**
     * What the backup agent asks, and the only answer that allows a backup is `true`. Unlike [settings] this does
     * not hide a failed read: it returns null when the file cannot be read. A fresh install with no file yet reads
     * as the default, on. A damaged file is replaced by the corruption handler with the switch off, so it reads false.
     */
    suspend fun readBackupEnabled(): Boolean? = try {
        store.data.first()[BACKUP_ENABLED] ?: true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    /**
     * Whether the switch is off because the wearer turned it off in the app, for the backup agent
     * ([com.infinite8looper.compass.nav.backupPass]); null when the file cannot be read. A switch that is off in a
     * damaged file that was reset, or in a file written before this record was kept, is not.
     */
    suspend fun readBackupOffByWearer(): Boolean? = try {
        store.data.first()[BACKUP_OFF_BY_WEARER] ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    /**
     * The switch and whether the wearer turned it off, from one read of the file, so the two are as they stood
     * together; each is as [readBackupEnabled] and [readBackupOffByWearer] give it, and both are null when the file
     * cannot be read.
     */
    suspend fun readBackupSwitch(): Pair<Boolean?, Boolean?> = try {
        store.data.first().let { (it[BACKUP_ENABLED] ?: true) to (it[BACKUP_OFF_BY_WEARER] ?: false) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null to null
    }
    /** Stores the switch as the wearer set it in the app, and with it that the wearer was the one who turned it off. */
    suspend fun setBackupEnabled(on: Boolean) { store.edit { it[BACKUP_ENABLED] = on; it[BACKUP_OFF_BY_WEARER] = !on } }
    suspend fun setFocused(id: Long?) { store.edit { if (id == null) it.remove(FOCUSED) else it[FOCUSED] = id } }
    suspend fun setLastPosition(p: GeoPoint, altitudeM: Float) {
        positionStore.edit { it[LAT] = p.lat; it[LON] = p.lon; it[ALT] = altitudeM }
    }
    suspend fun nextWaypointNumber(): Int {
        var n = 0
        store.edit { n = (it[COUNTER] ?: 0) + 1; it[COUNTER] = n }
        return n
    }

    companion object {
        private val MAGNETIC = booleanPreferencesKey("magnetic_north")
        private val IMPERIAL = booleanPreferencesKey("imperial")
        private val TICK_FEEDBACK = booleanPreferencesKey("tick_feedback")
        private val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        private val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val BACKUP_OFF_BY_WEARER = booleanPreferencesKey("backup_off_by_wearer")
        private val BLIND_NAVIGATION = booleanPreferencesKey("blind_navigation")
        private val BREADCRUMB_TRAIL = booleanPreferencesKey("breadcrumb_trail")
        private val TRAIL_MODE = stringPreferencesKey("trail_mode")
        private val COLORS = stringPreferencesKey("colors")
        private val FOCUSED = longPreferencesKey("focused_id")
        private val LAT = doublePreferencesKey("last_lat")
        private val LON = doublePreferencesKey("last_lon")
        private val ALT = floatPreferencesKey("last_alt")
        private val COUNTER = intPreferencesKey("waypoint_counter")
        private val COVERED = intPreferencesKey("covered")
        private val LEVEL = intPreferencesKey("level")

        // The store holds the application's context only (`app`), which lives as long as the process: no leak.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context, "settings").also { instance = it } }
    }
}
