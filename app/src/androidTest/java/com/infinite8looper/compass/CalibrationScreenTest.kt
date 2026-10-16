package com.infinite8looper.compass

import android.app.Application
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.abs

/**
 * The calibration screen on the real activity (v3 spec, "Calibration"): the words ask for a motion a wrist can make,
 * the figure 8 is gone, a ring of segments shows the directions the measured field has pointed in, labeled
 * `Covered`, beside the magnetometer's level; the screen is kept on while calibration is open, for two minutes from
 * opening or from the last new segment.
 *
 * The field is the emulator's own magnetometer, as its sensor console set it: scripts/all-suites.sh lays the virtual
 * watch flat at a magnetic heading of 40, a field of (-12.86, 15.32, -45) microtesla, which points through the back
 * of the watch toward its upper left: one region of the 24 (nav/Calibration.kt), 4 %. Lighting further segments
 * needs the field changed through the console, which scripts/calibration-check.sh does from this machine, and which
 * one test here asks for in the log, for scripts/field-requests.sh to pass on to the console (scripts/all-suites.sh
 * and scripts/run-test.sh start it).
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

    /** What a screen reader reads of a node: its text, or else its description. */
    private fun AccessibilityNodeInfo.spoken(): String = (text ?: contentDescription)?.toString().orEmpty()

    /**
     * What a screen reader reads when it stops on a node: the node's own words, then those of the nodes under it that
     * it does not stop on by themselves, in order (how TalkBack reads a node that groups its children).
     */
    private fun AccessibilityNodeInfo.readAsOne(): String {
        val words = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, top: Boolean) {
            if (!top && (n.isScreenReaderFocusable || n.isFocusable)) return
            n.spoken().takeIf { it.isNotEmpty() }?.let { words += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, false) }
        }
        walk(this, true)
        return words.joinToString(", ")
    }

    /**
     * A screen reader stops on `Covered` and the share as one ("Covered, 4%"): one node of the system's tree that it
     * stops on holds both, and that node is a polite live region, so a share that grows is announced.
     */
    @Test fun theShareIsReadWithItsLabelAsOneAndAGrowingShareIsAnnounced() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCalibration()
            val coveredAndShare = Regex("Covered, \\d{1,3}%")
            val deadline = SystemClock.uptimeMillis() + 10_000
            var node: AccessibilityNodeInfo? = null
            while (node == null) {
                val stops = nodes().filter { it.isVisibleToUser && (it.isScreenReaderFocusable || it.isFocusable) }
                node = stops.firstOrNull { coveredAndShare.matches(it.readAsOne()) }
                if (node == null) {
                    assertTrue(
                        "no node a screen reader stops on reads `Covered` and the share as one; it stops on ${stops.map { it.readAsOne() }}, " +
                            "words on screen ${wordsOnScreen()}",
                        SystemClock.uptimeMillis() < deadline,
                    )
                    delay(200)
                }
            }
            Log.i("CalibrationScreenTest", "read as: ${node.readAsOne()}, live region ${node.liveRegion}")
            assertEquals("the share's live region (read as `${node.readAsOne()}`)", View.ACCESSIBILITY_LIVE_REGION_POLITE, node.liveRegion)
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

    /** The field the magnetometer reads now, in microtesla, from the sensor itself. */
    private suspend fun fieldNow(): FloatArray? {
        val sensors = app.getSystemService(SensorManager::class.java)
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return null
        var values: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { values = event.values.copyOf(3) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        try {
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (values == null && SystemClock.uptimeMillis() < deadline) delay(50)
        } finally { sensors.unregisterListener(listener) }
        return values
    }

    /**
     * Asks for the emulator's magnetic field to be set to [field] (x:y:z microtesla) through its sensor console:
     * the request goes to the log, where scripts/field-requests.sh reads it and passes it to the console. Returns
     * once the magnetometer reads it.
     */
    private suspend fun setField(field: String) {
        val wanted = field.split(":").map { it.toFloat() }
        Log.i(FIELD_REQUEST_TAG, "magnetic-field $field")
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            val now = fieldNow()
            if (now != null && (0..2).all { abs(now[it] - wanted[it]) < 0.5f }) return
            assertTrue(
                "the magnetometer did not read $field in 15 s (it reads ${now?.joinToString(":")}): the field is set " +
                    "through the console by scripts/field-requests.sh, which scripts/all-suites.sh and scripts/run-test.sh start",
                SystemClock.uptimeMillis() < deadline,
            )
            delay(250)
        }
    }

    private fun percent(share: String?) = share?.removeSuffix("%")?.toIntOrNull()

    /** Waits up to 10 s for the share shown to be one [isSo] accepts, and gives it. */
    private suspend fun shareWhen(what: String, isSo: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            shareShown()?.let { if (isSo(it)) return it }
            assertTrue("$what did not come in 10 s: share ${shareShown()}, on screen ${wordsOnScreen()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
    }

    /** Reads the share shown every 100 ms for [ms] and gives every reading. */
    private suspend fun sharesFor(ms: Long): List<String?> {
        val out = ArrayList<String?>()
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { out += shareShown(); delay(100) }
        return out
    }

    /**
     * The regions lit are kept across a visit to the check screen (calibration's `Check` row, then Back), and a new
     * opening of calibration from the settings starts afresh. The field is changed through the emulator's sensor
     * console: from the field of the watch lying flat at 40 (one region) to the field of the watch face down (another
     * region), and back, so two regions are lit while the field points into one.
     */
    @Test fun theRegionsLitAreKeptAcrossAVisitToTheCheckScreenAndANewOpeningStartsAfresh() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                setField(FLAT_AT_40)
                openCalibration()
                val one = shareWhen("the flat field's segment lit") { it != "0%" }
                setField(FACE_DOWN)
                val two = shareWhen("the face-down field's segment lit") { (percent(it) ?: 0) > percent(one)!! }
                setField(FLAT_AT_40)
                delay(1_500)
                assertEquals("the share after the field went back to the flat one", two, shareShown())
                Log.i("CalibrationScreenTest", "lit: $one with the flat field, $two with the face-down one too")

                val check = nodes().first { it.isVisibleToUser && it.text?.toString() == "Check" }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }
                assertTrue(check.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                shows("the check screen") { visible("Heading source") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                shows("calibration again") { visible(INSTRUCTION) }
                val back = sharesFor(3_000)
                assertTrue("back from the check screen the share was not $two throughout: $back", back.all { it == two })

                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                shows("the settings again") { visible("Calibrate") }
                val row = nodes().first { it.text?.toString() == "Calibrate" }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }
                assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                shows("calibration opened again") { visible(INSTRUCTION) }
                val again = sharesFor(3_000)
                assertTrue("opened again, calibration showed the earlier share $two: $again", again.none { it == two })
                assertEquals("opened again, the share with the flat field alone: $again", one, again.last())
            } finally {
                setField(FLAT_AT_40)
            }
        }
        Unit
    }

    private companion object {
        const val INSTRUCTION = "Turn your wrist slowly in every direction"
        /** The tag scripts/field-requests.sh reads. */
        const val FIELD_REQUEST_TAG = "CompassFieldRequest"
        /** The field scripts/set-heading.sh 40 sets: the watch flat at a magnetic heading of 40. */
        const val FLAT_AT_40 = "-12.856:15.321:-45"
        /** An earth field of (0, 20, -45) seen with the watch face down (as CalibrationTest and calibration-check.sh). */
        const val FACE_DOWN = "0:20:45"
    }
}
