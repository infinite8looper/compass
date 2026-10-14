package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.TrailMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Location while the app is on screen (v3 spec): with the permission held, position updates run while the compass
 * or the waypoints screen is on screen, and stop on every way out (a swipe to settings, a screen opened from a row,
 * calibration, Home, the screen going off). Seen on the real activity's own view model, whose live position is in its
 * screen state for as long as the updates run and is gone the moment they stop. No waypoint is a target and the
 * trail and the coordinates are off, so nothing but the screens asks for location; a position is stored, so true
 * north does not either. Needs `scripts/geo-feed.sh -72.2896 43.7022` running.
 */
@RunWith(AndroidJUnit4::class)
class LocationOnScreenTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val crumbDao = WaypointDatabase.get(app).crumbs()
    private val settings = SettingsStore.get(app)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        settings.setTrailMode(TrailMode.OFF)
        settings.setBreadcrumbTrail(false)
        settings.setShowCoordinates(false)
    }
    @Before fun setUp() = runBlocking {
        reset()
        settings.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
        // Rows to open, none a target.
        dao.insert(Waypoint(name = "Place 1", lat = 43.7022, lon = -72.2896, accuracyM = 8f, createdAt = System.currentTimeMillis(), hue = 30f, isTarget = false))
        Unit
    }
    @After fun tearDown() = reset()

    private val collectors = ArrayList<Job>()
    @After fun stopCollecting() { collectors.forEach { it.cancel() } }

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    /** The view model of the app's activity that is resumed, waited for up to 10 s. */
    private suspend fun resumedViewModel(): CompassViewModel {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            var vm: CompassViewModel? = null
            instrumentation.runOnMainSync {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                vm = resumed?.let { ViewModelProvider(it)[CompassViewModel::class.java] }
            }
            vm?.let { return it }
            assertTrue("the app's activity did not come back", SystemClock.uptimeMillis() < deadline)
            delay(200)
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().readText()

    /**
     * The app's nodes as the app has them now: each fetched again from the app ([AccessibilityNodeInfo.refresh]), since
     * the system's cache of the tree can hold a list's old contents for seconds after it has scrolled (RingScreensTest).
     */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        root.refresh()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let { if (it.refresh()) walk(it) } }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }
    private fun visible(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
        delay(700)
    }
    private fun compassInView() = nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } &&
        !visible("Log here") && !visible("Settings")

    /**
     * Scrolls forward, through the system's tree, the list that holds the visible words [anchor] (the three screens
     * are all built, so the list is told by what it shows), until a node reads [text]; fails if none ever does.
     */
    private suspend fun scrollTo(text: String, anchor: String) {
        if (nodes().any { it.isVisibleToUser && it.text?.toString() == text }) return
        val list = nodes().first { it.isVisibleToUser && it.text?.toString() == anchor }
            .let { n -> generateSequence(n) { it.parent }.first { it.isScrollable && (it.collectionInfo?.columnCount ?: 1) <= 1 } }
        repeat(10) {
            if (nodes().any { it.isVisibleToUser && it.text?.toString() == text }) return
            list.refresh()
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(1_000)
        }
        fail("`$text` did not come on screen; on screen: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() })
    }

    /** One finger across the middle of the screen: the swipe that turns the ring (as RingScreensTest). */
    private fun swipe(toTheLeft: Boolean) {
        val bounds = instrumentation.uiAutomation.rootInActiveWindow.let { root -> Rect().also { root.getBoundsInScreen(it) } }
        val y = bounds.exactCenterY()
        val far = bounds.width() * 0.3f
        val from = bounds.exactCenterX() + if (toTheLeft) far else -far
        val to = bounds.exactCenterX() + if (toTheLeft) -far else far
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, at: Long) {
            val e = MotionEvent.obtain(down, at, action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from, down)
        for (i in 1..12) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, from + (to - from) * i / 12, SystemClock.uptimeMillis()) }
        send(MotionEvent.ACTION_UP, to, SystemClock.uptimeMillis())
    }

    private suspend fun running(vm: CompassViewModel, what: String) =
        assertNotNull(what, withTimeoutOrNull(60_000) { vm.nav.first { it.fixElapsedMs != null } })
    private suspend fun stopped(vm: CompassViewModel, what: String) {
        assertNotNull(what, withTimeoutOrNull(5_000) { vm.nav.first { it.fixElapsedMs == null } })
        // And it stays stopped.
        withTimeoutOrNull(6_000) { vm.nav.collect { assertNull("$what: a position came", it.fixElapsedMs) } }
    }

    /** The screen state's values as they come, collected for the whole test, so the activity's own state stays live. */
    private fun watch(vm: CompassViewModel): CopyOnWriteArrayList<NavUi> {
        val seen = CopyOnWriteArrayList<NavUi>()
        collectors += CoroutineScope(Dispatchers.Main).launch { vm.nav.collect { seen += it } }
        return seen
    }

    @Test fun locationRunsOnTheCompassAndTheWaypointsAndStopsForSettingsAndAScreenOpenedFromARow() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val seen = watch(vm)
            shows("the compass") { compassInView() }
            running(vm, "on the compass")

            // To the waypoints: the updates go on through the swipe, with no gap.
            val from = seen.size
            swipe(toTheLeft = true); shows("the waypoints") { visible("Log here") }
            val had = vm.nav.value.fixElapsedMs
            withTimeout(15_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != had } }
            assertTrue("a gap in the positions between the compass and the waypoints", seen.subList(from, seen.size).all { it.fixElapsedMs != null })

            // A row opened from the list covers the three screens.
            scrollTo("Place 1", anchor = "Log here")
            val row = nodes().first { it.text?.toString() == "Place 1" }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }
            // A long press opens a row (a tap sets it as a target).
            assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            shows("the edit screen") { visible("Target") || visible("Hold to delete") }
            stopped(vm, "under the edit screen")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("the waypoints again") { visible("Place 1") }
            running(vm, "back on the waypoints")

            // Settings, at rest: two screens to the left of the waypoints, one to the right of the compass.
            swipe(toTheLeft = false); shows("the compass") { compassInView() }
            swipe(toTheLeft = false); shows("the settings") { visible("Settings") }
            stopped(vm, "on settings")

            // Calibration, opened from settings, runs none either.
            scrollTo("Calibrate", anchor = "True north")
            val calibrate = nodes().first { it.text?.toString() == "Calibrate" }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }
            assertTrue(calibrate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(1_000)
            stopped(vm, "on calibration")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("the settings again") { visible("Calibrate") }
            swipe(toTheLeft = true); shows("the compass") { compassInView() }
            running(vm, "back on the compass")
        }
    }

    /** The GPS provider's request as the system last set it, from `dumpsys location`: its interval and whose it is. */
    private fun gpsRequest(): String =
        shell("dumpsys location").lines().lastOrNull { "gps provider request = " in it }?.substringAfter("gps provider request = ")?.trim() ?: "none"

    /**
     * A save from a position in hand listens for a better one for ten seconds, asking every second; Home, and the
     * screen going off, end that request with the screens' own, at once and not when the ten seconds are over. Read
     * from the system's own record of the GPS provider's request (`dumpsys location`), which names the app while any
     * request of the app's is running.
     */
    @Test fun aSavesListeningStopsAtHomeAndWithTheScreenOff() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // An earlier test's save may still be listening: its ten seconds are let pass.
            delay(11_000)
            // The screen state is not collected here beyond a moment: what keeps location running must be the
            // app's own screens, as on the watch (the waypoint saved is a target, which v2 runs location for while
            // the screens collect the state).
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            running(vm, "on the compass")
            var current = vm
            for (leave in listOf("Home", "the screen off")) {
                val saving = current
                val job = kotlinx.coroutines.withContext(Dispatchers.Main) { saving.logHere() }
                withTimeout(10_000) { job.join() }
                delay(1_500)
                val listening = gpsRequest()
                assertTrue("$leave: after the save: $listening", "@+1s0ms" in listening && "com.infinite8looper.compass" in listening)
                if (leave == "Home") {
                    instrumentation.uiAutomation.injectInputEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true)
                    instrumentation.uiAutomation.injectInputEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true)
                } else shell("input keyevent KEYCODE_SLEEP")
                try {
                    // Within 4 s of leaving, well inside the ten seconds, nothing of the app's is asked for.
                    val deadline = SystemClock.uptimeMillis() + 4_000
                    while ("com.infinite8looper.compass" in gpsRequest() && SystemClock.uptimeMillis() < deadline) delay(200)
                    val left = gpsRequest()
                    assertFalse("$leave: $left", "com.infinite8looper.compass" in left)
                } finally {
                    if (leave != "Home") { shell("input keyevent KEYCODE_WAKEUP"); delay(1_500) }
                    shell("am start -n com.infinite8looper.compass/.MainActivity")
                }
                val again = resumedViewModel()
                running(again, "back after $leave")
                current = again
                // The status of that save is let go before the next.
                delay(4_000)
            }
        }
    }

    @Test fun locationStopsAtHomeAndWithTheScreenOffAndComesBackWithTheApp() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            watch(vm)
            shows("the compass") { compassInView() }
            running(vm, "on the compass")

            instrumentation.uiAutomation.injectInputEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true)
            instrumentation.uiAutomation.injectInputEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true)
            stopped(vm, "at Home")
            // Back as the wearer comes back, from the launcher's entry; the activity that comes up has the view model.
            shell("am start -n com.infinite8looper.compass/.MainActivity")
            val again = resumedViewModel()
            watch(again)
            running(again, "back from Home")

            shell("input keyevent KEYCODE_SLEEP")
            try {
                stopped(again, "with the screen off")
            } finally {
                shell("input keyevent KEYCODE_WAKEUP")
                delay(1_500)
                shell("am start -n com.infinite8looper.compass/.MainActivity")
            }
            val woken = resumedViewModel()
            watch(woken)
            running(woken, "with the screen on again")
        }
    }
}
