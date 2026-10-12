package com.infinite8looper.compass.data

import android.content.Context
import androidx.datastore.core.DataStore
import java.io.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

data class Settings(
    val north: NorthRef, val units: Units, val focusedId: Long?,
    val lastPosition: GeoPoint?, val lastAltitudeM: Float?,
    val tickFeedback: Boolean = true, val showCoordinates: Boolean = false,
    val backupEnabled: Boolean = true,
)

fun defaultUnits(locale: Locale): Units =
    if (locale.country in setOf("US", "GB", "LR", "MM")) Units.IMPERIAL else Units.METRIC

class SettingsStore(
    context: Context,
    fileName: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val app: Context = context.applicationContext
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // A damaged file is reset to defaults except the backup switch, which is the one setting that fails closed.
        corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(BACKUP_ENABLED to false) },
        scope = scope,
    ) { app.preferencesDataStoreFile(fileName) }

    /**
     * The last known position lives in its own file, which the backup rules do not include: where the wearer last
     * was is not part of what is backed up. The file is named after the settings file.
     */
    private val positionStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) { app.preferencesDataStoreFile("$fileName-position") }

    private val positionData: Flow<Preferences> = positionStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val settings: Flow<Settings> = combine(
        store.data.catch { if (it is IOException) emit(mutablePreferencesOf(BACKUP_ENABLED to false)) else throw it },
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
        )
    }

    suspend fun setNorth(n: NorthRef) { store.edit { it[MAGNETIC] = n == NorthRef.MAGNETIC } }
    suspend fun setUnits(u: Units) { store.edit { it[IMPERIAL] = u == Units.IMPERIAL } }
    suspend fun setTickFeedback(on: Boolean) { store.edit { it[TICK_FEEDBACK] = on } }
    suspend fun setShowCoordinates(on: Boolean) { store.edit { it[SHOW_COORDINATES] = on } }
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
    suspend fun setBackupEnabled(on: Boolean) { store.edit { it[BACKUP_ENABLED] = on } }
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
        private val FOCUSED = longPreferencesKey("focused_id")
        private val LAT = doublePreferencesKey("last_lat")
        private val LON = doublePreferencesKey("last_lon")
        private val ALT = floatPreferencesKey("last_alt")
        private val COUNTER = intPreferencesKey("waypoint_counter")

        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context, "settings").also { instance = it } }
    }
}
