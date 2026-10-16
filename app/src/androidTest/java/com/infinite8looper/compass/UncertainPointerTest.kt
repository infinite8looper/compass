package com.infinite8looper.compass

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.nav.CrumbPoint
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.Units
import com.infinite8looper.compass.nav.WaypointInfo
import com.infinite8looper.compass.nav.buildTargets
import com.infinite8looper.compass.nav.buildTrail
import com.infinite8looper.compass.nav.isGoodEnough
import com.infinite8looper.compass.ui.CompassDial
import com.infinite8looper.compass.ui.CompassReadout
import com.infinite8looper.compass.ui.CrumbColor
import com.infinite8looper.compass.ui.readoutStyles
import com.infinite8looper.compass.ui.waypointColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * What the compass draws when the position is less certain than a target is near. Owner, 2026-10-16: "Hide the
 * pointer (Recommended)": no pointer or line when the position's accuracy is larger than the distance to the crumb
 * or target; the distance with ± after it; the crumb not counted as reached.
 *
 * The emulators' positions all come with an accuracy of 5 m or less (the geo console sets no accuracy: API 33 to 35
 * give 5 m, API 37 3 to 5 m, an NMEA sentence's HDOP of 25 included), and arrival within 10 m is decided first, so
 * the app's own location updates never reach this state on an emulator. So the real compass readout and dial
 * ([CompassReadout], [CompassDial]) are composed in the real activity from the screen state the pure functions
 * build ([buildTrail], [buildTargets]) for a fix of the accuracy given, facing true north, and read back from the screen:
 * the words through the accessibility tree, the line by the pixels of its color on the line's side of the dial.
 * Each state's picture is left at /data/local/tmp/uncertain-<name>.png for scripts to pull.
 */
@RunWith(AndroidJUnit4::class)
class UncertainPointerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val here = GeoPoint(43.7022, -72.2896)

    /** The point [meters] due east of [here] (a degree of longitude is 111 195 m times the cosine of the latitude). */
    private fun east(meters: Double) = GeoPoint(here.lat, here.lon + meters / (111_195.0 * Math.cos(Math.toRadians(here.lat))))

    /** The screen state while the crumb [meters] east is walked to, with a fix of [accuracyM] at [here]. */
    private fun crumbState(meters: Double, accuracyM: Float): NavUi {
        val trail = buildTrail(
            TrailMode.RETRACING, listOf(CrumbPoint(2, east(meters)), CrumbPoint(1, east(-400.0))), here, DECLINATION, NorthRef.TRUE, Units.IMPERIAL,
            fixGoodEnough = isGoodEnough(accuracyM), fixAccuracyM = accuracyM,
        )
        return NavUi(
            emptyList(), trail.current, Units.IMPERIAL, NorthRef.TRUE, DECLINATION, SystemClock.elapsedRealtime(), emptyList(),
            trail = trail, fixAccuracyM = accuracyM, position = here,
        )
    }

    /** The screen state with one waypoint [meters] east as the focused target, with a fix of [accuracyM] at [here]. */
    private fun waypointState(meters: Double, accuracyM: Float): NavUi {
        val targets = buildTargets(
            listOf(WaypointInfo(1, "Waypoint 1", east(meters), WAYPOINT_PLACE, true)), 1, here, DECLINATION, NorthRef.TRUE, Units.IMPERIAL,
            fixAccuracyM = accuracyM,
        )
        return NavUi(
            targets, targets.first(), Units.IMPERIAL, NorthRef.TRUE, DECLINATION, SystemClock.elapsedRealtime(), emptyList(),
            fixAccuracyM = accuracyM, position = here,
        )
    }

    private fun show(scenario: ActivityScenario<MainActivity>, nav: NavUi) {
        // Made here, not remembered: a second setContent on the same activity keeps what the first remembered.
        val navState = mutableStateOf(nav)
        scenario.onActivity {
            it.setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    val r = minOf(maxWidth, maxHeight) / 2
                    val density = LocalDensity.current
                    val styles = remember(r, density) { readoutStyles(r, density) }
                    CompassReadout(
                        true, navState, remember { mutableStateOf<LogStatus>(LogStatus.Idle) }, remember { mutableStateOf(false) },
                        remember { mutableStateOf("0°") }, remember { mutableStateOf(false) }, remember { mutableStateOf(true) },
                        { PaneView.IN_VIEW }, {}, r, styles,
                        dial = { targets, focused, ticks, modifier ->
                            CompassDial({ 0f }, targets, modifier, focused = focused, crumbTicks = ticks)
                        },
                    )
                }
            }
        }
    }

    private fun words(): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo) { n.text?.let { out += it.toString() }; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        walk(root)
        return out
    }

    private suspend fun shows(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (text !in words()) {
            assertTrue("`$text` did not show in 10 s: ${words()}", SystemClock.uptimeMillis() < deadline)
            delay(100)
        }
        delay(500)
    }

    /**
     * The pixels of [color] (each channel within 24) on the line from the middle of the screen toward 3 o'clock, from
     * 0.45 to 0.95 of the radius, three rows high: where the line to a target due east is drawn, facing north.
     */
    private fun pixelsEastOf(color: Color): Int {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val argb = color.toArgb()
        val cx = shot.width / 2; val cy = shot.height / 2; val r = minOf(shot.width, shot.height) / 2
        var n = 0
        for (x in cx + (r * 0.45f).toInt() until cx + (r * 0.95f).toInt()) for (y in cy - 1..cy + 1) {
            val p = shot.getPixel(x, y)
            if (abs(android.graphics.Color.red(p) - android.graphics.Color.red(argb)) <= 24 &&
                abs(android.graphics.Color.green(p) - android.graphics.Color.green(argb)) <= 24 &&
                abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(argb)) <= 24
            ) n++
        }
        return n
    }

    private fun keepPicture(name: String) {
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/uncertain-$name.png").close()
        SystemClock.sleep(1_000)
    }

    @Test fun aCrumbNearerThanTheAccuracyHasNoLineAndItsDistanceHasTheAccuracyAfterIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 10 ft east with a 50 ft fix: `10 ft ±50 ft`, and no line.
            show(scenario, crumbState(meters = 3.048, accuracyM = 15.24f))
            shows("10 ft ±50 ft")
            assertEquals("crumb-colored pixels toward the crumb", 0, pixelsEastOf(CrumbColor))
            keepPicture("crumb-near")
        }
        Unit
    }

    @Test fun theSameAccuracyWithTheCrumbFurtherAwayHasTheLine() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 328 ft east with the same 50 ft fix: the line, and the poor fix's accuracy after the distance as before.
            show(scenario, crumbState(meters = 100.0, accuracyM = 15.24f))
            shows("328 ft ±50 ft")
            val line = pixelsEastOf(CrumbColor)
            assertTrue("crumb-colored pixels toward the crumb: $line", line >= 20)
            keepPicture("crumb-far")
        }
        Unit
    }

    @Test fun aWaypointNearerThanTheAccuracyHasNoLineAndItsDistanceHasTheAccuracyAfterIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 49 ft east (beyond the 30 ft of arrival) with a 98 ft fix: `49 ft ±98 ft`, and no line.
            show(scenario, waypointState(meters = 15.0, accuracyM = 30f))
            shows("49 ft ±98 ft")
            assertEquals("waypoint-colored pixels toward the waypoint", 0, pixelsEastOf(waypointColor(WAYPOINT_PLACE)))
            keepPicture("waypoint-near")
            // 328 ft east with the same fix: the line, and no accuracy after the distance.
            show(scenario, waypointState(meters = 100.0, accuracyM = 30f))
            shows("328 ft")
            val line = pixelsEastOf(waypointColor(WAYPOINT_PLACE))
            assertTrue("waypoint-colored pixels toward the waypoint: $line", line >= 20)
            keepPicture("waypoint-far")
        }
        Unit
    }

    private companion object {
        /** The first new waypoint's place (a green with Standard colors). */
        const val WAYPOINT_PLACE = 108.3f
        /** The declination at 43.7022,-72.2896 in 2026, about 14 degrees west: the dial is true, with no `MAG`. */
        const val DECLINATION = -14.3f
    }
}
