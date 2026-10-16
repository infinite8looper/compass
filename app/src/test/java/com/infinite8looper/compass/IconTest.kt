package com.infinite8looper.compass

import com.infinite8looper.compass.nav.FALLBACK_ACCENT
import com.infinite8looper.compass.nav.FALLBACK_NORTH
import com.infinite8looper.compass.nav.fromHsv
import com.infinite8looper.compass.nav.hueApart
import com.infinite8looper.compass.nav.saturationOf
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The launcher icon, read from the resource files the build packs (app/src/main/res), as numbers.
 *
 * The spec (v3, Icon): "The background becomes a colored disc that fills the round mask; the mark is enlarged toward
 * the edge of the 66 dp safe zone; the monochrome layer follows." The brief: the disc is "the app's standard blue,
 * deep enough for the white ring and the red needle to read".
 */
class IconTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(res, path)).documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun children(e: Element, tag: String): List<Element> {
        val nodes = e.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** `#RRGGBB` or `#AARRGGBB` as 0xRRGGBB, with the alpha checked to be opaque. */
    private fun rgbOf(value: String): Int {
        val hex = value.removePrefix("#")
        if (hex.length == 8) assertEquals("an opaque color: $value", "FF", hex.substring(0, 2).uppercase())
        return hex.takeLast(6).toInt(16)
    }

    /** The color the adaptive icon's background layer names, looked up in res/values. */
    private fun discRgb(): Int {
        val ref = children(xml("mipmap-anydpi/ic_launcher.xml"), "background").single().attr("drawable")
        assertTrue("the background is a color of the app's own, not $ref", ref.startsWith("@color/"))
        val name = ref.removePrefix("@color/")
        val found = res.listFiles { f -> f.name.startsWith("values") }!!.flatMap { dir ->
            dir.listFiles { f -> f.extension == "xml" }!!.flatMap { children(xml("${dir.name}/${it.name}"), "color") }
        }.filter { it.getAttribute("name") == name }
        return rgbOf(found.single().textContent.trim())
    }

    private fun contrast(a: Int, b: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        fun luminance(rgb: Int) =
            0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)
        val (dark, light) = listOf(luminance(a), luminance(b)).sorted()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun valueOf(rgb: Int) = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF) / 255f

    /** The foreground's paths: the ring (stroked) first, then the needle's two triangles (filled). */
    private fun paths(file: String): List<Element> = children(xml(file), "path")

    /** The ring's radius from its path, `M54,54m-r,0a r,r ...`. */
    private fun ringRadius(path: Element): Double =
        Regex("""^M54,54m-([0-9.]+),0a\1,\1 """).find(path.attr("pathData"))!!.groupValues[1].toDouble()

    /** A triangle `Mx,yLx,yLx,yZ` as its three points. */
    private fun points(path: Element): List<Pair<Double, Double>> =
        Regex("""([0-9.]+),([0-9.]+)""").findAll(path.attr("pathData")).map {
            it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }.toList()

    @Test
    fun theDiscIsTheAppsBlueDeepenedUntilTheWhiteRingAndTheRedNeedleRead() {
        val disc = discRgb()
        val (ring, north, south) = paths("drawable/ic_launcher_foreground.xml")
        val white = rgbOf(ring.attr("strokeColor"))
        val red = rgbOf(north.attr("fillColor"))
        assertEquals("the ring is white", 0xFFFFFF, white)
        assertEquals("the needle's tail is white", 0xFFFFFF, rgbOf(south.attr("fillColor")))
        assertEquals("the needle's point is the app's north red", FALLBACK_NORTH, red)
        assertTrue("the disc has the hue of the app's blue", hueApart(disc, FALLBACK_ACCENT) <= 1f)
        assertTrue("the disc has the saturation of the app's blue", abs(saturationOf(disc) - saturationOf(FALLBACK_ACCENT)) <= 0.01f)
        assertTrue("white on the disc: ${contrast(white, disc)}", contrast(white, disc) >= 4.5)
        assertTrue("the red needle on the disc: ${contrast(red, disc)}", contrast(red, disc) >= 3.0)
        // Deep enough and no deeper: the same blue a little lighter no longer gives the red needle 3 to 1.
        val lighter = fromHsv(
            com.infinite8looper.compass.nav.hueOf(disc), saturationOf(disc), valueOf(disc) + 0.02f,
        )
        assertTrue("a lighter shade still reads: ${contrast(red, lighter)}", contrast(red, lighter) < 3.0)
    }

    @Test
    fun theMarkReachesTheEdgeOfThe66dpSafeZone() {
        for (file in listOf("drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml")) {
            val vector = xml(file)
            assertEquals(file, "108", vector.attr("viewportWidth"))
            assertEquals(file, "108", vector.attr("viewportHeight"))
            val (ring, north, south) = paths(file)
            val outer = ringRadius(ring) + ring.attr("strokeWidth").toDouble() / 2
            assertEquals("$file: the ring's outer edge 33 from the center, 66 across", 33.0, outer, 0.01)
            val inner = ringRadius(ring) - ring.attr("strokeWidth").toDouble() / 2
            val needle = points(north) + points(south)
            for ((x, y) in needle) assertTrue("$file: $x,$y inside the ring", hypot(x - 54, y - 54) < inner)
            val length = needle.maxOf { it.second } - needle.minOf { it.second }
            assertTrue("$file: the needle spans most of the ring: $length of ${2 * inner}", length >= 0.85 * 2 * inner)
        }
    }

    /**
     * The start screen shows the launcher icon as the launcher does, its disc behind the mark, on a black screen. Wear
     * app quality WO-V15: "Show a 48x48dp app icon on a black background during app startup." and "The splash
     * screen icon must match the app launcher icon."
     */
    @Test
    fun theStartScreenShowsTheIconsDiscOnBlack() {
        val style = children(xml("values/themes.xml"), "style").single { it.getAttribute("name") == "AppTheme" }
        val items = children(style, "item").associate { it.getAttribute("name") to it.textContent.trim() }
        assertEquals(
            "the icon's disc behind the mark at the start", "@color/ic_launcher_background",
            items["android:windowSplashScreenIconBackgroundColor"],
        )
        val screen = items["android:windowSplashScreenBackground"]
        assertTrue("the start screen is black: $screen", screen == null || screen == "@android:color/black")
    }

    @Test
    fun theMonochromeLayerHasTheForegroundsShapes() {
        val color = paths("drawable/ic_launcher_foreground.xml")
        val mono = paths("drawable/ic_launcher_monochrome.xml")
        assertEquals(color.size, mono.size)
        for ((c, m) in color.zip(mono)) {
            assertEquals(c.attr("pathData"), m.attr("pathData"))
            assertEquals(c.attr("strokeWidth"), m.attr("strokeWidth"))
            // And each is drawn as the foreground's is: stroked where it is stroked, filled where it is filled, and
            // with a color that shows (opaque), so no path of the monochrome layer is there and not drawn.
            assertEquals("stroked: ${m.attr("pathData")}", drawn(c, "strokeColor"), drawn(m, "strokeColor"))
            assertEquals("filled: ${m.attr("pathData")}", drawn(c, "fillColor"), drawn(m, "fillColor"))
            assertTrue("drawn at all: ${m.attr("pathData")}", drawn(m, "strokeColor") || drawn(m, "fillColor"))
        }
    }

    /** Whether the path's color [name] (`strokeColor` or `fillColor`) is given and opaque: `#RRGGBB` or `#FFRRGGBB`. */
    private fun drawn(path: Element, name: String): Boolean {
        val hex = path.attr(name).removePrefix("#")
        return when (hex.length) {
            6 -> true
            8 -> hex.substring(0, 2).uppercase() == "FF"
            else -> false
        } && (name != "strokeColor" || (path.attr("strokeWidth").toDoubleOrNull() ?: 0.0) > 0.0)
    }
}
