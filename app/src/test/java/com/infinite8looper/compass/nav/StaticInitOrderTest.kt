package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test
import java.net.URLClassLoader

/**
 * The ring's read offset ([RING_READ_OFFSET_TURNS], WatchColors.kt) is built from [FIRST_WAYPOINT_PLACE]
 * (Navigation.kt), which is itself built from WatchColors.kt's functions. When the offset was a field, whichever file
 * the JVM set up first decided its value: with Navigation.kt first, WatchColors.kt was set up in the middle of it and
 * read the place as 0 (seen 2026-10-14, when the unit run's order of classes changed: the first new waypoint was
 * drawn by the accent). Each check here loads the two files afresh, in a class loader of its own, in one order or the
 * other, as a process starting would.
 */
class StaticInitOrderTest {
    private val places = "com.infinite8looper.compass.nav.NavigationKt"
    private val colors = "com.infinite8looper.compass.nav.WatchColorsKt"

    /** A class loader that loads the app's classes, Kotlin's and its coroutines' afresh, not from the test's own loader. */
    private fun freshLoader(): ClassLoader = URLClassLoader(
        arrayOf(
            StaticInitOrderTest::class.java.classLoader!!.loadClass(places).protectionDomain.codeSource.location,
            KotlinVersion::class.java.protectionDomain.codeSource.location,
            kotlinx.coroutines.flow.Flow::class.java.protectionDomain.codeSource.location,
        ),
        null,   // the JVM's own classes only
    )

    private fun check(firstSetUp: String) {
        val loader = freshLoader()
        Class.forName(firstSetUp, true, loader)
        val navigation = Class.forName(places, true, loader)
        val watchColors = Class.forName(colors, true, loader)
        assertNotSame("not loaded afresh", StaticInitOrderTest::class.java.classLoader!!.loadClass(places), navigation)
        val place = navigation.getMethod("getFIRST_WAYPOINT_PLACE").invoke(null) as Float
        val offset = watchColors.getMethod("getRING_READ_OFFSET_TURNS").invoke(null) as Float
        assertEquals("$firstSetUp first", 108.325f, place, 0.001f)
        assertEquals("$firstSetUp first", 2f / 3f - place / 360f, offset, 1e-6f)

        // And the color drawn: the first place reads as the ring's third anchor in Watch face (API 35's scheme).
        val mode = Class.forName("com.infinite8looper.compass.nav.ColorMode", true, loader).enumConstants.single { (it as Enum<*>).name == "WATCH_FACE" }
        val schemeClass = Class.forName("com.infinite8looper.compass.nav.WatchScheme", true, loader)
        val scheme = schemeClass.getConstructor(Int::class.java, Int::class.java, Int::class.java, Int::class.javaObjectType, Int::class.javaObjectType, Int::class.javaObjectType)
            .newInstance(0xD9E2FF, 0xFDD7FA, 0xF2B8B5, 0xE2E2E9, 0xC5C6D0, 0x8F9099)
        val paletteClass = Class.forName("com.infinite8looper.compass.nav.Palette", true, loader)
        val palette = watchColors.getMethod("paletteFor", mode.javaClass, schemeClass).invoke(null, mode, scheme)
        val third = paletteClass.getMethod("getRingThird").invoke(palette) as Int
        val drawn = watchColors.getMethod("waypointRgb", paletteClass, Float::class.java).invoke(null, palette, place) as Int
        assertEquals("$firstSetUp first: %06X, third %06X".format(drawn, third), third, drawn)
    }

    @Test fun theRingOffsetIsRightWhenNavigationIsSetUpFirst() = check(places)
    @Test fun theRingOffsetIsRightWhenWatchColorsIsSetUpFirst() = check(colors)
}
