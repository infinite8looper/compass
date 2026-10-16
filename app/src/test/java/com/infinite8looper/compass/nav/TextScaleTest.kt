package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class TextScaleTest {
    @Test fun theMenusTextFollowsTheSystemsFontScale() {
        // The six steps of the font size setting on the Wear OS emulators (API 35 and 37).
        for (scale in listOf(0.94f, 1.0f, 1.06f, 1.12f, 1.18f, 1.24f)) assertEquals(scale, menuTextScale(scale), 0f)
    }

    @Test fun aScaleThatIsNoSizeIsTakenAsTheDefault() {
        assertEquals(1f, menuTextScale(0f), 0f)
        assertEquals(1f, menuTextScale(-1.2f), 0f)
        assertEquals(1f, menuTextScale(Float.NaN), 0f)
        assertEquals(1f, menuTextScale(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun theScaleStaysWithinWhatTheScreensAreLaidOutFor() {
        assertEquals(MENU_TEXT_SCALE_MIN, menuTextScale(0.5f), 0f)
        assertEquals(MENU_TEXT_SCALE_MIN, menuTextScale(MENU_TEXT_SCALE_MIN), 0f)
        assertEquals(MENU_TEXT_SCALE_MAX, menuTextScale(MENU_TEXT_SCALE_MAX), 0f)
        assertEquals(MENU_TEXT_SCALE_MAX, menuTextScale(2f), 0f)
        // The watch's own range lies inside it.
        assertTrue(MENU_TEXT_SCALE_MIN <= 0.94f && MENU_TEXT_SCALE_MAX >= 1.24f)
    }

    @Test fun aSizeOnAMenuIsItsSizeFromTheRadiusTimesTheScale() {
        assertEquals(14.755f, menuTextSize(14.755f, 1f), 1e-4f)
        assertEquals(14.755f * 1.24f, menuTextSize(14.755f, 1.24f), 1e-4f)
        assertEquals(14.755f * 0.94f, menuTextSize(14.755f, 0.94f), 1e-4f)
        assertTrue(menuTextSize(10f, 1.24f) > menuTextSize(10f, 1.18f))
    }
}
