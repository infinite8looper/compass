package com.infinite8looper.compass

import android.app.Application
import android.app.backup.BackupManager
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinite8looper.compass.data.Crumb
import com.infinite8looper.compass.data.Settings
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.*
import com.infinite8looper.compass.sensors.HeadingReading
import com.infinite8looper.compass.sensors.HeadingSource
import com.infinite8looper.compass.sensors.LocationSource
import com.infinite8looper.compass.sensors.declinationAt
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val blindNavigation: Boolean = true,
    /** The breadcrumb trail setting. */
    val breadcrumbTrail: Boolean = false,
    /**
     * The breadcrumb trail: its mode, how many crumbs it has, the crumb that is walked to and the other crumbs'
     * ticks. While it is retraced, [focused] is that crumb and none of [targets] is focused.
     */
    val trail: TrailUi = TrailUi(),
    /** Why no position can come at all, or null when one can: the location permission is not held, or location is switched off. */
    val noPosition: LogFailure? = null,
    /** The reported accuracy of the live fix, in meters; null while no fix is live. */
    val fixAccuracyM: Float? = null,
    /** Where the live fix puts the watch, for the coordinates readout; null while no fix is live. */
    val position: GeoPoint? = null,
) {
    companion object {
        val EMPTY = NavUi(emptyList(), null, Units.METRIC, NorthRef.TRUE, null, null, emptyList(), emptyMap(), loaded = false)
    }
}

/**
 * The settings screen's switches, as the settings file holds them. They come from the settings file alone: not from
 * the waypoints, the crumbs or a position, so nothing that holds up [NavUi] holds up a switch.
 */
data class SwitchesUi(
    val north: NorthRef = NorthRef.TRUE, val units: Units = Units.METRIC, val tickFeedback: Boolean = true,
    val blindNavigation: Boolean = true, val breadcrumbTrail: Boolean = false, val showCoordinates: Boolean = false,
    val backupEnabled: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CompassViewModel(
    app: Application, val locationSource: LocationSource, private val settingsStore: SettingsStore = SettingsStore.get(app),
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, LocationSource(app))

    private val database = WaypointDatabase.get(app)
    private val dao = database.waypoints()
    private val headingSource = HeadingSource(app)
    val supported: Boolean = headingSource.isSupported

    private val sharing = SharingStarted.WhileSubscribed(2_000)

    /**
     * The switches of the settings screen, or null until the settings file has been read. Nothing else is waited
     * for: while [nav] is still [NavUi.EMPTY] because the crumbs cannot be read with the breadcrumb trail setting on,
     * this has the stored values, the trail's switch among them, and the switch can be turned off.
     */
    val switches: StateFlow<SwitchesUi?> = settingsStore.settings
        .map<Settings, SwitchesUi?> { s ->
            SwitchesUi(s.north, s.units, s.tickFeedback, s.blindNavigation, s.breadcrumbTrail, s.showCoordinates, s.backupEnabled)
        }
        .distinctUntilChanged()
        .catch { Log.w(TAG, "the switches stopped updating (" + it.javaClass.simpleName + ")") }
        .stateIn(viewModelScope, sharing, null)
    private val permissionTick = MutableStateFlow(0)

    private val headingOn = MutableStateFlow(false)

    /**
     * The screens say here whether the heading sensor is wanted; what they say is decided in one place,
     * [headingWanted]. The sensor starts at once and stops [HEADING_STOP_DELAY_MS] after it is no longer wanted, so
     * going from the compass to calibration, or the system restarting the screen, does not stop and start it.
     */
    fun setHeadingWanted(wanted: Boolean) { headingOn.value = wanted }

    /**
     * The latest reading. While the sensor is not running this stays the last one it gave; null before the first.
     * The magnetometer's accuracy and field strength in a reading are the last it reported, across a stop and a
     * start too ([carriedOver]): a restarted sensor's first readings do not say unknown.
     */
    val heading: StateFlow<HeadingReading?> = headingOn
        .mapLatest { wanted -> if (!wanted) delay(HEADING_STOP_DELAY_MS); wanted }
        .distinctUntilChanged()
        .flatMapLatest { running -> if (running) headingSource.readings() else emptyFlow() }
        .scan<HeadingReading, HeadingReading?>(null) { last, reading ->
            val magnet = carriedOver(
                last?.let { MagnetReport(it.accuracy, it.fieldMicroTesla) }, MagnetReport(reading.accuracy, reading.fieldMicroTesla),
            )
            reading.copy(accuracy = magnet.accuracy, fieldMicroTesla = magnet.fieldMicroTesla)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The accuracy the magnetometer last reported while the sensor ran, for the settings screen, which does not
     * run it. Null until there has been one.
     */
    val lastAccuracy: StateFlow<Int?> = heading.mapNotNull { it?.accuracy }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val waypoints = dao.observeAll()
    private val hasTargets = waypoints.map { ws -> ws.any { it.isTarget } }.distinctUntilChanged()

    private val crumbDao = database.crumbs()
    /**
     * The breadcrumb trail's crumbs, newest first, as places: read from the database once and again whenever the
     * table changes, and held here. Everything that needs the crumbs (a press, a turn of the crown, a position fix,
     * the screens) takes them from here, so none of those reads the database. Null until the first read.
     */
    private val crumbsHeld: StateFlow<List<CrumbPoint>?> = crumbDao.observeAll().map<List<Crumb>, List<CrumbPoint>?> { rows -> rows.map { it.toPoint() } }
        // A read that fails is tried again, after 1 s and then less often ([crumbsRetryDelayMs]), for as long as
        // the app runs. Meanwhile the list stays what it last was (nothing, if no read has worked yet), and
        // whatever needs the trail says so instead of taking it for empty ([readTrail]).
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            Log.w(TAG, "the crumbs could not be read (" + cause.javaClass.simpleName + "); trying again")
            delay(crumbsRetryDelayMs(attempt))
            true
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val crumbs: Flow<List<CrumbPoint>> = crumbsHeld.filterNotNull()
    /**
     * The crumbs as the screens and the decision about location get them ([crumbsForScreens]): the held list, and
     * while it is unread no crumbs with the breadcrumb trail setting off, so that with the setting off nothing waits
     * for a read of the crumbs. With the setting on and the list unread this gives null, each time that comes about:
     * the trail is not known then, and the screens wait ([nav]).
     */
    private val crumbsShown: Flow<List<CrumbPoint>?> =
        combine(crumbsHeld, settingsStore.settings.map { it.breadcrumbTrail }.distinctUntilChanged()) { held, on -> crumbsForScreens(held, on) }
            .distinctUntilChanged()
    /**
     * What the trail is doing: the stored mode, as the setting and the crumbs let it stand ([effectiveTrailMode]).
     * Null while it is not known, which is with the setting on and the crumbs unread.
     */
    private val trailMode: Flow<TrailMode?> =
        combine(settingsStore.settings, crumbsShown) { s, cs -> cs?.let { effectiveTrailMode(s.trailMode, s.breadcrumbTrail, it.size) } }
            .distinctUntilChanged()

    private val compassOnScreen = MutableStateFlow(false)
    private val compassInViewNow = MutableStateFlow(false)

    /**
     * The screens say here whether the compass is on screen: one of the three screens is on top and the compass is
     * the one at rest in view, or is passing during a swipe. A trail asks for location only then, and so does the
     * coordinates readout.
     */
    fun setCompassOnScreen(on: Boolean) { compassOnScreen.value = on }

    /**
     * The screens say here whether the compass is the screen at rest in view with the app in front
     * ([compassInViewForTrail]). A crumb is consumed only then: on another screen, or at Home, location may be
     * running for the targets, and the trail does not advance.
     */
    fun setCompassInView(inView: Boolean) { compassInViewNow.value = inView }

    /** What the screens last said with [setCompassInView]. */
    val compassInView: StateFlow<Boolean> = compassInViewNow.asStateFlow()

    private val noPosition = MutableStateFlow<LogFailure?>(null)

    /**
     * Looks again at whether a position can come at all (the permission, and location being switched on). Called
     * when the permission may have changed and each time the app comes back on screen, which is when the wearer
     * can have changed either in the system's settings.
     */
    fun refreshLocationState() { noPosition.value = noPositionReason(locationSource.hasPermission(), isLocationOn()) }

    private val showCoordinates = settingsStore.settings.map { it.showCoordinates }.distinctUntilChanged()

    /**
     * Whether location should be running right now. This is the one place that decides it: while at least one
     * waypoint is a target; or while a breadcrumb trail is being laid or retraced and the compass is on screen
     * ([trailWantsLocation]); or while the coordinates are to be shown and the compass is on screen
     * ([coordinatesWantLocation]). In every case only with the permission held.
     */
    private val locationWanted: Flow<Boolean> =
        combine(hasTargets, trailMode, compassOnScreen, showCoordinates, permissionTick) { hasTargets, mode, compass, coordinates, _ ->
            // While the trail is not known the screens show nothing, and location does not run for them.
            mode != null && (hasTargets || trailWantsLocation(mode, compass) || coordinatesWantLocation(coordinates, compass)) &&
                locationSource.hasPermission()
        }.distinctUntilChanged()

    /** The live fix, or null when location is not wanted, so a fix never outlives the updates that made it. */
    private val fix: Flow<Fix?> = locationWanted
        .flatMapLatest { wanted ->
            if (!wanted) flowOf(null)
            else locationSource.updates(5_000)
                .onEach { quietly("save last position") { settingsStore.setLastPosition(it.point, it.altitudeM) } }
                // Each fix is looked at once for the trail, here, before the screens get it: one fix, at most
                // one crumb consumed.
                .onEach { quietly("follow the trail") { retrace(it) } }
                .map<Fix, Fix?> { it }
                // A location failure ends the fixes, not the screen state: the waypoints and settings keep flowing.
                .catch { Log.w(TAG, "location updates failed (" + it.javaClass.simpleName + ")") }
        }
        .onStart { emit(null) }

    /**
     * The targets, and the crumb walked to, that the wearer counted as arrived at when [nav] was last worked out:
     * arrival ends further out than it begins ([nextArrived]), so it has to be remembered. Only [nav] reads and
     * writes it. With no live fix nothing has arrived, so it empties whenever location stops.
     */
    private var arrived: Set<Long> = emptySet()

    val nav: StateFlow<NavUi> = combine(waypoints, settingsStore.settings, fix, crumbsShown, noPosition) { ws, s, f, cs, noPosition ->
        // With the setting on and the crumbs unread the trail is not known: the screens are as they are before the
        // stores have answered, for as long as that lasts, and an unread trail is never shown as an empty one.
        if (cs == null) return@combine NavUi.EMPTY
        val known = f?.point ?: s.lastPosition
        val declination = known?.let { declinationAt(it, f?.altitudeM ?: s.lastAltitudeM ?: 0f, System.currentTimeMillis()) }
        val mode = effectiveTrailMode(s.trailMode, s.breadcrumbTrail, cs.size)
        // While a trail is retraced the crumb walked to stands where the focused target does, and the waypoints
        // are all still targets with none of them focused. The stored focus is not touched, so it is back afterwards.
        val targets = buildTargets(
            ws.map { it.toInfo() }, s.focusedId, f?.point, declination, s.north, s.units, arrived,
            focusSuspended = mode == TrailMode.RETRACING,
        )
        // The crumb walked to is shown as arrived at only by a fix that could consume it.
        val trail = buildTrail(mode, cs, f?.point, declination, s.north, s.units, arrived, fixGoodEnough = f != null && isGoodEnough(f.accuracyM))
        val focused = trail.current ?: targets.firstOrNull { it.focused }
        arrived = targets.filter { it.arrived }.mapTo(HashSet(trail.arrivedIds)) { it.id }
        // Only a live fix gives distances; the saved last position is not presented as where the wearer is now.
        val distances = f?.let { live -> ws.associate { it.id to distanceMeters(live.point, GeoPoint(it.lat, it.lon)) } }.orEmpty()
        NavUi(targets, focused, s.units, s.north, declination, f?.elapsedMs, ws, distances,
            tickFeedback = s.tickFeedback, showCoordinates = s.showCoordinates, backupEnabled = s.backupEnabled,
            blindNavigation = s.blindNavigation, breadcrumbTrail = s.breadcrumbTrail, trail = trail, noPosition = noPosition,
            fixAccuracyM = f?.accuracyM, position = f?.point)
    }
        .catch { Log.w(TAG, "screen state stopped updating (" + it.javaClass.simpleName + ")") }
        .stateIn(viewModelScope, sharing, NavUi.EMPTY)

    init {
        refreshLocationState()
        // A press acts on what the trail was doing when it was made, which the settings store answers from memory
        // once it has read the two values ([SettingsStore.storedTrail]). They are read here, as the view model is
        // made, so that the first press of a process finds them there and does not wait for the file.
        launchQuietly("read the trail's stored mode") { settingsStore.storedTrail() }
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

    fun permissionChanged() { permissionTick.value++; refreshLocationState() }

    /** The target ids, newest first, and which of them has the focus, read from the stores themselves. */
    private suspend fun targetsAndFocus(): Pair<List<Long>, Long?> {
        val ids = dao.observeAll().first().filter { it.isTarget }.map { it.id }
        return ids to resolveFocus(ids, settingsStore.settings.first().focusedId)
    }

    /**
     * A change of the focus reads the targets and the stored focus and then writes the focus it works out from
     * them. These are made one at a time, in the order they were asked for: the crown asks for a step at every
     * detent, and two steps that both read before either had written would move the focus once. It is a lock of
     * its own, not the one for the waypoint edits, so a step does not wait behind an edit.
     */
    private val focusChanges = Mutex()

    fun moveFocus(step: Int) = launchQuietly("move focus") {
        // While a trail is retraced the waypoint focus is suspended: it does not move, and is back afterwards.
        if (trailModeNow() == TrailMode.RETRACING) return@launchQuietly
        focusChanges.withLock {
            val (ids, focused) = targetsAndFocus()
            // With no targets there is nothing to step to, and a stored focus must survive.
            if (ids.isNotEmpty()) settingsStore.setFocused(stepFocus(ids, focused, step))
        }
    }

    /** Stores a new waypoint as a target. Throws if the waypoint cannot be stored, so the caller can say so. */
    suspend fun logWaypoint(fix: Fix): Waypoint {
        // The focus falls back to the newest target when none is stored, so pin it down before adding one. Under
        // the lock a step of the focus takes, so the pin cannot write back a focus that a step has just moved on from.
        focusChanges.withLock { targetsAndFocus().second?.let { settingsStore.setFocused(it) } }
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
     * A single press of the side button: what [logHere] does, except that while a breadcrumb trail is being laid
     * the fix is stored as a crumb of the trail and not as a waypoint ([singlePress]). The status, the fix rules
     * and the rule for a press while a save is in progress are the same for both; a dropped crumb is reported as
     * saved under its place in the trail (`Saved Crumb 3`).
     */
    fun sidePress(): Job = save(byTrail = true)

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
    fun logHere(): Job = save(byTrail = false)

    /**
     * The `Drop crumb` row: what [sidePress] does while a breadcrumb trail is being laid, and nothing at any other
     * time. The row shows only while a trail is laid; a tap that lands just as the trail stops being laid starts no
     * save and shows no status, so the row never saves a waypoint.
     */
    fun dropCrumb(): Job = save(byTrail = true, crumbOnly = true)

    private fun save(byTrail: Boolean, crumbOnly: Boolean = false): Job {
        val pressedAt = SystemClock.elapsedRealtime()
        // On the main thread, where presses are handled one after another, so two of them cannot both start a save.
        return viewModelScope.launch(Dispatchers.Main.immediate) {
            if (crumbOnly) {
                val laying = try {
                    singlePress(trailModeNow()) == SinglePress.CRUMB
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "could not read the trail (" + e.javaClass.simpleName + ")")
                    false
                }
                if (!laying) return@launch
            }
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
            if (refused == null) {
                val result = saveFreshFix(pressedAt, byTrail)
                // A save that stored nothing and has nothing to say ends the status here.
                synchronized(logLock) { if (result == LogStatus.Idle) endStatus() else logState.value = result }
            }
        }
    }

    /** `Trail complete` is owed a showing: a trail was completed while another status had its place ([trailCompleteOver]). Guarded by [logLock]. */
    private var completeOwed = false

    /** The status that was showing has ended: what is owed is shown in its place, as a result of its own, or nothing is. Call with [logLock] held. */
    private fun endStatus() {
        val step = statusEnded(completeOwed)
        completeOwed = step.owed
        if (step.status != LogStatus.Idle) logPressCount.value += 1
        logState.value = step.status
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
     * its time ([logStatusShowMs]): the status returns to [LogStatus.Idle], or, if a trail was completed meanwhile,
     * gives its place to `Trail complete`. A report for an earlier press, or one
     * that comes while a save is in progress, changes nothing, so it cannot remove a newer status.
     */
    fun logResultShown(press: Int) {
        synchronized(logLock) {
            if (press == logPressCount.value && logStatusShowMs(logState.value) != null) endStatus()
        }
    }

    private fun isLocationOn(): Boolean =
        getApplication<Application>().getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /**
     * Waits for a fix taken since [pressedAt] and stores it: as a crumb if [byTrail] and a trail is being laid when
     * the press is taken up, and as a waypoint otherwise. Never throws, except to pass a cancellation on.
     */
    private suspend fun saveFreshFix(pressedAt: Long, byTrail: Boolean): LogStatus {
        var passedOver = 0
        val asCrumb = try {
            // A crumb can be stored only on a trail that has been read. One that has not is waited for here,
            // before any fix is looked for and with no lock held, and if it stays unread the press has failed.
            (byTrail && singlePress(trailModeNow()) == SinglePress.CRUMB).also { crumb -> if (crumb && crumbsHeld.value == null) readTrail() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not read the trail (" + e.javaClass.simpleName + ")")
            return LogStatus.Failed(LogFailure.SAVE_FAILED)
        }
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
            if (asCrumb) dropCrumb(fix) else LogStatus.Saved(logWaypoint(fix).name, fix.accuracyM, fix.elapsedMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not save the " + (if (asCrumb) "crumb" else "waypoint") + " (" + e.javaClass.simpleName + ")")
            LogStatus.Failed(LogFailure.SAVE_FAILED)
        }
    }

    // The breadcrumb trail. Its crumbs are rows of the waypoint database and its mode is in the settings file. Every
    // change to either is made under the lock the waypoint edits use, from what the stores hold at that moment, and
    // is decided by the pure functions in nav/Trail.kt.

    /**
     * The trail as it stands now, with its crumbs' positions and the units arrival is judged in: the mode from the
     * settings and the crumbs from the list held in memory ([crumbsHeld]), so this reads no database.
     *
     * Throws if the crumbs have not been read: no read has worked yet and none does within [CRUMBS_SEEN_WITHIN_MS].
     * An unread trail is not taken for an empty one, and the reading goes on being tried ([crumbsHeld]).
     *
     * Only what depends on the crumbs comes here, and with the breadcrumb trail setting off nothing does: a save,
     * the crown and a position fix do not wait for the crumbs then ([trailModeNow], [retrace]). With the setting on
     * and the crumbs unread, what waits is, by the stored mode:
     * - off: nothing. A press saves a waypoint and the crown moves the focus, at once.
     * - laying: the crown moves the focus at once. A press of the side button waits here before it looks for a fix,
     *   holding no lock, and ends in `Couldn't save`.
     * - retracing: a press of the side button waits here before it looks for a fix, and ends in `Couldn't save`; a
     *   turn of the crown waits here and moves nothing. Neither holds the edit lock. A position fix does not wait.
     * - in every mode a double press, or a tap on the trail's row, waits here with the edit lock held and changes
     *   nothing. `Log here` never comes here.
     */
    private suspend fun readTrail(): Triple<Trail, List<CrumbPoint>, Units> {
        val s = settingsStore.settings.first()
        val points = crumbsHeld.value ?: withTimeoutOrNull(CRUMBS_SEEN_WITHIN_MS) { crumbs.first() } ?: throw IOException("the crumbs have not been read")
        return Triple(Trail(effectiveTrailMode(s.trailMode, s.breadcrumbTrail, points.size), points.map { it.id }), points, s.units)
    }

    /**
     * The trail's mode as it stands now. It is known at once, without the crumbs, wherever it does not depend on
     * them ([trailModeUnread]): always with the breadcrumb trail setting off. Otherwise it comes from [readTrail],
     * which is immediate once the crumbs have been read.
     *
     * A press and a turn of the crown ask this as they are made. The stored mode and the switch come from memory
     * ([SettingsStore.storedTrail]) and so do the crumbs, so the mode is the one that stood when the press was
     * made: a change that is made after it does not reach back into it.
     */
    private suspend fun trailModeNow(): TrailMode {
        val stored = settingsStore.storedTrail()
        // With the crumbs read nothing here waits: the mode is decided before the press's or the turn's own call returns.
        crumbsHeld.value?.let { return effectiveTrailMode(stored.mode, stored.settingOn, it.size) }
        return trailModeUnread(stored.mode, stored.settingOn) ?: readTrail().first.mode
    }

    /**
     * Waits until the held list shows a change this view model has just made to the crumbs, so that what is read
     * next under the lock is what was written. The database reports a change within moments; the wait is bounded.
     */
    private suspend fun awaitCrumbs(shows: (List<CrumbPoint>) -> Boolean) {
        withTimeoutOrNull(CRUMBS_SEEN_WITHIN_MS) { crumbs.first(shows) }
    }

    /** Stores what [change] did to the trail that was [before]: the crumb it consumed is deleted, and a new mode is written. */
    private suspend fun applyTrail(before: Trail, change: TrailChange) {
        // The store's own delete, as for a waypoint, which is what keeps a deleted crumb's position out of backups.
        change.removedId?.let { id -> crumbDao.delete(id); awaitCrumbs { held -> held.none { it.id == id } } }
        if (change.trail.mode != before.mode) settingsStore.setTrailMode(change.trail.mode)
        if (change.completed) {
            // Shown where a logging result is shown, for as long. A save in progress or a result that is showing
            // keeps its place, and `Trail complete` follows it, once.
            synchronized(logLock) {
                val step = trailCompleteOver(logState.value)
                completeOwed = completeOwed || step.owed
                if (step.status != logState.value) {
                    logPressCount.value += 1
                    logState.value = step.status
                }
            }
        }
    }

    /**
     * What a double press of the side button does, and a tap on the trail's row: starts a trail, switches from
     * laying to retracing, or returns from retracing to laying ([nextTrail]). Nothing while the setting is off.
     */
    fun trailDoublePress(): Job = launchEdit("switch the trail") {
        if (!settingsStore.settings.first().breadcrumbTrail) return@launchEdit
        val trail = readTrail().first
        val change = nextTrail(trail, TrailEvent.DoublePress)
        // A `Trail complete` still waiting from the trail before does not follow into a new one.
        if (startsNewTrail(trail.mode, change.trail.mode)) dropTrailComplete()
        applyTrail(trail, change)
    }

    /**
     * `Trail complete` is given up if it has not been shown out ([trailCompleteDropped]). The screens call this
     * when the compass is no longer on screen with the app in front ([trailCompleteKept]), so the words never come
     * up on a later visit.
     */
    fun dropTrailComplete() {
        synchronized(logLock) {
            val step = trailCompleteDropped(logState.value, completeOwed)
            completeOwed = step.owed
            if (step.status != logState.value) logState.value = step.status
        }
    }

    /** Removes every crumb, through the store's own delete, and ends the trail. Waypoints are not touched. */
    fun clearTrail(): Job = launchEdit("clear the trail") {
        crumbDao.clear()
        awaitCrumbs { it.isEmpty() }
        settingsStore.setTrailMode(TrailMode.OFF)
    }

    /**
     * Stores [fix] as the trail's newest crumb, if the trail is still being laid now that the fix is here: a press
     * taken while laying whose fix arrives after the trail was switched to retracing, or off, stores nothing and
     * reports nothing.
     */
    private suspend fun dropCrumb(fix: Fix): LogStatus = waypointEdits.withLock {
        val trail = readTrail().first
        val dropped = nextTrail(trail, TrailEvent.CrumbDropped(NEW_CRUMB)).trail
        if (dropped == trail) return@withLock LogStatus.Idle
        quietly("save last position") { settingsStore.setLastPosition(fix.point, fix.altitudeM) }
        val id = crumbDao.insert(Crumb(lat = fix.point.lat, lon = fix.point.lon, accuracyM = fix.accuracyM, createdAt = System.currentTimeMillis()))
        awaitCrumbs { held -> held.any { it.id == id } }
        LogStatus.Saved(crumbName(dropped.crumbIds.size), fix.accuracyM, fix.elapsedMs)
    }

    /** The crumb the last fix arrived at, for [trailAfterFix]. */
    private var crumbArrivedAt: Long? = null

    /**
     * Looks at one position fix for the trail ([trailAfterFix]): while it is retraced and the compass is in view, a
     * fix accurate enough to be saved and within the arrival distance of the crumb walked to deletes that crumb, by
     * its id, and the next newest takes over; after the last one the trail is off and `Trail complete` is shown.
     * Called once for each fix.
     */
    private suspend fun retrace(fix: Fix) {
        // Read from memory, so a fix costs nothing more unless a trail is being retraced.
        val s = settingsStore.settings.first()
        if (!s.breadcrumbTrail || s.trailMode != TrailMode.RETRACING) return
        // A fix consumes nothing of a trail whose crumbs have not been read, and does not wait for them: it takes
        // no lock, so the waypoint edits go on. Once read, the list never becomes unread again.
        if (crumbsHeld.value == null) return
        // Not to be cut short: this runs inside the stream of fixes, and deleting the last crumb ends the trail,
        // which, with no waypoint as a target, ends the reason for location and so cancels that stream while the
        // rest of the work (the mode, `Trail complete`) is still to do.
        withContext(NonCancellable) {
            waypointEdits.withLock {
                val (trail, points, units) = readTrail()
                val step = trailAfterFix(trail, points, crumbArrivedAt, fix.point, units, fix.accuracyM, compassInViewNow.value)
                crumbArrivedAt = step.arrivedAt
                applyTrail(trail, step.change)
            }
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
    fun setBlindNavigation(on: Boolean) = launchQuietly("set blind navigation") { settingsStore.setBlindNavigation(on) }
    /** Stores the switch. Off also ends laying or retracing; the crumbs stay until the trail is cleared. */
    fun setBreadcrumbTrail(on: Boolean) = launchEdit("set breadcrumb trail") { settingsStore.setBreadcrumbTrail(on) }

    /**
     * Stores the switch as the wearer set it. Off is on record as the wearer's doing, which is what lets the backup
     * agent put a backup without waypoints in the place of the stored one; the screens ask before they call this
     * with off.
     */
    fun setBackupEnabled(on: Boolean) = launchQuietly("set backup") {
        settingsStore.setBackupEnabled(on)
        // Tells the system the data is worth backing up again; the backup agent decides what is actually saved.
        BackupManager.dataChanged(getApplication<Application>().packageName)
    }

    /** Runs a write whose failure must not take the app down. Cancellation still propagates. */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "could not $what (" + e.javaClass.simpleName + ")") }
    }

    private fun launchQuietly(what: String, block: suspend () -> Unit): Job = viewModelScope.launch { quietly(what, block) }

    private companion object {
        const val TAG = "CompassViewModel"
        /** How long the heading sensor keeps running after it is no longer wanted. */
        const val HEADING_STOP_DELAY_MS = 2_000L
        /** Stands for a crumb that is not stored yet, where [nextTrail] is asked whether one may be added. Row ids start at 1. */
        const val NEW_CRUMB = 0L
        /** The longest a change this view model made to the crumbs is waited for to show in the held list. */
        const val CRUMBS_SEEN_WITHIN_MS = 2_000L
    }
}
