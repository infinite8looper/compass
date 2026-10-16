package com.infinite8looper.compass

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Privacy policy` row at the end of the settings, in the real activity, found and clicked through the system's
 * accessibility tree. The emulators have no paired phone, so the real call to the phone fails or goes unanswered,
 * and the row shows the policy's address on the watch.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyRowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (!root.refresh()) return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let { if (it.refresh()) walk(it) } }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }
    private fun withText(text: String) = nodes().filter { it.text?.toString() == text }
    private fun words() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.filter { it.isNotEmpty() }

    private suspend fun shows(what: String, seconds: Int = 10, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + seconds * 1_000L
        while (!isSo()) {
            assertTrue("$what did not come in $seconds s; on screen: ${words()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
    }

    /** The nearest node above [n] that takes a click, fetched again. */
    private fun clickableAbove(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var p = n?.parent
        while (p != null) {
            if (!p.refresh()) return null
            if (p.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } || p.isClickable) return p
            p = p.parent
        }
        return null
    }
    private fun row() = clickableAbove(withText("Privacy policy").singleOrNull())

    /** From the compass, Back shows the settings; the list is scrolled through the system's tree to its end. */
    private suspend fun toPrivacyRow() {
        shows("the compass") { nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } }
        delay(1_500)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        shows("the settings after one Back from the compass") { withText("Settings").any { it.isVisibleToUser } }
        delay(1_000)
        var steps = 0
        while (withText("Privacy policy").none { it.isVisibleToUser } && steps < 14) {
            val list = nodes().firstOrNull { it.isScrollable && it.isFocused }
            assertNotNull("no list that can be scrolled has the focus on the settings", list)
            list!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            steps++
            delay(800)
        }
        shows("the `Privacy policy` row") { withText("Privacy policy").any { it.isVisibleToUser } }
    }

    @Test fun theLastRowOfTheSettingsIsPrivacyPolicyAndWithNoPhoneItShowsTheAddress() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            toPrivacyRow()
            // It is the last row: the list scrolls no further, and nothing of the app's comes below it.
            nodes().firstOrNull { it.isScrollable && it.isFocused }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(800)
            val label = withText("Privacy policy").single()
            val labelBox = Rect().also { label.getBoundsInScreen(it) }
            val below = nodes().filter { it.isVisibleToUser && !it.text.isNullOrEmpty() }
                .filter { n -> Rect().also { n.getBoundsInScreen(it) }.top > labelBox.bottom }
                .map { it.text.toString() }
            assertEquals("words below the row: $below", emptyList<String>(), below)

            shows("the row taking a tap") { row()?.let { it.isEnabled && it.isClickable } == true }
            val r = row()!!
            // A screen reader is told what a tap does.
            assertEquals("Open on your phone", r.actionList.first { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label?.toString())
            // A finger's target is 48 dp tall at least.
            val box = Rect().also { r.getBoundsInScreen(it) }
            val dp = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("the row is ${box.height() / dp} dp tall", box.height() >= (48 * dp).toInt())

            assertTrue(r.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            // No phone is paired with an emulator: the address shows on the watch, in two lines.
            shows("the policy's address on the row", seconds = 20) { withText("infinite8looper.github.io\n/privacy.html").any { it.isVisibleToUser } }
            assertTrue("${words()}", withText("Opened on your phone").isEmpty())
            // The row takes a tap again: a second try asks the phone again.
            shows("the row taking a tap again") { row()?.let { it.isEnabled && it.isClickable } == true }
        }
    }
}
