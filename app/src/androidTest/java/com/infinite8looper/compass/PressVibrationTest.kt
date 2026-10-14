package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.SAVE_HERE_ACTION
import com.infinite8looper.compass.nav.TrailMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every press that saves is answered with a vibration at once, before the row is written (v3 spec, "Saving": "The
 * press ... gives a vibration at once, before any position is asked for"). Read from the system's own record of
 * vibrations (`dumpsys vibrator_manager`, as scripts/haptics.sh reads it), which lists every haptic feedback an app
 * asks for with its time, on the real activity. The routes the emulators can make: a double tap on the dial, the
 * dial's accessibility action, the `Log here` row and the `Drop crumb` row. (The emulator images describe no side
 * button and have no one-handed gesture service.) Needs `scripts/geo-feed.sh -72.2896 43.7022` running.
 */
@RunWith(AndroidJUnit4::class)
class PressVibrationTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val crumbDao = WaypointDatabase.get(app).crumbs()
    private val settings = SettingsStore.get(app)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        settings.setTrailMode(TrailMode.OFF)
        settings.setBreadcrumbTrail(false)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().readText()

    /** The device's clock as the vibration record writes it. */
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private fun stampOf(ms: Long) = stamp.format(Date(ms))

    /**
     * The app's vibrations the system recorded from [fromMs] to [toMs] (wall clock), each as its line. On API 35 and
     * later the line names the haptic feedback constant; API 33 and 34 record only the effect.
     */
    private fun vibrations(fromMs: Long, toMs: Long): List<String> {
        val from = stampOf(fromMs); val to = stampOf(toMs)
        return shell("dumpsys vibrator_manager").lines().filter { "com.infinite8looper.compass" in it }.mapNotNull { line ->
            val t = Regex("^\\s*(?:startTime: )?(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            line.takeIf { t >= from && t <= to }
        }.distinct()
    }

    /** A vibration was asked for after [markMs] and no later than the row was written at [writtenAtMs]: the press's own. */
    private suspend fun assertVibratedBeforeTheRow(route: String, markMs: Long, writtenAtMs: Long) {
        // The system adds a vibration to its record when it has ended (played out, or cut short by the next one).
        delay(1_500)
        val lines = vibrations(markMs, writtenAtMs)
        val press = if (Build.VERSION.SDK_INT >= 35) lines.filter { "performHapticFeedback(constant=1)" in it } else lines
        assertTrue(
            "$route: no vibration from the press at ${stampOf(markMs)} to the row written at ${stampOf(writtenAtMs)}; recorded then: $lines; " +
                "recorded in the minute before and after: ${vibrations(markMs - 60_000, writtenAtMs + 60_000)}",
            press.isNotEmpty(),
        )
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }
    private fun onScreen(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    private fun compassInView() =
        nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } &&
            !onScreen("Log here") && !onScreen("Settings")
    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
        delay(700)
    }
    private fun rowOf(text: String) = nodes().first { it.text?.toString() == text }.let { n -> generateSequence(n) { it.parent }.first { it.isClickable } }

    private val screen: Rect by lazy { Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) } }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(e, true))
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN); SystemClock.sleep(40); send(MotionEvent.ACTION_UP)
    }

    /** When the newest waypoint was written, once there are [count]. */
    private suspend fun waypointWrittenAt(count: Int): Long =
        withTimeout(60_000) { dao.observeAll().first { it.size == count } }.maxOf { it.createdAt }

    /** Swipes the ring one screen left, from the compass to the waypoints. */
    private fun swipeLeft() {
        val y = screen.exactCenterY(); val far = screen.width() * 0.3f
        val from = screen.exactCenterX() + far; val to = screen.exactCenterX() - far
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(e, true)); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (i in 1..12) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, from + (to - from) * i / 12) }
        send(MotionEvent.ACTION_UP, to)
    }

    @Test fun aDoubleTapAndTheDialsActionAreAnsweredWithAVibrationBeforeTheRowIsWritten() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            shows("the compass") { compassInView() }
            val dialY = screen.exactCenterY() - screen.height() * 0.2f
            var mark = System.currentTimeMillis()
            tap(screen.exactCenterX(), dialY); SystemClock.sleep(120); tap(screen.exactCenterX(), dialY)
            assertVibratedBeforeTheRow("double tap", mark, waypointWrittenAt(1))
            delay(3_000)
            mark = System.currentTimeMillis()
            val dial = nodes().first { n -> n.actionList.any { it.label?.toString() == SAVE_HERE_ACTION } }
            assertTrue(dial.performAction(dial.actionList.first { it.label?.toString() == SAVE_HERE_ACTION }.id))
            assertVibratedBeforeTheRow("the dial's accessibility action", mark, waypointWrittenAt(2))
        }
    }

    /** The system's status of a recorded vibration, lower case: `finished`, `cancelled_superseded`, ... */
    private fun statusOf(line: String): String =
        (Regex("\\|\\s*([a-z_]+)\\s*\\| duration").find(line) ?: Regex("status: ([A-Za-z_]+)").find(line))?.groupValues?.get(1)?.lowercase() ?: "?"
    private fun startOf(line: String): String =
        Regex("^\\s*(?:startTime: )?(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)").find(line)!!.groupValues[1]

    /**
     * An instant save finishes tens of milliseconds after its press. Its confirming vibration waits until the press's
     * has played, so neither is cut short and both are felt (Task 3's record showed the press `cancelled_superseded`
     * by the confirming vibration about 40 ms after it).
     */
    @Test fun anInstantSavesConfirmingVibrationDoesNotCutThePressShort() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            shows("the compass") { compassInView() }
            swipeLeft(); shows("the waypoints") { onScreen("Log here") }
            // A position in hand, so the save is instant (the feed sends one each second).
            delay(3_000)
            val mark = System.currentTimeMillis()
            assertTrue(rowOf("Log here").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val writtenAt = waypointWrittenAt(1)
            assertTrue("the save took ${writtenAt - mark} ms, not an instant save", writtenAt - mark < 150)
            delay(2_000)
            val lines = vibrations(mark, mark + 3_000)
            val all = "recorded: $lines"
            assertTrue("no vibration was cut short; $all", lines.none { statusOf(it) != "finished" })
            if (Build.VERSION.SDK_INT >= 35) {
                val press = lines.single { "performHapticFeedback(constant=1)" in it }
                val confirm = lines.single { "performHapticFeedback(constant=16)" in it }
                val pressAt = stamp.parse(startOf(press))!!.time; val confirmAt = stamp.parse(startOf(confirm))!!.time
                assertTrue("the confirming vibration came ${confirmAt - pressAt} ms after the press; $all", confirmAt - pressAt >= 140)
            } else {
                assertEquals(all, 2, lines.size)
            }
        }
    }

    @Test fun theLogHereAndDropCrumbRowsAreAnsweredWithAVibrationBeforeTheRowIsWritten() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            shows("the compass") { compassInView() }
            swipeLeft(); shows("the waypoints") { onScreen("Log here") }
            var mark = System.currentTimeMillis()
            assertTrue(rowOf("Log here").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertVibratedBeforeTheRow("Log here", mark, waypointWrittenAt(1))
            delay(3_000)
            settings.setBreadcrumbTrail(true); settings.setTrailMode(TrailMode.LAYING)
            shows("the Drop crumb row") { nodes().any { it.text?.toString() == "Drop crumb" } }
            mark = System.currentTimeMillis()
            assertTrue(rowOf("Drop crumb").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val crumbAt = withTimeout(60_000) { while (crumbDao.all().isEmpty()) delay(20); crumbDao.all().single().createdAt }
            assertVibratedBeforeTheRow("Drop crumb", mark, crumbAt)
        }
    }
}
