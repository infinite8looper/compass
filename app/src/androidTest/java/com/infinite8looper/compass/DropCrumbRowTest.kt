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
 * The `Drop crumb` row of the waypoints screen, in the real activity with the real database: the row is found in
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
        delay(1_500)
        repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); delay(1_500) }
        withTimeout(10_000) { while (withText("Log here").none { it.isVisibleToUser }) delay(100) }
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

    @Test fun theRowDoesWhatASinglePressOfTheSideButtonDoesWhateverTheTrailIsDoing() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            toWaypoints()

            // Laying: the press drops a crumb, and so does the row.
            vm.trailDoublePress().join()
            assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)
            press()
            assertEquals("Crumb 1", savedName(vm, 1))
            assertTrue(dropCrumbRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals("Crumb 2", savedName(vm, 2))
            assertEquals(2, crumbDao.all().size)
            assertTrue(dao.observeAll().first().isEmpty())

            // Retracing: the press saves a waypoint and leaves the trail alone, and so does the row.
            vm.trailDoublePress().join()
            assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
            // The number in a waypoint's name goes on from the last one the app gave, on this device.
            press()
            val byPress = savedName(vm, 3)
            assertEquals(listOf(byPress), dao.observeAll().first().map { it.name })
            assertTrue(dropCrumbRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val byRow = savedName(vm, 4)
            assertEquals(listOf(byRow, byPress), dao.observeAll().first().map { it.name })
            assertTrue("saved $byPress and $byRow", byPress!!.startsWith("Waypoint ") && byRow!!.startsWith("Waypoint ") && byRow != byPress)
            assertEquals(2, crumbDao.all().size)
            assertEquals(2, dao.observeAll().first().size)
            assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
        }
    }

    @Test fun withNoTrailTheRowSavesAWaypointAsASinglePressDoes() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            toWaypoints()
            assertTrue(dropCrumbRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val byRow = savedName(vm, 1)
            assertEquals(listOf(byRow), dao.observeAll().first().map { it.name })
            press()
            val byPress = savedName(vm, 2)
            assertEquals(listOf(byPress, byRow), dao.observeAll().first().map { it.name })
            assertTrue("saved $byRow and $byPress", byRow!!.startsWith("Waypoint ") && byPress!!.startsWith("Waypoint ") && byRow != byPress)
            assertTrue(crumbDao.all().isEmpty())
            assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        }
    }

    @Test fun theRowIsThereOnlyWhileTheSettingIsOn() = runBlocking {
        // Crumbs are stored, so the trail's own row shows with the setting off, and `Drop crumb` does not.
        crumbDao.insert(Crumb(lat = 43.7022, lon = -72.2896, accuracyM = 5f, createdAt = System.currentTimeMillis()))
        ActivityScenario.launch(MainActivity::class.java).use {
            toWaypoints()
            withTimeout(10_000) { while (withText("Trail").isEmpty()) delay(100) }
            assertTrue("`Drop crumb` shows with the setting off", withText("Drop crumb").isEmpty())
            settings.setBreadcrumbTrail(true)
            withTimeout(10_000) { while (withText("Drop crumb").isEmpty()) delay(100) }
            dropCrumbRow()
            settings.setBreadcrumbTrail(false)
            withTimeout(10_000) { while (withText("Drop crumb").isNotEmpty()) delay(100) }
        }
    }
}
