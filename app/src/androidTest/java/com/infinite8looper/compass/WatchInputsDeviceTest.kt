package com.infinite8looper.compass

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.graphics.Rect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.input.WearableButtons
import com.infinite8looper.compass.nav.hasAssignableButton
import com.infinite8looper.compass.nav.trailSettingDetail
import com.infinite8looper.compass.sensors.WatchButtons
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app reads of the watch's buttons, on the watch the tests run on, and what the settings then say of the
 * breadcrumb trail. Like WatchColorsDeviceTest, each test checks whichever case that watch gives.
 */
@RunWith(AndroidJUnit4::class)
class WatchInputsDeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The emulator's own hardware name, on every Wear OS image the suites run on. */
    private val onAnEmulator = Build.HARDWARE == "ranchu"

    @Test fun theReportIsWhatTheWatchSaysOfItsButtonsAndIsKept() {
        val report = WatchButtons.report(ctx)
        Log.i("WatchInputsDeviceTest", "API ${Build.VERSION.SDK_INT}: button count ${report.buttonCount}, side keys ${report.sideKeys}, assignable ${report.assignable}")
        // The same as the watch's own answers, asked here again.
        assertEquals(WearableButtons.getButtonCount(ctx), report.buttonCount)
        assertEquals(WatchButtons.SIDE_KEYS.filter { WearableButtons.getButtonInfo(ctx, it) != null }, report.sideKeys)
        assertEquals(hasAssignableButton(report.buttonCount, report.sideKeys.size), report.assignable)
        assertEquals(report.assignable, WatchButtons.assignable(ctx))
        // Asked once: the second answer is the first one, kept.
        assertSame(report, WatchButtons.report(ctx))
    }

    /**
     * The Wear OS emulator images say they have no button at all, so every other device test runs the case of a
     * watch without a button the app can act on. If an image ever says otherwise this fails, and says what it said:
     * SideButtonTest then runs its other case.
     */
    @Test fun theEmulatorImagesDescribeNoButtonAnAppCanActOn() {
        val report = WatchButtons.report(ctx)
        if (onAnEmulator) {
            assertEquals("the emulator's button count (API ${Build.VERSION.SDK_INT})", 0, report.buttonCount)
            assertEquals("the side keys the emulator describes (API ${Build.VERSION.SDK_INT})", emptyList<Int>(), report.sideKeys)
            assertFalse(report.assignable)
        } else {
            // A watch: whatever it says, the three side keys are the only ones asked about.
            assertTrue(report.sideKeys.all { it in listOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3) })
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }
    private fun words() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
    private fun waitFor(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s; on screen: ${words()}", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(100)
        }
    }

    /** One finger from a place straight up by [far] pixels, slowly enough that the list is moved and not flung. */
    private fun dragUp(x: Float, fromY: Float, far: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fromY)
        for (i in 1..10) { SystemClock.sleep(30); send(MotionEvent.ACTION_MOVE, fromY - far * i / 10) }
        SystemClock.sleep(150)
        send(MotionEvent.ACTION_UP, fromY - far)
    }

    /** The settings row of the breadcrumb trail, in the real activity: it speaks of a press only on a watch with a button. */
    @Test fun theTrailsRowInSettingsSpeaksOfAPressOnlyOnAWatchWithAButton() {
        val assignable = WatchButtons.assignable(ctx)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("the compass") { words().any { it == "Hold flat" || it == "--" || it.endsWith("°") } }
            SystemClock.sleep(1_000)
            // Back turns the ring one screen toward settings.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the settings") { "Settings" in words() }
            // The row is some way down the list: a finger moves the list up a fifth of the screen at a time.
            val window = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            var steps = 0
            while ("Breadcrumb trail" !in words() && steps++ < 20) {
                dragUp(window.exactCenterX(), window.exactCenterY() + window.height() * 0.1f, window.height() * 0.2f)
                SystemClock.sleep(700)
            }
            waitFor("the `Breadcrumb trail` row") { "Breadcrumb trail" in words() }
            // The row is the switch: the nearest thing around the label that can be checked. Its words are the
            // label and the smaller line under it.
            var row: AccessibilityNodeInfo? = nodes().first { it.isVisibleToUser && it.text?.toString() == "Breadcrumb trail" }
            while (row != null && !row.isCheckable) row = row.parent
            assertNotNull("nothing around `Breadcrumb trail` is a switch", row)
            val lines = ArrayList<String>()
            fun gather(n: AccessibilityNodeInfo) { n.text?.toString()?.let { lines += it }; for (i in 0 until n.childCount) n.getChild(i)?.let(::gather) }
            gather(row!!)
            assertEquals(listOf("Breadcrumb trail", trailSettingDetail(assignable)), lines)
            if (!assignable) {
                assertEquals(listOf("Breadcrumb trail", "Trail row on waypoints"), lines)
                assertTrue("the settings speak of a press on a watch with no button: ${words()}", words().none { it.contains("press", ignoreCase = true) })
            } else assertEquals(listOf("Breadcrumb trail", "Double press starts a trail"), lines)
        }
    }
}
