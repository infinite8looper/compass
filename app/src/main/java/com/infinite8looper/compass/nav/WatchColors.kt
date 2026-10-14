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

/**
 * Under this saturation a color is a gray. A scheme's primary under it, once it reads, is not used; the ink and the
 * grays taken from a scheme ([graysFor]) are held under it. The grays of the schemes the emulators gave had up to 0.085.
 */
const val GRAY_SATURATION = 0.10f

/** North is at least this many degrees of hue from the pointer. */
const val MIN_HUE_APART = 60f

/**
 * North has at least this much saturation, whatever else is done to it. It is one heavy tick among heavy ticks in the ink color, and the colors
 * of a watch's scheme are pale (mostly 0.19 to 0.31 from the Wear OS 7.0 emulator's system), which next to the ink is
 * hard to pick out at a glance.
 */
const val NORTH_MIN_SATURATION = 0.5f

/**
 * The error color is told from the accent by its hue or by how strong it is: one that is less than this many
 * degrees of hue and less than [ERROR_MIN_SATURATION_APART] of saturation from the accent looks like it.
 */
const val ERROR_MIN_HUE_APART = 60f
const val ERROR_MIN_SATURATION_APART = 0.3f

/**
 * The colors the app reads from the watch's color scheme: the three roles, and the scheme's colors for text
 * ([onSurface]), secondary text ([onSurfaceVariant]) and outlines ([outline]), each null where the scheme gives none.
 */
data class WatchScheme(
    val primary: Int, val tertiary: Int, val error: Int,
    val onSurface: Int? = null, val onSurfaceVariant: Int? = null, val outline: Int? = null,
)

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
 * - It must be a color: one with less than [MIN_SATURATION], as it stands or once lightened, is not used. A gray
 *   pointer could not be told from the ticks, nor a gray switch that is on from one that is off.
 *
 * The primary alone is kept when it is pale (v3): one under [MIN_SATURATION] once it reads, but at or over
 * [GRAY_SATURATION], is deepened to [MIN_SATURATION], with its hue and its brightness kept ([deepened]). Only a primary
 * under [GRAY_SATURATION], which is a gray, is not used.
 *
 * A scheme whose primary is not used is not used at all: all three colors are the app's own, so the app's blue
 * never stands beside a north from the watch. A tertiary or an error color that is not used gives only its own
 * role the app's red.
 *
 * Then north is kept apart from the accent and deep enough to find among the ticks: on the color that is
 * returned, its hue is at least [MIN_HUE_APART] from the accent's, its saturation at least
 * [NORTH_MIN_SATURATION], and it reads on black. A tertiary that already is all three is used as it is. Otherwise
 * its hue is turned to that far from the accent's, the way round it already leans (clockwise when the two are the
 * same), it is given that much saturation, and it is made brighter until it reads.
 *
 * Last, an error color that looks like the accent (less than [ERROR_MIN_HUE_APART] of hue and less than
 * [ERROR_MIN_SATURATION_APART] of saturation from it) gives its place to the app's red, so that deleting is not
 * drawn like something to tap. The error color is not compared with north: it may match it, as the app's own red
 * does.
 */
fun roleColors(scheme: WatchScheme?): RoleColors {
    val own = RoleColors(FALLBACK_ACCENT, FALLBACK_NORTH, FALLBACK_ERROR)
    val accent = scheme?.let { usableAccent(it.primary) } ?: return own
    val north = northFor(accent, usable(scheme.tertiary) ?: FALLBACK_NORTH)
    val error = usable(scheme.error)?.takeUnless { looksLike(it, accent) } ?: FALLBACK_ERROR
    return RoleColors(accent, north, error)
}

/**
 * The accent made from the scheme's primary [rgb]: lightened until it reads on black; then, if that leaves less than
 * [MIN_SATURATION] but at least [GRAY_SATURATION], deepened to [MIN_SATURATION] ([deepened]); null when it is a gray.
 */
private fun usableAccent(rgb: Int): Int? {
    val lit = readable(rgb and 0xFFFFFF)
    val saturation = saturationOf(lit)
    return when {
        saturation >= MIN_SATURATION -> lit
        saturation < GRAY_SATURATION -> null
        else -> deepened(lit)
    }
}

/**
 * [rgb], a pale color that reads on black, with its saturation raised to [MIN_SATURATION]: the same hue and the same
 * brightest channel, the others lowered. Checked on the color returned, in whole red, green and blue: where rounding
 * leaves it a hair under the saturation it aims a little higher, and where lowering the other channels took it under
 * [MIN_CONTRAST] it is made brighter.
 */
private fun deepened(rgb: Int): Int {
    val hue = hueOf(rgb)
    var saturation = MIN_SATURATION
    var value = valueOf(rgb)
    repeat(NORTH_PASSES) {
        val made = fromHsv(hue, saturation, value)
        when {
            contrastOnBlack(made) < MIN_CONTRAST -> if (value < 1f) value = minOf(1f, value + NORTH_STEP) else return rgb
            saturationOf(made) < MIN_SATURATION -> saturation = minOf(1f, saturation + NORTH_STEP)
            else -> return made
        }
    }
    return rgb
}

/** [rgb] lightened until it reads on black, or null when that leaves a gray. */
private fun usable(rgb: Int): Int? = readable(rgb and 0xFFFFFF).takeUnless { saturationOf(it) < MIN_SATURATION }

/** Whether [error] is too near [accent] in both hue and saturation to be told from it. */
private fun looksLike(error: Int, accent: Int): Boolean =
    hueApart(error, accent) < ERROR_MIN_HUE_APART && abs(saturationOf(error) - saturationOf(accent)) < ERROR_MIN_SATURATION_APART

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

/**
 * North for a watch whose accent is [accent], made from [from]: [from] itself when it is at least [MIN_HUE_APART]
 * from the accent, has at least [NORTH_MIN_SATURATION] and reads on black. Otherwise its hue is turned to
 * [MIN_HUE_APART] from the accent's, the way round it already leans (clockwise when the two are the same), its
 * saturation is raised to [NORTH_MIN_SATURATION], and it is made as much brighter as reading on black needs; a
 * color that is too dark even at full brightness is made paler instead, which stops above [NORTH_MIN_SATURATION]
 * at every hue.
 *
 * The three bounds are checked on the color that is returned, in whole red, green and blue: the hue, saturation
 * and brightness aimed at are kept as fractions and never read back from a rounded color on the way, and where
 * rounding leaves the result a hair under a bound, the aim is moved a little further and the color made again.
 */
private fun northFor(accent: Int, from: Int): Int {
    fun holds(rgb: Int) =
        hueApart(accent, rgb) >= MIN_HUE_APART && saturationOf(rgb) >= NORTH_MIN_SATURATION && contrastOnBlack(rgb) >= MIN_CONTRAST
    if (holds(from)) return from
    // Which way round north is from the accent, as an angle from -180 to 180, and how far.
    val lean = ((hueOf(from) - hueOf(accent) + 540f) % 360f) - 180f
    val way = if (lean < 0f) -1f else 1f
    var apart = maxOf(abs(lean), MIN_HUE_APART)
    var saturation = maxOf(saturationOf(from), NORTH_MIN_SATURATION)
    var value = valueOf(from)
    // Each pass moves one aim by less than a step of a color channel, so the color returned is close to the first that holds.
    repeat(NORTH_PASSES) {
        val rgb = fromHsv((hueOf(accent) + way * apart + 360f) % 360f, saturation, value)
        when {
            holds(rgb) -> return rgb
            contrastOnBlack(rgb) < MIN_CONTRAST -> if (value < 1f) value = minOf(1f, value + NORTH_STEP) else saturation -= NORTH_STEP
            saturationOf(rgb) < NORTH_MIN_SATURATION -> saturation = minOf(1f, saturation + NORTH_STEP)
            else -> apart = minOf(180f, apart + NORTH_HUE_STEP)
        }
    }
    // Not reached for any color: full brightness at the least saturation reads at every hue. The app's own red
    // stands in, turned away from the accent if it has to be, should it ever be.
    return if (hueApart(accent, FALLBACK_NORTH) >= MIN_HUE_APART) FALLBACK_NORTH else fromHsv((hueOf(accent) + 180f) % 360f, 1f, 1f).let(::readable)
}

/** How far one pass moves the saturation or the brightness aimed at, and the hue: under one step of a color channel. */
private const val NORTH_STEP = 1f / 512f
private const val NORTH_HUE_STEP = 0.1f
private const val NORTH_PASSES = 4_000

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

internal fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
    fun channel(n: Float): Int {
        val k = (n + hue / 60f) % 6f
        return ((value - value * saturation * maxOf(0f, minOf(k, 4f - k, 1f))) * 255f).roundToInt()
    }
    return (channel(5f) shl 16) or (channel(3f) shl 8) or channel(1f)
}

// The `Colors` setting: `Standard` or `Watch face`.

/** The two choices of the `Colors` setting. */
enum class ColorMode { STANDARD, WATCH_FACE }

/**
 * The setting as the settings file holds it, the name of a [ColorMode]. Nothing stored, and anything that is not
 * exactly the name of [ColorMode.WATCH_FACE], is [ColorMode.STANDARD].
 */
fun colorModeOf(stored: String?): ColorMode = if (stored == ColorMode.WATCH_FACE.name) ColorMode.WATCH_FACE else ColorMode.STANDARD

/** The app's own ink, secondary text, faint ticks and the dial's degree numbers: the colors of `Standard`. */
const val STANDARD_INK = 0xE8EAED
const val STANDARD_DIM = 0x9E9E9E
const val STANDARD_FAINT = 0x6E6E6E
const val STANDARD_LABEL = 0x7C7C7C

/** The light color of text, heavy ticks and outlines; secondary text; fine ticks and what is off; the dial's numbers. */
data class Grays(val ink: Int, val dim: Int, val faint: Int, val label: Int)

/** How a waypoint's stored place, 0 to 360, becomes its color: the full wheel of hues, or the ring of the three roles. */
enum class WaypointColors { WHEEL, RING }

/** Every color the screens take from the setting. */
data class Palette(val roles: RoleColors, val grays: Grays, val waypoints: WaypointColors)

/** `Standard`: north red, the pointer blue, the app's own grays, and the full wheel for waypoints. */
val STANDARD_PALETTE = Palette(
    RoleColors(FALLBACK_ACCENT, FALLBACK_NORTH, FALLBACK_ERROR),
    Grays(STANDARD_INK, STANDARD_DIM, STANDARD_FAINT, STANDARD_LABEL),
    WaypointColors.WHEEL,
)

/**
 * The colors for the setting [mode] on a watch whose scheme is [scheme]. `Standard` is [STANDARD_PALETTE] whatever
 * [scheme] is. `Watch face` is the roles of [roleColors], the ink and the grays of [graysFor] and the ring for the
 * waypoints; with no scheme it cannot be chosen, and the colors are those of `Standard`.
 */
fun paletteFor(mode: ColorMode, scheme: WatchScheme?): Palette =
    if (mode == ColorMode.STANDARD || scheme == null) STANDARD_PALETTE
    else Palette(roleColors(scheme), graysFor(scheme), WaypointColors.RING)

/**
 * The ink and the grays on a watch whose scheme is [scheme]: the ink from its `onSurface`, the secondary text from
 * `onSurfaceVariant`, and the faint ticks and the dial's numbers from `outline`. A color the scheme does not give
 * keeps the app's own.
 *
 * What is taken from the scheme's color is its hue and its saturation, the saturation held under [GRAY_SATURATION]
 * so that a gray never looks like the accent. The brightness is the least at which it has at least the contrast
 * with black of the app's own color for that role, so each reads as well as the app's own, and the four keep their
 * order from lightest to darkest. Where even full brightness does not read that well (a strong blue beside the
 * app's near-white ink), it keeps full brightness and gives up as little of its saturation as reading needs.
 */
fun graysFor(scheme: WatchScheme?): Grays {
    val own = STANDARD_PALETTE.grays
    if (scheme == null) return own
    return Grays(
        ink = scheme.onSurface?.let { grayLike(it, own.ink) } ?: own.ink,
        dim = scheme.onSurfaceVariant?.let { grayLike(it, own.dim) } ?: own.dim,
        faint = scheme.outline?.let { grayLike(it, own.faint) } ?: own.faint,
        label = scheme.outline?.let { grayLike(it, own.label) } ?: own.label,
    )
}

/** The hue and (held under [GRAY_SATURATION]) the saturation of [from], at the least brightness that reads on black as well as [own]. */
private fun grayLike(from: Int, own: Int): Int {
    val rgb = from and 0xFFFFFF
    val hue = hueOf(rgb)
    // A hair under the bound, so that the rounding of the channels does not carry it over.
    val saturation = minOf(saturationOf(rgb), GRAY_SATURATION - 0.01f)
    val target = contrastOnBlack(own)
    // At full brightness a dark hue such as blue can read less well than the app's ink: it then keeps full
    // brightness and has as much of its saturation as still reads, found by halving (white, with none, reads best).
    if (contrastOnBlack(fromHsv(hue, saturation, 1f)) < target) {
        var less = 0f
        var more = saturation
        repeat(16) {
            val mid = (less + more) / 2f
            if (contrastOnBlack(fromHsv(hue, mid, 1f)) >= target) less = mid else more = mid
        }
        return fromHsv(hue, less, 1f)
    }
    // Otherwise a darker color never reads better, so the least brightness that is enough is found by halving.
    var low = 0
    var high = 255
    while (high - low > 1) {
        val mid = (low + high) / 2
        if (contrastOnBlack(fromHsv(hue, saturation, mid / 255f)) >= target) high = mid else low = mid
    }
    return fromHsv(hue, saturation, high / 255f)
}

/** A waypoint's color in [palette] for its stored place [position], 0 to 360. It allocates nothing. */
fun waypointRgb(palette: Palette, position: Float): Int = when (palette.waypoints) {
    WaypointColors.WHEEL -> wheelColor(position)
    WaypointColors.RING -> palette.roles.let { ringColor(position / 360f, it.accent, it.north, it.error) }
}
