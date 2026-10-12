package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.os.SystemClock
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
import com.infinite8looper.compass.sensors.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    private suspend fun viewModelWithoutPermission() =
        withContext(Dispatchers.Main) { CompassViewModel(app, LocationSource(app) { false }) }

    private suspend fun awaitSaved(vm: CompassViewModel) =
        withTimeout(60_000) { vm.logStatus.first { it is LogStatus.Saved || it is LogStatus.Failed } } as? LogStatus.Saved
            ?: throw AssertionError("not saved: ${vm.logStatus.value}")

    /** Waits, without collecting the status, until it is Idle again, and returns how long that took. */
    private suspend fun msUntilIdle(vm: CompassViewModel, limitMs: Long = 8_000): Long {
        val start = SystemClock.elapsedRealtime()
        while (vm.logStatus.value != LogStatus.Idle) {
            assertTrue("still ${vm.logStatus.value} after $limitMs ms", SystemClock.elapsedRealtime() - start < limitMs)
            delay(25)
        }
        return SystemClock.elapsedRealtime() - start
    }

    @Test fun logHereGoesFromSavingToSavedAndStoresOneWaypoint() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            val seen = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { seen += it } }
            vm.logHere()
            val saved = awaitSaved(vm)
            val shownFor = msUntilIdle(vm)
            watching.cancel()

            val rows = dao.observeAll().first()
            assertEquals(1, rows.size)
            val row = rows.single()
            assertTrue(row.name, Regex("Waypoint [1-9][0-9]*").matches(row.name))
            assertEquals(row.name, saved.name)
            assertEquals(row.accuracyM, saved.accuracyM, 0f)
            assertTrue(row.isTarget)
            assertTrue("off by ${distanceMeters(hanover.point, GeoPoint(row.lat, row.lon))} m", distanceMeters(hanover.point, GeoPoint(row.lat, row.lon)) < 100.0)

            // Idle, then Saving with no accuracy yet, then only Saving until the one Saved, then Idle again.
            assertEquals(LogStatus.Idle, seen.first())
            assertEquals(LogStatus.Saving(null), seen[1])
            val savedAt = seen.indexOf(saved)
            assertTrue("$seen", savedAt >= 2 && seen.subList(1, savedAt).all { it is LogStatus.Saving })
            assertEquals(listOf(saved, LogStatus.Idle), seen.subList(savedAt, seen.size))
            // The result stays for about two seconds.
            assertTrue("shown for $shownFor ms", shownFor in 1_500..3_500)
        }
    }

    @Test fun pressesWhileASaveIsInProgressSaveOneWaypoint() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // Two presses with nothing between them, and a third once the save is visibly under way.
            withContext(Dispatchers.Main) { vm.logHere(); vm.logHere() }
            withTimeout(60_000) { vm.logStatus.first { it != LogStatus.Idle } }
            if (vm.logStatus.value is LogStatus.Saving) vm.logHere()
            val saved = awaitSaved(vm)
            msUntilIdle(vm)
            // A second save would need a fix of its own: leave it the time to arrive before counting.
            delay(3_000)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
        }
    }

    @Test fun logHereWithoutThePermissionReportsItAndSavesNothing() = runBlocking {
        val vm = viewModelWithoutPermission()
        vm.logHere()
        val status = withTimeout(10_000) { vm.logStatus.first { it !is LogStatus.Idle && it !is LogStatus.Saving } }
        assertEquals(LogStatus.Failed(LogFailure.NO_PERMISSION), status)
        // A failure stays for about three seconds, then the status clears with nothing collecting it.
        val shownFor = msUntilIdle(vm)
        assertTrue("shown for $shownFor ms", shownFor in 2_500..4_500)
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test fun aPressWhileAResultShowsStartsAgainWithItsOwnDisplayTime() = runBlocking {
        val vm = viewModelWithoutPermission()
        vm.logHere()
        withTimeout(10_000) { vm.logStatus.first { it is LogStatus.Failed } }
        delay(2_000)
        // One second before the first result would clear, a second press gives a second result.
        vm.logHere()
        delay(2_000)
        // The first result's three seconds are over; the second one's are not.
        assertEquals(LogStatus.Failed(LogFailure.NO_PERMISSION), vm.logStatus.value)
        val rest = msUntilIdle(vm)
        assertTrue("cleared after another $rest ms", rest in 500..2_500)
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
                vm.logHere()
                val saved = awaitSaved(vm)
                assertTrue("the press came ${pressedAt - newestBefore} ms after a fix", newestBefore <= pressedAt)
                assertTrue(
                    "saved a fix from ${pressedAt - saved.fixElapsedMs} ms before the press",
                    saved.fixElapsedMs >= pressedAt,
                )
                msUntilIdle(vm)
            }
            running.cancel()
            assertEquals(3, dao.observeAll().first().size)
        }
    }
}
