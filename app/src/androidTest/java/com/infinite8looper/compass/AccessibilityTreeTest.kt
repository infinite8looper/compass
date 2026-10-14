package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the real activity gives accessibility services, read as a screen reader reads it: through the system's
 * own tree of the window, with nothing of the app's replaced.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTreeTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val crumbDao = WaypointDatabase.get(app).crumbs()
    private val settings = SettingsStore.get(app)

    // The test of the dial's action saves a waypoint, so the stores are emptied around every test.
    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        settings.setTrailMode(TrailMode.OFF)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all
    }

    /** The app's nodes, once the compass has drawn either its heading or, on a watch held upright, the hold-flat hint. */
    private fun compassNodes(): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            val all = nodes().filter { it.packageName == "com.infinite8looper.compass" }
            val drawn = all.any { it.text?.toString() == "Hold flat" || it.text?.toString()?.let { t -> t == "--" || t.endsWith("°") } == true }
            if (drawn) return all
            assertTrue("the compass did not show a heading or the hold-flat hint in 15 s", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(250)
        }
    }

    @Test fun theRowOfThreeScreensIsACollectionOfThree() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val collections = compassNodes().mapNotNull { it.collectionInfo }
            assertTrue("no collection in the tree", collections.isNotEmpty())
            // The row is laid out as millions of pages so that it wraps; a screen reader is told of the three screens.
            assertTrue("columns reported: ${collections.map { it.columnCount }}", collections.all { it.columnCount <= 3 })
            assertTrue("columns reported: ${collections.map { it.columnCount }}", collections.any { it.columnCount == 3 && it.rowCount == 1 })
        }
    }

    private fun AccessibilityNodeInfo.words() = text?.toString()
    private fun AccessibilityNodeInfo.isHeadingNumber() = words()?.let { it == "--" || it.endsWith("°") } == true

    /**
     * The heading number is on screen only while the watch is held flat enough: a watch held upright shows the
     * hold-flat hint in its place, and there is then no heading to describe, so the test is skipped there and
     * counted as skipped. `scripts/all-suites.sh` lays each emulator flat before the suite, so it runs.
     */
    @Test fun theHeadingIsDescribedWithItsNameAndUnit() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val all = compassNodes()
            val heading = all.firstOrNull { it.isHeadingNumber() }
            assumeTrue("skipped: the watch is upright (hold-flat), so there is no heading number on screen to describe", heading != null)
            val spoken = heading!!.contentDescription?.toString()
            assertNotNull("the heading ${heading.text} has no description", spoken)
            assertTrue("described as: $spoken", Regex("Heading (unknown|\\d{1,3} degrees?(, magnetic)?)").matches(spoken!!))
            // The number in the description is the number shown.
            if (heading.words() != "--") assertTrue(spoken.startsWith("Heading ${heading.words()!!.removeSuffix("°")} degree"))
            // `magnetic` is said exactly while `MAG` shows, and never for a heading that is not known.
            val mag = all.any { it.words() == "MAG" }
            if (heading.words() != "--") assertEquals("MAG on screen: $mag, described as: $spoken", mag, spoken.endsWith(", magnetic"))
        }
    }

    /** Whichever of the two the watch's pose gives, the tree holds that one and nothing left over from the other. */
    @Test fun theTreeHoldsEitherTheHeadingOrTheHoldFlatHintAndNeverBoth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val all = compassNodes()
            val numbers = all.filter { it.isHeadingNumber() }
            val hints = all.filter { it.words() == "Hold flat" }
            val described = all.filter { it.contentDescription?.toString()?.startsWith("Heading") == true }
            assertEquals("heading numbers ${numbers.size}, hold-flat hints ${hints.size}", 1, numbers.size + hints.size)
            if (hints.isNotEmpty()) {
                // The description does not outlive the number, and no text in the readout ends in a degree sign.
                assertTrue("in hold-flat something is still described as a heading: ${described.map { it.contentDescription }}", described.isEmpty())
                assertTrue(all.none { it.words()?.endsWith("°") == true })
            } else {
                assertEquals("one heading, one description", 1, described.size)
                assertEquals("the description is the heading number's own", numbers.single(), described.single())
            }
        }
    }

    private fun AccessibilityNodeInfo.saveAction() = actionList.firstOrNull { it.label?.toString() == "Save waypoint here" }

    /**
     * The dial has an action of its own for saving, with a name, so that saving does not depend on a gesture: an
     * accessibility service finds it on the dial, and performing it does what a double tap does. It needs
     * `scripts/geo-feed.sh -72.2896 43.7022` running, as the other tests that save do.
     */
    @Test fun theDialHasANamedActionThatSavesAndOnlyWhileTheCompassIsInView() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CompassViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
            val all = compassNodes()
            val withAction = all.filter { it.saveAction() != null }
            assertEquals("nodes with an action named `Save waypoint here`: ${all.flatMap { n -> n.actionList.mapNotNull { it.label } }}", 1, withAction.size)
            val dial = withAction.single()
            // It is the dial: the node is as large as the window, takes the focus, and holds the heading or the hint.
            val window = Rect().also { InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            assertEquals(window, Rect().also { dial.getBoundsInScreen(it) })
            assertTrue("the dial's node is not visible", dial.isVisibleToUser)
            // It has a name of its own, so a screen reader stops on it and can offer the action.
            assertEquals("Compass", dial.contentDescription?.toString())
            assertTrue((0 until dial.childCount).mapNotNull { dial.getChild(it)?.text?.toString() }.any { it == "Hold flat" || it == "--" || it.endsWith("°") })

            assertEquals(0, vm.logPress.value)
            assertTrue("the action was refused", dial.performAction(dial.saveAction()!!.id))
            withTimeout(5_000) { while (vm.logPress.value < 1) delay(10) }
            withTimeout(60_000) { while (vm.logStatus.value !is LogStatus.Saved) delay(20) }
            assertEquals(1, dao.observeAll().first().size)
            assertEquals(1, vm.logPress.value)

            // With another screen in view the action does nothing, and says so. Back turns the ring to settings.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (nodes().none { it.isVisibleToUser && it.text?.toString() == "Settings" }) {
                assertTrue("the settings did not come in 10 s", SystemClock.uptimeMillis() < deadline)
                delay(100)
            }
            delay(700)
            // The dial is read again from the system's tree. Either it has left the tree, so no service can ask for
            // the action (the node read before can no longer be refreshed), or it is still there, off the screen,
            // and the action is refused. Each case is checked; neither passes having checked nothing.
            val again = nodes().filter { it.packageName == "com.infinite8looper.compass" && it.saveAction() != null }
            assertTrue("nodes with the action, with the settings in view: ${again.size}", again.size <= 1)
            if (again.isEmpty()) {
                assertFalse("the dial has no action in the tree, yet the node read before is still the system's", dial.refresh())
            } else {
                assertFalse("the dial is said to be on screen with the settings in view", again.single().isVisibleToUser)
                assertFalse("the action was taken with the settings in view", again.single().performAction(again.single().saveAction()!!.id))
            }
            delay(1_000)
            assertEquals(1, vm.logPress.value)
            assertEquals(1, dao.observeAll().first().size)
        }
    }
}
