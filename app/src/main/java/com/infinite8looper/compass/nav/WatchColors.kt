package com.infinite8looper.compass.nav

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// The three colors that follow the watch, decided from numbers. A color here is its red, green and blue as one
// number, 0xRRGGBB; anything above those 24 bits is ignored.

/** The app's own colors, used where the watch gives none, or gives one that cannot do the job. */
const val FALLBACK_ACCENT = 0x2F8BFF
const val FALLBACK_NORTH = 0xFF3B30
const val FALLBACK_ERROR = 0xFF3B30

/**
 * The least contrast with the black field that one of the three colors may have: 4.5 to 1, the ratio the Web
 * Content Accessibility Guidelines ask of small text, since the accent and the error color are drawn as small text
 * as well as thin lines.
 */
const val MIN_CONTRAST = 4.5

/**
 * The least saturation at which a color is still told from the grays of the instrument, where gray means off or
 * secondary. The ink has 0.02; the palest color the Wear OS 7.0 emulator's system made had 0.19.
 */
const val MIN_SATURATION = 0.15f

/** North is at least this many degrees of hue from the pointer. */
const val MIN_HUE_APART = 60f

/**
 * North has at least this much saturation. It is one heavy tick among heavy ticks in the ink color, and the colors
 * of a watch's scheme are pale (mostly 0.19 to 0.31 from the Wear OS 7.0 emulator's system), which next to the ink is
 * hard to pick out at a glance.
 */
const val NORTH_MIN_SATURATION = 0.5f

/** The three colors the app reads from the watch's color scheme. */
data class WatchScheme(val primary: Int, val tertiary: Int, val error: Int)

/** The colors of the three roles: the pointer and what is on or can be acted on; north; a failure and deleting. */
data class RoleColors(val accent: Int, val north: Int, val error: Int)

/**
 * The colors of the three roles on a watch whose color scheme is [scheme], or that has none (null).
 *
 * With no scheme they are the app's own blue and red. With one, the accent is its primary, north its tertiary and
 * the error color its error color, each put through the same two checks first:
 *
 * - It must read on black. A color with less than [MIN_CONTRAST] is mixed with white, just far enough to have it,
 *   which keeps its hue.
 * - It must be a color. One with less than [MIN_SATURATION], as it stands or once lightened, gives its place to
 *   the app's own color for that role: a gray pointer could not be told from the ticks, nor a gray switch that is
 *   on from one that is off.
 *
 * Then north is kept apart from the accent, whatever the two came from. If their hues are less than
 * [MIN_HUE_APART] apart, north's hue is turned to exactly that far from the accent's, the way round it already
 * leans (clockwise when the two are the same), keeping how pale and how bright it was.
 *
 * Last, north alone is deepened: with less than [NORTH_MIN_SATURATION] it is given that much, keeping its hue and
 * its brightness. If turning or deepening left it too dark to read on black it is lightened again, which comes
 * last, so reading on black is never given up for the other two.
 *
 * The error color is not compared with the other two: it may match north, as the app's own red does.
 */
fun roleColors(scheme: WatchScheme?): RoleColors {
    if (scheme == null) return RoleColors(FALLBACK_ACCENT, FALLBACK_NORTH, FALLBACK_ERROR)
    val accent = usable(scheme.primary, FALLBACK_ACCENT)
    val north = readable(deepened(apartFrom(accent, usable(scheme.tertiary, FALLBACK_NORTH))))
    return RoleColors(accent, north, usable(scheme.error, FALLBACK_ERROR))
}

/** [rgb] lightened until it reads on black, or [fallback] when that leaves a gray. */
private fun usable(rgb: Int, fallback: Int): Int {
    val light = readable(rgb and 0xFFFFFF)
    return if (saturationOf(light) < MIN_SATURATION) fallback else light
}

/** [rgb] if it has [MIN_CONTRAST] on black, and otherwise the least mix of it with white that has. */
private fun readable(rgb: Int): Int {
    if (contrastOnBlack(rgb) >= MIN_CONTRAST) return rgb
    // More white is always lighter, so the least share of white that is enough is found by halving.
    var low = 0.0
    var high = 1.0
    repeat(16) {
        val mid = (low + high) / 2.0
        if (contrastOnBlack(towardWhite(rgb, mid)) >= MIN_CONTRAST) high = mid else low = mid
    }
    return towardWhite(rgb, high)
}

private fun towardWhite(rgb: Int, share: Double): Int {
    fun mix(channel: Int) = (channel + (255 - channel) * share).roundToInt()
    return (mix(rgb shr 16 and 0xFF) shl 16) or (mix(rgb shr 8 and 0xFF) shl 8) or mix(rgb and 0xFF)
}

/** [north], with its hue turned away from [accent]'s if the two are less than [MIN_HUE_APART] apart. */
private fun apartFrom(accent: Int, north: Int): Int {
    if (hueApart(accent, north) >= MIN_HUE_APART) return north
    // Which way round north already is from the accent, as an angle from -180 to 180.
    val lean = ((hueOf(north) - hueOf(accent) + 540f) % 360f) - 180f
    val hue = (hueOf(accent) + (if (lean < 0f) -MIN_HUE_APART else MIN_HUE_APART) + 360f) % 360f
    return fromHsv(hue, saturationOf(north), valueOf(north))
}

/** [north] with at least [NORTH_MIN_SATURATION], at the same hue and brightness. */
private fun deepened(north: Int): Int =
    if (saturationOf(north) >= NORTH_MIN_SATURATION) north else fromHsv(hueOf(north), NORTH_MIN_SATURATION, valueOf(north))

/**
 * The contrast ratio of [rgb] with black, from 1 (black) to 21 (white), as the Web Content Accessibility
 * Guidelines define it: the color's relative luminance plus 0.05, over black's 0.05.
 */
fun contrastOnBlack(rgb: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val luminance = 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)
    return (luminance + 0.05) / 0.05
}

/** How much of [rgb] is color, from 0 (a gray) to 1: the share of its brightest channel that its dimmest lacks. */
fun saturationOf(rgb: Int): Float {
    val max = maxChannel(rgb)
    return if (max == 0) 0f else (max - minChannel(rgb)) / max.toFloat()
}

/** The hue of [rgb] in degrees round the color wheel, from 0 (red) up to 360; 0 for a gray, which has none. */
fun hueOf(rgb: Int): Float {
    val r = rgb shr 16 and 0xFF
    val g = rgb shr 8 and 0xFF
    val b = rgb and 0xFF
    val max = maxChannel(rgb)
    val spread = (max - minChannel(rgb)).toFloat()
    if (spread == 0f) return 0f
    val sixth = when (max) {
        r -> ((g - b) / spread).mod(6f)
        g -> (b - r) / spread + 2f
        else -> (r - g) / spread + 4f
    }
    return (sixth * 60f).mod(360f)
}

/** How far apart the hues of [a] and [b] are, the shorter way round the wheel: 0 to 180 degrees. */
fun hueApart(a: Int, b: Int): Float = abs(((hueOf(a) - hueOf(b) + 540f) % 360f) - 180f)

private fun valueOf(rgb: Int): Float = maxChannel(rgb) / 255f

private fun maxChannel(rgb: Int): Int = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
private fun minChannel(rgb: Int): Int = minOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)

private fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
    fun channel(n: Float): Int {
        val k = (n + hue / 60f) % 6f
        return ((value - value * saturation * maxOf(0f, minOf(k, 4f - k, 1f))) * 255f).roundToInt()
    }
    return (channel(5f) shl 16) or (channel(3f) shl 8) or channel(1f)
}
