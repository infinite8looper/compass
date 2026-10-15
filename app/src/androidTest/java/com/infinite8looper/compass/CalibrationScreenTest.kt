package com.infinite8looper.compass

import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.TrailMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The calibration screen on the real activity (v3 spec, "Calibration"): the words ask for a motion a wrist can make,
 * the figure 8 is gone, a ring of segments shows the directions the measured field has pointed in, labeled
 * `Covered`, beside the magnetometer's level; the screen is kept on while calibration is open, for two minutes from
 * opening or from the last new segment.
 *
 * The field is the emulator's own magnetometer, as its sensor console set it: scripts/all-suites.sh lays the virtual
 * watch flat at a magnetic heading of 40, a field of (-12.86, 15.32, -45) microtesla, which points through the back
 * of the watch toward its upper left: one region of the 24 (nav/Calibration.kt), 4 %. Lighting further segments
 * needs the field changed through the console, which scripts/calibration-check.sh does from this machine.
 */
@RunWith(AndroidJUnit4::class)
class CalibrationScreenTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val settings = SettingsStore.get(app)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        settings.setTrailMode(TrailMode.OFF)
        settings.setBreadcrumbTrail(false)
        Unit
    }
    @Before fun setUp() = reset()
    @After fun tearDown() {
        reset()
        // The two-minute test lets the screen go to sleep; the next test needs it awake.
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        root.refresh()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let { if (it.refresh()) walk(it) } }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }
    private fun visible(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    private fun wordsOnScreen() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s; on screen: ${wordsOnScreen()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
        delay(700)
    }
    private fun compassInView() = nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } &&
        !visible("Log here") && !visible("Settings")

    private suspend fun scrollTo(text: String, anchor: String) {
        if (visible(text)) return
        val list = nodes().first { it.isVisibleToUser && it.text?.toString() == anchor }
            .let { n -> generateSequence(n) { it.parent }.first { it.isScrollable && (it.collectionInfo?.columnCount ?: 1) <= 1 } }
        repeat(10) {
            if (visible(text)) return
            list.refresh()
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(1_000)
        }
        fail("`$text` did not come on screen; on screen: ${wordsOnScreen()}")
    }

    /** One finger across the middle of the screen: the swipe that turns the ring of screens (as RingScreensTest). */
    private fun swipeToSettings() {
        val bounds = instrumentation.uiAutomation.rootInActiveWindow.let { root -> Rect().also { root.getBoundsInScreen(it) } }
        val y = bounds.exactCenterY()
        val from = bounds.exactCenterX() - bounds.width() * 0.3f
        val to = bounds.exactCenterX() + bounds.width() * 0.3f
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

    private suspend fun openCalibration() {
        shows("the compass") { compassInView() }
        swipeToSettings()
        shows("the settings") { visible("Settings") }
        scrollTo("Calibrate", anchor = "Settings")
        val row = nodes().first { it.text?.toString() == "Calibrate" }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        shows("calibration") { visible(INSTRUCTION) }
    }

    /** Whether any view of the activity's window asks for the screen to be kept on. */
    private fun keptOn(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        fun walk(v: View) { if (v.keepScreenOn) on = true; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        instrumentation.runOnMainSync { scenario.onActivity { walk(it.window.decorView) } }
        return on
    }

    /** The share of the ring lit, as the screen writes it under `Covered`. */
    private fun shareShown(): String? = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.matches(Regex("\\d{1,3}%")) == true }?.text?.toString()

    @Test fun calibrationAsksForAWristMotionAndShowsTheRingCoveredBesideTheLevel() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCalibration()
            assertTrue("the instruction: ${wordsOnScreen()}", visible(INSTRUCTION))
            assertTrue("no word about a figure 8: ${wordsOnScreen()}", wordsOnScreen().none { "figure 8" in it })
            assertTrue("the ring's label: ${wordsOnScreen()}", visible("Covered"))
            assertTrue("the level: ${wordsOnScreen()}", listOf("High", "Medium", "Low", "Unreliable", "Unknown").any { visible(it) })
            // The field the console set lights its segment within moments.
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (shareShown() == null || shareShown() == "0%") {
                assertTrue("no segment lit in 10 s: share ${shareShown()}, on screen ${wordsOnScreen()}", SystemClock.uptimeMillis() < deadline)
                delay(200)
            }
            Log.i("CalibrationScreenTest", "share covered: ${shareShown()}, on screen ${wordsOnScreen()}")
        }
        Unit
    }

    @Test fun theScreenIsKeptOnWhileCalibrationIsOpenAndNotAfterLeavingIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openCalibration()
            assertTrue("calibration open, the screen not kept on", keptOn(scenario))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("the settings again") { visible("Calibrate") }
            assertFalse("back on the settings, the screen still kept on", keptOn(scenario))
        }
    }

    /**
     * With no new segment (the watch lies still), the screen is kept on for two minutes from the opening, then let
     * go: it is still kept on 110 s after, and no longer 130 s after.
     */
    @Test fun withNoNewSegmentTheScreenIsLetGoTwoMinutesAfterOpening() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openCalibration()
            val opened = SystemClock.uptimeMillis()
            assertTrue(keptOn(scenario))
            delay(110_000 - (SystemClock.uptimeMillis() - opened))
            assertTrue("calibration let the screen go before 110 s", keptOn(scenario))
            while (keptOn(scenario)) {
                assertTrue("still kept on 130 s after calibration opened", SystemClock.uptimeMillis() - opened < 130_000)
                delay(250)
            }
            Log.i("CalibrationScreenTest", "let go ${SystemClock.uptimeMillis() - opened} ms after opening")
        }
        Unit
    }

    private companion object { const val INSTRUCTION = "Turn your wrist slowly in every direction" }
}
