package com.infinite8looper.compass

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.nav.RoleColors
import com.infinite8looper.compass.nav.WatchScheme
import com.infinite8looper.compass.nav.roleColors
import com.infinite8looper.compass.ui.AccentColor
import com.infinite8looper.compass.ui.ErrorColor
import com.infinite8looper.compass.ui.NorthColor
import com.infinite8looper.compass.ui.followWatchColors
import com.infinite8looper.compass.ui.watchScheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app reads of the watch's color scheme, on the watch the tests run on, as that watch is set: with the
 * system's setting for dynamic color on, on API level 35 or above, the first branch of each test runs, and
 * anywhere else the second.
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
                ),
                scheme,
            )
        } else {
            assertNull(scheme)
        }
    }

    @Test fun followingTheWatchSetsTheThreeColorsAndTheyAreOpaque() {
        followWatchColors(ctx)
        val expected = roleColors(watchScheme(ctx))
        if (!watchGivesAScheme) assertEquals(RoleColors(accent = 0x2F8BFF, north = 0xFF3B30, error = 0xFF3B30), expected)
        assertEquals(Color(0xFF000000.toInt() or expected.accent), AccentColor)
        assertEquals(Color(0xFF000000.toInt() or expected.north), NorthColor)
        assertEquals(Color(0xFF000000.toInt() or expected.error), ErrorColor)
        assertEquals(1f, AccentColor.alpha, 0f)
        assertEquals(1f, NorthColor.alpha, 0f)
        assertEquals(1f, ErrorColor.alpha, 0f)
    }
}
