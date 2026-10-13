package com.infinite8looper.compass

import android.Manifest
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
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
}
