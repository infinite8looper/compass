package com.infinite8looper.compass

import android.Manifest
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.ui.ScreenCompositions
import com.infinite8looper.compass.ui.WaypointsPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A position on the waypoints screen composes only the distance lines of its rows: not the header, not `Log here`,
 * not the trail's row, not the rows themselves (ScreenCompositions counts each part). Needs
 * `scripts/geo-feed.sh -72.2896 43.7022` running, as the suite starts it: positions come about every 5 s.
 */
@RunWith(AndroidJUnit4::class)
class WaypointsPositionTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val dao = WaypointDatabase.get(app).waypoints()
    private val settings = SettingsStore.get(app)

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        settings.setTrailMode(TrailMode.OFF)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun swipeLeft() {
        val bounds = instrumentation.uiAutomation.rootInActiveWindow.let { root -> Rect().also { root.getBoundsInScreen(it) } }
        val y = bounds.exactCenterY(); val far = bounds.width() * 0.3f
        val from = bounds.exactCenterX() + far; val to = bounds.exactCenterX() - far
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (i in 1..12) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, from + (to - from) * i / 12) }
        send(MotionEvent.ACTION_UP, to)
    }

    private fun parts(scenario: ActivityScenario<MainActivity>): List<Int> {
        lateinit var p: List<Int>
        scenario.onActivity { p = ScreenCompositions.waypointsParts.toList() + ScreenCompositions.composed.toList() }
        return p
    }

    @Test fun aPositionComposesOnlyTheDistanceLines() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(Waypoint(name = "Place 1", lat = 43.7040, lon = -72.2896, accuracyM = 5f, createdAt = now - 2, hue = 10f, isTarget = true))
        dao.insert(Waypoint(name = "Place 2", lat = 43.7022, lon = -72.2860, accuracyM = 5f, createdAt = now - 1, hue = 200f, isTarget = false))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CompassViewModel
            scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[CompassViewModel::class.java] }
            delay(2_000)
            swipeLeft()
            withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null && it.distances.isNotEmpty() } }
            delay(1_500)
            val before = parts(scenario)
            val navBefore = vm.nav.value
            val had = navBefore.fixElapsedMs
            // Two new positions.
            val first = withTimeout(30_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != had } }.fixElapsedMs
            withTimeout(30_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != had && it.fixElapsedMs != first } }
            delay(500)
            val after = parts(scenario)
            val navAfter = vm.nav.value
            val same = "waypoints equal ${navBefore.waypoints == navAfter.waypoints}, trail equal ${navBefore.trail == navAfter.trail}, setting equal ${navBefore.breadcrumbTrail == navAfter.breadcrumbTrail}"
            val names = WaypointsPart.entries
            val screens = "screens composed ${before.drop(names.size)} -> ${after.drop(names.size)}"
            fun changed() = screens + "; " + names.filter { before[it.ordinal] != after[it.ordinal] }.joinToString { "${it.name} ${before[it.ordinal]}->${after[it.ordinal]}" }
            for (part in listOf(WaypointsPart.HEADER, WaypointsPart.LOG_HERE, WaypointsPart.TRAIL, WaypointsPart.ROW)) {
                assertEquals("$part composed again for a position; changed: ${changed()}; $same", before[part.ordinal], after[part.ordinal])
            }
            assertTrue("the rows were never composed", before[WaypointsPart.ROW.ordinal] > 0)
            assertTrue("the distance lines were not composed for the positions; changed: ${changed()}", after[WaypointsPart.DISTANCE.ordinal] > before[WaypointsPart.DISTANCE.ordinal])
        }
    }
}
