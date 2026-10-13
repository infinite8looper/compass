package com.infinite8looper.compass

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system's font size, changed the way the watch's own settings change it (the system setting `font_scale`), with
 * the real activity on screen: the menus' text follows it and the compass's does not. Sizes are read from the
 * system's accessibility tree, as bounds on the screen.
 */
@RunWith(AndroidJUnit4::class)
class FontScaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scaleBefore: String

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString().trim() }

    private fun setFontScale(scale: String) {
        shell("settings put system font_scale $scale")
        assertEquals(scale, shell("settings get system font_scale"))
        // The activity is made again for the new size.
        SystemClock.sleep(3_000)
    }

    @Before fun setUp() {
        scaleBefore = shell("settings get system font_scale").let { if (it.toFloatOrNull() == null) "1.0" else it }
    }
    @After fun tearDown() { shell("settings put system font_scale $scaleBefore") }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    /** Waits for a node that [isIt], on screen, and gives where it is. */
    private fun where(what: String, isIt: (String) -> Boolean): Rect {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            nodes().firstOrNull { n -> n.isVisibleToUser && n.text?.toString()?.let(isIt) == true }?.let { return bounds(it) }
            assertTrue("$what did not show in 15 s", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(250)
        }
    }

    /** The compass's large line: the heading number, or on a watch held upright the words of the hold-flat hint. */
    private fun compassLine() = where("the compass's heading or its hold-flat hint") { it == "Hold flat" || it == "--" || it.endsWith("°") }

    /** Back turns the row of screens one screen toward settings: from the compass, two of them end on the waypoints. */
    private fun logHere(): Rect {
        repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(1_500) }
        return where("`Log here`") { it == "Log here" }
    }

    @Test fun aListRowsTextIsLargerAtALargerFontSizeAndTheCompassHeadingIsNot() {
        setFontScale("1.0")
        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(1_500)
            val compassDefault = compassLine()
            val rowDefault = logHere()

            // The largest step of the watch's own font size setting. The activity starts again, on the compass.
            setFontScale("1.24")
            val compassLarge = compassLine()
            val rowLarge = logHere()

            assertEquals("the compass's line moved or changed size", compassDefault, compassLarge)
            val grew = rowLarge.height().toFloat() / rowDefault.height()
            assertTrue("`Log here` was ${rowDefault.height()} px tall and is ${rowLarge.height()} px", grew in 1.18f..1.30f)
            assertTrue("`Log here` was ${rowDefault.width()} px wide and is ${rowLarge.width()} px", rowLarge.width() > rowDefault.width())

            // And the smallest.
            setFontScale("0.94")
            assertEquals("the compass's line moved or changed size", compassDefault, compassLine())
            val rowSmall = logHere()
            assertTrue("`Log here` was ${rowDefault.height()} px tall and is ${rowSmall.height()} px", rowSmall.height() < rowDefault.height())
        }
    }
}
