package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.DOUBLE_PRESS_WINDOW_MS
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.TrailMode
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
 * Real key events of the side button, sent to the real activity with the compass in view, at intervals the test
 * sets and then reads back from the events themselves. The tests that save need `scripts/geo-feed.sh -72.2896
 * 43.7022` running, as in CompassViewModelTest.
 */
@RunWith(AndroidJUnit4::class)
class SideButtonTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val crumbDao = WaypointDatabase.get(app).crumbs()
    private val settings = SettingsStore.get(app)
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        settings.setBreadcrumbTrail(false)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    /** One short press of the side button, sent and handled before this returns. Returns the time it was let go. */
    private fun press(): Long {
        val down = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STEM_1, 0), true))
        val up = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STEM_1, 0), true))
        return up
    }

    /**
     * Two presses, the second going down [gapMs] after the first was let go, as near as the device allows.
     * Returns the gap there really was, from the first press's release to the second's down, by the events' times.
     */
    private fun pressTwice(gapMs: Long): Long {
        val firstUp = press()
        SystemClock.sleep((firstUp + gapMs - SystemClock.uptimeMillis()).coerceAtLeast(0))
        val secondDown = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(secondDown, secondDown, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STEM_1, 0), true))
        assertTrue(automation.injectInputEvent(KeyEvent(secondDown, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STEM_1, 0), true))
        // The gap there really was is logged, so a run's intervals can be read: `adb logcat -d -s SideButtonTest`.
        Log.i("SideButtonTest", "asked for $gapMs ms between two presses, got ${secondDown - firstUp} ms")
        return secondDown - firstUp
    }

    private suspend fun storedMode() = settings.settings.first().trailMode

    /** Lets the app's first frames pass, so the compass is at rest in view and has the focus. */
    private suspend fun settle() = delay(1_500)

    @Test fun withTheSettingOnTwoPressesCloseTogetherStartATrailAndSaveNothing() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            settle()
            val gap = pressTwice(150)
            assertTrue("the presses came $gap ms apart", gap in 100..DOUBLE_PRESS_WINDOW_MS)
            withTimeout(10_000) { while (storedMode() != TrailMode.LAYING) delay(20) }
            // Neither press was a single press: nothing is being saved, and nothing is saved later.
            delay(1_500)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            assertTrue(dao.observeAll().first().isEmpty()); assertTrue(crumbDao.all().isEmpty())

            // A single press now drops a crumb, a third of a second after it is let go and not before.
            val up = press()
            delay(200)
            assertEquals("${SystemClock.uptimeMillis() - up} ms after the press", LogStatus.Idle, vm.logStatus.value)
            withTimeout(60_000) { while (crumbDao.all().isEmpty()) delay(20) }
            assertEquals("Crumb 1", (vm.logStatus.value as? LogStatus.Saved)?.name)
            assertTrue(dao.observeAll().first().isEmpty())

            // And two close together switch to retracing.
            val second = pressTwice(200)
            assertTrue("the presses came $second ms apart", second in 150..DOUBLE_PRESS_WINDOW_MS)
            withTimeout(10_000) { while (storedMode() != TrailMode.RETRACING) delay(20) }
            assertEquals(1, crumbDao.all().size)
        }
    }

    @Test fun withTheSettingOnTwoPressesFurtherApartAreTwoSinglePresses() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            settle()
            val gap = pressTwice(450)
            assertTrue("the presses came $gap ms apart", gap > DOUBLE_PRESS_WINDOW_MS)
            // No trail is started. The first press saves a waypoint; the second comes while that save is in
            // progress and does nothing, as before.
            withTimeout(60_000) { while (vm.logStatus.value !is LogStatus.Saved) delay(20) }
            delay(3_000)
            assertEquals(TrailMode.OFF, storedMode())
            assertEquals(1, dao.observeAll().first().size)
            assertEquals(1, vm.logPress.value)
            assertTrue(crumbDao.all().isEmpty())
        }
    }

    @Test fun withTheSettingOffAPressActsAtOnceAndTwoCloseTogetherStartNoTrail() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            settle()
            // The save has begun by the time the press's release has been handled: nothing waits.
            val up = press()
            val status = vm.logStatus.value
            val after = SystemClock.uptimeMillis() - up
            Log.i("SideButtonTest", "with the setting off the save had begun $after ms after the press was let go")
            assertTrue("$status, $after ms after the press", status is LogStatus.Saving)
            assertEquals(1, vm.logPress.value)
            // A second press close behind it is a second single press, which a save in progress ignores.
            SystemClock.sleep(100)
            press()
            assertEquals(1, vm.logPress.value)
            withTimeout(60_000) { while (vm.logStatus.value !is LogStatus.Saved) delay(20) }
            delay(3_000)
            assertEquals(TrailMode.OFF, storedMode())
            assertEquals(1, dao.observeAll().first().size)
            assertTrue(crumbDao.all().isEmpty())
        }
    }

    @Test fun withTheSettingOnASinglePressWaitsForTheWindowAndThenSavesAWaypoint() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            settle()
            val up = press()
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            // Still nothing a little before the window ends; the save has begun a little after it.
            SystemClock.sleep((up + DOUBLE_PRESS_WINDOW_MS - 80 - SystemClock.uptimeMillis()).coerceAtLeast(0))
            val early = vm.logStatus.value
            val earlyAt = SystemClock.uptimeMillis() - up
            if (earlyAt < DOUBLE_PRESS_WINDOW_MS) assertEquals("$earlyAt ms after the press", LogStatus.Idle, early)
            withTimeout(5_000) { while (vm.logStatus.value == LogStatus.Idle) delay(5) }
            val began = SystemClock.uptimeMillis() - up
            Log.i("SideButtonTest", "a single press began its save $began ms after it was let go")
            assertTrue("the save began $began ms after the press", began in DOUBLE_PRESS_WINDOW_MS..DOUBLE_PRESS_WINDOW_MS + 700)
            withTimeout(60_000) { while (vm.logStatus.value !is LogStatus.Saved) delay(20) }
            assertEquals(1, dao.observeAll().first().size)
            assertEquals(TrailMode.OFF, storedMode())
        }
    }
}
