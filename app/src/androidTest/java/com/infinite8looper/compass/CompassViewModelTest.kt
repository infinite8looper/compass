package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.distanceMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
}
