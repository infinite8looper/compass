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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.Locale

data class Settings(
    val north: NorthRef, val units: Units, val focusedId: Long?,
    val lastPosition: GeoPoint?, val lastAltitudeM: Float?,
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
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) { app.preferencesDataStoreFile(fileName) }

    val settings: Flow<Settings> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { p ->
        val lat = p[LAT]; val lon = p[LON]
        Settings(
            north = if (p[MAGNETIC] == true) NorthRef.MAGNETIC else NorthRef.TRUE,
            units = when (p[IMPERIAL]) { true -> Units.IMPERIAL; false -> Units.METRIC; null -> defaultUnits(Locale.getDefault()) },
            focusedId = p[FOCUSED],
            lastPosition = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
            lastAltitudeM = p[ALT],
        )
    }

    suspend fun setNorth(n: NorthRef) { store.edit { it[MAGNETIC] = n == NorthRef.MAGNETIC } }
    suspend fun setUnits(u: Units) { store.edit { it[IMPERIAL] = u == Units.IMPERIAL } }
    suspend fun setFocused(id: Long?) { store.edit { if (id == null) it.remove(FOCUSED) else it[FOCUSED] = id } }
    suspend fun setLastPosition(p: GeoPoint, altitudeM: Float) {
        store.edit { it[LAT] = p.lat; it[LON] = p.lon; it[ALT] = altitudeM }
    }
    suspend fun nextWaypointNumber(): Int {
        var n = 0
        store.edit { n = (it[COUNTER] ?: 0) + 1; it[COUNTER] = n }
        return n
    }

    companion object {
        private val MAGNETIC = booleanPreferencesKey("magnetic_north")
        private val IMPERIAL = booleanPreferencesKey("imperial")
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
