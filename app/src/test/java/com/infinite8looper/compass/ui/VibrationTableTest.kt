package com.infinite8looper.compass.ui

import android.os.VibrationEffect.Composition
import android.view.HapticFeedbackConstants
import com.infinite8looper.compass.nav.DialCrossing
import com.infinite8looper.compass.nav.TICK_QUIET_AFTER_TARGET_NANOS
import org.junit.Assert.*
import org.junit.Test

/**
 * The one table of the app's vibrations ([Feel]): which composition primitives each plays where the watch supports
 * them, and which haptic feedback constant where it does not (v3 spec, "Vibrations": "Which primitive plays for which
 * kind is fixed in one table in the code"; decided item 8: "a light one for the thin lines, a deeper one for the
 * thick lines (every 30 degrees), and a third, in between and of a different shape" for the target).
 */
class VibrationTableTest {
    private val dial = listOf(Feel.THIN, Feel.THICK, Feel.TARGET)

    @Test fun eachDialCrossingHasItsFeel() {
        assertNull(Feel.of(DialCrossing.NONE))
        assertEquals(Feel.THIN, Feel.of(DialCrossing.THIN))
        assertEquals(Feel.THICK, Feel.of(DialCrossing.THICK))
        assertEquals(Feel.TARGET, Feel.of(DialCrossing.TARGET))
    }

    @Test fun theDialsThreeArePrimitivesWithOneScaleAndDelayEach() {
        for (f in dial) {
            assertTrue("$f", f.primitives.isNotEmpty())
            assertEquals("$f", f.primitives.size, f.scales.size)
            assertEquals("$f", f.primitives.size, f.delaysMs.size)
            assertTrue("$f", f.scales.all { it > 0f && it <= 1f })
            assertTrue("$f", f.delaysMs.all { it >= 0 })
        }
    }

    @Test fun thinIsLightThickIsDeepAndTheTargetIsBetweenWithAShapeOfItsOwn() {
        assertArrayEquals(intArrayOf(Composition.PRIMITIVE_TICK), Feel.THIN.primitives)
        assertArrayEquals(intArrayOf(Composition.PRIMITIVE_THUD), Feel.THICK.primitives)
        // Two knocks, a short gap apart.
        assertEquals(2, Feel.TARGET.primitives.size)
        assertTrue(Feel.TARGET.delaysMs[1] > 0)
        assertTrue(Feel.THIN.scales.max() < Feel.TARGET.scales.max())
        assertTrue(Feel.TARGET.scales.max() < Feel.THICK.scales.max())
        // The three differ from each other, where primitives play and where they do not.
        assertEquals(3, dial.map { it.primitives.toList() to it.scales.toList() }.toSet().size)
        assertEquals(3, dial.map { it.constantFrom34 }.toSet().size)
        assertEquals(3, dial.map { it.constant }.toSet().size)
        // The lines stay quiet for longer than the target's gap, so a line does not land between its two knocks.
        assertTrue(Feel.TARGET.delaysMs.sum() * 1_000_000L < TICK_QUIET_AFTER_TARGET_NANOS)
    }

    @Test fun whereNoPrimitivesPlayTheDialUsesTheConstantsItUsedBefore() {
        // v2's tick: the texture tick from API 34, the clock tick before.
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Feel.THIN.constant)
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, Feel.THIN.constantFrom34)
        // Constants whose effects differ from each other and from the press on every emulator image (API 33, 34,
        // 35, 37): GESTURE_START plays the click effect, CONFIRM two thuds. Not the long press, whose heavy click the
        // emulators' vibrator does not support, so nothing plays; not CONTEXT_CLICK, whose tick effect is what the
        // texture tick falls back to where the vibrator lacks it (API 37).
        assertEquals(HapticFeedbackConstants.GESTURE_START, Feel.THICK.constant)
        assertEquals(HapticFeedbackConstants.CONFIRM, Feel.TARGET.constant)
        for (f in dial) assertNotEquals("$f", HapticFeedbackConstants.LONG_PRESS, f.constantFrom34)
        for (f in dial) assertNotEquals("$f", Feel.PRESS.constant, f.constantFrom34)
    }

    @Test fun theSaveAndBlindNavigationVibrationsAreTheSystemsAsBefore() {
        // The press, the confirming vibration, and v2's lock and heartbeat: haptic feedback constants only.
        for (f in listOf(Feel.PRESS, Feel.SAVED, Feel.LOCK, Feel.HEARTBEAT, Feel.LONG_PRESS)) {
            assertTrue("$f", f.primitives.isEmpty())
            assertEquals("$f", f.constant, f.constantFrom34)
        }
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Feel.PRESS.constant)
        assertEquals(HapticFeedbackConstants.CONFIRM, Feel.SAVED.constant)
        assertEquals(HapticFeedbackConstants.CONFIRM, Feel.LOCK.constant)
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, Feel.HEARTBEAT.constant)
        // The long press that opens the waypoints, the edit screen's and the list's: the system's long press.
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Feel.LONG_PRESS.constant)
    }
}
