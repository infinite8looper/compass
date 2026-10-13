package com.infinite8looper.compass.nav

import kotlin.math.floor
import kotlin.math.roundToInt

// The ring a waypoint's color comes from, decided from numbers. A color here is its red, green and blue as one
// number, 0xRRGGBB, as in WatchColors.kt; anything above those 24 bits is ignored and never returned.

/**
 * The color at [position] on a ring that fades between three colors: [first] at 0, [second] a third of the way
 * round, [third] at two thirds, and [first] again at the full turn. [position] is in turns and wraps, so 1.25 and
 * -0.75 are 0.25; a position that is not a number is 0.
 *
 * The app gives it a waypoint's stored value over 360 and the three colors that follow the watch (the accent, north
 * and the error color), so every waypoint's color is one of the ring's. Two anchors that are the same color, as the
 * app's own north and error colors are, give that color for the whole third between them.
 *
 * Between two neighbors the fade is straight in each of the red, green and blue values as they are stored (sRGB),
 * which is how the mockups this ring was chosen from were drawn. It is not a fade in light or in a perceptual color
 * space: half way between two colors is half way between their channel values, and can be darker than both.
 *
 * It allocates nothing, so it can be called while drawing.
 */
fun ringColor(position: Float, first: Int, second: Int, third: Int): Int {
    val turns = if (position.isNaN() || position.isInfinite()) 0f else position - floor(position)
    // Three thirds, each from one anchor to the next. A position a rounding below the full turn can come out as 3.
    val along = turns * 3f
    val third1 = along.toInt().coerceIn(0, 2)
    val share = (along - third1).coerceIn(0f, 1f)
    val from = when (third1) { 0 -> first; 1 -> second; else -> third }
    val to = when (third1) { 0 -> second; 1 -> third; else -> first }
    return (mixChannel(from shr 16, to shr 16, share) shl 16) or (mixChannel(from shr 8, to shr 8, share) shl 8) or mixChannel(from, to, share)
}

private fun mixChannel(from: Int, to: Int, share: Float): Int {
    val a = from and 0xFF
    val b = to and 0xFF
    return (a + (b - a) * share).roundToInt()
}

/**
 * The share of a color's saturation that its soft tint keeps: 0.40 of 0.65, which is what the tint of a waypoint's
 * color kept when those were the two saturations.
 */
const val SOFT_TINT_SATURATION_KEPT = 0.40f / 0.65f

/**
 * The soft tint of [rgb]: the same hue and the same brightness with [SOFT_TINT_SATURATION_KEPT] of the saturation.
 * The brightest channel stays as it is and each other channel keeps that share of its distance below it, which
 * changes neither which channel is brightest nor the proportions between the distances, and those two are the hue.
 * A gray stays that gray. The tint is drawn partly transparent; that is the caller's.
 */
fun softTintOf(rgb: Int): Int {
    val r = rgb shr 16 and 0xFF
    val g = rgb shr 8 and 0xFF
    val b = rgb and 0xFF
    val top = maxOf(r, g, b)
    fun soft(channel: Int) = (top - (top - channel) * SOFT_TINT_SATURATION_KEPT).roundToInt()
    return (soft(r) shl 16) or (soft(g) shl 8) or soft(b)
}

/** A soft tint is drawn at this much of full strength: 153 of 255, which is 0.6. */
const val SOFT_TINT_ALPHA_255 = 153

/** The least contrast with the black field that a line drawn on it has: 3 to 1, what the Web Content Accessibility Guidelines ask of a mark that carries meaning. */
const val MIN_LINE_CONTRAST = 3.0

/** What [rgb] drawn at the soft tint's strength over black comes out as: each channel at 153 of 255. */
fun softTintOverBlack(rgb: Int): Int {
    fun over(channel: Int) = ((channel and 0xFF) * SOFT_TINT_ALPHA_255 + 127) / 255
    return (over(rgb shr 16) shl 16) or (over(rgb shr 8) shl 8) or over(rgb)
}

/**
 * The color of a soft line for a target whose color is [rgb]: its soft tint ([softTintOf]), made to read on black.
 *
 * A tint that has [MIN_LINE_CONTRAST] against black when drawn at its strength is used as it is. One that has not
 * (with the app's own colors, the ring's darker stretch between the blue and the red) is made brighter, by the
 * least whole step of brightness that reads: every channel is raised in the same proportion, which keeps the hue
 * and the saturation. A color too dark to read even at full brightness, which no ring of the three role colors
 * holds, is then mixed with white, just far enough; that keeps its hue too.
 *
 * It allocates nothing, so it can be called while drawing.
 */
fun softLineColor(rgb: Int): Int {
    val tint = softTintOf(rgb)
    if (readsAsSoftLine(tint)) return tint
    val top = maxOf(tint shr 16 and 0xFF, tint shr 8 and 0xFF, tint and 0xFF)
    val brightest = if (top == 0) tint else brighter(tint, top, 255)
    if (top > 0 && readsAsSoftLine(brightest)) {
        // The least brightness that reads lies above the tint's own and at or below the full one.
        var low = top
        var high = 255
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (readsAsSoftLine(brighter(tint, top, mid))) high = mid else low = mid
        }
        return brighter(tint, top, high)
    }
    // More white is always lighter, so the least share of white that is enough is found by halving.
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (readsAsSoftLine(paler(brightest, mid))) high = mid else low = mid
    }
    return paler(brightest, high)
}

private fun readsAsSoftLine(rgb: Int): Boolean = contrastOnBlack(softTintOverBlack(rgb)) >= MIN_LINE_CONTRAST

/** [rgb], whose brightest channel is [top], with every channel raised in proportion so that the brightest is [to]. */
private fun brighter(rgb: Int, top: Int, to: Int): Int {
    fun raised(channel: Int) = ((channel and 0xFF) * to + top / 2) / top
    return (raised(rgb shr 16) shl 16) or (raised(rgb shr 8) shl 8) or raised(rgb)
}

/** [rgb] mixed with white, [share] of the way. */
private fun paler(rgb: Int, share: Float): Int {
    fun mixed(channel: Int) = mixChannel(channel, 255, share)
    return (mixed(rgb shr 16) shl 16) or (mixed(rgb shr 8) shl 8) or mixed(rgb)
}
