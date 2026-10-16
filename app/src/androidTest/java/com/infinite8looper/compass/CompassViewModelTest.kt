package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.Crumb
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.LogFailure
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.TrailUi
import com.infinite8looper.compass.nav.crumbTargetId
import com.infinite8looper.compass.nav.distanceMeters
import com.infinite8looper.compass.nav.FALLBACK_ACCENT
import com.infinite8looper.compass.nav.FALLBACK_NORTH
import com.infinite8looper.compass.nav.FIRST_WAYPOINT_PLACE
import com.infinite8looper.compass.nav.SKIP_HUE_APART
import com.infinite8looper.compass.nav.hueOf
import com.infinite8looper.compass.nav.normalizeDeg
import com.infinite8looper.compass.nav.shortestDelta
import com.infinite8looper.compass.nav.logStatusShowMs
import com.infinite8looper.compass.sensors.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Runs the real view model against the app's own database and settings file on the device. */
@RunWith(AndroidJUnit4::class)
class CompassViewModelTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val settings = SettingsStore.get(app)
    private val hanover = Fix(GeoPoint(43.7022, -72.2896), 5f, 160f, 0L)

    private val crumbDao = WaypointDatabase.get(app).crumbs()

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        // Off is how the breadcrumb trail comes: every test that wants it switches it on.
        settings.setBreadcrumbTrail(false)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private suspend fun viewModel() = withContext(Dispatchers.Main) { CompassViewModel(app) }

    @Test fun loggingASecondWaypointKeepsTheFocusOnTheFirst() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        val nav = withTimeout(10_000) { vm.nav.first { it.waypoints.size == 2 } }
        assertEquals(listOf(second.id, first.id), nav.targets.map { it.id })
        assertEquals(first.id, nav.focused?.id)
        assertEquals(first.id, settings.settings.first().focusedId)
    }

    @Test fun loggingKeepsAFocusTheWearerChose() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        settings.setFocused(second.id)
        val third = vm.logWaypoint(hanover.copy(point = GeoPoint(43.6950, -72.3000)))
        val nav = withTimeout(10_000) { vm.nav.first { it.waypoints.size == 3 } }
        assertEquals(listOf(third.id, second.id, first.id), nav.targets.map { it.id })
        assertEquals(second.id, nav.focused?.id)
    }

    @Test fun moveFocusWithNoTargetsLeavesTheStoredFocusUntouched() = runBlocking {
        settings.setFocused(42L)
        val vm = viewModel()
        vm.moveFocus(1).join()
        assertEquals(42L, settings.settings.first().focusedId)
    }

    @Test fun moveFocusStepsThroughTheTargetsAndWraps() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        // Targets are newest first: [second, first], and the focus is on first.
        vm.moveFocus(1).join()
        assertEquals(second.id, settings.settings.first().focusedId)
        vm.moveFocus(1).join()
        assertEquals(first.id, settings.settings.first().focusedId)
        vm.moveFocus(-1).join()
        assertEquals(second.id, settings.settings.first().focusedId)
    }

    @Test fun crownStepsThatArriveTogetherAreAllTaken() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        val third = vm.logWaypoint(hanover.copy(point = GeoPoint(43.6950, -72.3000)))
        // Targets are newest first: [third, second, first], and the focus is on first.
        assertEquals(first.id, settings.settings.first().focusedId)
        // As a turn of the crown gives them: on the main thread, one after another, none waited for.
        withContext(Dispatchers.Main) { List(5) { vm.moveFocus(1) } }.joinAll()
        // Five steps on from first, round the three: third, second, first, third, second.
        assertEquals(second.id, settings.settings.first().focusedId)
        // Two steps back, the same way: third, first.
        withContext(Dispatchers.Main) { List(2) { vm.moveFocus(-1) } }.joinAll()
        assertEquals(first.id, settings.settings.first().focusedId)
        // And a turn one way and then the other: seven on and three back are four on, which is one on.
        withContext(Dispatchers.Main) { List(7) { vm.moveFocus(1) } + List(3) { vm.moveFocus(-1) } }.joinAll()
        assertEquals(third.id, settings.settings.first().focusedId)
    }

    @Test fun aCrownStepMadeWhileAWaypointIsSavedIsKept() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        repeat(6) { round ->
            // The targets are newest first and end in [second, first]; the focus is on first, the last of them.
            settings.setFocused(first.id)
            // A step back from first, and a save that begins before the step has been stored. Whichever of the
            // two comes first, the step ends on second: a new waypoint goes to the front and takes no focus.
            val saved = withContext(Dispatchers.Main) {
                val step = vm.moveFocus(-1)
                val save = async { vm.logWaypoint(hanover.copy(point = GeoPoint(43.6950 + round * 0.001, -72.3000))) }
                step.join()
                save.await()
            }
            assertEquals("round $round", second.id, settings.settings.first().focusedId)
            assertEquals("round $round", saved.id, dao.observeAll().first().first().id)
        }
    }

    @Test fun blindNavigationAndTheBreadcrumbTrailReachTheScreenStateAndFollowTheirSetters() = runBlocking {
        val vm = viewModel()
        try {
            settings.setBlindNavigation(true); settings.setBreadcrumbTrail(false)
            var nav = withTimeout(10_000) { vm.nav.first { it.loaded && it.blindNavigation && !it.breadcrumbTrail } }
            assertTrue(nav.blindNavigation); assertFalse(nav.breadcrumbTrail)
            vm.setBlindNavigation(false).join(); vm.setBreadcrumbTrail(true).join()
            nav = withTimeout(10_000) { vm.nav.first { !it.blindNavigation && it.breadcrumbTrail } }
            assertFalse(nav.blindNavigation); assertTrue(nav.breadcrumbTrail)
            // Stored, not only shown.
            assertFalse(settings.settings.first().blindNavigation); assertTrue(settings.settings.first().breadcrumbTrail)
            vm.setBlindNavigation(true).join(); vm.setBreadcrumbTrail(false).join()
            nav = withTimeout(10_000) { vm.nav.first { it.blindNavigation && !it.breadcrumbTrail } }
            assertTrue(nav.blindNavigation); assertFalse(nav.breadcrumbTrail)
        } finally {
            settings.setBlindNavigation(true); settings.setBreadcrumbTrail(false)
        }
    }

    @Test fun navIsNotLoadedUntilTheStoresHaveAnswered() = runBlocking {
        assertFalse(NavUi.EMPTY.loaded)
        val vm = viewModel()
        val nav = withTimeout(10_000) { vm.nav.first { it.loaded } }
        assertTrue(nav.waypoints.isEmpty())
    }

    /** Needs `scripts/geo-feed.sh -72.2896 43.7022` running and the app on screen, as in LocationSourceTest. */
    @Test fun everyWaypointGetsADistanceWhileAFixIsLive() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            val near = vm.logWaypoint(hanover)
            val far = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
            vm.setTarget(far.id, false).join()
            val nav = withTimeout(60_000) { vm.nav.first { it.distances.size == 2 } }
            assertEquals(setOf(near.id, far.id), nav.distances.keys)
            val expected = distanceMeters(hanover.point, GeoPoint(43.7067, -72.2896))
            assertEquals(expected, nav.distances.getValue(far.id), 60.0)
            assertTrue(nav.distances.getValue(near.id) < 60.0)
            assertFalse(nav.targets.any { it.id == far.id })
        }
    }

    @Test fun noDistancesAreShownWhenNothingIsATargetAndSoNoFixIsLive() = runBlocking {
        val vm = viewModel()
        vm.logWaypoint(hanover)
        vm.setAllTargets(false).join()
        withTimeout(10_000) { vm.nav.first { it.loaded && it.waypoints.size == 1 && it.targets.isEmpty() } }
        // Watch for a few seconds, with the screen state being collected, so a fix that arrived late would show.
        withTimeoutOrNull(3_000) { vm.nav.collect { assertTrue(it.distances.isEmpty()) } }
        Unit
    }

    /** Needs the feed running and the app on screen. A fix that has arrived must not outlive the location updates. */
    @Test fun clearingTheTargetsRemovesTheDistancesAndTheFix() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            vm.logWaypoint(hanover)
            val live = withTimeout(60_000) { vm.nav.first { it.distances.isNotEmpty() && it.fixElapsedMs != null } }
            assertTrue(live.targets.isNotEmpty())
            val declinationWithFix = live.declination
            assertNotNull(declinationWithFix)
            vm.setAllTargets(false).join()
            val after = withTimeout(10_000) { vm.nav.first { it.targets.isEmpty() && it.distances.isEmpty() } }
            assertNull(after.fixElapsedMs)
            // The declination comes from the stored last position, so it stays.
            assertNotNull(after.declination)
        }
    }

    // One-press logging. The tests that save need the feed running and the app on screen, like the ones above.

    /** A view model whose location source answers that the permission is not held, as before the wearer grants it. */
    // The places new waypoints are given. The owner (2026-10-14), choosing "Skip red and blue (Recommended)": "New
    // waypoints never land within about ±25° of north's red or the pointer's blue; a place that would is moved on to
    // the next one. Existing waypoints keep their colors."

    private fun placeApart(a: Float, b: Float) = kotlin.math.abs(shortestDelta(a, b))

    @Test fun newWaypointsAreNotGivenPlacesNearNorthsRedOrThePointersBlue() = runBlocking {
        val vm = viewModel()
        val places = (0 until 10).map { i -> vm.logWaypoint(hanover.copy(point = GeoPoint(43.70 + i * 0.001, -72.29))).hue }
        for (place in places) {
            assertTrue("$place in $places", placeApart(place, hueOf(FALLBACK_NORTH)) >= SKIP_HUE_APART)
            assertTrue("$place in $places", placeApart(place, hueOf(FALLBACK_ACCENT)) >= SKIP_HUE_APART)
        }
        assertEquals(FIRST_WAYPOINT_PLACE, places.first())
        assertEquals(places.size, places.toSet().size)
        // Stored as given.
        assertEquals(places.toSet(), dao.observeAll().first().map { it.hue }.toSet())
    }

    @Test fun onAnInstallWithOlderWaypointsANewOneIsGivenAPlaceFarFromTheirsAndTheirsAreKept() = runBlocking {
        // Places numbered from 0, as before 2026-10-14: 0, 137.5, 275.0, 52.5, 190.0, 327.5, 105.0 and 242.6. The
        // numbering's next place, 108.3, is 3.3 degrees from 105.0.
        val older = (0 until 8).map { normalizeDeg(it * 137.508f) }
        older.forEachIndexed { i, hue ->
            dao.insert(com.infinite8looper.compass.data.Waypoint(name = "Old $i", lat = 43.7, lon = -72.29, accuracyM = 5f, createdAt = 1L + i, hue = hue, isTarget = false))
        }
        val vm = viewModel()
        val added = vm.logWaypoint(hanover)
        assertTrue("${added.hue}", older.all { placeApart(added.hue, it) >= 23f })
        assertEquals(older.toSet(), dao.observeAll().first().filter { it.id != added.id }.map { it.hue }.toSet())
    }

    @Test fun waypointsSavedTogetherAreGivenDifferentPlaces() = runBlocking {
        val vm = viewModel()
        val saved = withContext(Dispatchers.Main) {
            (0 until 6).map { i -> async { vm.logWaypoint(hanover.copy(point = GeoPoint(43.70 + i * 0.001, -72.29))) } }.map { it.await() }
        }
        assertEquals(saved.map { it.hue }.toString(), 6, saved.map { it.hue }.toSet().size)
    }

    @Test fun aColorChangeAndATargetChangeMadeTogetherBothLand() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        // As the edit screen makes them: on the main thread, one straight after the other, many times over.
        val jobs = withContext(Dispatchers.Main) {
            (0 until 20).flatMap { i -> listOf(vm.setHue(w.id, 10f + i), vm.setTarget(w.id, i % 2 == 0)) }
        }
        withTimeout(10_000) { jobs.joinAll() }
        val stored = dao.get(w.id)!!
        assertEquals(29f, stored.hue, 0f)
        assertFalse(stored.isTarget)
    }

    @Test fun aRenameAndAColorChangeMadeTogetherBothLand() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        val jobs = withContext(Dispatchers.Main) { listOf(vm.rename(w.id, "Camp"), vm.setHue(w.id, 200f), vm.setTarget(w.id, false)) }
        withTimeout(10_000) { jobs.joinAll() }
        val stored = dao.get(w.id)!!
        assertEquals("Camp", stored.name)
        assertEquals(200f, stored.hue, 0f)
        assertFalse(stored.isTarget)
    }

    @Test fun colorChangesInQuickSuccessionEndOnTheLast() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        val jobs = withContext(Dispatchers.Main) { (1..40).map { vm.setHue(w.id, it * 9f) } }
        withTimeout(10_000) { jobs.joinAll() }
        // 360 is the same color as 0, and is stored as 0.
        assertEquals(0f, dao.get(w.id)!!.hue, 0f)
    }

    @Test fun aRenameToNothingOrOnlySpacesKeepsTheName() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        withTimeout(10_000) { vm.rename(w.id, "Camp").join() }
        assertEquals("Camp", dao.get(w.id)!!.name)
        for (input in listOf("", "   ", " \n\t ", null)) {
            withTimeout(10_000) { vm.rename(w.id, input).join() }
            assertEquals("Camp", dao.get(w.id)!!.name)
        }
    }

    @Test fun aColorChangeForAWaypointThatWasDeletedBringsNothingBack() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        val jobs = withContext(Dispatchers.Main) { listOf(vm.setHue(w.id, 50f), vm.delete(w.id), vm.setHue(w.id, 80f), vm.rename(w.id, "Back")) }
        withTimeout(10_000) { jobs.joinAll() }
        assertNull(dao.get(w.id))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    private suspend fun viewModelWithoutPermission() =
        withContext(Dispatchers.Main) { CompassViewModel(app, LocationSource(app) { false }) }

    /** Presses, waits for that press's save to end, and returns its result, which has to be Saved. */
    private suspend fun pressAndAwaitSaved(vm: CompassViewModel): LogStatus.Saved {
        withTimeout(60_000) { vm.logHere().join() }
        return vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
    }

    /** Waits, without collecting the status, until it is Idle again, and returns how long that took. */
    private suspend fun msUntilIdle(vm: CompassViewModel, limitMs: Long = 10_000): Long {
        val start = SystemClock.elapsedRealtime()
        while (vm.logStatus.value != LogStatus.Idle) {
            assertTrue("still ${vm.logStatus.value} after $limitMs ms", SystemClock.elapsedRealtime() - start < limitMs)
            delay(25)
        }
        return SystemClock.elapsedRealtime() - start
    }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).readBytes()

    /**
     * Makes sure no position is in hand at the press that follows at once, so the save waits for one as in v2: v3
     * saves a position not older than 10 seconds at the press, the app's own or the location provider's last known
     * ("A position in hand, not older than 10 seconds at the press, is written at once"). Location is switched off for
     * 11 seconds, so no position comes from any source and every one held grows older than 10 seconds, and switched
     * on again; the press comes before the first new position can.
     */
    private suspend fun noPositionInHand() {
        shell("settings put secure location_mode 0")
        try { delay(11_000) } finally { shell("settings put secure location_mode 3") }
    }

    /** The view model the app's own screens use: the one the real activity holds. */
    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    @Test fun logHereGoesFromSavingToSavedAndStoresOneWaypoint() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            val seen = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { seen += it } }
            val saved = pressAndAwaitSaved(vm)

            val rows = dao.observeAll().first()
            assertEquals(1, rows.size)
            val row = rows.single()
            assertTrue(row.name, Regex("Waypoint [1-9][0-9]*").matches(row.name))
            assertEquals(row.name, saved.name)
            assertEquals(row.accuracyM, saved.accuracyM, 0f)
            assertTrue(row.isTarget)
            assertTrue("off by ${distanceMeters(hanover.point, GeoPoint(row.lat, row.lon))} m", distanceMeters(hanover.point, GeoPoint(row.lat, row.lon)) < 100.0)

            // The result goes once a screen reports that it has shown it for its time. A screen reports that only
            // after it has had the result, so the report is made here once the watcher has had it too: the status is
            // a state, which gives a watcher that is late only the latest value, and the press's result can come
            // and go before the main thread runs the watcher (seen on API 34: the watcher had Saving, then Idle).
            withTimeout(10_000) { while (saved !in seen) delay(10) }
            vm.logResultShown(vm.logPress.value)
            withTimeout(10_000) { vm.logStatus.first { it == LogStatus.Idle } }
            withTimeout(10_000) { while (seen.last() != LogStatus.Idle) delay(10) }
            watching.cancel()
            // Idle, then Saving with no accuracy yet, then only Saving until the one Saved, then Idle again.
            assertEquals(LogStatus.Idle, seen.first())
            assertEquals(LogStatus.Saving(null), seen[1])
            val savedAt = seen.indexOf(saved)
            assertTrue("$seen", savedAt >= 2 && seen.subList(1, savedAt).all { it is LogStatus.Saving })
            assertEquals(listOf(saved, LogStatus.Idle), seen.subList(savedAt, seen.size))
        }
    }

    @Test fun pressesWhileASaveIsInProgressSaveOneWaypoint() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // Two presses with nothing between them, and a third once the save is visibly under way.
            val first = withContext(Dispatchers.Main) { vm.logHere().also { vm.logHere() } }
            withTimeout(60_000) { vm.logStatus.first { it != LogStatus.Idle } }
            if (vm.logStatus.value is LogStatus.Saving) vm.logHere()
            withTimeout(60_000) { first.join() }
            val saved = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
            // A second save would need a fix of its own: leave it the time to arrive before counting.
            delay(3_000)
            assertEquals(saved, vm.logStatus.value)
            assertEquals(1, vm.logPress.value)
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
        }
    }

    @Test fun logHereWithoutThePermissionReportsItAndSavesNothing() = runBlocking {
        val vm = viewModelWithoutPermission()
        withTimeout(10_000) { vm.logHere().join() }
        assertEquals(LogStatus.Failed(LogFailure.NO_PERMISSION), vm.logStatus.value)
        assertTrue(dao.observeAll().first().isEmpty())
    }

    /** No screen shows this view model's status, as when the wearer is on another screen or the watch is asleep. */
    @Test fun aResultNothingHasShownStaysUntilItIsReportedShown() = runBlocking {
        val vm = viewModelWithoutPermission()
        withTimeout(10_000) { vm.logHere().join() }
        val failed = LogStatus.Failed(LogFailure.NO_PERMISSION)
        assertEquals(failed, vm.logStatus.value)
        // Well over the three seconds a failure is shown for, checked all the way.
        val until = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < until) {
            assertEquals(failed, vm.logStatus.value)
            delay(50)
        }
        vm.logResultShown(vm.logPress.value)
        assertEquals(LogStatus.Idle, vm.logStatus.value)
    }

    @Test fun aNewPressReplacesAWaitingResultAndTheOldOnesClearCannotRemoveIt() = runBlocking {
        val vm = viewModelWithoutPermission()
        val failed = LogStatus.Failed(LogFailure.NO_PERMISSION)
        withTimeout(10_000) { vm.logHere().join() }
        val first = vm.logPress.value
        assertEquals(failed, vm.logStatus.value)
        // The first result is seen once, and is still waiting to be reported shown when the second press comes.
        assertTrue(vm.logResultFirstSeen(first))
        assertFalse(vm.logResultFirstSeen(first))
        withTimeout(10_000) { vm.logHere().join() }
        val second = vm.logPress.value
        assertNotEquals(first, second)
        assertEquals(failed, vm.logStatus.value)
        // The second result reads the same as the first and is its own result all the same.
        assertFalse(vm.logResultFirstSeen(first))
        assertTrue(vm.logResultFirstSeen(second))
        assertFalse(vm.logResultFirstSeen(second))
        vm.logResultShown(first)
        assertEquals(failed, vm.logStatus.value)
        vm.logResultShown(second)
        assertEquals(LogStatus.Idle, vm.logStatus.value)
        // With nothing showing there is nothing to see or clear.
        assertFalse(vm.logResultFirstSeen(second))
    }

    @Test fun aPressWhileASavedResultWaitsStartsANewSave() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // Each save waits for a position taken after its press, so the second's is newer than the first's.
            noPositionInHand()
            val first = pressAndAwaitSaved(vm)
            val firstPress = vm.logPress.value
            // Well over the two seconds a saved result is shown for: nothing has shown it, so it waits.
            delay(4_000)
            assertEquals(first, vm.logStatus.value)
            noPositionInHand()
            assertEquals(first, vm.logStatus.value)
            val second = pressAndAwaitSaved(vm)
            assertNotEquals(first.name, second.name)
            assertTrue(second.fixElapsedMs > first.fixElapsedMs)
            assertEquals(setOf(first.name, second.name), dao.observeAll().first().map { it.name }.toSet())
            vm.logResultShown(firstPress)
            assertEquals(second, vm.logStatus.value)
            vm.logResultShown(vm.logPress.value)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
        }
    }

    /** The real compass screen, in view, shows the result of its own view model for about two seconds. */
    @Test fun theCompassInViewShowsASavedResultForItsTimeThenClearsIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val saved = pressAndAwaitSaved(vm)
            val shownFor = msUntilIdle(vm)
            assertTrue("shown for $shownFor ms", shownFor in 1_500..4_500)
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
        }
    }

    /**
     * The press comes with the app's screen not in view. Whatever the save ends in (the system gives an app that
     * is not on screen no location, so it is likely "No GPS fix" after the 20 seconds) waits, and is shown for its
     * full time once the compass is in view again.
     */
    @Test fun aResultThatArrivesWhileTheCompassIsNotInViewWaitsForIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(60_000) { vm.logHere().join() }
            val result = vm.logStatus.value
            val showMs = logStatusShowMs(result) ?: throw AssertionError("no result: $result")
            val until = SystemClock.elapsedRealtime() + showMs + 3_000
            while (SystemClock.elapsedRealtime() < until) {
                assertEquals(result, vm.logStatus.value)
                delay(50)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            val shownFor = msUntilIdle(vm)
            assertTrue("$result shown for $shownFor ms", shownFor in (showMs - 500)..(showMs + 2_500))
        }
    }

    /**
     * Real location updates run before each press, so fixes from before it exist and the location service holds one
     * as its last known position. v3 saves a fix up to 10 seconds old at the press ("A position in hand, not older
     * than 10 seconds at the press, is written at once"); one older than that is never saved, which is what this
     * checks: before each press the fixes are made older than 10 seconds ([noPositionInHand]). What is saved must be
     * newer than the press.
     */
    @Test fun aFixMoreThanTenSecondsBeforeThePressIsNeverSaved() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            val earlier = CopyOnWriteArrayList<Fix>()
            val running = launch(Dispatchers.Main) { LocationSource(app).updates(1_000).collect { earlier += it } }
            repeat(3) {
                val had = earlier.size
                withTimeout(60_000) { while (earlier.size < had + 2) delay(10) }
                val newestBefore = earlier.last().elapsedMs
                noPositionInHand()
                val pressedAt = SystemClock.elapsedRealtime()
                val saved = pressAndAwaitSaved(vm)
                assertTrue("the press came ${pressedAt - newestBefore} ms after a fix", newestBefore <= pressedAt)
                assertTrue(
                    "saved a fix from ${pressedAt - saved.fixElapsedMs} ms before the press",
                    saved.fixElapsedMs >= pressedAt,
                )
            }
            running.cancel()
            assertEquals(3, dao.observeAll().first().size)
        }
    }

    /** Whether the system's sensor service lists a listener of the app's heading source as an active connection. */
    private fun headingSensorRegistered(): Boolean =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys sensorservice"),
        ).use { it.readBytes().decodeToString() }.lineSequence().any { "sensors.HeadingSource" in it && "WakeLockRefCount" in it }

    @Test fun theHeadingSensorRunsOnlyWhileItIsWantedAndStopsTwoSecondsAfter() = runBlocking {
        val vm = viewModel()
        // Not asked for: nothing is registered and there is no reading.
        delay(500)
        assertFalse(headingSensorRegistered())
        assertNull(vm.heading.value)
        assertNull(vm.lastAccuracy.value)

        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        val reading = withTimeout(10_000) { vm.heading.first { it != null } }!!
        assertTrue(headingSensorRegistered())
        assertTrue("heading ${reading.magneticDeg}", reading.magneticDeg >= 0f && reading.magneticDeg < 360f)

        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        // It keeps running through the stop delay, so a quick change of screen does not stop and start it.
        delay(1_000)
        assertTrue(headingSensorRegistered())
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        delay(2_500)
        assertTrue(headingSensorRegistered())

        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        delay(3_500)
        assertFalse(headingSensorRegistered())
        // The last reading stays for the screens to show.
        assertNotNull(vm.heading.value)
    }

    @Test fun theLastAccuracyOutlivesTheSensor() = runBlocking {
        val vm = viewModel()
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        // The magnetometer's accuracy comes with its own first event, a little after the first heading.
        val accuracy = withTimeout(15_000) { vm.lastAccuracy.first { it != null } }!!
        assertTrue("accuracy $accuracy", accuracy in 0..3)
        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        delay(3_500)
        assertFalse(headingSensorRegistered())
        assertEquals(accuracy, vm.lastAccuracy.value)
    }

    /**
     * The first reading that arrives after the sensor is stopped and started again has an accuracy and a field
     * strength. The view model's flow first gives the reading from before the stop again, so that one is set aside
     * and the test waits for the next, which a new sensor event has made.
     *
     * What this shows on an emulator: a restart loses neither value. It does not show that the view model's
     * carry-over is what keeps them: on the API 35 emulator this test passed with the carry-over switched off as
     * well, because there the first new reading already came with both values. The gap the carry-over closes
     * (headings that arrive before the magnetometer's first event) is tested as a rule in NavigationTest
     * (carriedOver) and would make this test fail on a device that has it.
     */
    @Test fun theFirstNewReadingAfterARestartHasAnAccuracyAndAFieldStrength() = runBlocking {
        val vm = viewModel()
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        withTimeout(15_000) { vm.heading.first { it?.accuracy != null && it.fieldMicroTesla != null } }
        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        delay(3_500)
        assertFalse(headingSensorRegistered())
        val beforeTheStop = vm.heading.value
        assertNotNull(beforeTheStop)

        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        // A reading that is not the one from before the stop: the flow holds one value and gives a new one only
        // when it differs, so this is a reading the restarted sensor produced.
        val fresh = withTimeout(20_000) { vm.heading.first { it != null && it != beforeTheStop } }!!
        assertTrue(headingSensorRegistered())
        assertNotNull("the first new reading has no accuracy", fresh.accuracy)
        assertNotNull("the first new reading has no field strength", fresh.fieldMicroTesla)
        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
    }

    /**
     * The compass's heading source is switched for the running app (v3 spec, "Check screen": "A row switches the
     * compass's heading source for the running app"), and a view model starts on the fused one ("The compass keeps
     * the fused source until the owner has compared them on the watch").
     */
    @Test fun theHeadingSourceIsSwitchedForTheRunningAppAndStartsFused() = runBlocking {
        val vm = viewModel()
        assertEquals(com.infinite8looper.compass.nav.HeadingKind.FUSED, vm.headingKind.value)
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        try {
            for (kind in listOf(com.infinite8looper.compass.nav.HeadingKind.MAGNETIC, com.infinite8looper.compass.nav.HeadingKind.BLEND, com.infinite8looper.compass.nav.HeadingKind.FUSED)) {
                withContext(Dispatchers.Main) { vm.setHeadingKind(kind) }
                assertEquals(kind, vm.headingKind.value)
                val r = withTimeout(15_000) { vm.heading.first { it?.kind == kind } }!!
                val own = when (kind) {
                    com.infinite8looper.compass.nav.HeadingKind.FUSED -> r.fusedDeg
                    com.infinite8looper.compass.nav.HeadingKind.MAGNETIC -> r.geomagneticDeg
                    com.infinite8looper.compass.nav.HeadingKind.BLEND -> r.blendDeg
                }
                assertEquals("$kind: $r", own, r.magneticDeg, 0f)
            }
        } finally {
            withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        }
    }

    /**
     * The check screen shows "the three headings" live (v3 spec, "Check screen") whichever source the compass is on.
     * While it asks for all three, a reading of the fused source carries the magnetic heading and the blend as well;
     * once it no longer asks, the fused source runs alone again, as before the check screen, and they are not worked
     * out (not a number).
     */
    @Test fun whileAllThreeHeadingsAreAskedForAFusedReadingCarriesTheMagneticOneAndTheBlend() = runBlocking {
        val vm = viewModel()
        assertFalse(vm.allHeadings.value)
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        try {
            withContext(Dispatchers.Main) { vm.setAllHeadings(true) }
            assertTrue(vm.allHeadings.value)
            val all = withTimeout(15_000) { vm.heading.first { it != null && it.geomagneticDeg.isFinite() && it.blendDeg.isFinite() } }!!
            assertEquals("$all", com.infinite8looper.compass.nav.HeadingKind.FUSED, all.kind)
            assertEquals("$all", all.fusedDeg, all.magneticDeg, 0f)
            withContext(Dispatchers.Main) { vm.setAllHeadings(false) }
            // The readings that come once the sensors have been started again for the fused source alone.
            val alone = withTimeout(15_000) { vm.heading.first { it != null && it.geomagneticDeg.isNaN() } }!!
            assertTrue("$alone", alone.blendDeg.isNaN())
            assertEquals("$alone", com.infinite8looper.compass.nav.HeadingKind.FUSED, alone.kind)
        } finally {
            withContext(Dispatchers.Main) { vm.setAllHeadings(false); vm.setHeadingWanted(false) }
        }
    }

    // The breadcrumb trail. The tests that drop or reach a crumb need the feed running and the app on screen.

    private fun crumbAt(point: GeoPoint, t: Long = 1) = Crumb(lat = point.lat, lon = point.lon, accuracyM = 5f, createdAt = t)
    private val farNorth = GeoPoint(43.7067, -72.2896)

    /**
     * The values with repeats of the one before left out. The database's notifications can report a table as
     * changed when it holds what it held, so a list that is watched can be given twice running.
     */
    private fun <T> List<T>.runs(): List<T> = filterIndexed { i, v -> i == 0 || v != this[i - 1] }

    private suspend fun trailOf(vm: CompassViewModel, wanted: (TrailUi) -> Boolean): TrailUi =
        withTimeout(10_000) { vm.nav.first { it.loaded && wanted(it.trail) } }.trail

    @Test fun doublePressesStartATrailRetraceItAndReturnToLayingAndTheModeIsStored() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        assertEquals(TrailUi(), trailOf(vm) { true })
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailUi(TrailMode.LAYING, 0), trailOf(vm) { it.mode == TrailMode.LAYING })
        assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)

        val older = crumbDao.insert(crumbAt(farNorth)); val newest = crumbDao.insert(crumbAt(hanover.point, 2))
        assertEquals(2, trailOf(vm) { it.count == 2 }.count)
        withTimeout(10_000) { vm.trailDoublePress().join() }
        val retracing = trailOf(vm) { it.mode == TrailMode.RETRACING }
        assertEquals(crumbTargetId(newest), retracing.current?.id)
        assertEquals("Crumb 2", retracing.current?.name)
        assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
        // A view model made afresh, as after the app was closed, finds the trail as it was left.
        val later = trailOf(viewModel()) { it.count == 2 }
        assertEquals(TrailMode.RETRACING, later.mode); assertEquals(crumbTargetId(newest), later.current?.id)

        withTimeout(10_000) { vm.trailDoublePress().join() }
        val laying = trailOf(vm) { it.mode == TrailMode.LAYING }
        assertNull(laying.current); assertEquals(2, laying.count)
        assertEquals(listOf(newest, older), crumbDao.all().map { it.id })
    }

    @Test fun aDoublePressOnAnEmptyTrailEndsIt() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailMode.LAYING, trailOf(vm) { it.mode == TrailMode.LAYING }.mode)
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailUi(), trailOf(vm) { it.mode == TrailMode.OFF })
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        // Nothing was completed, so nothing says so.
        assertEquals(LogStatus.Idle, vm.logStatus.value)
    }

    @Test fun withTheSettingOffADoublePressDoesNothingAndSwitchingItOffEndsTheModeButKeepsTheCrumbs() = runBlocking {
        val vm = viewModel()
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        assertEquals(TrailUi(), trailOf(vm) { true })

        settings.setBreadcrumbTrail(true)
        val id = crumbDao.insert(crumbAt(farNorth))
        // The crumb is put in the table behind the app's back here, so the app is given the moment it needs to see it.
        trailOf(vm) { it.count == 1 }
        withTimeout(10_000) { vm.trailDoublePress().join(); vm.trailDoublePress().join() }
        assertEquals(TrailMode.RETRACING, trailOf(vm) { it.mode == TrailMode.RETRACING }.mode)
        withTimeout(10_000) { vm.setBreadcrumbTrail(false).join() }
        val off = withTimeout(10_000) { vm.nav.first { !it.breadcrumbTrail } }
        assertEquals(TrailMode.OFF, off.trail.mode); assertNull(off.trail.current); assertEquals(1, off.trail.count)
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        assertEquals(listOf(id), crumbDao.all().map { it.id })
        // On again, the trail is not taken up by itself.
        withTimeout(10_000) { vm.setBreadcrumbTrail(true).join() }
        assertEquals(TrailMode.OFF, withTimeout(10_000) { vm.nav.first { it.breadcrumbTrail } }.trail.mode)
    }

    @Test fun clearingTheTrailRemovesEveryCrumbEndsTheTrailAndLeavesTheWaypoints() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        repeat(4) { crumbDao.insert(crumbAt(GeoPoint(43.70 + it / 1000.0, -72.2896), it.toLong())) }
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(4, trailOf(vm) { it.mode == TrailMode.LAYING && it.count == 4 }.count)
        withTimeout(10_000) { vm.clearTrail().join() }
        // The screen state follows the two stores, the crumbs first.
        assertEquals(TrailUi(), trailOf(vm) { it.count == 0 && it.mode == TrailMode.OFF })
        assertTrue(crumbDao.all().isEmpty())
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        assertEquals(listOf(w.id), dao.observeAll().first().map { it.id })
    }

    @Test fun whileRetracingNoWaypointHasTheFocusItCannotBeMovedAndItReturnsAfterwards() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = farNorth))
        settings.setFocused(first.id)
        val crumb = crumbDao.insert(crumbAt(GeoPoint(43.6950, -72.3000)))
        trailOf(vm) { it.count == 1 }
        withTimeout(10_000) { vm.trailDoublePress().join(); vm.trailDoublePress().join() }
        val nav = withTimeout(10_000) { vm.nav.first { it.trail.mode == TrailMode.RETRACING } }
        // The waypoints are still targets, none of them focused; the crumb stands where the focused target does.
        assertEquals(listOf(second.id, first.id), nav.targets.map { it.id })
        assertTrue(nav.targets.none { it.focused })
        assertEquals(crumbTargetId(crumb), nav.focused?.id)
        assertTrue(nav.focused!!.isCrumb)
        withTimeout(10_000) { vm.moveFocus(1).join() }
        assertEquals(first.id, settings.settings.first().focusedId)

        withTimeout(10_000) { vm.trailDoublePress().join() }
        val after = withTimeout(10_000) { vm.nav.first { it.trail.mode == TrailMode.LAYING } }
        assertEquals(first.id, after.focused?.id)
        assertFalse(after.focused!!.isCrumb)
        withTimeout(10_000) { vm.moveFocus(1).join() }
        assertEquals(second.id, settings.settings.first().focusedId)
    }

    @Test fun aSinglePressWhileLayingDropsACrumbAndLogHereStillSavesAWaypoint() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            withTimeout(10_000) { vm.trailDoublePress().join() }
            withTimeout(60_000) { vm.sidePress().join() }
            val dropped = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
            assertEquals("Crumb 1", dropped.name)
            val crumbs = crumbDao.all()
            assertEquals(1, crumbs.size)
            assertTrue("off by ${distanceMeters(hanover.point, crumbs[0].toPoint().point)} m", distanceMeters(hanover.point, crumbs[0].toPoint().point) < 100.0)
            assertEquals(dropped.accuracyM, crumbs[0].accuracyM, 0f)
            assertTrue(dao.observeAll().first().isEmpty())

            withTimeout(60_000) { vm.sidePress().join() }
            assertEquals("Crumb 2", (vm.logStatus.value as LogStatus.Saved).name)
            assertEquals(2, crumbDao.all().size)
            assertTrue(crumbDao.all()[0].id > crumbDao.all()[1].id)
            assertTrue(dao.observeAll().first().isEmpty())

            // `Log here` saves a named waypoint as always, and no crumb.
            val saved = pressAndAwaitSaved(vm)
            assertTrue(saved.name, Regex("Waypoint [1-9][0-9]*").matches(saved.name))
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
            assertEquals(2, crumbDao.all().size)
        }
    }

    @Test fun aSinglePressSavesAWaypointWhenNoTrailIsBeingLaid() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // The setting is off; then on with no trail started; then retracing.
            for (prepare in listOf<suspend () -> Unit>(
                { },
                { settings.setBreadcrumbTrail(true) },
                { crumbDao.insert(crumbAt(farNorth)); settings.setTrailMode(TrailMode.RETRACING) },
            )) {
                prepare()
                val before = dao.observeAll().first().size
                withTimeout(60_000) { vm.sidePress().join() }
                val saved = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
                assertTrue(saved.name, saved.name.startsWith("Waypoint "))
                assertEquals(before + 1, dao.observeAll().first().size)
            }
            assertEquals(1, crumbDao.all().size)
        }
    }

    /**
     * The press is one that waits for a position, so that its fix can arrive after the trail was switched off: v3
     * saves a position in hand at once ("A position in hand, not older than 10 seconds at the press, is written at
     * once"), and a crumb from a position in hand was taken before the switch, so whether it is stored would depend
     * on which of the two reached the stores first. No position may be that recent at the press ([noPositionInHand]).
     */
    @Test fun aCrumbWhoseFixArrivesAfterTheTrailStoppedBeingLaidIsNotStored() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            withTimeout(10_000) { vm.trailDoublePress().join() }
            noPositionInHand()
            // The press is taken while laying; the trail is switched off before a fix taken since the press can arrive.
            val press = withContext(Dispatchers.Main) { vm.sidePress().also { assertTrue(vm.logStatus.value is LogStatus.Saving) } }
            settings.setBreadcrumbTrail(false)
            withTimeout(60_000) { press.join() }
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            assertTrue(crumbDao.all().isEmpty())
            assertTrue(dao.observeAll().first().isEmpty())
        }
    }

    // The three tests below use the view model of the real activity, with its compass on screen: every view model
    // that gets fixes while a trail is retraced consumes crumbs, and there should be one of them.

    @Test fun retracingConsumesTheCrumbTheWearerStandsOnOnceAndGoesOnToTheNext() = runBlocking {
        settings.setBreadcrumbTrail(true)
        // Oldest first: one far to the north, then two at the fed position, a meter or two apart.
        val far = crumbDao.insert(crumbAt(farNorth))
        val near = crumbDao.insert(crumbAt(GeoPoint(43.70221, -72.2896), 2))
        val nearest = crumbDao.insert(crumbAt(hanover.point, 3))
        settings.setTrailMode(TrailMode.RETRACING)
        // Watched from before the app is on screen, since the first fix can come within a moment of that.
        val seen = CopyOnWriteArrayList<List<Long>>()
        val watching = launch(Dispatchers.Main) { crumbDao.observeAll().collect { seen += it.map { c -> c.id } } }
        withTimeout(10_000) { while (seen.isEmpty()) delay(10) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val left = withTimeout(60_000) { vm.nav.first { it.loaded && it.trail.count == 1 } }
            // Each of the two near ones went by itself, the newest first, and the far one is now walked to.
            withTimeout(10_000) { while (seen.last() != listOf(far)) delay(10) }
            assertEquals(listOf(listOf(nearest, near, far), listOf(near, far), listOf(far)), seen.toList().runs())
            assertEquals(TrailMode.RETRACING, left.trail.mode)
            assertEquals(crumbTargetId(far), left.focused?.id)
            assertEquals("Crumb 1", left.focused?.name)
            // Standing still for several more fixes consumes nothing more.
            delay(12_000)
            watching.cancel()
            assertEquals(listOf(far), crumbDao.all().map { it.id })
            assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
            val now = withTimeout(10_000) { vm.nav.first { it.focused?.distanceM != null } }
            assertEquals(distanceMeters(hanover.point, farNorth), now.focused!!.distanceM!!, 60.0)
            assertFalse(now.focused.arrived)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
        }
    }

    @Test fun aCrumbFurtherBackIsNotConsumedWhileTheNewestIsStillAhead() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val here = crumbDao.insert(crumbAt(hanover.point))
        val ahead = crumbDao.insert(crumbAt(farNorth, 2))
        settings.setTrailMode(TrailMode.RETRACING)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            // Fixes are arriving: the crumb walked to has a distance. The wearer stands on the older crumb.
            val nav = withTimeout(60_000) { vm.nav.first { it.focused?.distanceM != null } }
            assertEquals(crumbTargetId(ahead), nav.focused?.id)
            delay(12_000)
            assertEquals(listOf(ahead, here), crumbDao.all().map { it.id })
        }
    }

    @Test fun arrivingAtTheLastCrumbCompletesTheTrailSaysSoAndGivesTheFocusBack() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val w = vm.logWaypoint(hanover.copy(point = farNorth))
            val statuses = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { statuses += it } }
            val counts = CopyOnWriteArrayList<Int>()
            val counting = launch(Dispatchers.Main) { crumbDao.observeAll().collect { counts += it.size } }
            withTimeout(10_000) { while (counts.isEmpty()) delay(10) }
            crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            // The crumb is there, and then it is not.
            withTimeout(60_000) { while (counts.toList().runs() != listOf(0, 1, 0)) delay(10) }
            counting.cancel()
            val done = withTimeout(10_000) { vm.nav.first { it.trail.count == 0 && it.trail.mode == TrailMode.OFF && it.focused != null } }
            assertTrue(crumbDao.all().isEmpty())
            // The stored mode is written straight after the crumb is deleted; the trail counts as off from the deletion.
            withTimeout(10_000) { while (settings.settings.first().trailMode != TrailMode.OFF) delay(10) }
            // The waypoint is untouched and has the focus again.
            assertEquals(listOf(w.id), dao.observeAll().first().map { it.id })
            assertEquals(w.id, done.focused?.id)
            assertNull(done.trail.current)
            // `Trail complete` is shown by the compass, which is in view, for about two seconds, and then cleared.
            withTimeout(10_000) { while (LogStatus.TrailComplete !in statuses) delay(10) }
            val shownFor = msUntilIdle(vm)
            withTimeout(10_000) { while (statuses.last() != LogStatus.Idle) delay(10) }
            watching.cancel()
            assertTrue("shown for $shownFor ms", shownFor in 1_000..4_500)
            assertEquals(listOf(LogStatus.Idle, LogStatus.TrailComplete, LogStatus.Idle), statuses.toList())
        }
    }

    /**
     * With no waypoint as a target, the trail is the only reason location runs, and reaching the last crumb ends
     * that reason in the middle of the work the fix set off. The work is finished all the same.
     */
    @Test fun arrivingAtTheLastCrumbWithNoTargetsCompletesTheTrailToo() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val statuses = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { statuses += it } }
            val counts = CopyOnWriteArrayList<Int>()
            val counting = launch(Dispatchers.Main) { crumbDao.observeAll().collect { counts += it.size } }
            withTimeout(10_000) { while (counts.isEmpty()) delay(10) }
            crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            withTimeout(60_000) { while (counts.toList().runs() != listOf(0, 1, 0)) delay(10) }
            counting.cancel()
            withTimeout(10_000) { while (settings.settings.first().trailMode != TrailMode.OFF) delay(10) }
            withTimeout(10_000) { while (LogStatus.TrailComplete !in statuses) delay(10) }
            val shownFor = msUntilIdle(vm)
            withTimeout(10_000) { while (statuses.last() != LogStatus.Idle) delay(10) }
            watching.cancel()
            assertTrue("shown for $shownFor ms", shownFor in 1_000..4_500)
            assertEquals(listOf(LogStatus.Idle, LogStatus.TrailComplete, LogStatus.Idle), statuses.toList())
            assertTrue(dao.observeAll().first().isEmpty())
        }
    }

    /** Sends Back to the real activity, which turns the row of screens one screen toward settings, and lets it come to rest. */
    private suspend fun back() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        delay(1_500)
    }

    /**
     * A waypoint is a target, so fixes keep arriving on every screen. The wearer stands on the crumb walked to with
     * settings, then the waypoints, in view: nothing is consumed. Back on the compass, it is.
     */
    @Test fun aCrumbIsConsumedOnlyWhileTheCompassIsInView() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            vm.logWaypoint(hanover.copy(point = farNorth))
            delay(1_500)
            back()   // settings
            val crumb = crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            // Fixes are arriving there: the waypoint has a distance that keeps being worked out.
            withTimeout(60_000) { vm.nav.first { it.trail.mode == TrailMode.RETRACING && it.distances.isNotEmpty() && it.focused?.distanceM != null } }
            delay(12_000)
            assertEquals(listOf(crumb), crumbDao.all().map { it.id })
            back()   // waypoints
            delay(8_000)
            assertEquals(listOf(crumb), crumbDao.all().map { it.id })
            assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            back()   // the compass
            withTimeout(30_000) { while (crumbDao.all().isNotEmpty()) delay(20) }
            withTimeout(10_000) { while (settings.settings.first().trailMode != TrailMode.OFF) delay(10) }
        }
    }

    /**
     * The last crumb is reached while a save is in progress or its result is showing. The save keeps its status,
     * and `Trail complete` is shown after it, once.
     *
     * The save is one that waits for a position, so that the crumb is reached while it waits or while its result
     * shows: v3 saves a position in hand at once ("A position in hand, not older than 10 seconds at the press, is
     * written at once"), and no position may be that recent at the press ([noPositionInHand]).
     */
    @Test fun trailCompleteThatFallsDuringASaveIsShownAfterItOnce() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            delay(1_500)
            val statuses = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { statuses += it } }
            crumbDao.insert(crumbAt(hanover.point))
            trailOf(vm) { it.count == 1 }
            noPositionInHand()
            // A waypoint is saved with `Log here`, and the trail is switched to retracing while that save runs.
            val save = withContext(Dispatchers.Main) { vm.logHere().also { assertTrue(vm.logStatus.value is LogStatus.Saving) } }
            settings.setTrailMode(TrailMode.RETRACING)
            withTimeout(60_000) { save.join() }
            withTimeout(30_000) { while (crumbDao.all().isNotEmpty()) delay(20) }
            withTimeout(30_000) { while (LogStatus.TrailComplete !in statuses) delay(10) }
            withTimeout(10_000) { while (statuses.last() != LogStatus.Idle) delay(10) }
            delay(3_000)
            watching.cancel()
            val seen = statuses.toList()
            // Saving, then the saved result, then `Trail complete`, then nothing: each in its turn, the last two once.
            assertEquals("$seen", 1, seen.count { it == LogStatus.TrailComplete })
            assertEquals("$seen", 1, seen.count { it is LogStatus.Saved })
            val saved = seen.indexOfFirst { it is LogStatus.Saved }
            assertTrue("$seen", seen.subList(1, saved).all { it is LogStatus.Saving })
            assertEquals("$seen", listOf(LogStatus.TrailComplete, LogStatus.Idle), seen.subList(saved + 1, seen.size))
            assertEquals(LogStatus.Idle, seen.first())
            assertEquals(1, dao.observeAll().first().size)
        }
    }

    @Test fun withoutThePermissionTheScreenStateSaysNoPositionCanCome() = runBlocking {
        val without = viewModelWithoutPermission()
        assertEquals(LogFailure.NO_PERMISSION, withTimeout(10_000) { without.nav.first { it.loaded } }.noPosition)
        // With it, and location switched on as the tests have it, a position can come.
        val with = viewModel()
        assertNull(withTimeout(10_000) { with.nav.first { it.loaded } }.noPosition)
    }

    /**
     * The app is on screen but not in front, as under one of the system's own screens: fixes still arrive for the
     * waypoint that is a target, and the crumb the wearer stands on stays. In front again, it is consumed.
     */
    @Test fun aCrumbIsNotConsumedWhileTheAppIsNotInFront() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            vm.logWaypoint(hanover.copy(point = farNorth))
            delay(1_500)
            scenario.moveToState(Lifecycle.State.STARTED)
            // The view model has been told by the time the activity is paused, with no frame waited for.
            assertFalse(vm.compassInView.value)
            val crumb = crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            val fixBefore = withTimeout(60_000) { vm.nav.first { it.trail.mode == TrailMode.RETRACING && it.fixElapsedMs != null } }.fixElapsedMs
            // Fixes go on arriving: a later one than the first is seen.
            withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != fixBefore } }
            delay(8_000)
            assertEquals(listOf(crumb), crumbDao.all().map { it.id })
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(5_000) { vm.compassInView.first { it } }
            withTimeout(30_000) { while (crumbDao.all().isNotEmpty()) delay(20) }
        }
    }

    /**
     * The app is left for Home, where the activity is paused and stopped one right after the other and no frame is
     * drawn in between. The view model has been told the compass is out of view when the stop is announced, each
     * time, and is told it is in view again when the app is back in front. The crumb the wearer stands on stays while
     * the app is at Home, whatever fixes the system still delivers there, and is consumed once the app is in front.
     */
    @Test fun aCrumbIsNotConsumedWhileTheAppIsAtHome() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            vm.logWaypoint(hanover.copy(point = farNorth))
            withTimeout(10_000) { vm.compassInView.first { it } }
            // What the view model has been told at the moment the activity is stopped, read from inside the
            // lifecycle's own announcement of the stop, where no frame can have come since the pause before it
            // unless one already had.
            val toldAtStop = CopyOnWriteArrayList<Boolean>()
            scenario.onActivity { activity ->
                activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) toldAtStop += vm.compassInView.value })
            }
            repeat(5) { round ->
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse("round $round", vm.compassInView.value)
                scenario.moveToState(Lifecycle.State.RESUMED)
                withTimeout(5_000) { vm.compassInView.first { it } }
            }
            assertEquals(List(5) { false }, toldAtStop.toList())
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(vm.compassInView.value)
            val crumb = crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            // The screens stop watching the screen state at Home, and fixes come only while it is watched: here it
            // is watched, so that any fix the system delivers to an app at Home reaches the trail.
            val watching = launch(Dispatchers.Main) { vm.nav.collect { } }
            withTimeout(60_000) { vm.nav.first { it.trail.mode == TrailMode.RETRACING } }
            delay(20_000)
            assertFalse(vm.compassInView.value)
            assertEquals(listOf(crumb), crumbDao.all().map { it.id })
            watching.cancel()
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(30_000) { while (crumbDao.all().isNotEmpty()) delay(20) }
        }
    }

    /**
     * Runs [block] while the crumbs cannot be read: the `crumbs` table is dropped from the app's own database file
     * through a second connection, with the app's database open, so the app's next read of it fails as a read of a
     * damaged file would. [block] is given the way to put the table back, made by the statements the file itself
     * held for it; it is put back afterwards in any case.
     */
    private suspend fun whileTheCrumbsCannotBeRead(block: suspend (putBack: () -> Unit) -> Unit) {
        val params = SQLiteDatabase.OpenParams.Builder().setOpenFlags(SQLiteDatabase.OPEN_READWRITE).setJournalMode("TRUNCATE").build()
        SQLiteDatabase.openDatabase(app.getDatabasePath(WaypointDatabase.FILE_NAME), params).use { raw ->
            val made = raw.rawQuery("SELECT sql FROM sqlite_master WHERE tbl_name = 'crumbs' AND sql IS NOT NULL ORDER BY type = 'table' DESC", null)
                .use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertTrue("$made", made.first().startsWith("CREATE TABLE"))
            var gone = false
            val putBack = { if (gone) { made.forEach(raw::execSQL); gone = false } }
            raw.execSQL("DROP TABLE crumbs")
            gone = true
            try { block(putBack) } finally { putBack() }
        }
    }

    /** With the breadcrumb trail setting off, crumbs that cannot be read hold up nothing: the screen state, a save, the focus. */
    @Test fun withTheSettingOffCrumbsThatCannotBeReadHoldUpNothing() = runBlocking {
        val first = dao.insert(com.infinite8looper.compass.data.Waypoint(name = "A", lat = farNorth.lat, lon = farNorth.lon, accuracyM = 5f, createdAt = 1, hue = 0f, isTarget = true))
        val second = dao.insert(com.infinite8looper.compass.data.Waypoint(name = "B", lat = farNorth.lat, lon = farNorth.lon, accuracyM = 5f, createdAt = 2, hue = 90f, isTarget = true))
        // Retracing is stored from an earlier trail; with the setting off it means nothing.
        settings.setTrailMode(TrailMode.RETRACING)
        try { whileTheCrumbsCannotBeRead {
            // The app is in front, as it has to be for a position fix to come.
            ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // The read really fails: the store's own read throws.
            assertNotNull(runCatching { crumbDao.all() }.exceptionOrNull())
            val ui = withTimeout(5_000) { vm.nav.first { it.loaded } }
            assertEquals(setOf(first, second), ui.waypoints.map { it.id }.toSet())
            assertEquals(TrailMode.OFF, ui.trail.mode)
            // The crown moves the focus at once.
            assertEquals(second, withTimeout(5_000) { vm.nav.first { it.focused != null } }.focused?.id)
            val started = SystemClock.elapsedRealtime()
            withTimeout(5_000) { vm.moveFocus(1).join() }
            assertTrue("${SystemClock.elapsedRealtime() - started} ms", SystemClock.elapsedRealtime() - started < 1_000)
            assertEquals(first, settings.settings.first().focusedId)
            // The side button saves a waypoint, and is saving as soon as it is pressed.
            val save = withContext(Dispatchers.Main) { vm.sidePress().also { assertTrue(vm.logStatus.value is LogStatus.Saving) } }
            withTimeout(60_000) { save.join() }
            assertTrue("${vm.logStatus.value}", vm.logStatus.value is LogStatus.Saved)
            assertEquals(3, dao.observeAll().first().size)
            }
        } } finally { settings.setTrailMode(TrailMode.OFF) }
    }

    /**
     * With the crumbs unread, the screens show what the stores hold while the setting is off, go back to waiting when
     * it is switched on (an unread trail is not shown as an empty one), and show the stores again when it is off.
     */
    @Test fun withTheCrumbsUnreadSwitchingTheSettingOnMakesTheScreensWaitAndOffShowsThemAgain() = runBlocking {
        val id = dao.insert(com.infinite8looper.compass.data.Waypoint(name = "A", lat = farNorth.lat, lon = farNorth.lon, accuracyM = 5f, createdAt = 1, hue = 0f, isTarget = true))
        settings.setTrailMode(TrailMode.LAYING)
        try { whileTheCrumbsCannotBeRead {
            val vm = viewModel()
            val seen = CopyOnWriteArrayList<NavUi>()
            val watching = launch(Dispatchers.Main) { vm.nav.collect { seen += it } }
            assertEquals(listOf(id), withTimeout(5_000) { vm.nav.first { it.loaded } }.waypoints.map { it.id })
            settings.setBreadcrumbTrail(true)
            withTimeout(5_000) { vm.nav.first { !it.loaded } }
            delay(1_500)
            assertFalse(vm.nav.value.loaded)
            // At no time was the trail shown as one being laid with no crumbs.
            assertTrue(seen.none { it.loaded && it.breadcrumbTrail })
            settings.setBreadcrumbTrail(false)
            assertEquals(listOf(id), withTimeout(5_000) { vm.nav.first { it.loaded } }.waypoints.map { it.id })
            watching.cancel()
        } } finally { settings.setTrailMode(TrailMode.OFF) }
    }

    /** The settings screen's rows, as an accessibility service is given them by the real activity. */
    private val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun allNodes(): List<android.view.accessibility.AccessibilityNodeInfo> = buildList {
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null) return
            add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(automation.rootInActiveWindow)
    }

    private fun wordsUnder(n: android.view.accessibility.AccessibilityNodeInfo?): List<String> =
        if (n == null) emptyList() else listOfNotNull(n.text?.toString()) + (0 until n.childCount).flatMap { wordsUnder(n.getChild(it)) }

    /** The row of the settings list that carries [label], scrolling the list toward its end until the row is on screen. */
    private suspend fun settingsRow(label: String): android.view.accessibility.AccessibilityNodeInfo {
        // What each step had on screen, for the message of a failure.
        val seen = ArrayList<String>()
        repeat(12) {
            // A row is the switch, and its two lines of words are under it.
            allNodes().firstOrNull { n -> n.isCheckable && wordsUnder(n).any { it.contains(label) } }?.let { return it }
            seen += allNodes().filter { it.isCheckable }.joinToString("|") { wordsUnder(it).firstOrNull().orEmpty() }
            // The row of three screens scrolls too, sideways; the list is the scrolling thing inside it.
            val list = allNodes().lastOrNull { it.isScrollable && it.isVisibleToUser } ?: throw AssertionError("no list on screen")
            list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(700)
        }
        throw AssertionError("no row '$label' in $seen")
    }

    /**
     * The crumbs cannot be read and the breadcrumb trail setting is on, so the screens the trail belongs to wait. The
     * settings do not: the trail's row shows the stored value, on, and takes a tap, which switches the setting off
     * and with that ends the wait. The tap is the row's own click, made the way an accessibility service makes it,
     * on the real activity with the settings in view.
     */
    @Test fun withTheCrumbsUnreadableTheTrailSwitchShowsItsStoredValueAndATapOnItSwitchesItOff() = runBlocking {
        val id = dao.insert(com.infinite8looper.compass.data.Waypoint(name = "A", lat = farNorth.lat, lon = farNorth.lon, accuracyM = 5f, createdAt = 1, hue = 0f, isTarget = true))
        settings.setBreadcrumbTrail(true)
        settings.setTrailMode(TrailMode.LAYING)
        try { whileTheCrumbsCannotBeRead {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val vm = viewModelOf(scenario)
                delay(1_500)
                // The read really fails, and the screens that show the trail are waiting for it.
                assertNotNull(runCatching { crumbDao.all() }.exceptionOrNull())
                assertFalse(vm.nav.value.loaded)
                // The switches are there all the same, with what is stored.
                assertEquals(true, withTimeout(5_000) { vm.switches.first { it != null } }?.breadcrumbTrail)
                back()   // the settings
                val row = settingsRow("Breadcrumb trail")
                assertTrue("the row takes no tap", row.isEnabled)
                assertTrue("the row shows off", row.isChecked)
                // Another row is live too.
                assertTrue(settingsRow("Blind navigation").isEnabled)
                assertTrue(settingsRow("Breadcrumb trail").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(5_000) { while (settings.settings.first().breadcrumbTrail) delay(20) }
                assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
                // The crumbs are still unreadable; with the setting off nothing waits for them.
                assertNotNull(runCatching { crumbDao.all() }.exceptionOrNull())
                assertEquals(listOf(id), withTimeout(5_000) { vm.nav.first { it.loaded } }.waypoints.map { it.id })
                withTimeout(5_000) { while (settingsRow("Breadcrumb trail").isChecked) delay(50) }
            }
        } } finally { settings.setTrailMode(TrailMode.OFF) }
    }

    /**
     * A view model reads the trail's stored mode and switch as it is made, with no screen watching it and no press
     * made: a little later the store answers from memory, at once, which is what the first press of a process needs.
     * The store here is a new one on a file an earlier store wrote, so nothing of it has been read before.
     */
    @Test fun aNewViewModelHasTheStoredTrailReadBeforeTheFirstPress() = runBlocking {
        val file = "test-settings-${java.util.UUID.randomUUID()}"
        val files = listOf(file, "$file-position").map { java.io.File(app.filesDir, "datastore/$it.preferences_pb") }
        val writerJob = kotlinx.coroutines.SupervisorJob()
        val readerJob = kotlinx.coroutines.SupervisorJob()
        try {
            SettingsStore(app, file, kotlinx.coroutines.CoroutineScope(Dispatchers.IO + writerJob)).run { setBreadcrumbTrail(true); setTrailMode(TrailMode.LAYING) }
            writerJob.cancel(); writerJob.join()
            val store = SettingsStore(app, file, kotlinx.coroutines.CoroutineScope(Dispatchers.IO + readerJob))
            withContext(Dispatchers.Main) { CompassViewModel(app, LocationSource(app), store) }
            // Nothing is asked of the store meanwhile.
            delay(2_000)
            val answer = withContext(Dispatchers.Main) {
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.storedTrail() }.also { assertTrue("the answer had to be waited for", it.isCompleted) }
            }
            assertEquals(com.infinite8looper.compass.data.StoredTrail(TrailMode.LAYING, settingOn = true), answer.await())
        } finally {
            writerJob.cancel(); readerJob.cancel(); readerJob.join()
            files.forEach { it.delete() }
        }
    }

    /**
     * With the setting on and a trail being laid, a press of the side button while the crumbs cannot be read does not
     * look for a position fix first, and holds up no change to a waypoint while it waits for the crumbs.
     */
    @Test fun withTheSettingOnAndLayingAPressThatCannotReadTheTrailLooksForNoFixAndHoldsUpNoEdit() = runBlocking {
        val id = dao.insert(com.infinite8looper.compass.data.Waypoint(name = "A", lat = farNorth.lat, lon = farNorth.lon, accuracyM = 5f, createdAt = 1, hue = 0f, isTarget = true))
        settings.setBreadcrumbTrail(true)
        settings.setTrailMode(TrailMode.LAYING)
        try { whileTheCrumbsCannotBeRead {
            // The app is in front, so a fix would come if one were looked for.
            ActivityScenario.launch(MainActivity::class.java).use {
                val vm = viewModel()
                delay(1_500)
                val statuses = CopyOnWriteArrayList<LogStatus>()
                val watching = launch(Dispatchers.Main) { vm.logStatus.collect { statuses += it } }
                val started = SystemClock.elapsedRealtime()
                val press = withContext(Dispatchers.Main) { vm.sidePress() }
                // Changes to a waypoint are made one after another for as long as the press lasts, and each is timed.
                var slowest = 0L
                var edits = 0
                while (press.isActive) {
                    val at = SystemClock.elapsedRealtime()
                    withTimeout(10_000) { vm.rename(id, "A${edits++}").join() }
                    slowest = maxOf(slowest, SystemClock.elapsedRealtime() - at)
                    delay(50)
                }
                val took = SystemClock.elapsedRealtime() - started
                delay(200)
                watching.cancel()
                assertEquals(LogStatus.Failed(LogFailure.SAVE_FAILED), vm.logStatus.value)
                // No fix was looked at: the status never carried a fix's accuracy.
                assertTrue("$statuses", statuses.none { it is LogStatus.Saving && it.accuracyM != null })
                assertTrue("slowest edit $slowest ms of $edits", slowest < 1_000)
                assertTrue("$took ms", took < 3_000)
                assertEquals(1, dao.observeAll().first().size)
            }
        } } finally { settings.setTrailMode(TrailMode.OFF) }
    }

    /**
     * With the setting on and a trail being laid, a press of the side button while the crumbs cannot be read ends in
     * `Couldn't save` and stores nothing; `Log here` still saves a waypoint. The reading is tried again, so once the
     * table is back a press drops a crumb.
     */
    @Test fun withTheSettingOnAPressWhileTheCrumbsCannotBeReadCouldNotSaveAndTheReadIsTriedAgain() = runBlocking {
        settings.setBreadcrumbTrail(true)
        settings.setTrailMode(TrailMode.LAYING)
        try { whileTheCrumbsCannotBeRead { putBack ->
            ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            assertNotNull(runCatching { crumbDao.all() }.exceptionOrNull())
            withTimeout(60_000) { vm.sidePress().join() }
            assertEquals(LogStatus.Failed(LogFailure.SAVE_FAILED), vm.logStatus.value)
            assertEquals(0, dao.observeAll().first().size)
            // `Log here` saves a waypoint whatever the trail is doing.
            assertTrue(pressAndAwaitSaved(vm).name.startsWith("Waypoint"))
            assertEquals(1, dao.observeAll().first().size)
            putBack()
            assertEquals(emptyList<Long>(), crumbDao.all().map { it.id })
            // The view model's own reading comes right without anything being done to it: the trail shows up as laid, with no crumbs.
            assertEquals(0, withTimeout(60_000) { vm.nav.first { it.loaded && it.trail.mode == TrailMode.LAYING } }.trail.count)
            withTimeout(60_000) { vm.sidePress().join() }
            assertEquals("Crumb 1", (vm.logStatus.value as? LogStatus.Saved)?.name)
            assertEquals(1, crumbDao.all().size)
            // The table that was put back is watched like the one before: the new crumb reaches the screen state.
            trailOf(vm) { it.count == 1 }
            }
        } } finally { settings.setTrailMode(TrailMode.OFF) }
    }

    /**
     * `Trail complete` is showing when the wearer leaves the compass. It is gone at once, on no screen, and does not
     * come back when the compass is in view again.
     */
    @Test fun trailCompleteIsDroppedWhenTheCompassIsLeftAndDoesNotComeBack() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            delay(1_500)
            val statuses = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { statuses += it } }
            crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            withTimeout(60_000) { while (LogStatus.TrailComplete !in statuses) delay(5) }
            val shownAt = SystemClock.elapsedRealtime()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            // Gone well before its two seconds are up: it was dropped, not shown out.
            withTimeout(5_000) { while (vm.logStatus.value != LogStatus.Idle) delay(5) }
            val goneAfter = SystemClock.elapsedRealtime() - shownAt
            assertTrue("gone after $goneAfter ms", goneAfter < 1_400)
            delay(1_500)
            back(); back()   // waypoints, then the compass again
            delay(4_000)
            watching.cancel()
            assertEquals(listOf(LogStatus.Idle, LogStatus.TrailComplete, LogStatus.Idle), statuses.toList())
        }
    }

    /**
     * `Trail complete` is waiting to be shown (no screen of this view model shows anything) when a new trail is
     * started: it is dropped. A result of logging that waits is not.
     */
    @Test fun aNewTrailDropsATrailCompleteThatWasWaiting() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            // The app is on screen so that fixes come, with settings in view: the activity's own view model has
            // no compass in view and consumes nothing. The test's view model is told its compass is in view.
            delay(1_500)
            back()
            val vm = viewModel()
            crumbDao.insert(crumbAt(hanover.point))
            trailOf(vm) { it.count == 1 }
            settings.setTrailMode(TrailMode.RETRACING)
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(true); vm.setCompassInView(true) }
            // Fixes come to a view model only while its screen state is being watched, as a screen watches it.
            val watching = launch(Dispatchers.Main) { vm.nav.collect { } }
            withTimeout(60_000) { while (crumbDao.all().isNotEmpty()) delay(20) }
            withTimeout(10_000) { vm.logStatus.first { it == LogStatus.TrailComplete } }
            watching.cancel()
            delay(3_000)
            assertEquals(LogStatus.TrailComplete, vm.logStatus.value)
            withTimeout(10_000) { vm.trailDoublePress().join() }
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)
        }
    }

    @Test fun aTrailBringsPositionFixesOnlyWhileTheCompassIsOnScreen() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            // A view model of the test's own: no screen tells it where the compass is.
            val vm = viewModel()
            crumbDao.insert(crumbAt(farNorth))
            settings.setTrailMode(TrailMode.LAYING)
            withTimeout(10_000) { vm.nav.first { it.loaded && it.trail.mode == TrailMode.LAYING } }
            // No waypoint is a target and the compass is not on screen: no fix, watched for a few seconds.
            withTimeoutOrNull(4_000) { vm.nav.collect { assertNull(it.fixElapsedMs); assertTrue(it.trail.tickAngles.isEmpty()) } }
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
            val live = withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null } }
            // The crumb to the north is a tick at the top of a dial that shows true north.
            assertEquals(listOf(0f), withTimeout(10_000) { vm.nav.first { it.declination != null && it.trail.tickAngles.isNotEmpty() } }.trail.tickAngles)
            assertNotNull(live.fixElapsedMs)
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(false) }
            val gone = withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            assertTrue(gone.trail.tickAngles.isEmpty())
            // And not at all once the trail is off.
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
            withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null } }
            settings.setTrailMode(TrailMode.OFF)
            withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            Unit
        }
    }

    /**
     * With `Show coordinates` on and no waypoint a target, position fixes come while the compass is on screen and
     * only then, and the screen state carries where the watch is. With the setting off none come. Needs the feed
     * at 43.7022, -72.2896.
     */
    @Test fun theCoordinatesBringPositionFixesOnlyWhileTheSettingIsOnAndTheCompassIsOnScreen() = runBlocking {
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                // A view model of the test's own: no screen tells it where the compass is.
                val vm = viewModel()
                settings.setShowCoordinates(true)
                withTimeout(10_000) { vm.nav.first { it.loaded && it.showCoordinates } }
                withTimeoutOrNull(4_000) { vm.nav.collect { assertNull(it.position); assertNull(it.fixElapsedMs) } }
                withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
                val live = withTimeout(60_000) { vm.nav.first { it.position != null } }
                assertTrue(live.targets.isEmpty())
                assertEquals("43.7022° N", com.infinite8looper.compass.nav.formatLatitude(live.position!!.lat))
                assertEquals("72.2896° W", com.infinite8looper.compass.nav.formatLongitude(live.position.lon))
                assertNotNull(live.fixElapsedMs)
                withContext(Dispatchers.Main) { vm.setCompassOnScreen(false) }
                withTimeout(10_000) { vm.nav.first { it.position == null } }
                withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
                withTimeout(60_000) { vm.nav.first { it.position != null } }
                settings.setShowCoordinates(false)
                withTimeout(10_000) { vm.nav.first { it.position == null && !it.showCoordinates } }
                withTimeoutOrNull(4_000) { vm.nav.collect { assertNull(it.position) } }
                Unit
            }
        } finally { settings.setShowCoordinates(false) }
    }

    /** The switch is stored on and the permission is not held: no position comes, and the screen state says why. */
    @Test fun withoutThePermissionTheCoordinatesBringNoPosition() = runBlocking {
        try {
            settings.setShowCoordinates(true)
            ActivityScenario.launch(MainActivity::class.java).use {
                val vm = viewModelWithoutPermission()
                withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
                val ui = withTimeout(10_000) { vm.nav.first { it.loaded && it.showCoordinates } }
                assertEquals(LogFailure.NO_PERMISSION, ui.noPosition)
                withTimeoutOrNull(6_000) { vm.nav.collect { assertNull(it.position); assertNull(it.fixElapsedMs) } }
                val now = vm.nav.value
                val lines = com.infinite8looper.compass.nav.readout(
                    LogStatus.Idle, now.units, com.infinite8looper.compass.nav.FocusKind.NONE, null, false, false, now.position, false, null,
                    now.noPosition, showCoordinates = true, holdFlat = false,
                )
                assertNull(lines.latitude); assertNull(lines.longitude)
            }
        } finally { settings.setShowCoordinates(false) }
    }

    @Test fun aTargetTheWearerStandsOnHasArrivedAndHasNoBearing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            vm.logWaypoint(hanover)
            val nav = withTimeout(60_000) { vm.nav.first { it.focused?.distanceM != null } }
            assertTrue("${nav.focused!!.distanceM} m", nav.focused.arrived)
            assertNull(nav.focused.dialAngleDeg)
        }
    }
}
