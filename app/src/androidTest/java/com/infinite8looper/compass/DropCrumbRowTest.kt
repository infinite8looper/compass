package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.Crumb
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
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
 * The `Drop crumb` row of the waypoints screen, shown while a trail is laid, in the real activity with the real database: the row is found in
 * the system's accessibility tree and clicked through it, and a side button is pressed with real key events, on the
 * same screen. The tests that save need `scripts/geo-feed.sh -72.2896 43.7022` running, as in SideButtonTest.
 */
@RunWith(AndroidJUnit4::class)
class DropCrumbRowTest {
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
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }

    private fun withText(text: String) = nodes().filter { it.text?.toString() == text }

    /** Back turns the row of screens one screen toward settings: from the compass, two of them end on the waypoints. */
    private suspend fun toWaypoints() {
        // Each step is looked at, so a Back that turns nothing is told from a list that shows nothing: the compass
        // has drawn (its heading, or the hold-flat hint on a watch held upright), then one Back shows the settings,
        // then a second shows the waypoints.
        shows("the compass") { nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } }
        delay(1_500)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        shows("the settings after one Back from the compass") { withText("Settings").any { it.isVisibleToUser } }
        delay(1_500)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        shows("the waypoints screen after a second Back") { withText("Log here").any { it.isVisibleToUser } }
        delay(1_000)
        // The row under the trail's row stands at the lower edge of the list when the screen opens, and outside it
        // on a small screen: the list is scrolled one step, through the system's tree, so that row is inside it.
        nodes().firstOrNull { it.isScrollable && it.isFocused }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        delay(1_000)
    }

    /** One short press of the side button. */
    private fun press() {
        val down = SystemClock.uptimeMillis()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STEM_1, 0), true))
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STEM_1, 0), true))
    }

    /**
     * The row, as the system's tree has it: the words once, in a row that takes the click, as `Log here` is. The
     * words take no click of their own, so the row is one stop for a screen reader. Returns the row.
     */
    private fun dropCrumbRow(): AccessibilityNodeInfo {
        val words = withText("Drop crumb")
        assertEquals("nodes that read `Drop crumb`", 1, words.size)
        assertFalse("the words are a stop of their own", words.single().isClickable || words.single().isFocusable)
        val row = words.single().parent
        assertTrue("the row takes no click", row != null && row.isClickable && row.isEnabled)
        // Nothing else in the row can be stopped on or says anything.
        val others = (0 until row!!.childCount).mapNotNull { row.getChild(it) }.filter { it != words.single() }
        assertTrue("the row holds more than its words", others.none { it.isClickable || it.isFocusable || !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() })
        return row
    }

    /** What a save ends in, once the one in progress has ended; then the result is let run out. */
    private suspend fun savedName(vm: CompassViewModel, press: Int): String? {
        withTimeout(10_000) { while (vm.logPress.value < press) delay(10) }
        withTimeout(60_000) { while (vm.logStatus.value is LogStatus.Saving) delay(20) }
        val name = (vm.logStatus.value as? LogStatus.Saved)?.name
        withTimeout(15_000) { while (vm.logStatus.value != LogStatus.Idle) delay(20) }
        return name
    }

    /** The trail's own row is on screen, so the list has been drawn for what the trail is doing now. */
    private suspend fun trailRowSays(detail: String) = shows("the trail's row with `$detail`") { withText("Trail").isNotEmpty() && withText(detail).isNotEmpty() }

    /** Waits until [isSo], and says what was waited for and what the screen held if it never is. */
    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s; on screen: ${nodes().mapNotNull { it.text?.toString() }.filter { it.isNotEmpty() }}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
    }

    @Test fun whileATrailIsLaidTheRowDropsACrumbAsASinglePressOfTheSideButtonDoes() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            toWaypoints()
            vm.trailDoublePress().join()
            assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)
            trailRowSays("Laying, 0 crumbs")
            press()
            assertEquals("Crumb 1", savedName(vm, 1))
            assertTrue(dropCrumbRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals("Crumb 2", savedName(vm, 2))
            assertEquals(2, crumbDao.all().size)
            assertTrue(dao.observeAll().first().isEmpty())
            assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)
        }
    }

    /** A double press as the view model takes it, then the mode the settings file holds: the switch itself is checked apart from what the list shows. */
    private suspend fun doublePress(vm: CompassViewModel, leaves: TrailMode) {
        vm.trailDoublePress().join()
        assertEquals("the stored mode after the double press", leaves, settings.settings.first().trailMode)
    }

    @Test fun theRowIsThereOnlyWhileATrailIsLaid() = runBlocking {
        // Crumbs are stored, so the trail's own row shows whatever the setting and the trail are doing.
        crumbDao.insert(Crumb(lat = 43.7022, lon = -72.2896, accuracyM = 5f, createdAt = System.currentTimeMillis()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            toWaypoints()
            // The setting off.
            trailRowSays("Off, 1 crumb")
            assertTrue("`Drop crumb` shows with the setting off", withText("Drop crumb").isEmpty())
            // The setting on and the trail off.
            settings.setBreadcrumbTrail(true)
            delay(1_500)
            trailRowSays("Off, 1 crumb")
            assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
            assertTrue("`Drop crumb` shows with the trail off", withText("Drop crumb").isEmpty())
            // Laid: the row is there.
            doublePress(vm, leaves = TrailMode.LAYING)
            trailRowSays("Laying, 1 crumb")
            shows("`Drop crumb`") { withText("Drop crumb").isNotEmpty() }
            dropCrumbRow()
            // Retraced: gone again.
            doublePress(vm, leaves = TrailMode.RETRACING)
            trailRowSays("Retracing, 1 left")
            assertTrue("`Drop crumb` shows while the trail is retraced", withText("Drop crumb").isEmpty())
            // Laid again, then the setting off.
            doublePress(vm, leaves = TrailMode.LAYING)
            trailRowSays("Laying, 1 crumb")
            shows("`Drop crumb`") { withText("Drop crumb").isNotEmpty() }
            settings.setBreadcrumbTrail(false)
            shows("the list without `Drop crumb`") { withText("Drop crumb").isEmpty() }
        }
    }

    /** The row's call, made when no trail is being laid (a tap that lands as the trail stops being laid): nothing is saved and no status shows. */
    @Test fun theRowsCallSavesNothingWhenNoTrailIsBeingLaid() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            toWaypoints()
            // The trail off.
            vm.dropCrumb().join()
            assertEquals(LogStatus.Idle, vm.logStatus.value); assertEquals(0, vm.logPress.value)
            // The trail retraced.
            crumbDao.insert(Crumb(lat = 43.7022, lon = -72.2896, accuracyM = 5f, createdAt = System.currentTimeMillis()))
            settings.setTrailMode(TrailMode.RETRACING)
            trailRowSays("Retracing, 1 left")
            vm.dropCrumb().join()
            delay(2_000)
            assertEquals(LogStatus.Idle, vm.logStatus.value); assertEquals(0, vm.logPress.value)
            assertTrue(dao.observeAll().first().isEmpty())
            assertEquals(1, crumbDao.all().size)
        }
    }
}
