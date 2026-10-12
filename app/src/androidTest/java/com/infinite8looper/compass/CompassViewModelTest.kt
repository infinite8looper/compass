package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.LogFailure
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.distanceMeters
import com.infinite8looper.compass.nav.logStatusShowMs
import com.infinite8looper.compass.sensors.LocationSource
import kotlinx.coroutines.Dispatchers
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

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
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

            // The result goes once a screen reports that it has shown it for its time.
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
            val first = pressAndAwaitSaved(vm)
            val firstPress = vm.logPress.value
            // Well over the two seconds a saved result is shown for: nothing has shown it, so it waits.
            delay(4_000)
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
     * Real location updates run before each press, so fixes from before it exist and the location service has a
     * recent one to hand to a new request. Each press comes straight after such a fix. What is saved must be newer.
     */
    @Test fun aFixFromBeforeThePressIsNeverSaved() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            val earlier = CopyOnWriteArrayList<Fix>()
            val running = launch(Dispatchers.Main) { LocationSource(app).updates(1_000).collect { earlier += it } }
            repeat(3) {
                val had = earlier.size
                withTimeout(60_000) { while (earlier.size < had + 2) delay(10) }
                val newestBefore = earlier.last().elapsedMs
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
}
