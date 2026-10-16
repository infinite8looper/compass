package com.infinite8looper.compass

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.nav.DOUBLE_PRESS_WINDOW_MS
import com.infinite8looper.compass.nav.PressKind
import com.infinite8looper.compass.ui.SideButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.nav.TrailMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's handler of side-button keys ([SideButton]), given real key events on the main thread at intervals the
 * test keeps by the watch's own clock, and its real timer. This is the handling a watch with a button the app can
 * act on gets; it is checked here, apart from any screen, because the emulator images describe no such button and
 * the screens there install no key handling (SideButtonTest).
 *
 * The last three tests run the handling as the screens install it ([com.infinite8looper.compass.ui.rememberSideButtonKeys]
 * on the outermost box), put in place of the app's content in the real activity ([SideKeysHost]) and told the
 * watch has a button, with the app's real view model and settings: real key events go through the system to it.
 */
@RunWith(AndroidJUnit4::class)
class SideButtonKeysTest {
    /** What the handler made of the keys: each press it acted on, and when. */
    private class Seen { val kinds = ArrayList<PressKind>(); val atMs = ArrayList<Long>() }

    private fun key(action: Int, downAt: Long, code: Int = KeyEvent.KEYCODE_STEM_1, repeat: Int = 0) =
        KeyEvent(downAt, SystemClock.uptimeMillis(), action, code, repeat)

    /** One short press, down and up. Returns when it was let go. */
    private fun SideButton.press(seen: Seen, doubleEnabled: Boolean, inView: Boolean = true, code: Int = KeyEvent.KEYCODE_STEM_1): Long {
        val act: (PressKind) -> Unit = { seen.kinds += it; seen.atMs += SystemClock.uptimeMillis() }
        val down = SystemClock.uptimeMillis()
        assertTrue(onKey(key(KeyEvent.ACTION_DOWN, down, code), { inView }, { doubleEnabled }, act))
        val up = key(KeyEvent.ACTION_UP, down, code)
        assertTrue(onKey(up, { inView }, { doubleEnabled }, act))
        return up.eventTime
    }

    /** Runs [block] on the main thread with a handler whose timer ends with it. */
    private fun withButton(block: suspend (SideButton, Seen) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            coroutineScope {
                block(SideButton(this), Seen())
                coroutineContext.job.cancelChildren()
            }
        }
    }

    @Test fun withTheTrailSettingOffAPressIsASinglePressTheMomentItIsLetGo() = withButton { button, seen ->
        button.press(seen, doubleEnabled = false)
        assertEquals(listOf(PressKind.SINGLE), seen.kinds)
        // A second one close behind it is a second single press: nothing pairs.
        delay(100)
        button.press(seen, doubleEnabled = false)
        assertEquals(listOf(PressKind.SINGLE, PressKind.SINGLE), seen.kinds)
        delay(DOUBLE_PRESS_WINDOW_MS + 200)
        assertEquals(2, seen.kinds.size)
    }

    @Test fun withTheSettingOnASinglePressIsActedOnWhenTheWindowHasPassedAndNotBefore() = withButton { button, seen ->
        val up = button.press(seen, doubleEnabled = true)
        assertTrue(seen.kinds.isEmpty())
        delay(DOUBLE_PRESS_WINDOW_MS - 120)
        if (SystemClock.uptimeMillis() - up < DOUBLE_PRESS_WINDOW_MS) assertTrue("acted on ${seen.atMs.map { it - up }} ms after the press", seen.kinds.isEmpty())
        delay(600)
        assertEquals(listOf(PressKind.SINGLE), seen.kinds)
        val after = seen.atMs.single() - up
        assertTrue("the single press was acted on $after ms after it was let go", after in DOUBLE_PRESS_WINDOW_MS..DOUBLE_PRESS_WINDOW_MS + 400)
    }

    @Test fun withTheSettingOnTwoPressesInsideTheWindowAreOneDoublePress() = withButton { button, seen ->
        val up = button.press(seen, doubleEnabled = true)
        delay(120)
        val gap = SystemClock.uptimeMillis() - up
        button.press(seen, doubleEnabled = true)
        if (gap <= DOUBLE_PRESS_WINDOW_MS) {
            assertEquals("the presses came $gap ms apart", listOf(PressKind.DOUBLE), seen.kinds)
            // And no single press follows it.
            delay(DOUBLE_PRESS_WINDOW_MS + 300)
            assertEquals(listOf(PressKind.DOUBLE), seen.kinds)
        } else fail("the watch was too slow to make two presses inside the window: they came $gap ms apart")
    }

    @Test fun twoPressesFurtherApartThanTheWindowAreTwoSinglePresses() = withButton { button, seen ->
        button.press(seen, doubleEnabled = true)
        delay(DOUBLE_PRESS_WINDOW_MS + 150)
        assertEquals(listOf(PressKind.SINGLE), seen.kinds)
        button.press(seen, doubleEnabled = true)
        delay(DOUBLE_PRESS_WINDOW_MS + 300)
        assertEquals(listOf(PressKind.SINGLE, PressKind.SINGLE), seen.kinds)
    }

    @Test fun aPressWhileTheScreenIsNotInViewAHeldButtonAndAnotherKeyMakeNoPress() = withButton { button, seen ->
        // Not in view: the keys are taken, and no press comes of them.
        button.press(seen, doubleEnabled = false, inView = false)
        button.press(seen, doubleEnabled = true, inView = false)
        // A held button: its repeats arm nothing, and its release is no press.
        val act: (PressKind) -> Unit = { seen.kinds += it }
        val down = SystemClock.uptimeMillis()
        assertTrue(button.onKey(key(KeyEvent.ACTION_DOWN, down), { true }, { false }, act))
        assertTrue(button.onKey(key(KeyEvent.ACTION_DOWN, down, repeat = 1), { true }, { false }, act))
        assertTrue(button.onKey(key(KeyEvent.ACTION_UP, down), { true }, { false }, act))
        // The crown's own press and Back are not side buttons: they are left for whoever handles them.
        assertFalse(button.onKey(key(KeyEvent.ACTION_DOWN, down, KeyEvent.KEYCODE_STEM_PRIMARY), { true }, { false }, act))
        assertFalse(button.onKey(key(KeyEvent.ACTION_UP, down, KeyEvent.KEYCODE_BACK), { true }, { false }, act))
        delay(DOUBLE_PRESS_WINDOW_MS + 300)
        assertTrue("presses acted on: ${seen.kinds}", seen.kinds.isEmpty())
    }

    // The handling as the screens install it, in SideKeysHost.

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore.get(app)
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun resetTrail() = runBlocking { settings.setTrailMode(TrailMode.OFF); settings.setBreadcrumbTrail(false) }
    @Before fun setUp() { resetTrail(); SideKeysHost.singles.set(0); SideKeysHost.trailOn = false }
    @After fun tearDown() = resetTrail()

    /** One short press sent through the system, handled before this returns. Returns when it was let go. */
    private fun sendPress(): Long {
        val down = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STEM_1, 0), true))
        val up = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_STEM_1, 0), true))
        return up
    }

    private fun host(block: suspend () -> Unit) = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { SideKeysHost.showIn(it) }
            delay(1_500)      // composed, and the box has the focus
            block()
        }
    }

    @Test fun onTheScreensWithTheTrailOffASinglePressIsActedOnWhenItIsLetGo() = host {
        sendPress()
        assertEquals(1, SideKeysHost.singles.get())
        delay(DOUBLE_PRESS_WINDOW_MS + 300)
        assertEquals(1, SideKeysHost.singles.get())
    }

    @Test fun onTheScreensWithTheTrailOnASinglePressWaitsForTheWindow() = host {
        SideKeysHost.trailOn = true
        val up = sendPress()
        val at = SystemClock.uptimeMillis() - up
        if (at < DOUBLE_PRESS_WINDOW_MS) assertEquals("acted on $at ms after the press", 0, SideKeysHost.singles.get())
        withTimeout(2_000) { while (SideKeysHost.singles.get() == 0) delay(5) }
        val after = SystemClock.uptimeMillis() - up
        assertTrue("the single press was acted on $after ms after it was let go", after >= DOUBLE_PRESS_WINDOW_MS)
    }

    @Test fun onTheScreensADoublePressSwitchesTheTrailInTheViewModel() = host {
        settings.setBreadcrumbTrail(true)
        SideKeysHost.trailOn = true
        val first = sendPress()
        delay(120)
        val gap = SystemClock.uptimeMillis() - first
        sendPress()
        assertTrue("the presses came $gap ms apart", gap <= DOUBLE_PRESS_WINDOW_MS)
        withTimeout(10_000) { while (settings.settings.first().trailMode != TrailMode.LAYING) delay(20) }
        delay(DOUBLE_PRESS_WINDOW_MS + 300)
        assertEquals("single presses acted on", 0, SideKeysHost.singles.get())
    }
}
