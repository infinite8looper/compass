package com.infinite8looper.compass

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect.Composition
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.VibrationRecord.constantOf
import com.infinite8looper.compass.VibrationRecord.primitivesOf
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.HeadingKind
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.colorHex
import com.infinite8looper.compass.sensors.WatchButtons
import com.infinite8looper.compass.ui.Feel
import com.infinite8looper.compass.ui.watchScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The check screen on the real activity, in the debug build, whose diagnostics switch is on (v3 spec, "Check
 * screen"): reached from a row on calibration; it shows live the three headings, the estimated heading error, the
 * magnetometer's level and field strength, what the watch returned for its colors, whether it has an assignable
 * button and the one-handed framework, which vibration primitives it supports; a tap plays each of the three dial
 * vibrations; and a row switches the compass's heading source for the running app.
 *
 * scripts/all-suites.sh lays the virtual watch flat at a magnetic heading of 40 through the sensor console, so the
 * three headings read about 40. What the watch answered is compared with what this test asks the system itself.
 */
@RunWith(AndroidJUnit4::class)
class CheckScreenTest {
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
    @After fun tearDown() = reset()

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

    /** The value a row of the check screen shows for [label]: the row is read as `label: value`. */
    private fun valueOf(label: String): String? = nodes().firstNotNullOfOrNull { n ->
        n.contentDescription?.toString()?.takeIf { it.startsWith("$label: ") }?.removePrefix("$label: ")
    }
    private fun rowVisible(label: String) = nodes().any { n -> n.isVisibleToUser && n.contentDescription?.toString()?.startsWith("$label: ") == true }

    /** Scrolls the list that holds [anchor] until [isThere] says what is looked for is on screen. */
    private suspend fun scrollUntil(what: String, anchor: String, isThere: () -> Boolean) {
        if (isThere()) return
        // The list is the one scrolling list that holds [anchor] or, once that has been scrolled away, any of the
        // check screen's own rows (read as `label: value`) or its parts' names.
        fun listOf(n: AccessibilityNodeInfo) = generateSequence(n) { it.parent }.firstOrNull { it.isScrollable && (it.collectionInfo?.columnCount ?: 1) <= 1 }
        val list = nodes().filter { it.text?.toString() == anchor || it.contentDescription?.toString()?.startsWith("$anchor: ") == true }.firstNotNullOfOrNull(::listOf)
            ?: nodes().filter { n -> n.text?.toString() in CHECK_WORDS || CHECK_LABELS.any { n.contentDescription?.toString()?.startsWith("$it: ") == true } }
                .firstNotNullOfOrNull(::listOf)
            ?: fail("no list of the check screen to scroll; on screen: ${wordsOnScreen()}") as Nothing
        repeat(30) {
            if (isThere()) { delay(500); return }
            list.refresh()
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(600)
        }
        fail("`$what` did not come on screen; on screen: ${wordsOnScreen()}")
    }

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

    private fun clickRowOf(text: String) {
        val row = nodes().first { it.isVisibleToUser && it.text?.toString() == text }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }
        assertTrue("`$text` could not be clicked", row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun openCalibration() {
        shows("the compass") { compassInView() }
        swipeToSettings()
        shows("the settings") { visible("Settings") }
        scrollUntil("Calibrate", "Settings") { visible("Calibrate") }
        clickRowOf("Calibrate")
        shows("calibration") { visible(INSTRUCTION) }
    }

    private suspend fun openCheck() {
        openCalibration()
        assertTrue("calibration has the row that opens the check screen: ${wordsOnScreen()}", visible(CHECK_ROW))
        clickRowOf(CHECK_ROW)
        shows("the check screen") { visible(SOURCE_ROW) }
    }

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    private fun degreesOf(text: String?): Int? = text?.removeSuffix("°")?.toIntOrNull()
    private fun near40(text: String?) = degreesOf(text)?.let { abs(((it - 40 + 540) % 360) - 180) <= 5 } == true

    @Test fun calibrationOpensTheCheckScreenWhichShowsTheThreeHeadingsTheErrorTheLevelAndTheFieldLive() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCheck()
            // All three headings are worked out while it is open, whichever source the compass is on. Each is read
            // once it is scrolled onto the screen: the list composes only the rows near it.
            for (label in listOf("Fused", "Magnetic", "Blend")) {
                scrollUntil(label, SOURCE_ROW) { rowVisible(label) }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (!near40(valueOf(label))) {
                    assertTrue("$label did not read about 40 in 15 s: ${valueOf(label)}", SystemClock.uptimeMillis() < deadline)
                    delay(200)
                }
            }
            // The emulators' rotation vector gives no estimate (values[4] is 0), so the error reads none there.
            scrollUntil("Field", SOURCE_ROW) { rowVisible("Error") && rowVisible("Level") && rowVisible("Field") }
            assertEquals("--", valueOf("Error"))
            assertTrue("level ${valueOf("Level")}", valueOf("Level") in listOf("High", "Medium", "Low", "Unreliable", "Unknown"))
            // The console's field of (-12.86, 15.32, -45) microtesla is 49 microtesla strong.
            assertEquals("49 µT", valueOf("Field"))
            Log.i("CheckScreenTest", "API ${Build.VERSION.SDK_INT}: ${wordsOnScreen()}")
        }
        Unit
    }

    @Test fun theSourceRowSwitchesTheCompassesHeadingSourceForTheRunningApp() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            openCheck()
            assertEquals(HeadingKind.FUSED, vm.headingKind.value)
            assertTrue("the row says the source: ${wordsOnScreen()}", visible("Fused"))
            for (kind in listOf(HeadingKind.MAGNETIC, HeadingKind.BLEND, HeadingKind.FUSED, HeadingKind.MAGNETIC)) {
                clickRowOf(SOURCE_ROW)
                val name = kind.name.lowercase().replaceFirstChar { it.uppercase() }
                shows("the row saying $name") { visible(name) }
                assertEquals(kind, vm.headingKind.value)
            }
            // Back on the compass the source stays as switched: the compass's readings are of it.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("calibration again") { visible(INSTRUCTION) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("the settings again") { visible("Calibrate") }
            assertEquals(HeadingKind.MAGNETIC, vm.headingKind.value)
            val reading = withTimeout(15_000) { vm.heading.first { it?.kind == HeadingKind.MAGNETIC } }!!
            assertEquals("$reading", reading.geomagneticDeg, reading.magneticDeg, 0f)
        }
        Unit
    }

    @Test fun eachOfThePlayRowsPlaysItsDialVibration() = runBlocking {
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        val names = mapOf(Composition.PRIMITIVE_TICK to "TICK", Composition.PRIMITIVE_THUD to "THUD", Composition.PRIMITIVE_CLICK to "CLICK")
        ActivityScenario.launch(MainActivity::class.java).use {
            openCheck()
            for ((row, feel) in listOf(PLAY_THIN to Feel.THIN, PLAY_THICK to Feel.THICK, PLAY_TARGET to Feel.TARGET)) {
                scrollUntil(row, SOURCE_ROW) { visible(row) }
                val mark = System.currentTimeMillis()
                clickRowOf(row)
                // The system adds a vibration to its record when it has ended.
                delay(1_500)
                val lines = VibrationRecord.lines(mark)
                Log.i("CheckScreenTest", "API ${Build.VERSION.SDK_INT}: $row recorded $lines")
                assertEquals("$row: $lines", 1, lines.size)
                val line = lines.single()
                if (vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported(*feel.primitives)) {
                    assertEquals("$row: $line", feel.primitives.map { names.getValue(it) }, primitivesOf(line))
                } else if (Build.VERSION.SDK_INT >= 35) {
                    assertEquals("$row: $line", feel.constantFrom34, constantOf(line))
                }
            }
        }
        Unit
    }

    @Test fun itShowsWhatTheWatchAnsweredForItsColorsButtonsOneHandedGesturesAndPrimitives() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCheck()
            // The colors as the library gives them to the app.
            val scheme = watchScheme(app)
            val expectedColors = if (scheme == null) listOf("Color scheme" to "None") else listOf(
                "Primary" to scheme.primary, "Tertiary" to scheme.tertiary, "Error color" to scheme.error,
                "On surface" to scheme.onSurface!!, "On surface variant" to scheme.onSurfaceVariant!!, "Outline" to scheme.outline!!,
            ).map { (label, color) -> label to colorHex(color) }
            for ((label, value) in expectedColors) {
                scrollUntil(label, SOURCE_ROW) { rowVisible(label) }
                assertEquals(label, value, valueOf(label))
            }
            // The buttons, as the watch's buttons library answers.
            val buttons = WatchButtons.report(app)
            scrollUntil("Buttons", SOURCE_ROW) { rowVisible("Buttons") }
            assertEquals(if (buttons.buttonCount < 0) "Unknown" else buttons.buttonCount.toString(), valueOf("Buttons"))
            scrollUntil("Assignable button", SOURCE_ROW) { rowVisible("Assignable button") }
            assertEquals(if (buttons.assignable) "Yes" else "No", valueOf("Assignable button"))
            // The one-handed gestures: the system's Wear SDK library, whether the app can reach it, and whether
            // the system says it has gesture detection, asked here of the system and of the SDK directly.
            val listed = app.packageManager.systemSharedLibraryNames?.contains("wear-sdk") == true
            val sdk = try { Class.forName("com.google.wear.Sdk", false, app.classLoader) } catch (e: ClassNotFoundException) { null }
            val gestures = sdk?.getMethod("hasApiFeature", String::class.java)?.invoke(null, "com.google.wear.feature.GESTURE_DETECTION") == true
            Log.i("CheckScreenTest", "API ${Build.VERSION.SDK_INT}: wear-sdk listed $listed, reached ${sdk != null}, gesture detection $gestures")
            for ((label, value) in listOf("Wear SDK" to listed, "Wear SDK reached" to (sdk != null), "One-handed gestures" to gestures)) {
                scrollUntil(label, SOURCE_ROW) { rowVisible(label) }
                assertEquals(label, if (value) "Yes" else "No", valueOf(label))
            }
            // Each composition primitive, as the vibrator answers.
            val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
            for ((label, primitive) in PRIMITIVES) {
                scrollUntil(label, SOURCE_ROW) { rowVisible(label) }
                assertEquals(label, if (vibrator.areAllPrimitivesSupported(primitive)) "Yes" else "No", valueOf(label))
            }
        }
        Unit
    }

    private companion object {
        const val INSTRUCTION = "Turn your wrist slowly in every direction"
        const val CHECK_ROW = "Check"
        const val SOURCE_ROW = "Heading source"
        const val PLAY_THIN = "Play thin line"
        const val PLAY_THICK = "Play thick line"
        const val PLAY_TARGET = "Play target"
        /** Words of the check screen's own, and the labels of its rows that show a value. */
        val CHECK_WORDS = setOf(SOURCE_ROW, "Watch colors", "Inputs", "Vibrations", PLAY_THIN, PLAY_THICK, PLAY_TARGET)
        val CHECK_LABELS = listOf(
            "Fused", "Magnetic", "Blend", "Error", "Level", "Field", "Color scheme", "Primary", "Tertiary", "Error color", "On surface",
            "On surface variant", "Outline", "Buttons", "Side keys", "Assignable button", "Wear SDK", "Wear SDK reached", "One-handed gestures",
            "Click", "Thud", "Spin", "Quick rise", "Slow rise", "Quick fall", "Tick", "Low tick",
        )
        val PRIMITIVES = listOf(
            "Click" to Composition.PRIMITIVE_CLICK, "Thud" to Composition.PRIMITIVE_THUD, "Spin" to Composition.PRIMITIVE_SPIN,
            "Quick rise" to Composition.PRIMITIVE_QUICK_RISE, "Slow rise" to Composition.PRIMITIVE_SLOW_RISE,
            "Quick fall" to Composition.PRIMITIVE_QUICK_FALL, "Tick" to Composition.PRIMITIVE_TICK, "Low tick" to Composition.PRIMITIVE_LOW_TICK,
        )
    }
}
