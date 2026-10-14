package com.infinite8looper.compass

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.nav.ColorMode
import com.infinite8looper.compass.nav.MIN_HUE_APART
import com.infinite8looper.compass.nav.hueApart
import com.infinite8looper.compass.nav.Palette
import com.infinite8looper.compass.nav.RoleColors
import com.infinite8looper.compass.nav.STANDARD_PALETTE
import com.infinite8looper.compass.nav.WatchScheme
import com.infinite8looper.compass.nav.graysFor
import com.infinite8looper.compass.nav.paletteFor
import com.infinite8looper.compass.nav.ringColor
import com.infinite8looper.compass.nav.wheelColor
import com.infinite8looper.compass.ui.AccentColor
import com.infinite8looper.compass.ui.Dim
import com.infinite8looper.compass.ui.ErrorColor
import com.infinite8looper.compass.ui.Faint
import com.infinite8looper.compass.ui.Ink
import com.infinite8looper.compass.ui.LabelGray
import com.infinite8looper.compass.ui.NorthColor
import com.infinite8looper.compass.ui.watchFaceColorsAvailable
import com.infinite8looper.compass.ui.waypointColor
import com.infinite8looper.compass.ui.followWatchColors
import com.infinite8looper.compass.ui.watchScheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app reads of the watch's color scheme, on the watch the tests run on, as that watch is set: with the
 * system's setting for dynamic color on, on API level 35 or above, the first branch of each test runs, and
 * anywhere else the second. The `Colors` setting's own test, ColorsRowTest, sets the system's setting both ways.
 */
@RunWith(AndroidJUnit4::class)
class WatchColorsDeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The condition under which the Wear Material 3 library gives a scheme, read from the watch itself. */
    private val watchGivesAScheme: Boolean =
        Build.VERSION.SDK_INT >= 35 && Settings.Global.getInt(ctx.contentResolver, "dynamic_color_theme_enabled", 0) == 1

    @Test fun aSchemeIsReadOnlyWhereTheWatchGivesOne() {
        assertEquals(watchGivesAScheme, watchScheme(ctx) != null)
    }

    @Test fun theSchemeHoldsTheSystemsPrimaryTertiaryAndErrorColors() {
        val scheme = watchScheme(ctx)
        if (watchGivesAScheme && Build.VERSION.SDK_INT >= 35) {
            assertEquals(
                WatchScheme(
                    primary = ctx.getColor(android.R.color.system_primary_fixed),
                    tertiary = ctx.getColor(android.R.color.system_tertiary_fixed),
                    error = ctx.getColor(android.R.color.system_error_dark),
                    onSurface = ctx.getColor(android.R.color.system_on_surface_dark),
                    onSurfaceVariant = ctx.getColor(android.R.color.system_on_surface_variant_dark),
                    outline = ctx.getColor(android.R.color.system_outline_dark),
                ),
                scheme,
            )
            // The scheme as this watch gave it, for the record kept in WatchColorsTest.
            Log.i("WatchColorsDeviceTest", "API ${Build.VERSION.SDK_INT} scheme " + listOf(
                scheme!!.primary, scheme.tertiary, scheme.error, scheme.onSurface!!, scheme.onSurfaceVariant!!, scheme.outline!!,
            ).joinToString(" ") { "%06X".format(it and 0xFFFFFF) })
        } else {
            assertNull(scheme)
        }
    }

    @Test fun followingTheWatchSetsTheThreeColorsAndTheyAreOpaque() {
        // With `Watch face`, the colors are made from the scheme (v3: and from the app's own where there is none).
        followWatchColors(ctx, ColorMode.WATCH_FACE)
        val expected = paletteFor(ColorMode.WATCH_FACE, watchScheme(ctx))
        if (!watchGivesAScheme) assertEquals(STANDARD_PALETTE, expected)
        assertEquals(watchGivesAScheme, watchFaceColorsAvailable)
        assertColors(expected)
        followWatchColors(ctx, ColorMode.STANDARD)
    }

    @Test fun standardIsTheAppsOwnColorsWhateverTheWatchGives() {
        followWatchColors(ctx, ColorMode.WATCH_FACE)
        followWatchColors(ctx, ColorMode.STANDARD)
        // v3 spec: "`Standard`: north is red, the pointer and the wearer's position are blue [...] Nothing is read from the watch."
        assertEquals(RoleColors(accent = 0x2F8BFF, north = 0xFF3B30, error = 0xFF3B30), STANDARD_PALETTE.roles)
        assertColors(STANDARD_PALETTE)
        // Whether `Watch face` could be chosen is still known, for the settings row.
        assertEquals(watchGivesAScheme, watchFaceColorsAvailable)
        // A waypoint's stored place is a hue on the full wheel.
        assertEquals(Color(0xFF000000.toInt() or wheelColor(137.5f)), waypointColor(137.5f))
    }

    @Test fun onAWatchWithASchemeWatchFaceTakesTheInkAndTheGraysFromIt() {
        followWatchColors(ctx, ColorMode.WATCH_FACE)
        val scheme = watchScheme(ctx)
        if (watchGivesAScheme) {
            val p = paletteFor(ColorMode.WATCH_FACE, scheme)
            assertNotEquals(STANDARD_PALETTE, p)
            assertEquals(graysFor(scheme), p.grays)
            assertNotEquals(STANDARD_PALETTE.grays.ink, p.grays.ink)
            assertColors(p)
            // A waypoint's stored place is on the ring of the accent, north and a third anchor apart from both (the
            // owner, 2026-10-14: "for themed colors make sure the wheel has *3* anchorpoints"); until then the third
            // was always the error color.
            assertEquals(Color(0xFF000000.toInt() or ringColor(137.5f / 360f, p.roles.accent, p.roles.north, p.ringThird)), waypointColor(137.5f))
            assertTrue(hueApart(p.ringThird, p.roles.accent) >= MIN_HUE_APART && hueApart(p.ringThird, p.roles.north) >= MIN_HUE_APART)
        } else {
            assertNull(scheme)
            assertColors(STANDARD_PALETTE)
        }
        followWatchColors(ctx, ColorMode.STANDARD)
    }

    private fun assertColors(p: Palette) {
        fun opaque(rgb: Int) = Color(0xFF000000.toInt() or rgb)
        assertEquals(opaque(p.roles.accent), AccentColor)
        assertEquals(opaque(p.roles.north), NorthColor)
        assertEquals(opaque(p.roles.error), ErrorColor)
        assertEquals(opaque(p.grays.ink), Ink)
        assertEquals(opaque(p.grays.dim), Dim)
        assertEquals(opaque(p.grays.faint), Faint)
        assertEquals(opaque(p.grays.label), LabelGray)
        for (c in listOf(AccentColor, NorthColor, ErrorColor, Ink, Dim, Faint, LabelGray)) assertEquals(1f, c.alpha, 0f)
    }
}
