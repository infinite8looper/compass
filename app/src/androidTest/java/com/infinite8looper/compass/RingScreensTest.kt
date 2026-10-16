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
import com.infinite8looper.compass.ui.Ring
import com.infinite8looper.compass.ui.RingState
import com.infinite8looper.compass.ui.ScreenCompositions
import com.infinite8looper.compass.nav.PANE_START_PAGE
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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
 * The settings that would bring position fixes for the trail and the coordinates are off. Location runs all the
 * same while the compass or the waypoints screen is on screen (v3), with the position feed of the suite: positions
 * arrive during the turns, and one is made to arrive while a swipe is held with the finger moved 0.3 of the screen's
 * width. A position composes only the parts of the screens that show it ([ScreenCompositions] counts the screens),
 * so the counts must not rise for it.
 * What the screen state did meanwhile is kept and given in a failure's message.
 */
@RunWith(AndroidJUnit4::class)
class RingScreensTest {
    // Location runs for the screens only with the permission held.
    @get:org.junit.Rule val permission: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
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

    /**
     * The app's nodes as the app has them now. The system keeps a cache of the tree for a test, and a node's place
     * read from it can stay what it was for seconds after a list has moved (seen: a row read at its old place 1.5 s
     * after its list had stopped at its end, and at its new place 3 s after, with nothing done in between). So every
     * node is fetched again from the app ([AccessibilityNodeInfo.refresh], which bypasses the cache) before it is
     * read. A node that cannot be fetched again (the app dropped it while the tree was being walked) is not left out,
     * which could make a check that something is absent pass on a tree read half way: the whole tree is read again,
     * up to three times, and if it still cannot be read whole the test fails.
     */
    private fun nodes(): List<AccessibilityNodeInfo> {
        repeat(3) { attempt ->
            if (attempt > 0) SystemClock.sleep(200)
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@repeat
            if (!root.refresh()) return@repeat
            val all = ArrayList<AccessibilityNodeInfo>()
            var whole = true
            fun walk(n: AccessibilityNodeInfo) {
                all += n
                for (i in 0 until n.childCount) {
                    val child = n.getChild(i)
                    if (child == null || !child.refresh()) { whole = false; return }
                    walk(child)
                }
            }
            walk(root)
            if (whole) return all.filter { it.packageName == "com.infinite8looper.compass" }
        }
        fail("the app's tree could not be read whole in three tries")
        return emptyList()
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
            lateinit var vm: CompassViewModel
            scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[CompassViewModel::class.java] }
            showsPane(Pane.COMPASS)
            // A position has come, so location is running, before the counts are taken.
            withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null } }
            // Every screen is built with the app, before any turn.
            delay(1_500)
            val changes = java.util.concurrent.CopyOnWriteArrayList<String>()
            // New positions that arrived (not a position going away, as it does on settings).
            val arrivals = java.util.concurrent.atomic.AtomicInteger()
            val start = SystemClock.elapsedRealtime()
            val watching = launch(Dispatchers.Main) {
                var last: NavUi? = null
                vm.nav.collect { now ->
                    val was = last
                    if (was != null) changes += "${SystemClock.elapsedRealtime() - start} ms: " + navChanges(was, now)
                    if (was != null && now.fixElapsedMs != null && now.fixElapsedMs != was.fixElapsedMs) arrivals.incrementAndGet()
                    last = now
                }
            }
            val before = counts(scenario)
            assertEquals("screens built when the app has opened (settings, compass, waypoints)", listOf(1, 1, 1), before.built.zip(builtBefore) { now, then -> now - then })

            // A whole turn by touch: three swipes to the left come round to the compass again.
            // On the waypoints screen a new position is waited for, so positions arrive during the turns whatever
            // their timing: one on each turn.
            suspend fun aNewPosition() {
                val had = vm.nav.value.fixElapsedMs
                assertNotNull("no new position came on the waypoints screen; screen state changes: $changes",
                    withTimeoutOrNull(15_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != had } })
            }
            for (pane in listOf(Pane.WAYPOINTS, Pane.SETTINGS, Pane.COMPASS)) {
                swipe(toTheLeft = true); showsPane(pane)
                if (pane == Pane.WAYPOINTS) aNewPosition()
            }
            val afterSwipes = counts(scenario)
            assertEquals("screens built, after a turn by three swipes (settings, compass, waypoints)", before.built, afterSwipes.built)
            assertEquals("compositions of each screen, after a turn by three swipes (settings, compass, waypoints); screen state changes: $changes", before.composed, afterSwipes.composed)

            // A whole turn the other way, as the app itself slides: three Backs.
            for (pane in listOf(Pane.SETTINGS, Pane.WAYPOINTS, Pane.COMPASS)) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); showsPane(pane)
                if (pane == Pane.WAYPOINTS) aNewPosition()
            }
            val afterBacks = counts(scenario)
            assertEquals("screens built, after a turn by three Backs (settings, compass, waypoints)", before.built, afterBacks.built)
            assertEquals("compositions of each screen, after a turn by three Backs (settings, compass, waypoints); screen state changes: $changes", before.composed, afterBacks.composed)

            // And a swipe to the right, which a turn to the left does not make.
            swipe(toTheLeft = false); showsPane(Pane.SETTINGS)
            swipe(toTheLeft = true); showsPane(Pane.COMPASS)
            val afterBoth = counts(scenario)
            assertEquals("screens built, after a swipe each way (settings, compass, waypoints)", before.built, afterBoth.built)
            assertEquals("compositions of each screen, after a swipe each way (settings, compass, waypoints); screen state changes: $changes", before.composed, afterBoth.composed)

            // A position that arrives while a swipe is held, the finger moved 0.3 of the screen's width, the compass and
            // the waypoints both passing.
            val had = vm.nav.value.fixElapsedMs
            holdAPartSwipe {
                val came = withTimeoutOrNull(15_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != had } }
                assertNotNull("no position came while the swipe was held; screen state changes: $changes", came)
            }
            showsPane(Pane.COMPASS)
            val afterHeld = counts(scenario)
            watching.cancel()
            assertTrue("fewer than two new positions arrived during the turns (${arrivals.get()}): $changes", arrivals.get() >= 2)
            assertEquals("screens built, after a position came during a held swipe", before.built, afterHeld.built)
            assertEquals("compositions of each screen, after a position came during a held swipe (settings, compass, waypoints); screen state changes: $changes", before.composed, afterHeld.composed)
        }
    }

    /** Which fields of the screen state differ between two values, by name. */
    private fun navChanges(a: NavUi, b: NavUi): String = listOfNotNull(
        "targets".takeIf { a.targets != b.targets }, "focused".takeIf { a.focused != b.focused }, "units".takeIf { a.units != b.units },
        "north".takeIf { a.north != b.north }, "declination".takeIf { a.declination != b.declination },
        "fixElapsedMs".takeIf { a.fixElapsedMs != b.fixElapsedMs }, "waypoints".takeIf { a.waypoints != b.waypoints },
        "distances".takeIf { a.distances != b.distances }, "loaded".takeIf { a.loaded != b.loaded },
        "trail".takeIf { a.trail != b.trail }, "noPosition".takeIf { a.noPosition != b.noPosition },
        "fixAccuracyM".takeIf { a.fixAccuracyM != b.fixAccuracyM }, "position".takeIf { a.position != b.position },
        "settings".takeIf { a.copy(targets = b.targets, focused = b.focused, units = b.units, north = b.north, declination = b.declination,
            fixElapsedMs = b.fixElapsedMs, waypoints = b.waypoints, distances = b.distances, loaded = b.loaded, trail = b.trail,
            noPosition = b.noPosition, fixAccuracyM = b.fixAccuracyM, position = b.position) != b },
    ).joinToString(",").ifEmpty { "nothing (an equal value)" }

    /**
     * A finger put down on the compass and moved 0.3 of the screen's width toward the waypoints, held there while [during]
     * runs, then let go where it is (the ring comes to rest on the nearer page, the compass).
     */
    private suspend fun holdAPartSwipe(during: suspend () -> Unit) {
        val bounds = instrumentation.uiAutomation.rootInActiveWindow.let { root -> Rect().also { root.getBoundsInScreen(it) } }
        val y = bounds.exactCenterY()
        val from = bounds.exactCenterX() + bounds.width() * 0.3f
        val to = bounds.exactCenterX()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (i in 1..12) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, from + (to - from) * i / 12) }
        try { during() } finally { send(MotionEvent.ACTION_UP, to) }
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

    /**
     * The word that stands in the middle of the screen, left to right, on each screen at rest when its list is at
     * its top: on the compass the heading (or the hold-flat hint), on the others the first rows.
     */
    private fun middleWordIsCentered(pane: Pane): Boolean {
        val screen = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        val word = nodes().firstOrNull { n ->
            n.isVisibleToUser && n.text?.toString()?.let { t ->
                when (pane) {
                    Pane.COMPASS -> t == "Hold flat" || t == "--" || t.endsWith("°")
                    Pane.SETTINGS -> t == "Settings"
                    Pane.WAYPOINTS -> t == "Log here"
                }
            } == true
        } ?: return false
        val bounds = Rect().also(word::getBoundsInScreen)
        return kotlin.math.abs(bounds.centerX() - screen.centerX()) <= 2
    }

    /** One accessibility action on the ring itself: the node that tells a screen reader of the three screens. */
    private fun ringAction(action: Int) {
        val ring = nodes().firstOrNull { it.collectionInfo?.columnCount == 3 }
        assertNotNull("no node in the tree is the ring of three screens", ring)
        assertTrue("the ring did not take the action $action", ring!!.performAction(action))
    }

    @Test fun anAccessibilityServiceTurnsTheRingByScrollingAndByPaging() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            showsPane(Pane.COMPASS)
            val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val left = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
            val right = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
            val scrollLeft = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            val scrollRight = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            // Each action turns the ring one screen, the way it says, and leaves it at rest on that screen.
            for ((action, pane) in listOf(
                forward to Pane.WAYPOINTS, backward to Pane.COMPASS, right to Pane.WAYPOINTS, left to Pane.COMPASS,
                // Three the same way come round to the compass, either way.
                forward to Pane.WAYPOINTS, forward to Pane.SETTINGS, forward to Pane.COMPASS,
                backward to Pane.SETTINGS, backward to Pane.WAYPOINTS, backward to Pane.COMPASS,
                left to Pane.SETTINGS, left to Pane.WAYPOINTS, left to Pane.COMPASS,
                // Scrolling the way the screen is moved: to the right brings the screen on the right.
                scrollRight to Pane.WAYPOINTS, scrollLeft to Pane.COMPASS, scrollLeft to Pane.SETTINGS, scrollRight to Pane.COMPASS,
            )) {
                ringAction(action)
                showsPane(pane)
                waitsFor { middleWordIsCentered(pane) }
                assertTrue("after action $action the ring is not at rest on the $pane: ${wordsOnScreen()}", middleWordIsCentered(pane))
            }
        }
    }

    /**
     * Two scroll actions made one right after the other, with the system's animations on (the suite runs with them
     * off, where a slide ends at once): the second goes one screen further than the first, and the ring comes to
     * rest on that screen, as two Backs do.
     */
    @Test fun twoAccessibilityScrollsInQuickSuccessionTurnTheRingTwoScreens() = runBlocking {
        val shell = instrumentation.uiAutomation
        fun scale(): String = shell.executeShellCommand("settings get global animator_duration_scale").let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText().trim()
        }
        fun setScale(v: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand("settings put global animator_duration_scale $v")).readBytes() }
        val was = scale()
        setScale("1")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                showsPane(Pane.COMPASS)
                for ((action, pane) in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to Pane.SETTINGS, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to Pane.COMPASS)) {
                    ringAction(action)
                    ringAction(action)
                    delay(1_500)
                    showsPane(pane)
                    waitsFor { middleWordIsCentered(pane) }
                    assertTrue("after two quick actions $action the ring is not at rest on the $pane: ${wordsOnScreen()}", middleWordIsCentered(pane))
                }
            }
        } finally {
            setScale(if (was == "null" || was.isEmpty()) "0" else was)
        }
    }

    /**
     * A ring that something has moved part of a page and left there without a fling (a scroll by the system or a
     * slide cut off half way, none of which ends in the let-go that settles it) comes to rest on the nearest page
     * by itself. A real [Ring] in the real activity, moved through its state.
     */
    @Test fun aRingLeftBetweenTwoPagesComesToRestOnTheNearest() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val state = RingState(PANE_START_PAGE)
            scenario.onActivity { it.setContent { Ring(state) { Box(Modifier.fillMaxSize()) } } }
            delay(1_000)
            for ((part, rests) in listOf(0.3f to PANE_START_PAGE, 0.7f to PANE_START_PAGE + 1, -0.8f to PANE_START_PAGE)) {
                var moved = 0f
                scenario.onActivity { moved = state.dispatchRawDelta(state.pageWidth * part) }
                assertTrue("the ring did not move", moved != 0f)
                var place = 0 to 1f
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { place = state.currentPage to state.currentPageOffsetFraction }
                    if (place.second == 0f) break
                    delay(50)
                }
                assertEquals("where the ring rests after being moved ${part} of a page and left (page, fraction)", rests to 0f, place)
            }
        }
    }
}
