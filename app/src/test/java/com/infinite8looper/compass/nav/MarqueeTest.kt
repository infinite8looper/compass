package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class MarqueeTest {
    // 90 pixels of overflow at 60 pixels a second: 1.5 s each way, between holds of 1.2 s. One cycle is 5.4 s.
    private fun at(ms: Double, overflow: Float = 90f, perSecond: Float = 60f) = marqueeOffset(ms, overflow, perSecond)

    @Test fun theTimingIsTheOneAskedFor() {
        assertEquals(1_200.0, MARQUEE_HOLD_MS, 0.0)
        assertEquals(30f, MARQUEE_DP_PER_SECOND, 0f)
    }

    @Test fun textThatFitsNeverMoves() {
        var ms = 0.0
        while (ms < 20_000.0) {
            assertEquals(0f, at(ms, overflow = 0f), 0f)
            assertEquals(0f, at(ms, overflow = -12f), 0f)
            ms += 37.0
        }
    }

    @Test fun itHoldsAtTheStart() {
        var ms = 0.0
        while (ms <= 1_200.0) { assertEquals("at $ms", 0f, at(ms), 0f); ms += 10.0 }
    }

    @Test fun itSlidesAtASteadySpeedToExactlyTheOverflow() {
        assertEquals(30f, at(1_700.0), 1e-3f)
        assertEquals(45f, at(1_950.0), 1e-3f)
        assertEquals(60f, at(2_200.0), 1e-3f)
        // Equal times, equal steps.
        var ms = 1_200.0
        var last = at(ms)
        while (ms < 2_700.0) {
            ms += 100.0
            val now = at(ms)
            assertEquals("step ending at $ms", 6f, now - last, 1e-3f)
            last = now
        }
        assertEquals(90f, at(2_700.0), 0f)
    }

    @Test fun itHoldsAtTheEnd() {
        var ms = 2_700.0
        while (ms <= 3_900.0) { assertEquals("at $ms", 90f, at(ms), 0f); ms += 10.0 }
    }

    @Test fun itSlidesBackAtTheSameSpeedToExactlyZero() {
        assertEquals(60f, at(4_400.0), 1e-3f)
        assertEquals(45f, at(4_650.0), 1e-3f)
        assertEquals(30f, at(4_900.0), 1e-3f)
        assertEquals(0f, at(5_400.0), 0f)
    }

    @Test fun theCycleRepeats() {
        var ms = 0.0
        while (ms < 5_400.0) {
            for (cycle in listOf(1, 2, 7, 1_000)) assertEquals("at $ms in cycle $cycle", at(ms), at(ms + cycle * 5_400.0), 0.01f)
            ms += 50.0
        }
        // Still exact after a day.
        assertEquals(0f, at(16_000 * 5_400.0 + 600.0), 0f)
        assertEquals(90f, at(16_000 * 5_400.0 + 3_000.0), 0f)
    }

    @Test fun theOffsetIsNeverOutsideTheOverflow() {
        for (overflow in listOf(0.4f, 1f, 3f, 90f, 2_000f)) {
            var ms = 0.0
            while (ms < 80_000.0) {
                val offset = marqueeOffset(ms, overflow, 60f)
                assertTrue("$offset of $overflow at $ms", offset in 0f..overflow)
                ms += 13.0
            }
        }
    }

    /** A name a few pixels too long still goes through the whole cycle: both holds, and both ends. */
    @Test fun aFewPixelsOfOverflowStillScroll() {
        // 3 pixels at 60 a second take 50 ms each way.
        assertEquals(0f, at(1_200.0, overflow = 3f), 0f)
        assertEquals(1.5f, at(1_225.0, overflow = 3f), 1e-3f)
        assertEquals(3f, at(1_250.0, overflow = 3f), 0f)
        assertEquals(3f, at(2_450.0, overflow = 3f), 0f)
        assertEquals(1.5f, at(2_475.0, overflow = 3f), 1e-3f)
        assertEquals(0f, at(2_500.0, overflow = 3f), 0f)
        assertEquals(0f, at(3_000.0, overflow = 3f), 0f)
    }

    @Test fun inputsThatAreNotUsableGiveNoOffset() {
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0, -5_000.0)) assertEquals("$bad", 0f, at(bad), 0f)
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -90f)) assertEquals("$bad", 0f, at(2_000.0, overflow = bad), 0f)
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0f, -60f)) assertEquals("$bad", 0f, at(2_000.0, perSecond = bad), 0f)
        // A hold that is not a usable time is no hold.
        for (bad in listOf(Double.NaN, -1.0, Double.NEGATIVE_INFINITY)) {
            assertEquals("$bad", 30f, marqueeOffset(500.0, 90f, 60f, holdMs = bad), 1e-3f)
        }
        assertEquals(0f, marqueeOffset(500.0, 90f, 60f, holdMs = Double.POSITIVE_INFINITY), 0f)
    }

    /** A system animation scale stretches or shortens the whole cycle; with animations off there is no motion. */
    @Test fun theSystemsAnimationScaleScalesTheTime() {
        assertEquals(2_000.0, marqueeTimeMs(2_000_000_000L, 1f), 0.0)
        assertEquals(1_000.0, marqueeTimeMs(2_000_000_000L, 2f), 0.0)
        assertEquals(4_000.0, marqueeTimeMs(2_000_000_000L, 0.5f), 0.0)
        for (off in listOf(0f, -1f, Float.NaN)) assertEquals(0.0, marqueeTimeMs(2_000_000_000L, off), 0.0)
        assertEquals(0.0, marqueeTimeMs(-5L, 1f), 0.0)
    }

    /** While the text waits at an end nothing changes, and how long that still lasts is known: no frame is needed until then. */
    @Test fun theRestOfAWaitIsKnownAndThereIsNoneWhileSliding() {
        assertEquals(1_200.0, marqueeRestMs(0.0, 90f, 60f), 0.0)
        assertEquals(200.0, marqueeRestMs(1_000.0, 90f, 60f), 1e-6)
        assertEquals(0.0, marqueeRestMs(1_200.0, 90f, 60f), 0.0)
        assertEquals(0.0, marqueeRestMs(2_000.0, 90f, 60f), 0.0)
        assertEquals(1_200.0, marqueeRestMs(2_700.0, 90f, 60f), 1e-6)
        assertEquals(100.0, marqueeRestMs(3_800.0, 90f, 60f), 1e-6)
        assertEquals(0.0, marqueeRestMs(4_000.0, 90f, 60f), 0.0)
        // The next cycle's first wait.
        assertEquals(1_200.0, marqueeRestMs(5_400.0, 90f, 60f), 1e-6)
        assertEquals(700.0, marqueeRestMs(5_900.0 + 3 * 5_400.0, 90f, 60f), 1e-6)
        // Wherever a wait is left the offset is at an end, and a moment before the wait is over it still is.
        var ms = 0.0
        while (ms < 12_000.0) {
            val rest = marqueeRestMs(ms, 90f, 60f)
            if (rest > 0.0) {
                val here = marqueeOffset(ms, 90f, 60f)
                assertTrue("at $ms", here == 0f || here == 90f)
                assertEquals("at $ms", here, marqueeOffset(ms + rest - 0.001, 90f, 60f), 0f)
            }
            ms += 7.0
        }
        // Nothing to wait for where nothing slides or the inputs are not usable.
        for (bad in listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY)) assertEquals(0.0, marqueeRestMs(bad, 90f, 60f), 0.0)
        assertEquals(0.0, marqueeRestMs(100.0, 0f, 60f), 0.0)
        assertEquals(0.0, marqueeRestMs(100.0, 90f, Float.NaN), 0.0)
        assertEquals(0.0, marqueeRestMs(100.0, 90f, 60f, holdMs = Double.NaN), 0.0)
    }

    /**
     * What a name too long for its place does, by how much of its screen the wearer has: it slides on a screen at
     * rest in view with the app in front, stays where it is while the screen is passing in a swipe or something of
     * the system's is over the app, and stands at its start on a hidden screen.
     */
    @Test fun aNameSlidesInViewHoldsWhilePassingAndStartsOverWhenHidden() {
        assertEquals(MarqueePhase.RUNNING, marqueePhase(PaneView.IN_VIEW, appInFront = true, overflowPx = 40, motionScale = 1f))
        assertEquals(MarqueePhase.HELD, marqueePhase(PaneView.PASSING, appInFront = true, overflowPx = 40, motionScale = 1f))
        assertEquals(MarqueePhase.RESET, marqueePhase(PaneView.HIDDEN, appInFront = true, overflowPx = 40, motionScale = 1f))
        // Not in front (a dialog of the system's over the app, or the app leaving): nothing moves, nothing is lost.
        assertEquals(MarqueePhase.HELD, marqueePhase(PaneView.IN_VIEW, appInFront = false, overflowPx = 40, motionScale = 1f))
        assertEquals(MarqueePhase.HELD, marqueePhase(PaneView.PASSING, appInFront = false, overflowPx = 40, motionScale = 1f))
        assertEquals(MarqueePhase.RESET, marqueePhase(PaneView.HIDDEN, appInFront = false, overflowPx = 40, motionScale = 1f))
        // A name that fits, or animations removed: at its start, whatever the screen.
        for (view in PaneView.entries) for (front in listOf(true, false)) {
            assertEquals(MarqueePhase.RESET, marqueePhase(view, front, overflowPx = 0, motionScale = 1f))
            assertEquals(MarqueePhase.RESET, marqueePhase(view, front, overflowPx = -3, motionScale = 1f))
            assertEquals(MarqueePhase.RESET, marqueePhase(view, front, overflowPx = 40, motionScale = 0f))
        }
    }

    @Test fun whetherANameScrolls() {
        assertTrue(marqueeScrolls(overflowPx = 1, motionScale = 1f))
        assertTrue(marqueeScrolls(overflowPx = 300, motionScale = 0.5f))
        assertFalse(marqueeScrolls(overflowPx = 0, motionScale = 1f))
        assertFalse(marqueeScrolls(overflowPx = -4, motionScale = 1f))
        // With the system's animations removed nothing scrolls, and the name is cut short with an ellipsis.
        assertFalse(marqueeScrolls(overflowPx = 300, motionScale = 0f))
        assertFalse(marqueeScrolls(overflowPx = 300, motionScale = Float.NaN))
    }
}
