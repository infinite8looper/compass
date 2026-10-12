package com.infinite8looper.compass.nav

import androidx.compose.animation.core.EaseInOutCubic
import org.junit.Assert.*
import org.junit.Test

class HoldFlatLoopTest {
    @Test fun startsUprightAndInvisibleThenFadesIn() {
        assertEquals(90f, holdFlatTiltDeg(0f), 0f)
        assertEquals(0f, holdFlatVisibility(0f), 0f)
        assertEquals(0.5f, holdFlatVisibility(100f), 1e-4f)
        assertEquals(1f, holdFlatVisibility(200f), 0f)
        // Fully visible and still upright before it starts to turn.
        assertEquals(90f, holdFlatTiltDeg(300f), 0f)
        assertEquals(1f, holdFlatVisibility(300f), 0f)
        assertEquals(0f, holdFlatLevelReached(300f), 0f)
    }
    @Test fun tiltOnlyEverGoesDownToLevel() {
        var last = 90f
        var ms = 0f
        while (ms < HOLD_FLAT_LOOP_MS) {
            val tilt = holdFlatTiltDeg(ms)
            assertTrue("tilt rose at $ms: $last -> $tilt", tilt <= last + 1e-4f)
            assertTrue("tilt out of range at $ms: $tilt", tilt in 0f..90f)
            last = tilt
            ms += 4f
        }
        assertEquals(0f, last, 0f)
        assertEquals(45f, holdFlatTiltDeg(800f), 0.01f)
        assertTrue(holdFlatTiltDeg(550f) > 80f)
        assertTrue(holdFlatTiltDeg(1050f) < 10f)
    }
    @Test fun levelIsHeldForHalfASecondFullyVisible() {
        var ms = 1300f
        while (ms <= 1800f) {
            assertEquals("tilt at $ms", 0f, holdFlatTiltDeg(ms), 0f)
            assertEquals("visibility at $ms", 1f, holdFlatVisibility(ms), 0f)
            ms += 10f
        }
        assertEquals(0f, holdFlatLevelReached(1300f), 0f)
        assertEquals(0.5f, holdFlatLevelReached(1375f), 1e-4f)
        assertEquals(1f, holdFlatLevelReached(1450f), 0f)
        assertEquals(1f, holdFlatLevelReached(1800f), 0f)
        assertEquals(0.5f, holdFlatVisibility(1900f), 1e-4f)
    }
    @Test fun loopWrapsCleanly() {
        // The watch is gone at the end of one loop and at the start of the next, so the jump back upright is not seen.
        assertEquals(0f, holdFlatVisibility(1999.99f), 1e-3f)
        assertEquals(0f, holdFlatVisibility(HOLD_FLAT_LOOP_MS), 0f)
        for (ms in listOf(0f, 150f, 640f, 1299f, 1500f, 1950f)) {
            assertEquals(holdFlatTiltDeg(ms), holdFlatTiltDeg(ms + HOLD_FLAT_LOOP_MS), 1e-3f)
            assertEquals(holdFlatVisibility(ms), holdFlatVisibility(ms + 3 * HOLD_FLAT_LOOP_MS), 1e-3f)
            assertEquals(holdFlatLevelReached(ms), holdFlatLevelReached(ms + HOLD_FLAT_LOOP_MS), 1e-3f)
        }
    }
    @Test fun stillPositionIsTheHeldLevelPose() {
        assertTrue(HOLD_FLAT_STILL_MS > 1450f && HOLD_FLAT_STILL_MS < 1800f)
        assertEquals(0f, holdFlatTiltDeg(HOLD_FLAT_STILL_MS), 0f)
        assertEquals(1f, holdFlatVisibility(HOLD_FLAT_STILL_MS), 0f)
        assertEquals(1f, holdFlatLevelReached(HOLD_FLAT_STILL_MS), 0f)
    }
    @Test fun timesOutsideTheLoopAreSafe() {
        assertEquals(holdFlatTiltDeg(1500f), holdFlatTiltDeg(-500f), 1e-3f)
        assertEquals(holdFlatVisibility(1900f), holdFlatVisibility(-100f), 1e-3f)
        assertEquals(holdFlatLevelReached(1400f), holdFlatLevelReached(-600f), 1e-3f)
        for (ms in listOf(-1e9f, 1e9f, Float.MAX_VALUE, -Float.MAX_VALUE)) {
            assertTrue(holdFlatTiltDeg(ms) in 0f..90f)
            assertTrue(holdFlatVisibility(ms) in 0f..1f)
            assertTrue(holdFlatLevelReached(ms) in 0f..1f)
        }
        // A time that is not a number shows the still position.
        for (ms in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, holdFlatTiltDeg(ms), 0f)
            assertEquals(1f, holdFlatVisibility(ms), 0f)
            assertEquals(1f, holdFlatLevelReached(ms), 0f)
        }
    }
    @Test fun easingIsTheOneTheDrawingUsedBefore() {
        // The approved animation turned with Compose's EaseInOutCubic. The tilt must follow the same curve.
        for (i in 0..1000) {
            val t = i / 1000f
            assertEquals("at $t", 90f * (1f - EaseInOutCubic.transform(t)), holdFlatTiltDeg(300f + 1000f * t), 2e-3f)
        }
    }
}
