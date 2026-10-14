package com.infinite8looper.compass

import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.ui.ScreenCompositions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ring of three screens in the real activity, turned with real touches and with Back: the three screens are
 * built once and stay. A turn of the ring builds no screen and composes none, which is read from the counters the
 * screens themselves keep ([ScreenCompositions]), and a list is where it was left when the ring comes round to it
 * again, which is read from the system's accessibility tree.
 *
 * The settings that would bring position fixes (the breadcrumb trail, the coordinates) are off, so nothing but the
 * turning changes what the screens show while the counts are compared.
 */
@RunWith(AndroidJUnit4::class)
class RingScreensTest {
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
    @Before fun setUp() {
        reset()
        // Enough waypoints that their list scrolls on every screen size. None is a target: nothing is walked to.
        runBlocking {
            val t = System.currentTimeMillis()
            for (i in 1..12) dao.insert(Waypoint(name = "Place $i", lat = 43.7022, lon = -72.2896, accuracyM = 8f, createdAt = t + i, hue = 30f * i, isTarget = false))
        }
    }
    @After fun tearDown() = reset()

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }

    private fun onScreen(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    private fun compassOnScreen() =
        nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true }

    /** Waits until [isSo], and says what was waited for and what the screen held if it never is. */
    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s; on screen: ${wordsOnScreen()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
    }

    /** Gives the system's tree up to 5 s to hold [isSo]; what it then holds is asserted by the caller. */
    private suspend fun waitsFor(isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!isSo() && SystemClock.uptimeMillis() < deadline) delay(100)
    }

    /** The three screens in the order a swipe to the left brings them, each with how it is known to be the one in view. */
    private suspend fun showsPane(pane: Pane) {
        when (pane) {
            Pane.SETTINGS -> shows("the settings") { settingsInView() }
            Pane.COMPASS -> shows("the compass") { compassOnScreen() && !settingsInView() && !waypointsInView() }
            Pane.WAYPOINTS -> shows("the waypoints") { waypointsInView() }
        }
        // The row of screens has come to rest and the screen has drawn.
        delay(700)
    }
    // A row of each list that is on screen wherever the list is scrolled to in these tests.
    private fun settingsInView() = nodes().any { it.isVisibleToUser && it.text?.toString() in setOf("Settings", "Tick feedback", "Blind navigation", "Back up waypoints", "Calibrate") }
    private fun waypointsInView() = nodes().any { it.isVisibleToUser && (it.text?.toString() == "Log here" || it.text?.toString()?.startsWith("Place ") == true) }

    /** One finger across the middle of the screen, three fifths of its width in about 200 ms: the swipe that turns the ring. */
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
        val steps = 12
        for (i in 1..steps) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, from + (to - from) * i / steps, SystemClock.uptimeMillis())
        }
        send(MotionEvent.ACTION_UP, to, SystemClock.uptimeMillis())
    }

    /** What the screens' own counters say, read on the thread that writes them. */
    private class Counts(val built: List<Int>, val composed: List<Int>)
    private fun counts(scenario: ActivityScenario<MainActivity>): Counts {
        lateinit var c: Counts
        scenario.onActivity { c = Counts(ScreenCompositions.built.toList(), ScreenCompositions.composed.toList()) }
        return c
    }

    /** Where on the screen the first node with [text] stands. */
    private fun place(text: String): Rect? = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }?.let { n -> Rect().also { n.getBoundsInScreen(it) } }

    private fun wordsOnScreen() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.filter { it.isNotEmpty() }

    /** Scrolls the list of the screen in view to its end, through the system's tree, a step at a time. */
    private suspend fun scrollListToItsEnd() {
        var steps = 0
        while (steps < 8) {
            val list = nodes().firstOrNull { it.isScrollable && it.isFocused }
            assertNotNull("no list that can be scrolled has the focus", list)
            if (!list!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            steps++
            delay(1_000)
        }
        assertTrue("the list took no scroll at all; on screen: ${wordsOnScreen()}", steps > 0)
    }

    @Test fun aTurnOfTheRingBuildsNoScreenAndComposesNone() = runBlocking {
        // The counters are the process's, and an earlier test's activity has counted in them: what this activity
        // builds is counted from here.
        val builtBefore = ScreenCompositions.built.toList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showsPane(Pane.COMPASS)
            // Every screen is built with the app, before any turn.
            delay(1_500)
            val before = counts(scenario)
            assertEquals("screens built when the app has opened (settings, compass, waypoints)", listOf(1, 1, 1), before.built.zip(builtBefore) { now, then -> now - then })

            // A whole turn by touch: three swipes to the left come round to the compass again.
            for (pane in listOf(Pane.WAYPOINTS, Pane.SETTINGS, Pane.COMPASS)) { swipe(toTheLeft = true); showsPane(pane) }
            val afterSwipes = counts(scenario)
            assertEquals("screens built, after a turn by three swipes (settings, compass, waypoints)", before.built, afterSwipes.built)
            assertEquals("compositions of each screen, after a turn by three swipes (settings, compass, waypoints)", before.composed, afterSwipes.composed)

            // A whole turn the other way, as the app itself slides: three Backs.
            for (pane in listOf(Pane.SETTINGS, Pane.WAYPOINTS, Pane.COMPASS)) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); showsPane(pane) }
            val afterBacks = counts(scenario)
            assertEquals("screens built, after a turn by three Backs (settings, compass, waypoints)", before.built, afterBacks.built)
            assertEquals("compositions of each screen, after a turn by three Backs (settings, compass, waypoints)", before.composed, afterBacks.composed)

            // And a swipe to the right, which a turn to the left does not make.
            swipe(toTheLeft = false); showsPane(Pane.SETTINGS)
            swipe(toTheLeft = true); showsPane(Pane.COMPASS)
            val afterBoth = counts(scenario)
            assertEquals("screens built, after a swipe each way (settings, compass, waypoints)", before.built, afterBoth.built)
            assertEquals("compositions of each screen, after a swipe each way (settings, compass, waypoints)", before.composed, afterBoth.composed)
        }
    }

    @Test fun theSettingsListIsWhereItWasAfterATurnOfTheRing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            showsPane(Pane.COMPASS)
            swipe(toTheLeft = false); showsPane(Pane.SETTINGS)
            assertTrue("the settings do not open at their top", onScreen("Settings"))
            scrollListToItsEnd()
            // The system's tree can lag a scroll by a moment, so what it holds is waited for, here and below.
            shows("the settings list scrolled past its header") { !onScreen("Settings") }
            val row = listOf("Blind navigation", "Breadcrumb trail", "Show coordinates", "Back up waypoints").first { place(it) != null }
            val before = place(row)
            for (pane in listOf(Pane.COMPASS, Pane.WAYPOINTS, Pane.SETTINGS)) { swipe(toTheLeft = true); showsPane(pane) }
            waitsFor { !onScreen("Settings") && place(row) == before }
            assertFalse("after a turn the settings list is at its top again: ${wordsOnScreen()}", onScreen("Settings"))
            assertEquals("where `$row` stands after a turn of the ring", before, place(row))
        }
    }

    @Test fun theWaypointsListIsWhereItWasAfterATurnOfTheRing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            showsPane(Pane.COMPASS)
            swipe(toTheLeft = true); showsPane(Pane.WAYPOINTS)
            assertTrue("the waypoints do not open at their top", onScreen("Log here"))
            scrollListToItsEnd()
            shows("the waypoints list scrolled past `Log here`") { !onScreen("Log here") }
            val row = (1..12).map { "Place $it" }.first { place(it) != null }
            val before = place(row)
            for (pane in listOf(Pane.COMPASS, Pane.SETTINGS, Pane.WAYPOINTS)) { swipe(toTheLeft = false); showsPane(pane) }
            waitsFor { !onScreen("Log here") && place(row) == before }
            assertFalse("after a turn the waypoints list is at its top again: ${wordsOnScreen()}", onScreen("Log here"))
            assertEquals("where `$row` stands after a turn of the ring", before, place(row))
        }
    }
}
