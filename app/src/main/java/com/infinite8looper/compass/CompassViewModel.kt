package com.infinite8looper.compass

import android.app.Application
import android.app.backup.BackupManager
import android.location.LocationManager
import android.os.SystemClock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NavUi(
    val targets: List<TargetUi>, val focused: TargetUi?, val units: Units, val north: NorthRef,
    val declination: Float?, val fixElapsedMs: Long?, val waypoints: List<Waypoint>,
    /** Meters from the live fix to every waypoint, targets or not. Empty while no fix is live. */
    val distances: Map<Long, Double> = emptyMap(),
    /** False only for [EMPTY], the state before the stores have answered. */
    val loaded: Boolean = true,
    val tickFeedback: Boolean = true,
    val showCoordinates: Boolean = false,
    val backupEnabled: Boolean = true,
) {
    companion object {
        val EMPTY = NavUi(emptyList(), null, Units.METRIC, NorthRef.TRUE, null, null, emptyList(), emptyMap(), loaded = false)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompassViewModel(app: Application, val locationSource: LocationSource) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, LocationSource(app))

    private val database = WaypointDatabase.get(app)
    private val dao = database.waypoints()
    private val settingsStore = SettingsStore.get(app)
    private val headingSource = HeadingSource(app)
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
        val targets = buildTargets(ws.map { it.toInfo() }, s.focusedId, f?.point, declination, s.north, s.units)
        // Only a live fix gives distances; the saved last position is not presented as where the wearer is now.
        val distances = f?.let { live -> ws.associate { it.id to distanceMeters(live.point, GeoPoint(it.lat, it.lon)) } }.orEmpty()
        NavUi(targets, targets.firstOrNull { it.focused }, s.units, s.north, declination, f?.elapsedMs, ws, distances,
            tickFeedback = s.tickFeedback, showCoordinates = s.showCoordinates, backupEnabled = s.backupEnabled)
    }
        .catch { Log.w(TAG, "screen state stopped updating", it) }
        .stateIn(viewModelScope, sharing, NavUi.EMPTY)

    init {
        // Rebuilds the database file once, off the main thread, so it holds nothing of rows deleted or changed
        // by an earlier build or on another device. A failure is not fatal: the backup agent tries again.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.sanitize()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not check the waypoint database (" + e.javaClass.simpleName + ")")
            }
        }
    }

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

    private val logState = MutableStateFlow<LogStatus>(LogStatus.Idle)
    /** Where logging with one press stands. It is kept here, not on a screen, so it outlives the screen it started on. */
    val logStatus: StateFlow<LogStatus> = logState.asStateFlow()
    private val logPressCount = MutableStateFlow(0)
    /**
     * The number of the press whose save or result [logStatus] holds: it goes up by one for every press that is
     * taken up. Two results that read the same, such as two failures in a row, are told apart by it.
     */
    val logPress: StateFlow<Int> = logPressCount.asStateFlow()
    /** The press whose result has been reported as seen. */
    private var logSeenPress = 0
    /** Guards the three values above where they have to change or be compared together. */
    private val logLock = Any()

    /**
     * Saves the position as a new waypoint, from the first fix good enough that is taken after this call.
     * Does nothing while a save is in progress; a press while a result is waiting or showing starts a new save.
     * Without the location permission it reports that and saves nothing: asking for the permission is the
     * screen's part, before it calls this.
     *
     * The save belongs to the view model, so it goes on when the screen that started it is left. Its result stays
     * in [logStatus] until a screen reports with [logResultShown] that it has shown it for its time, however long
     * that takes: a result that arrives while no one can see it is not lost.
     * The returned job ends when this press's save has ended, or at once for a press that was not taken up.
     */
    fun logHere(): Job {
        val pressedAt = SystemClock.elapsedRealtime()
        // On the main thread, where presses are handled one after another, so two of them cannot both start a save.
        return viewModelScope.launch(Dispatchers.Main.immediate) {
            if (logState.value is LogStatus.Saving) return@launch
            val refused = when {
                !locationSource.hasPermission() -> LogStatus.Failed(LogFailure.NO_PERMISSION)
                !isLocationOn() -> LogStatus.Failed(LogFailure.LOCATION_OFF)
                else -> null
            }
            synchronized(logLock) {
                logPressCount.value += 1
                logState.value = refused ?: LogStatus.Saving(null)
            }
            if (refused == null) logState.value = saveFreshFix(pressedAt)
        }
    }

    /**
     * Called by the screen that shows the result of press number [press], the first time it shows it. True once
     * for each result, so what marks the moment (the confirming tap) happens once however often the result comes
     * into view. False for any press but the one whose result [logStatus] holds.
     */
    fun logResultFirstSeen(press: Int): Boolean = synchronized(logLock) {
        val first = press == logPressCount.value && logStatusShowMs(logState.value) != null && logSeenPress != press
        if (first) logSeenPress = press
        first
    }

    /**
     * Called by the screen that shows the result of press number [press], once the result has been in view for
     * its time ([logStatusShowMs]): the status returns to [LogStatus.Idle]. A report for an earlier press, or one
     * that comes while a save is in progress, changes nothing, so it cannot remove a newer status.
     */
    fun logResultShown(press: Int) {
        synchronized(logLock) {
            if (press == logPressCount.value && logStatusShowMs(logState.value) != null) logState.value = LogStatus.Idle
        }
    }

    private fun isLocationOn(): Boolean =
        getApplication<Application>().getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /** Waits for a fix taken since [pressedAt] and stores it. Never throws, except to pass a cancellation on. */
    private suspend fun saveFreshFix(pressedAt: Long): LogStatus {
        var passedOver = 0
        val fix = try {
            // The location source already drops fixes older than ten seconds; this also drops any from before the press.
            val fixes = locationSource.updates(1_000).onEach { if (!isFixSincePress(it, pressedAt)) passedOver++ }
            acquireFix(fixes, notBeforeMs = pressedAt) { logState.value = LogStatus.Saving(it.accuracyM) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "location updates failed while logging (" + e.javaClass.simpleName + ")")
            null
        }
        if (fix == null) {
            // A count only: it tells a search that got nothing from one that got only fixes from before the press.
            if (passedOver > 0) Log.i(TAG, "no fix since the press; fixes from before it passed over: $passedOver")
            return LogStatus.Failed(LogFailure.NO_FIX)
        }
        return try {
            LogStatus.Saved(logWaypoint(fix).name, fix.accuracyM, fix.elapsedMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not save the waypoint (" + e.javaClass.simpleName + ")")
            LogStatus.Failed(LogFailure.SAVE_FAILED)
        }
    }

    /**
     * The changes to waypoints that the screens make are applied one at a time, in the order they were made.
     * A rename and a color change each read the row and write it back whole, so two of them running at once, or
     * one of them around a target change, would write back a row that no longer holds the other's change. The
     * edit screen makes such changes close together: it writes the color several times a second while the wheel
     * is turned.
     */
    private val waypointEdits = Mutex()
    private fun launchEdit(what: String, block: suspend () -> Unit): Job =
        launchQuietly(what) { waypointEdits.withLock { block() } }

    fun setTarget(id: Long, on: Boolean) = launchEdit("set target") { dao.setTarget(id, on) }
    fun setAllTargets(on: Boolean) = launchEdit("set all targets") { dao.setAllTargets(on) }
    /** Gives the waypoint the cleaned-up [name]. A name that is empty or only spaces keeps the one it has. */
    fun rename(id: Long, name: String?) = launchEdit("rename") {
        dao.get(id)?.let { dao.update(it.copy(name = cleanName(name, it.name))) }
    }
    fun setHue(id: Long, hue: Float) = launchEdit("set hue") {
        dao.get(id)?.let { dao.update(it.copy(hue = normalizeDeg(hue))) }
    }
    /** Deletes the waypoint through the store's own delete, which is what keeps its content out of backups. */
    fun delete(id: Long) = launchEdit("delete") { dao.delete(id) }
    fun setNorth(n: NorthRef) = launchQuietly("set north") { settingsStore.setNorth(n) }
    fun setUnits(u: Units) = launchQuietly("set units") { settingsStore.setUnits(u) }
    fun setTickFeedback(on: Boolean) = launchQuietly("set tick feedback") { settingsStore.setTickFeedback(on) }
    fun setShowCoordinates(on: Boolean) = launchQuietly("set show coordinates") { settingsStore.setShowCoordinates(on) }

    fun setBackupEnabled(on: Boolean) = launchQuietly("set backup") {
        settingsStore.setBackupEnabled(on)
        // Tells the system the data is worth backing up again; the backup agent decides what is actually saved.
        BackupManager.dataChanged(getApplication<Application>().packageName)
    }

    /** Runs a write whose failure must not take the app down. Cancellation still propagates. */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "could not $what", e) }
    }

    private fun launchQuietly(what: String, block: suspend () -> Unit): Job = viewModelScope.launch { quietly(what, block) }

    private companion object { const val TAG = "CompassViewModel" }
}
