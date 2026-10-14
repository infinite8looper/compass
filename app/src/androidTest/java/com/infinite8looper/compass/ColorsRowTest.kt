package com.infinite8looper.compass

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.nav.ColorMode
import com.infinite8looper.compass.nav.STANDARD_PALETTE
import com.infinite8looper.compass.nav.paletteFor
import com.infinite8looper.compass.ui.AccentColor
import com.infinite8looper.compass.ui.Ink
import com.infinite8looper.compass.ui.NorthColor
import com.infinite8looper.compass.ui.followWatchColors
import com.infinite8looper.compass.ui.watchScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Colors` row of the settings, in the real activity with the app's own settings file. The system's setting
 * `dynamic_color_theme_enabled`, which decides whether the Wear Material 3 library gives a color scheme (on API
 * level 35 and above), is set through the shell, as `adb shell settings` does, and put back as it was after each test.
 * The row is found in the system's accessibility tree and clicked through it.
 */
@RunWith(AndroidJUnit4::class)
class ColorsRowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore.get(app)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var dynamicColorBefore: String = "null"

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
    private fun setDynamicColor(on: Boolean) {
        shell("settings put global dynamic_color_theme_enabled ${if (on) 1 else 0}")
        assertEquals(if (on) "1" else "0", shell("settings get global dynamic_color_theme_enabled"))
    }

    @Before fun setUp() = runBlocking {
        dynamicColorBefore = shell("settings get global dynamic_color_theme_enabled")
        settings.setColors(ColorMode.STANDARD)
    }
    @After fun tearDown() = runBlocking {
        settings.setColors(ColorMode.STANDARD)
        if (dynamicColorBefore == "null") shell("settings delete global dynamic_color_theme_enabled")
        else shell("settings put global dynamic_color_theme_enabled $dynamicColorBefore")
        Unit
    }

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

    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s; on screen: ${words()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
    }

    /** From the compass, Back shows the settings; the list is scrolled through the system's tree until `Colors` is in view. */
    private suspend fun toColorsRow() {
        shows("the compass") { nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } }
        delay(1_500)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        shows("the settings after one Back from the compass") { withText("Settings").any { it.isVisibleToUser } }
        delay(1_000)
        var steps = 0
        while (withText("Colors").none { it.isVisibleToUser } && steps < 8) {
            val list = nodes().firstOrNull { it.isScrollable && it.isFocused }
            assertNotNull("no list that can be scrolled has the focus on the settings", list)
            list!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            steps++
            delay(800)
        }
        shows("the `Colors` row") { withText("Colors").any { it.isVisibleToUser } }
    }

    /**
     * The row that holds the word `Colors`, as the app has it now: the nearest node above the word that has a click
     * action or is a button, each fetched again, since the system's cached copy of a node can keep a state it had
     * seconds ago (the row before the settings file was read takes no tap). Null when it cannot be found.
     */
    private fun rowOrNull(): AccessibilityNodeInfo? {
        var n = withText("Colors").singleOrNull()?.parent
        while (n != null) {
            if (!n.refresh()) return null
            if (n.className?.toString() == "android.widget.Button" || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } || n.isClickable) return n
            n = n.parent
        }
        return null
    }
    private fun row(): AccessibilityNodeInfo = rowOrNull().also { assertNotNull("no row above `Colors`: ${words()}", it) }!!
    private fun rowSays(detail: String) = withText(detail).any { d -> generateSequence(d.parent) { it.parent }.take(4).any { a -> (0 until a.childCount).any { a.getChild(it)?.text?.toString() == "Colors" } } }

    private fun opaque(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    /** What a screen reader says a click on [n] does: the label of its click action, as the system's tree has it. */
    private fun clickLabelOf(n: AccessibilityNodeInfo?): String? =
        n?.actionList?.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label?.toString()
    /** Every click label the app's nodes offer now. */
    private fun clickLabels() = nodes().mapNotNull { clickLabelOf(it) }

    @Test fun withNoSchemeTheRowSaysSoAndTakesNoTap() = runBlocking {
        // The library gives no scheme below API level 35 whatever the setting, and from 35 only while it is 1.
        if (Build.VERSION.SDK_INT >= 35) setDynamicColor(false)
        assertNull(watchScheme(app))
        ActivityScenario.launch(MainActivity::class.java).use {
            toColorsRow()
            shows("`Not available on this watch` on the row") { rowSays("Not available on this watch") }
            assertFalse("the row says a choice: ${words()}", rowSays("Standard") || rowSays("Watch face"))
            val r = rowOrNull()
            assertFalse("the row takes a tap", r != null && r.isEnabled && r.isClickable)
            // No click is offered with the owner's words (2026-10-14, approved: `Use watch face colors` / `Use
            // standard colors`): the row takes no tap, so it says nothing a tap would do.
            assertNull(clickLabelOf(r))
            val offered = clickLabels()
            assertFalse("$offered", offered.any { it == "Use watch face colors" || it == "Use standard colors" })
            // A click sent anyway, to the row or to the word, changes nothing.
            (r ?: withText("Colors").single()).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(1_000)
            assertEquals(ColorMode.STANDARD, settings.settings.first().colors)
            assertEquals(opaque(STANDARD_PALETTE.roles.accent), AccentColor)
            assertEquals(opaque(STANDARD_PALETTE.grays.ink), Ink)
        }
    }

    @Test fun watchFaceStoredOnAWatchWithNoSchemeGivesTheStandardColors() = runBlocking {
        if (Build.VERSION.SDK_INT >= 35) setDynamicColor(false)
        settings.setColors(ColorMode.WATCH_FACE)
        ActivityScenario.launch(MainActivity::class.java).use {
            toColorsRow()
            shows("`Not available on this watch` on the row") { rowSays("Not available on this watch") }
            assertEquals(opaque(STANDARD_PALETTE.roles.accent), AccentColor)
            assertEquals(opaque(STANDARD_PALETTE.roles.north), NorthColor)
            assertEquals(opaque(STANDARD_PALETTE.grays.ink), Ink)
            // What was stored is kept.
            assertEquals(ColorMode.WATCH_FACE, settings.settings.first().colors)
        }
    }

    @Test fun withASchemeTheRowSwitchesBetweenStandardAndWatchFace() = runBlocking {
        if (Build.VERSION.SDK_INT < 35) {
            // No scheme can be had here: the other two tests are this level's whole case.
            assertNull(watchScheme(app))
            return@runBlocking
        }
        setDynamicColor(true)
        val scheme = watchScheme(app)
        assertNotNull("the library gives no scheme with the setting on", scheme)
        val watchFace = paletteFor(ColorMode.WATCH_FACE, scheme)
        ActivityScenario.launch(MainActivity::class.java).use {
            toColorsRow()
            shows("`Standard` on the row") { rowSays("Standard") }
            assertEquals(opaque(STANDARD_PALETTE.roles.accent), AccentColor)
            assertEquals(opaque(STANDARD_PALETTE.grays.ink), Ink)

            // The rows take a tap once the settings file has been read; the system's tree can lag that by a moment.
            shows("the row taking a tap") { rowOrNull()?.let { it.isEnabled && it.isClickable } == true }
            // The click label says what a tap does, in the words the owner approved (2026-10-14).
            assertEquals("Use watch face colors", clickLabelOf(row()))
            assertTrue(row().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            shows("`Watch face` on the row") { rowSays("Watch face") }
            shows("the click label `Use standard colors`; it is `${clickLabelOf(rowOrNull())}`") { clickLabelOf(rowOrNull()) == "Use standard colors" }
            assertEquals(ColorMode.WATCH_FACE, settings.settings.first().colors)
            shows("the accent from the scheme") { AccentColor == opaque(watchFace.roles.accent) }
            assertEquals(opaque(watchFace.roles.north), NorthColor)
            assertEquals(opaque(watchFace.grays.ink), Ink)
            assertNotEquals(opaque(STANDARD_PALETTE.roles.accent), AccentColor)

            assertTrue(row().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            shows("`Standard` on the row again") { rowSays("Standard") }
            shows("the click label `Use watch face colors` again") { clickLabelOf(rowOrNull()) == "Use watch face colors" }
            assertEquals(ColorMode.STANDARD, settings.settings.first().colors)
            shows("the app's own accent again") { AccentColor == opaque(STANDARD_PALETTE.roles.accent) }
            assertEquals(opaque(STANDARD_PALETTE.grays.ink), Ink)
        }
    }

    @Test fun watchFaceStoredIsShownInTheSchemesColorsFromTheFirstFrame() = runBlocking {
        if (Build.VERSION.SDK_INT < 35) {
            assertNull(watchScheme(app))
            return@runBlocking
        }
        setDynamicColor(true)
        settings.setColors(ColorMode.WATCH_FACE)
        val watchFace = paletteFor(ColorMode.WATCH_FACE, watchScheme(app))
        // The colors start as `Standard`'s, as in a process just started, so that only the activity's own reading
        // of the setting can change them.
        instrumentation.runOnMainSync { followWatchColors(app, ColorMode.STANDARD) }
        assertEquals(opaque(STANDARD_PALETTE.roles.accent), AccentColor)
        // The accent as it is the moment the activity's onCreate has returned, through the system's own callback:
        // before the activity is started, so before anything that runs once it is (the colors' collector runs
        // only from STARTED) and before the first frame.
        var accent: Color? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) accent = AccentColor
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertNotNull("onActivityPostCreated was not called", accent)
                assertEquals(opaque(watchFace.roles.accent), accent)
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
