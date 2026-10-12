package com.infinite8looper.compass

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NavUi(
    val targets: List<TargetUi>, val focused: TargetUi?, val units: Units, val north: NorthRef,
    val declination: Float?, val fixElapsedMs: Long?, val waypoints: List<Waypoint>,
    /** Meters from the live fix to every waypoint, targets or not. Empty while no fix is live. */
    val distances: Map<Long, Double> = emptyMap(),
    /** False only for [EMPTY], the state before the stores have answered. */
    val loaded: Boolean = true,
    val tickFeedback: Boolean = true,
    val showCoordinates: Boolean = false,
) {
    companion object {
        val EMPTY = NavUi(emptyList(), null, Units.METRIC, NorthRef.TRUE, null, null, emptyList(), emptyMap(), loaded = false)
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

    /**
     * Whether location should be running right now. This is the one place that decides it: today only when at
     * least one waypoint is a target and the permission is held; another reason can be added here.
     */
    private val locationWanted: Flow<Boolean> =
        combine(hasTargets, permissionTick) { hasTargets, _ -> hasTargets && locationSource.hasPermission() }
            .distinctUntilChanged()

    /** The live fix, or null when location is not wanted, so a fix never outlives the updates that made it. */
    private val fix: Flow<Fix?> = locationWanted
        .flatMapLatest { wanted ->
            if (!wanted) flowOf(null)
            else locationSource.updates(5_000)
                .onEach { quietly("save last position") { settingsStore.setLastPosition(it.point, it.altitudeM) } }
                .map<Fix, Fix?> { it }
                // A location failure ends the fixes, not the screen state: the waypoints and settings keep flowing.
                .catch { Log.w(TAG, "location updates failed", it) }
        }
        .onStart { emit(null) }

    val nav: StateFlow<NavUi> = combine(waypoints, settingsStore.settings, fix) { ws, s, f ->
        val known = f?.point ?: s.lastPosition
        val declination = known?.let { declinationAt(it, f?.altitudeM ?: s.lastAltitudeM ?: 0f, System.currentTimeMillis()) }
        val targets = buildTargets(ws.map { it.toInfo() }, s.focusedId, f?.point, declination, s.north)
        // Only a live fix gives distances; the saved last position is not presented as where the wearer is now.
        val distances = f?.let { live -> ws.associate { it.id to distanceMeters(live.point, GeoPoint(it.lat, it.lon)) } }.orEmpty()
        NavUi(targets, targets.firstOrNull { it.focused }, s.units, s.north, declination, f?.elapsedMs, ws, distances,
            tickFeedback = s.tickFeedback, showCoordinates = s.showCoordinates)
    }
        .catch { Log.w(TAG, "screen state stopped updating", it) }
        .stateIn(viewModelScope, sharing, NavUi.EMPTY)

    fun permissionChanged() { permissionTick.value++ }

    /** The target ids, newest first, and which of them has the focus, read from the stores themselves. */
    private suspend fun targetsAndFocus(): Pair<List<Long>, Long?> {
        val ids = dao.observeAll().first().filter { it.isTarget }.map { it.id }
        return ids to resolveFocus(ids, settingsStore.settings.first().focusedId)
    }

    fun moveFocus(step: Int) = launchQuietly("move focus") {
        val (ids, focused) = targetsAndFocus()
        // With no targets there is nothing to step to, and a stored focus must survive.
        if (ids.isNotEmpty()) settingsStore.setFocused(stepFocus(ids, focused, step))
    }

    /** Stores a new waypoint as a target. Throws if the waypoint cannot be stored, so the caller can say so. */
    suspend fun logWaypoint(fix: Fix): Waypoint {
        // The focus falls back to the newest target when none is stored, so pin it down before adding one.
        targetsAndFocus().second?.let { settingsStore.setFocused(it) }
        val n = settingsStore.nextWaypointNumber()
        val w = Waypoint(
            name = "Waypoint $n", lat = fix.point.lat, lon = fix.point.lon, accuracyM = fix.accuracyM,
            createdAt = System.currentTimeMillis(), hue = hueForIndex(n - 1), isTarget = true,
        )
        quietly("save last position") { settingsStore.setLastPosition(fix.point, fix.altitudeM) }
        return w.copy(id = dao.insert(w))
    }

    fun setTarget(id: Long, on: Boolean) = launchQuietly("set target") { dao.setTarget(id, on) }
    fun setAllTargets(on: Boolean) = launchQuietly("set all targets") { dao.setAllTargets(on) }
    fun rename(id: Long, name: String?) = launchQuietly("rename") {
        dao.get(id)?.let { dao.update(it.copy(name = cleanName(name, it.name))) }
    }
    fun setHue(id: Long, hue: Float) = launchQuietly("set hue") {
        dao.get(id)?.let { dao.update(it.copy(hue = normalizeDeg(hue))) }
    }
    fun delete(id: Long) = launchQuietly("delete") { dao.delete(id) }
    fun setNorth(n: NorthRef) = launchQuietly("set north") { settingsStore.setNorth(n) }
    fun setUnits(u: Units) = launchQuietly("set units") { settingsStore.setUnits(u) }
    fun setTickFeedback(on: Boolean) = launchQuietly("set tick feedback") { settingsStore.setTickFeedback(on) }
    fun setShowCoordinates(on: Boolean) = launchQuietly("set show coordinates") { settingsStore.setShowCoordinates(on) }

    /** Runs a write whose failure must not take the app down. Cancellation still propagates. */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "could not $what", e) }
    }

    private fun launchQuietly(what: String, block: suspend () -> Unit): Job = viewModelScope.launch { quietly(what, block) }

    private companion object { const val TAG = "CompassViewModel" }
}
