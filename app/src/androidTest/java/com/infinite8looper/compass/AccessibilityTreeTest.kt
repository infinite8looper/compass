package com.infinite8looper.compass

import android.Manifest
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
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

    /**
     * The heading number is on screen only while the watch is held flat enough: an emulator in its default pose is
     * upright and shows the hold-flat hint in its place. Where the number shows, it must be described; where the
     * hint shows, there is no heading to describe, and the test says which of the two it saw.
     */
    @Test fun theHeadingIsDescribedWithItsNameAndUnit() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val all = compassNodes()
            val heading = all.firstOrNull { it.text?.toString()?.let { t -> t == "--" || t.endsWith("°") } == true }
            if (heading == null) {
                assertTrue(all.any { it.text?.toString() == "Hold flat" })
                println("AccessibilityTreeTest: hold-flat pose, no heading number on screen")
                return
            }
            val spoken = heading.contentDescription?.toString()
            assertNotNull("the heading ${heading.text} has no description", spoken)
            assertTrue("described as: $spoken", Regex("Heading (unknown|\\d{1,3} degrees?(, magnetic)?)").matches(spoken!!))
            // The number in the description is the number shown.
            if (heading.text.toString() != "--") assertTrue(spoken.startsWith("Heading ${heading.text.toString().removeSuffix("°")} degree"))
        }
    }
}
