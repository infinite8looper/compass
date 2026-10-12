package com.infinite8looper.compass.nav

// The hold-flat hint's loop: where the drawn watch is at a time into the loop. In milliseconds: the watch fades
// in upright, waits, turns level, rests there while the level marks brighten, and fades out.
const val HOLD_FLAT_LOOP_MS = 2000f
private const val APPEAR_END_MS = 200f
private const val TURN_START_MS = 300f
private const val TURN_END_MS = 1300f
private const val REACHED_MS = 150f
private const val REST_END_MS = 1800f
/** The time shown when the system has animations switched off: level, fully visible, marks bright. */
const val HOLD_FLAT_STILL_MS = (TURN_END_MS + REST_END_MS) / 2f

/** [ms] brought into one loop. A time that is not a finite number becomes the still position. */
private fun loopTime(ms: Float): Float {
    if (!ms.isFinite()) return HOLD_FLAT_STILL_MS
    val m = ms % HOLD_FLAT_LOOP_MS
    return if (m < 0f) m + HOLD_FLAT_LOOP_MS else m
}

/** How far the watch is from level, in degrees: 90 is upright, 0 is level. It only ever goes down within a loop. */
fun holdFlatTiltDeg(ms: Float): Float {
    val turned = ((loopTime(ms) - TURN_START_MS) / (TURN_END_MS - TURN_START_MS)).coerceIn(0f, 1f)
    return 90f * (1f - easeInOutCubic(turned))
}

/** How visible the watch is, 0 to 1: it is gone at both ends of the loop, so the jump back upright is not seen. */
fun holdFlatVisibility(ms: Float): Float {
    val m = loopTime(ms)
    return when {
        m < APPEAR_END_MS -> m / APPEAR_END_MS
        m > REST_END_MS -> (HOLD_FLAT_LOOP_MS - m) / (HOLD_FLAT_LOOP_MS - REST_END_MS)
        else -> 1f
    }.coerceIn(0f, 1f)
}

/** How far the level marks have brightened, 0 to 1, which they do once the watch lies level. */
fun holdFlatLevelReached(ms: Float): Float = ((loopTime(ms) - TURN_END_MS) / REACHED_MS).coerceIn(0f, 1f)

/**
 * The cubic Bezier easing with control points (0.65, 0) and (0.35, 1): slow, fast, slow, with no overshoot.
 * The curve's own parameter is found by halving, which is exact enough that the result does not differ
 * visibly from any other solver's.
 */
private fun easeInOutCubic(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    var lo = 0f
    var hi = 1f
    repeat(30) {
        val mid = (lo + hi) / 2f
        if (bezier(mid, 0.65f, 0.35f) < t) lo = mid else hi = mid
    }
    return bezier((lo + hi) / 2f, 0f, 1f)
}

/** One coordinate of a cubic Bezier from 0 to 1 with inner control values [a] and [b], at parameter [s]. */
private fun bezier(s: Float, a: Float, b: Float): Float {
    val r = 1f - s
    return 3f * r * r * s * a + 3f * r * s * s * b + s * s * s
}
