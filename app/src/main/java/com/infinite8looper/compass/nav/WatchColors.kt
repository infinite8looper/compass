package com.infinite8looper.compass.nav

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// The colors that follow the watch with `Colors` at `Watch face`, and the setting itself, decided from numbers. A color here is its red, green and blue as one
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
 * The least saturation of the accent (v3): a pale primary is deepened to it. The owner (2026-10-14), on a pale
 * accent: "Deepen to ~0.3 saturation", about what the Wear OS 7.0 emulator's schemes have (0.22 to 0.28).
 */
const val ACCENT_MIN_SATURATION = 0.30f

/**
 * How much more saturation the accent has than the ink, at least: the accent has [ACCENT_MIN_SATURATION] or more
 * and the ink taken from a scheme is held under [GRAY_SATURATION] less a hair ([graysFor]), so where both are near
 * white and share a hue the accent still has the color the ink lacks.
 */
const val ACCENT_INK_SATURATION_APART = 0.2f

/**
 * Under this saturation a color is a gray. A scheme's primary under it, once it reads, makes a gray scheme
 * ([isGrayScheme]); the ink and the grays taken from a scheme ([graysFor]) are held under it. The grays of the schemes the emulators gave had up to 0.085.
 */
const val GRAY_SATURATION = 0.10f

/**
 * In a gray scheme ([isGrayScheme]) the pointer is a gray-red toward north, near it in hue: north is told from it by
 * its saturation, at least this much more (the margin by which the error color is told from the accent too).
 */
const val NORTH_POINTER_SATURATION_APART = 0.3f

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
 * The primary alone is kept when it is pale (v3): one under [ACCENT_MIN_SATURATION] once it reads, but at or over
 * [GRAY_SATURATION], is deepened to [ACCENT_MIN_SATURATION], with its hue and its brightness kept ([deepened]). A
 * primary under [GRAY_SATURATION] is a gray, and is not used as the accent as it stands (below).
 *
 * A primary under [GRAY_SATURATION] makes a gray scheme ([isGrayScheme], [grayRoleColors]) when the tertiary or the
 * error color is a color: north is made from that color and the pointer is a gray-red between the primary and north
 * (owner, 2026-10-16). A scheme whose primary is a gray and that has no color at all is not used: all three colors
 * are the app's own, so the app's blue never stands beside a north from the watch. A tertiary or an error color
 * that is not used gives only its own role the app's red.
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
    if (scheme == null) return own
    if (isGrayScheme(scheme)) return grayRoleColors(scheme)
    val accent = usableAccent(scheme.primary) ?: return own
    val north = northFor(accent, usable(scheme.tertiary) ?: FALLBACK_NORTH)
    val error = usable(scheme.error)?.takeUnless { looksLike(it, accent) } ?: FALLBACK_ERROR
    return RoleColors(accent, north, error)
}

/**
 * Whether [scheme] is a gray scheme: its primary, once it reads on black, has less than [GRAY_SATURATION] (a gray),
 * and its tertiary or its error color is a color ([MIN_SATURATION] or more once it reads), which north is made from.
 */
fun isGrayScheme(scheme: WatchScheme): Boolean =
    saturationOf(readable(scheme.primary and 0xFFFFFF)) < GRAY_SATURATION && (usable(scheme.tertiary) ?: usable(scheme.error)) != null

/**
 * The three roles of a gray scheme ([isGrayScheme]). Owner, 2026-10-16: "For a color scheme like mine, North should
 * be the red from the watch face, and the marker colors and other elements should be gray or gray-red. I.e. North is
 * the dominant theme color and the waypoints and other UI elements come from other theme colors with
 * interpolation".
 *
 * - North is made from the tertiary, or from the error color where the tertiary is a gray. The tertiary comes first
 *   because it is the color the system makes from the watch face's own accent (on the owner's watch the tertiary
 *   0xFFC0B7 from the watch face's accent_color #FF3E32), where the error color is the system's error tone. Its hue
 *   and brightness are kept and it is deepened to at least [NORTH_MIN_SATURATION] and to
 *   [NORTH_POINTER_SATURATION_APART] more than the pointer's, and made to read on black.
 * - The pointer (the accent) is the primary, made to read, mixed with north: the least share of north that gives it
 *   [ACCENT_MIN_SATURATION], a gray-red that the ink (under [GRAY_SATURATION]) and north are both told from by
 *   saturation.
 * - The error color is the scheme's as for any scheme: the app's red where it looks like the pointer ([looksLike]).
 *
 * The bounds are checked on the colors returned, in whole red, green and blue.
 */
private fun grayRoleColors(scheme: WatchScheme): RoleColors {
    val from = usable(scheme.tertiary) ?: usable(scheme.error) ?: FALLBACK_NORTH
    val primary = readable(scheme.primary and 0xFFFFFF)
    val hue = hueOf(from)
    // Aimed at as fractions, never read back from a rounded color on the way (as for [northFor]): the saturation is
    // raised until north is deep enough beside the pointer made toward it, and the brightness until it reads; at
    // full brightness a hue too dark to read gives up saturation instead.
    var saturation = maxOf(saturationOf(from), NORTH_MIN_SATURATION, ACCENT_MIN_SATURATION + NORTH_POINTER_SATURATION_APART)
    var value = valueOf(from)
    repeat(NORTH_PASSES) {
        val north = fromHsv(hue, minOf(1f, saturation), value)
        if (contrastOnBlack(north) < MIN_CONTRAST) {
            if (value < 1f) value = minOf(1f, value + NORTH_STEP) else saturation -= NORTH_STEP
            return@repeat
        }
        val accent = towardNorth(primary, north)
        if (saturationOf(north) >= NORTH_MIN_SATURATION && saturationOf(north) - saturationOf(accent) >= NORTH_POINTER_SATURATION_APART) {
            val error = usable(scheme.error)?.takeUnless { looksLike(it, accent) } ?: FALLBACK_ERROR
            return RoleColors(accent, north, error)
        }
        saturation = minOf(1f, saturation + NORTH_STEP)
    }
    // Not reached for any color: at full brightness 0.61 of saturation reads at every hue. The app's own set stands in.
    return RoleColors(FALLBACK_ACCENT, FALLBACK_NORTH, FALLBACK_ERROR)
}

/**
 * [gray] (a color that reads on black) mixed with [north] (one that reads too), straight in red, green and blue, and
 * made to read on black ([readable]): with the least share of north, found by halving, at which the result has
 * [ACCENT_MIN_SATURATION].
 */
private fun towardNorth(gray: Int, north: Int): Int {
    fun mix(share: Float): Int {
        fun channel(shift: Int): Int {
            val a = gray shr shift and 0xFF
            val b = north shr shift and 0xFF
            return (a + (b - a) * share).roundToInt()
        }
        return readable((channel(16) shl 16) or (channel(8) shl 8) or channel(0))
    }
    if (saturationOf(gray) >= ACCENT_MIN_SATURATION) return gray
    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        if (saturationOf(mix(mid)) >= ACCENT_MIN_SATURATION) high = mid else low = mid
    }
    return mix(high)
}

/**
 * The accent made from the scheme's primary [rgb]: lightened until it reads on black; then, if that leaves less than
 * [ACCENT_MIN_SATURATION] but at least [GRAY_SATURATION], deepened to [ACCENT_MIN_SATURATION] ([deepened]); null
 * when it is a gray.
 */
private fun usableAccent(rgb: Int): Int? {
    val lit = readable(rgb and 0xFFFFFF)
    val saturation = saturationOf(lit)
    return when {
        saturation >= ACCENT_MIN_SATURATION -> lit
        saturation < GRAY_SATURATION -> null
        else -> deepened(lit)
    }
}

/**
 * [rgb], a pale color that reads on black, with its saturation raised to [ACCENT_MIN_SATURATION]: the same hue and
 * the same brightest channel, the others lowered. Checked on the color returned, in whole red, green and blue: where rounding
 * leaves it a hair under the saturation it aims a little higher, and where lowering the other channels took it under
 * [MIN_CONTRAST] it is made brighter.
 */
private fun deepened(rgb: Int): Int {
    val hue = hueOf(rgb)
    var saturation = ACCENT_MIN_SATURATION
    var value = valueOf(rgb)
    repeat(NORTH_PASSES) {
        val made = fromHsv(hue, saturation, value)
        when {
            contrastOnBlack(made) < MIN_CONTRAST -> if (value < 1f) value = minOf(1f, value + NORTH_STEP) else return rgb
            saturationOf(made) < ACCENT_MIN_SATURATION -> saturation = minOf(1f, saturation + NORTH_STEP)
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

/**
 * Every color the screens take from the setting. [ringThird] is the third anchor of the ring the waypoints' colors
 * come from with [WaypointColors.RING], after the accent and north ([ringThirdFor]).
 */
data class Palette(
    val roles: RoleColors, val grays: Grays, val waypoints: WaypointColors, val ringThird: Int = ringThirdFor(roles),
)

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
    else if (isGrayScheme(scheme)) roleColors(scheme).let { Palette(it, graysFor(scheme), WaypointColors.RING, grayRingThird(scheme)) }
    else Palette(roleColors(scheme), graysFor(scheme), WaypointColors.RING)

/**
 * The third anchor of a gray scheme's ring, after the gray-red pointer and north: a gray of the scheme, so that the
 * waypoints' colors are grays and gray-reds between the three (owner, 2026-10-16). It is the scheme's `outline`, or
 * where it gives none its `onSurfaceVariant`, else its primary; made to read on black ([readable]) and held under
 * [GRAY_SATURATION] (its hue and brightness kept).
 */
fun grayRingThird(scheme: WatchScheme): Int {
    val from = (scheme.outline ?: scheme.onSurfaceVariant ?: scheme.primary) and 0xFFFFFF
    val gray = if (saturationOf(from) < GRAY_SATURATION) from else fromHsv(hueOf(from), GRAY_SATURATION - 0.01f, valueOf(from))
    return readable(gray)
}

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

/**
 * How far round the ring, in turns, a stored place is read with [WaypointColors.RING]: turned so that the first new
 * waypoint's place ([FIRST_WAYPOINT_PLACE], 108.3) lands on the ring's third anchor, two thirds of the way round,
 * rather than near north (a third) or on the accent (0, the pointer's color). The owner (2026-10-14): "new waypoint
 * should be near some other color". The ring's order and thirds are kept, and no stored place is changed.
 *
 * It is worked out when read, not kept in a field of this file: [FIRST_WAYPOINT_PLACE] lives in Navigation.kt and is
 * made from this file's functions, so a field here could be set while Navigation.kt was still being set up, with the
 * place still 0 (which happened in the unit tests when Navigation.kt was the first of the two used).
 */
val RING_READ_OFFSET_TURNS: Float get() = 2f / 3f - FIRST_WAYPOINT_PLACE / 360f

/**
 * A waypoint's color in [palette] for its stored place [position], 0 to 360: the full wheel at that hue, or the ring
 * read [RING_READ_OFFSET_TURNS] on. It allocates nothing.
 */
fun waypointRgb(palette: Palette, position: Float): Int = when (palette.waypoints) {
    WaypointColors.WHEEL -> wheelColor(position)
    WaypointColors.RING -> palette.roles.let {
        ringColor(position / 360f + RING_READ_OFFSET_TURNS, it.accent, it.north, palette.ringThird)
    }
}

/**
 * The third anchor of the ring of [roles], after the accent and north, so that the ring has three colors apart in
 * hue and is a wheel without repeats. The owner (2026-10-14): "for themed colors make sure the wheel has *3*
 * anchorpoints-- otherwise the wheel will have repetitions (i.e., not actually a wheel)".
 *
 * It is the error color where that is at least [MIN_HUE_APART] of hue from both the accent and north. Otherwise
 * (the app's own red is north's red, and a scheme's error color can lie near north) it is a color made at the
 * middle of the widest gap of hue between the accent and north ([widestGapMiddle]), which is at least 90 degrees
 * from each: with the mean of their two saturations, at least [MIN_SATURATION], at full brightness, and lightened
 * until it reads on black, as the error color has to. The error color itself, the one errors are drawn in, is not
 * changed.
 */
fun ringThirdFor(roles: RoleColors): Int {
    val error = roles.error and 0xFFFFFF
    if (hueApart(error, roles.accent) >= MIN_HUE_APART && hueApart(error, roles.north) >= MIN_HUE_APART) return error
    val saturation = maxOf(MIN_SATURATION, (saturationOf(roles.accent) + saturationOf(roles.north)) / 2f)
    var made = readable(fromHsv(widestGapMiddle(roles.accent, roles.north), saturation, 1f))
    // Lightening lowers the saturation; at full brightness every hue reads by about 0.6 of saturation, so this is
    // reached only by a hue that had to be lightened a great deal, and is then given a little more.
    var more = saturation
    while (saturationOf(made) < MIN_SATURATION && more < 1f) {
        more = minOf(1f, more + NORTH_STEP * 8)
        made = readable(fromHsv(widestGapMiddle(roles.accent, roles.north), more, 1f))
    }
    return made
}

/**
 * The hue in the middle of the wider of the two gaps between the hues of [a] and [b], in degrees from 0 up to 360:
 * the hue furthest from both. For one hue twice, the one opposite it.
 */
fun widestGapMiddle(a: Int, b: Int): Float {
    val from = hueOf(a and 0xFFFFFF)
    val to = hueOf(b and 0xFFFFFF)
    // The gap from [from] round to [to] the way the hues increase, 0 up to 360; the other gap is the rest.
    val gap = ((to - from) % 360f + 360f) % 360f
    val middle = if (gap == 0f || gap >= 180f) from + (if (gap == 0f) 360f else gap) / 2f else to + (360f - gap) / 2f
    return ((middle % 360f) + 360f) % 360f
}
