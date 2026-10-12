package com.infinite8looper.compass.nav

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

// The decisions behind the controls of the waypoints and edit screens: how far a hold has come, what the list's
// header says, whether a touch is on the color wheel, how often a color is written, where the scroll arc sits and
// how far a row comes in from the round edge.

/** How long `Hold to delete` has to be held. */
const val HOLD_TO_DELETE_MS = 1000L

/**
 * How far a hold has come, from 0 when it starts to 1 when it has lasted [holdMs]: the hold is complete at 1 and
 * at nothing less. A hold time of nothing is complete as soon as it has started.
 */
fun holdProgress(elapsedMs: Long, holdMs: Long = HOLD_TO_DELETE_MS): Float = when {
    elapsedMs < 0 -> 0f
    holdMs <= 0 || elapsedMs >= holdMs -> 1f
    else -> elapsedMs.toFloat() / holdMs
}

/** The header of the waypoints list: how many of the [total] waypoints are targets, or that there are none yet. */
fun headerText(targets: Int, total: Int): String =
    if (total <= 0) "No waypoints yet" else "${targets.coerceIn(0, total)} of $total targets"

/** What a tap on the header does: true sets every waypoint as a target, false clears them all, which it does only when all are set. */
fun headerTapSetsAll(targets: Int, total: Int): Boolean = total <= 0 || targets < total

/** The band of the edit screen's color wheel, in fractions of the screen's radius. */
const val WHEEL_BAND_INNER = 0.86f
const val WHEEL_BAND_OUTER = 0.97f
/** Closer to the center than this fraction of the radius a finger names no color: the angle there swings too fast. */
const val WHEEL_DEAD_CENTER = 0.3f

/**
 * Whether a touch that goes down [touchRadius] from the center of a screen of radius [screenRadius] starts on the
 * color wheel: on its band, on the rim outside it, or within [tolerance] inside it, which is the room a fingertip
 * aimed at the band needs. All three are in the same unit.
 *
 * This is the one definition of "on the wheel": a touch that starts there is the wheel's and nothing else's (it
 * does not go back, and it does not hold `Hold to delete`), and a touch that starts anywhere else never changes
 * the color.
 */
fun startsOnWheel(touchRadius: Float, screenRadius: Float, tolerance: Float): Boolean {
    if (touchRadius.isNaN() || tolerance.isNaN() || !(screenRadius > 0f)) return false
    return touchRadius >= WHEEL_BAND_INNER * screenRadius - tolerance.coerceAtLeast(0f)
}

/**
 * The hue under a finger that is [dx] to the right of and [dy] below the center of a screen of radius
 * [screenRadius]: 0 at 12 o'clock, growing clockwise, below 360. Null within [WHEEL_DEAD_CENTER] of the center.
 */
fun wheelHue(dx: Float, dy: Float, screenRadius: Float): Float? {
    if (dx.isNaN() || dy.isNaN() || !(screenRadius > 0f)) return null
    if (hypot(dx, dy) < WHEEL_DEAD_CENTER * screenRadius) return null
    val hue = normalizeDeg(Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat())
    return if (hue >= 360f) 0f else hue
}

/** The least time between two writes of a color that is being changed: four a second. */
const val COLOR_WRITE_GAP_MS = 250L

/**
 * How long a color change made at [nowMs] waits before it is written, when the last write was at [lastWriteMs]
 * (null: none yet): nothing when the gap has passed, otherwise what is left of it. A change that is followed by
 * another before its wait is over is dropped for the newer one, so a color that keeps changing is written once per
 * gap and the last one is always written.
 */
fun colorWriteWaitMs(nowMs: Long, lastWriteMs: Long?, gapMs: Long = COLOR_WRITE_GAP_MS): Long {
    if (lastWriteMs == null || gapMs <= 0) return 0L
    return (lastWriteMs + gapMs - nowMs).coerceIn(0L, gapMs)
}

/** The part of the frame's scroll track that is lit: from [start] to [end], each from 0 (the top of the list) to 1. */
data class ScrollArc(val start: Float, val end: Float)

/** The shortest the scroll arc gets, as a fraction of its track. */
const val SCROLL_ARC_MIN = 0.2f

/**
 * Where the scroll arc is for a list that is [position] of the way through (0 at the top, 1 at the bottom) and has
 * [visibleFraction] of its rows on screen. Null when everything fits, and when either is not known.
 */
fun scrollArc(position: Float, visibleFraction: Float): ScrollArc? {
    if (position.isNaN() || visibleFraction.isNaN() || visibleFraction <= 0f || visibleFraction >= 1f) return null
    val length = visibleFraction.coerceAtLeast(SCROLL_ARC_MIN)
    val start = position.coerceIn(0f, 1f) * (1f - length)
    return ScrollArc(start, start + length)
}

/** Beyond this fraction of the ring's radius above or below the middle a row comes in no further. */
const val ROW_INSET_MAX_Y = 0.7f

/**
 * How far each end of a row comes in from where it would be at the middle of the screen, for a row whose middle is
 * [yFromCenter] above or below the screen's center, so that its ends follow a ring of radius [ringRadius]. Both
 * are in the same unit, and so is the answer.
 */
fun rowInset(yFromCenter: Float, ringRadius: Float): Float {
    if (yFromCenter.isNaN() || !(ringRadius > 0f)) return 0f
    val y = kotlin.math.abs(yFromCenter).coerceAtMost(ROW_INSET_MAX_Y * ringRadius)
    return ringRadius - sqrt(ringRadius * ringRadius - y * y)
}
