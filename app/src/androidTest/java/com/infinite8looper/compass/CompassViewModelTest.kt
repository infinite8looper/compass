package com.infinite8looper.compass

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real view model against the app's own database and settings file on the device. */
@RunWith(AndroidJUnit4::class)
class CompassViewModelTest {
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
}
