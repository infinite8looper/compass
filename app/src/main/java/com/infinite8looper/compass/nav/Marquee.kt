package com.infinite8looper.compass.nav

// A name too long for its place slides back and forth in it: it waits at its start, slides at a steady speed until
// its end shows, waits there, slides back the same way, and starts over. It never wraps around.

/** How long the text waits at each end, in milliseconds. */
const val MARQUEE_HOLD_MS = 1_200.0
/** How fast the text slides, in dp a second. */
const val MARQUEE_DP_PER_SECOND = 30f

/**
 * How far the text has slid, [elapsedMs] after it started: from 0, where its start shows, to [overflow], where its
 * end does. [overflow] is how much wider the text is than its place, and [perSecond] the speed, in the same unit a
 * second. The answer is exactly 0 through the first wait and exactly [overflow] through the second.
 *
 * Text that fits ([overflow] of 0 or less) never moves. A time, a width or a speed that is not a usable number
 * gives 0, and a wait that is not one is no wait.
 */
fun marqueeOffset(elapsedMs: Double, overflow: Float, perSecond: Float, holdMs: Double = MARQUEE_HOLD_MS): Float {
    if (!elapsedMs.isFinite() || elapsedMs < 0.0) return 0f
    if (!overflow.isFinite() || overflow <= 0f) return 0f
    if (!perSecond.isFinite() || perSecond <= 0f) return 0f
    if (holdMs == Double.POSITIVE_INFINITY) return 0f
    val hold = if (holdMs.isFinite() && holdMs > 0.0) holdMs else 0.0
    val slide = overflow.toDouble() / perSecond * 1_000.0
    val t = elapsedMs % (2.0 * (hold + slide))
    return when {
        t <= hold -> 0f
        t < hold + slide -> ((t - hold) / slide * overflow).toFloat().coerceIn(0f, overflow)
        t <= 2.0 * hold + slide -> overflow
        else -> ((2.0 * (hold + slide) - t) / slide * overflow).toFloat().coerceIn(0f, overflow)
    }
}

/**
 * The time [marqueeOffset] is asked for, [elapsedNanos] after the text started, under the system's animation scale
 * ([motionScale]: 1 as it comes, 2 for animations twice as long). With animations switched off (a scale of 0), or
 * a scale or a time that is not usable, it is 0: the text stays at its start.
 */
fun marqueeTimeMs(elapsedNanos: Long, motionScale: Float): Double =
    if (!motionScale.isFinite() || motionScale <= 0f || elapsedNanos < 0L) 0.0 else elapsedNanos / 1e6 / motionScale

/**
 * Whether text that is [overflowPx] pixels wider than its place slides: only when it is wider at all, and only
 * while the system's animations are not switched off. With them off it stays still and is cut short with an
 * ellipsis.
 */
fun marqueeScrolls(overflowPx: Int, motionScale: Float): Boolean = overflowPx > 0 && motionScale.isFinite() && motionScale > 0f

/**
 * How much longer the text waits where it is, [elapsedMs] after it started: what is left of the wait at an end, in
 * milliseconds, during which [marqueeOffset] does not change; 0 while it slides. Nothing has to be drawn until then.
 * 0 as well wherever [marqueeOffset] gives no slide at all.
 */
fun marqueeRestMs(elapsedMs: Double, overflow: Float, perSecond: Float, holdMs: Double = MARQUEE_HOLD_MS): Double {
    if (!elapsedMs.isFinite() || elapsedMs < 0.0) return 0.0
    if (!overflow.isFinite() || overflow <= 0f || !perSecond.isFinite() || perSecond <= 0f) return 0.0
    if (!holdMs.isFinite() || holdMs <= 0.0) return 0.0
    val slide = overflow.toDouble() / perSecond * 1_000.0
    val t = elapsedMs % (2.0 * (holdMs + slide))
    return when {
        t < holdMs -> holdMs - t
        t < holdMs + slide -> 0.0
        t < 2.0 * holdMs + slide -> 2.0 * holdMs + slide - t
        else -> 0.0
    }
}
