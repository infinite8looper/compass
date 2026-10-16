package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.LogStatus
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

/**
 * The touches the compass dial takes, sent as real touch events to the real activity: two taps one after the other
 * save the position, and each of the dial's other touches (one tap, a long press, a vertical swipe, a sideways
 * swipe, the `Calibrate` prompt's own tap area) does what it did, next to a double tap and mixed with one.
 *
 * The tests that save need `scripts/geo-feed.sh -72.2896 43.7022` running, as in CompassViewModelTest.
 *
 * The suite runs with the system's animations off, so the ring of screens does not slide: it is on its new page at
 * once. A double tap that lands while the ring slides is therefore not made here; that it saves nothing is the
 * rule of `doubleTapSaves` (InputsTest), which asks for the compass in view at both taps.
 */
@RunWith(AndroidJUnit4::class)
class DialInputsTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val crumbDao = WaypointDatabase.get(app).crumbs()
    private val settings = SettingsStore.get(app)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        settings.setTrailMode(TrailMode.OFF)
        settings.setBreadcrumbTrail(false)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    // What is on screen, read from the system's own tree.

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return all.filter { it.packageName == "com.infinite8looper.compass" }
    }
    private fun onScreen(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    private fun place(text: String): Rect? = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
    private fun wordsOnScreen() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.filter { it.isNotEmpty() }
    private fun compassInView() =
        nodes().any { n -> n.isVisibleToUser && n.text?.toString()?.let { it == "Hold flat" || it == "--" || it.endsWith("°") } == true } &&
            !onScreen("Log here") && !onScreen("Settings")

    private suspend fun shows(what: String, isSo: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!isSo()) {
            assertTrue("$what did not come in 10 s; on screen: ${wordsOnScreen()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
        // The screen has come to rest and has drawn.
        delay(700)
    }

    // Touches.

    private val screen: Rect by lazy { Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) } }
    private val cx get() = screen.exactCenterX()
    private val cy get() = screen.exactCenterY()
    /**
     * A place on the dial that no tap area of its own covers: above the middle, where the name of a target stands. So
     * it is with the coordinates off, as this suite runs; with them on and `Calibrate` showing, the prompt's tap area
     * reaches up from the top of the readout over it ([com.infinite8looper.compass.nav.promptTapArea]).
     */
    private val dialY get() = cy - screen.height() * 0.2f

    /**
     * Sends one touch event that happened at [at] (now, unless told), as its own time says, as a touch screen stamps
     * the time a finger touched it. With [wait] (the default) it returns once the app has handled it, which on a
     * busy emulator can be a few hundred milliseconds later; without, at once. Returns the event's time.
     */
    private fun send(action: Int, x: Float, y: Float, downAt: Long, wait: Boolean = true, at: Long = SystemClock.uptimeMillis()): Long {
        val e = MotionEvent.obtain(downAt, at, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(automation.injectInputEvent(e, wait))
        e.recycle()
        return at
    }

    /** Waits until the clock of the touch events reads [ms]. */
    private fun sleepUntil(ms: Long) { SystemClock.sleep((ms - SystemClock.uptimeMillis()).coerceAtLeast(0)) }

    /**
     * One tap: a finger down at [downAt] (now, unless told) and up again [heldMs] later, by the events' own times,
     * each sent no sooner than its time. How long the app takes to handle a touch changes neither time. Returns the
     * time it was let go.
     */
    private fun tap(x: Float = cx, y: Float = dialY, heldMs: Long = 40, wait: Boolean = true, downAt: Long = SystemClock.uptimeMillis()): Long {
        sleepUntil(downAt)
        send(MotionEvent.ACTION_DOWN, x, y, downAt, wait, at = downAt)
        sleepUntil(downAt + heldMs)
        return send(MotionEvent.ACTION_UP, x, y, downAt, wait, at = downAt + heldMs)
    }

    /**
     * Two taps, the second going down [gapMs] after the first was let go, by the events' own times. Returns the time
     * the second was let go.
     */
    private fun doubleTap(x: Float = cx, y: Float = dialY, gapMs: Long = 120, secondX: Float = x, secondY: Float = y): Long {
        val up = tap(x, y)
        return tap(secondX, secondY, downAt = up + gapMs)
    }

    /** One finger from one place to another in about 200 ms. */
    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, fromX, fromY, down)
        val steps = 12
        for (i in 1..steps) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps, down)
        }
        send(MotionEvent.ACTION_UP, toX, toY, down)
    }
    private fun swipeSideways(toTheLeft: Boolean) {
        val far = screen.width() * 0.3f
        swipe(cx + if (toTheLeft) far else -far, dialY, cx + if (toTheLeft) -far else far, dialY)
    }
    private fun swipeUp() { val far = screen.height() * 0.3f; swipe(cx, cy + far, cx, cy - far) }

    /** Nothing has been saved and no save is under way, [afterMs] after the last touch: longer than a double tap may take. */
    private suspend fun nothingSaved(vm: CompassViewModel, what: String, afterMs: Long = doubleTapMs + 700) {
        delay(afterMs)
        assertEquals("$what: presses taken up", 0, vm.logPress.value)
        assertEquals("$what: the status", LogStatus.Idle, vm.logStatus.value)
        assertTrue("$what: waypoints stored", dao.observeAll().first().isEmpty())
        assertTrue("$what: crumbs stored", crumbDao.all().isEmpty())
    }

    /**
     * Two taps, and the save has begun by the time the second tap's release has been handled: nothing waits. The
     * time is logged (`adb logcat -s DialInputsTest`).
     */
    private fun doubleTapSavesAtOnce(vm: CompassViewModel, what: String) {
        val up = doubleTap()
        val status = vm.logStatus.value
        val after = SystemClock.uptimeMillis() - up
        Log.i("DialInputsTest", "$what: the save had begun $after ms after the second tap was let go")
        assertTrue("$what: $status, $after ms after the second tap", status is LogStatus.Saving || status is LogStatus.Saved)
    }

    /** The save that press number [press] started has ended in a saved position. */
    private suspend fun saved(vm: CompassViewModel, press: Int, what: String) {
        withTimeout(5_000) { while (vm.logPress.value < press) delay(10) }
        assertEquals("$what: presses taken up", press, vm.logPress.value)
        withTimeout(60_000) { while (vm.logStatus.value !is LogStatus.Saved) delay(20) }
    }

    @Test fun aDoubleTapOnTheDialSavesAWaypointAndOneTapDoesNothing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }

            tap()
            nothingSaved(vm, "after one tap")
            assertTrue("one tap left the compass: ${wordsOnScreen()}", compassInView())

            // Two taps: the save has begun when the second tap's release has been handled. Nothing waits.
            doubleTapSavesAtOnce(vm, "with the trail off")
            saved(vm, 1, "after a double tap")
            assertEquals(1, dao.observeAll().first().size)
            assertTrue(crumbDao.all().isEmpty())
            assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        }
    }

    /**
     * `Breadcrumb trail` is on by default (v3: "`Breadcrumb trail` is on by default, also for an install that never
     * touched the switch"). With it on and no trail being laid, a double tap saves a waypoint, and at once: the
     * 300 ms wait for a second press belongs to the side button alone.
     */
    @Test fun withTheTrailSettingOnAndNoTrailADoubleTapSavesAWaypointAtOnce() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            doubleTapSavesAtOnce(vm, "with the trail setting on and no trail")
            saved(vm, 1, "after a double tap with the trail setting on")
            assertEquals(1, dao.observeAll().first().size)
            assertTrue(crumbDao.all().isEmpty())
            assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        }
    }

    /** A tap somewhere else first, a moment before, does not take the double tap's first tap as its partner. */
    @Test fun aStrayTapJustBeforeADoubleTapDoesNotUseItUp() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            // The stray tap on one side, the double tap on the other, further apart than the double-tap distance.
            val slop = ViewConfiguration.get(app).scaledDoubleTapSlop
            val far = screen.width() * 0.38f
            assertTrue("the screen is too narrow for two taps further apart than $slop px", 2 * far > slop + 2)
            val strayUp = tap(x = cx - far, y = cy)
            val up = tap(x = cx + far, y = cy, downAt = strayUp + 120)
            tap(x = cx + far, y = cy, downAt = up + 120)
            saved(vm, 1, "after a stray tap and then a double tap")
            assertEquals(1, dao.observeAll().first().size)
        }
    }

    @Test fun aDoubleTapIsJudgedByWhenItsTouchesHappenedNotWhenTheyWereHandled() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            // The first tap is handled at once. Then the app's main thread is kept busy for longer than the double-tap
            // time, as a slow frame on a busy watch can, while the second tap goes down 120 ms after the first was let
            // go: the app handles it only once the thread is free, well past the double-tap time after the first,
            // but by the touches' own times the two are a double tap, and it saves.
            val up = tap()
            val busyMs = doubleTapMs + 300
            scenario.onActivity { it.window.decorView.post { SystemClock.sleep(busyMs) } }
            tap(wait = false, downAt = up + 120)
            saved(vm, 1, "after a double tap the app handled late")
            assertEquals(1, dao.observeAll().first().size)
        }
    }

    @Test fun whileATrailIsBeingLaidADoubleTapDropsACrumb() = runBlocking {
        settings.setBreadcrumbTrail(true)
        settings.setTrailMode(TrailMode.LAYING)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            doubleTapSavesAtOnce(vm, "while laying")
            saved(vm, 1, "after a double tap while laying")
            assertEquals("Crumb 1", (vm.logStatus.value as? LogStatus.Saved)?.name)
            assertEquals(1, crumbDao.all().size)
            assertTrue(dao.observeAll().first().isEmpty())
            assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)
        }
    }

    @Test fun twoTapsTooFarApartInTimeOrInPlaceAreNoDoubleTap() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }

            // In time: the second goes down after the system's double-tap time has run out.
            doubleTap(gapMs = doubleTapMs + 200)
            nothingSaved(vm, "after two taps ${doubleTapMs + 200} ms apart")

            // In place: further apart than the system's double-tap distance, across the dial.
            val slop = ViewConfiguration.get(app).scaledDoubleTapSlop
            val far = screen.width() * 0.38f
            assertTrue("the screen is too narrow for two taps further apart than $slop px", 2 * far > slop + 2)
            doubleTap(x = cx - far, y = cy, secondX = cx + far, secondY = cy)
            nothingSaved(vm, "after two taps ${2 * far} px apart, with a double-tap distance of $slop px")
            assertTrue("the taps left the compass: ${wordsOnScreen()}", compassInView())

            // And two taps close in both still save.
            doubleTap()
            saved(vm, 1, "after a double tap")
            assertEquals(1, dao.observeAll().first().size)
        }
    }

    @Test fun aLongPressOpensTheWaypointsAsBeforeAndSavesNothingAlsoRightAfterATap() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }

            // A touch held a little less than the system's long-press time is a tap, and opens nothing.
            tap(heldMs = longPressMs - 150)
            nothingSaved(vm, "after a touch held ${longPressMs - 150} ms")
            assertTrue("a touch held under the long-press time left the compass: ${wordsOnScreen()}", compassInView())

            // Held a little longer than it, it opens the waypoints, no later than it did.
            tap(heldMs = longPressMs + 200)
            shows("the waypoints, after a long press") { onScreen("Log here") }
            nothingSaved(vm, "after a long press", afterMs = 300)

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("the compass, after Back") { compassInView() }

            // A tap and then a touch that is held: the second touch is a long press, not the second tap of two.
            tap()
            SystemClock.sleep(100)
            tap(heldMs = longPressMs + 200)
            shows("the waypoints, after a tap and a long press") { onScreen("Log here") }
            nothingSaved(vm, "after a tap and a long press")

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            shows("the compass, after Back") { compassInView() }
            doubleTap()
            saved(vm, 1, "after a double tap")
            assertTrue("the double tap left the compass: ${wordsOnScreen()}", compassInView())
        }
    }

    @Test fun aVerticalSwipeMovesTheFocusAsBeforeAndSavesNothingAlsoRightAfterATap() = runBlocking {
        val t = System.currentTimeMillis()
        val ids = (1..3).map { i -> dao.insert(Waypoint(name = "Place $i", lat = 43.70 + i * 0.01, lon = -72.28, accuracyM = 8f, createdAt = t + i, hue = 90f * i, isTarget = true)) }
        settings.setFocused(ids[0])
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            suspend fun focused() = settings.settings.first().focusedId
            suspend fun focusLeaves(from: Long?, what: String): Long? {
                withTimeout(5_000) { while (focused() == from) delay(20) }
                return focused().also { assertNotEquals(what, from, it); assertTrue(what, it in ids) }
            }

            swipeUp()
            val second = focusLeaves(ids[0], "the focus after a swipe up")

            // A tap and then a swipe: the swipe moves the focus once more and the two are no double tap.
            tap(y = cy)
            SystemClock.sleep(80)
            swipeUp()
            val third = focusLeaves(second, "the focus after a tap and a swipe up")
            assertEquals("the stored waypoints", 3, dao.observeAll().first().size)
            delay(doubleTapMs + 700)
            assertEquals(0, vm.logPress.value); assertEquals(LogStatus.Idle, vm.logStatus.value)
            assertEquals("the focus moved again by itself", third, focused())

            // A double tap saves, and moves no focus by itself.
            doubleTap()
            saved(vm, 1, "after a double tap")
            assertEquals(4, dao.observeAll().first().size)
        }
    }

    @Test fun aSidewaysSwipeTurnsTheRingAsBeforeAndSavesNothingAlsoRightAfterATap() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }

            swipeSideways(toTheLeft = true)
            shows("the waypoints, after a swipe to the left") { onScreen("Log here") }
            nothingSaved(vm, "after a swipe to the left", afterMs = 300)
            swipeSideways(toTheLeft = false)
            shows("the compass, after a swipe back") { compassInView() }

            // A tap and then a swipe: the second touch turns the ring and is not the second tap of two.
            tap()
            SystemClock.sleep(80)
            swipeSideways(toTheLeft = false)
            shows("the settings, after a tap and a swipe to the right") { onScreen("Settings") }
            nothingSaved(vm, "after a tap and a swipe to the right")

            // Two taps on another screen are not the compass's: on the settings' header they save nothing.
            val header = place("Settings")!!
            doubleTap(x = header.exactCenterX(), y = header.exactCenterY())
            nothingSaved(vm, "after a double tap on the settings")
            assertTrue("the double tap left the settings: ${wordsOnScreen()}", onScreen("Settings"))

            swipeSideways(toTheLeft = true)
            shows("the compass, after a swipe back") { compassInView() }
            doubleTap()
            saved(vm, 1, "after a double tap")
            assertEquals(1, dao.observeAll().first().size)
        }
    }

    /**
     * The `Calibrate` prompt shows on the compass only while the watch's magnetometer reports low accuracy, which
     * is the watch's to say. Where it shows, the part of the dial from the word away from the middle is the prompt's
     * own tap area ([com.infinite8looper.compass.nav.promptTapArea]), so the middle of the word is in it wherever the word stands:
     * two taps there open calibration, once, and save nothing. Where it does not show there is no such area, and
     * two taps at the same place are a double tap on the dial like any other.
     */
    @Test fun twoTapsOnTheCalibratePromptOpenCalibrationOnceAndSaveNothing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            shows("the compass") { compassInView() }
            val prompt = place("Calibrate")
            Log.i("DialInputsTest", "the Calibrate prompt on the compass: ${if (prompt == null) "not showing" else "showing at $prompt"}")
            if (prompt != null) {
                doubleTap(x = prompt.exactCenterX(), y = prompt.exactCenterY())
                shows("calibration, after two taps on the prompt") { !compassInView() && !onScreen("Log here") && !onScreen("Settings") }
                nothingSaved(vm, "after two taps on the Calibrate prompt")
                // Opened once: one Back is the compass again.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                shows("the compass, after one Back from calibration") { compassInView() }
                // And one tap there opens it as before.
                tap(x = prompt.exactCenterX(), y = prompt.exactCenterY())
                shows("calibration, after one tap on the prompt") { !compassInView() && !onScreen("Log here") && !onScreen("Settings") }
                nothingSaved(vm, "after one tap on the Calibrate prompt")
            } else {
                // Where the prompt's tap area would be: under the heading, in the lower half of the dial.
                val below = cy + screen.height() * 0.22f
                tap(y = below)
                nothingSaved(vm, "after one tap under the heading")
                assertTrue("one tap under the heading left the compass: ${wordsOnScreen()}", compassInView())
                doubleTap(y = below)
                saved(vm, 1, "after a double tap under the heading")
                assertTrue("the double tap left the compass: ${wordsOnScreen()}", compassInView())
            }
        }
    }
}
