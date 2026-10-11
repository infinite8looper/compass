package com.infinite8looper.compass

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.*
import com.infinite8looper.compass.sensors.HeadingReading
import com.infinite8looper.compass.sensors.HeadingSource
import com.infinite8looper.compass.sensors.LocationSource
import com.infinite8looper.compass.sensors.declinationAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NavUi(
    val targets: List<TargetUi>, val focused: TargetUi?, val units: Units, val north: NorthRef,
    val declination: Float?, val fixElapsedMs: Long?, val waypoints: List<Waypoint>,
) {
    companion object {
        val EMPTY = NavUi(emptyList(), null, Units.METRIC, NorthRef.TRUE, null, null, emptyList())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompassViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = WaypointDatabase.get(app).waypoints()
    private val settingsStore = SettingsStore.get(app)
    private val headingSource = HeadingSource(app)
    val locationSource = LocationSource(app)
    val supported: Boolean = headingSource.isSupported

    private val sharing = SharingStarted.WhileSubscribed(2_000)
    private val permissionTick = MutableStateFlow(0)

    val heading: StateFlow<HeadingReading?> = headingSource.readings().stateIn(viewModelScope, sharing, null)

    private val waypoints = dao.observeAll()
    private val hasTargets = waypoints.map { ws -> ws.any { it.isTarget } }.distinctUntilChanged()

    /** Location runs only while at least one waypoint is a target and something is collecting [nav]. */
    private val fix: Flow<Fix?> = combine(hasTargets, permissionTick) { has, _ -> has }
        .flatMapLatest { has ->
            if (has && locationSource.hasPermission()) locationSource.updates(5_000) else emptyFlow()
        }
        .onEach { settingsStore.setLastPosition(it.point, it.altitudeM) }
        .map<Fix, Fix?> { it }
        .onStart { emit(null) }

    val nav: StateFlow<NavUi> = combine(waypoints, settingsStore.settings, fix) { ws, s, f ->
        val known = f?.point ?: s.lastPosition
        val declination = known?.let { declinationAt(it, f?.altitudeM ?: s.lastAltitudeM ?: 0f, System.currentTimeMillis()) }
        val targets = buildTargets(ws.map { it.toInfo() }, s.focusedId, f?.point, declination, s.north)
        NavUi(targets, targets.firstOrNull { it.focused }, s.units, s.north, declination, f?.elapsedMs, ws)
    }.stateIn(viewModelScope, sharing, NavUi.EMPTY)

    fun permissionChanged() { permissionTick.value++ }

    fun moveFocus(step: Int) = viewModelScope.launch {
        val n = nav.value
        settingsStore.setFocused(stepFocus(n.targets.map { it.id }, n.focused?.id, step))
    }

    suspend fun logWaypoint(fix: Fix): Waypoint {
        val n = settingsStore.nextWaypointNumber()
        val w = Waypoint(
            name = "Waypoint $n", lat = fix.point.lat, lon = fix.point.lon, accuracyM = fix.accuracyM,
            createdAt = System.currentTimeMillis(), hue = hueForIndex(n - 1), isTarget = true,
        )
        settingsStore.setLastPosition(fix.point, fix.altitudeM)
        return w.copy(id = dao.insert(w))
    }

    fun setTarget(id: Long, on: Boolean) = viewModelScope.launch { dao.setTarget(id, on) }
    fun setAllTargets(on: Boolean) = viewModelScope.launch { dao.setAllTargets(on) }
    fun rename(id: Long, name: String?) = viewModelScope.launch {
        dao.get(id)?.let { dao.update(it.copy(name = cleanName(name, it.name))) }
    }
    fun setHue(id: Long, hue: Float) = viewModelScope.launch {
        dao.get(id)?.let { dao.update(it.copy(hue = normalizeDeg(hue))) }
    }
    fun delete(id: Long) = viewModelScope.launch { dao.delete(id) }
    fun setNorth(n: NorthRef) = viewModelScope.launch { settingsStore.setNorth(n) }
    fun setUnits(u: Units) = viewModelScope.launch { settingsStore.setUnits(u) }
}
