package com.infinite8looper.compass.nav

import kotlin.math.abs

/**
 * What the shown heading passed on a frame, for the dial's vibrations: [NONE], a [THIN] line (every
 * [TICK_STEP_DEG] degrees), a [THICK] line (every [THICK_LINE_EVERY_DEG] degrees, the cardinal points among them)
 * or the focused [TARGET]'s bearing. Listed from the weakest claim to the strongest: one frame gives one vibration,
 * the latest kind in this order that applies.
 */
enum class DialCrossing { NONE, THIN, THICK, TARGET }

/** The spacing of the dial's heavier ticks. */
const val THICK_LINE_EVERY_DEG = 30
private const val CELLS_PER_THICK_LINE = 15
private const val TICK_CELL_COUNT = 180

/**
 * How far either side of the target's bearing the shown heading counts as being on that side: within 90 degrees.
 * Beyond, it is nearer the opposite direction, where the side changes too and nothing is passed.
 */
const val TARGET_SIDE_REACH_DEG = 90f

/**
 * How long the lines stay quiet after the target's vibration, so that a line passed just after it does not cut it
 * short: the system stops a vibration that is playing when the same app asks for another.
 */
const val TICK_QUIET_AFTER_TARGET_NANOS = 150_000_000L

/**
 * Which side of the focused target's bearing the shown heading is on: [Below] (counterclockwise of it, a smaller
 * heading), [Above], or [Unset] with no bearing, on the bearing since there was one, or nearer the opposite direction.
 */
@JvmInline
value class TargetSide internal constructor(val sign: Int) {
    companion object {
        val Unset = TargetSide(0)
        val Below = TargetSide(-1)
        val Above = TargetSide(1)
    }
}

/**
 * The side after the shown heading moves to [headingDeg], with the target's bearing on the dial [targetDeg] (null
 * with no focused target or no bearing to it). As with the tick cells ([nextTickCell]), the side changes only once
 * the heading is [TICK_MARGIN_DEG] past the bearing, so resting on it or jittering around it passes it once at most.
 * A heading that is not a finite number changes nothing. A bearing that is null or not a number, a heading more than
 * [TARGET_SIDE_REACH_DEG] from the bearing, and a focus that has moved to another target ([targetChanged]) give
 * [TargetSide.Unset]: the new target's side is taken on the next frame, and taking a side is not passing it.
 */
fun nextTargetSide(current: TargetSide, headingDeg: Float, targetDeg: Float?, targetChanged: Boolean = false): TargetSide {
    if (targetDeg == null || !targetDeg.isFinite() || targetChanged) return TargetSide.Unset
    if (!headingDeg.isFinite()) return current
    val off = shortestDelta(targetDeg, headingDeg)
    return when {
        abs(off) > TARGET_SIDE_REACH_DEG -> TargetSide.Unset
        abs(off) <= TICK_MARGIN_DEG -> current
        off < 0f -> TargetSide.Below
        else -> TargetSide.Above
    }
}

/**
 * Whether the move from the tick cell [before] to [after] passed a thick line. The dial went the shorter way round;
 * the line at `2 * k` degrees is the lower edge of cell `k`, and it is thick when `k` is a multiple of 15.
 */
fun crossesThickLine(before: TickCell, after: TickCell): Boolean {
    if (!isTickCrossing(before, after)) return false
    val up = ((after.index - before.index) % TICK_CELL_COUNT + TICK_CELL_COUNT) % TICK_CELL_COUNT
    if (up <= TICK_CELL_COUNT / 2) {
        // Lower edges of before + 1 .. after.
        for (i in 1..up) if ((before.index + i) % TICK_CELL_COUNT % CELLS_PER_THICK_LINE == 0) return true
    } else {
        // Lower edges of before, before - 1, .. after + 1.
        for (i in 0 until TICK_CELL_COUNT - up) {
            if (((before.index - i) % TICK_CELL_COUNT + TICK_CELL_COUNT) % TICK_CELL_COUNT % CELLS_PER_THICK_LINE == 0) return true
        }
    }
    return false
}

/**
 * The one vibration a frame gives on the dial, from the tick cell the shown heading was in and is in now
 * ([cellBefore], [cellAfter], [nextTickCell]) and its side of the focused target's bearing before and now
 * ([sideBefore], [sideAfter], [nextTargetSide]), which carry the previous and the present shown heading with the
 * margin against jitter. The strongest kind wins: [DialCrossing.TARGET] when the side went from one to the other,
 * else [DialCrossing.THICK] when a thick line was passed, else [DialCrossing.THIN] for any line.
 *
 * With [tickFeedback] off there is none. With [blindNavigation] on the target gives none of its own: the lock
 * pulse of v2 is its vibration then, and the lines keep theirs.
 *
 * Whether it is played is decided by [dialVibrationDue] and, in the frame loop, by the catch-up, the hold-flat
 * state and the compass being in view.
 */
fun dialCrossing(
    cellBefore: TickCell, cellAfter: TickCell, sideBefore: TargetSide, sideAfter: TargetSide,
    tickFeedback: Boolean, blindNavigation: Boolean,
): DialCrossing = when {
    !tickFeedback -> DialCrossing.NONE
    !blindNavigation && sideBefore != TargetSide.Unset && sideAfter != TargetSide.Unset && sideBefore != sideAfter -> DialCrossing.TARGET
    crossesThickLine(cellBefore, cellAfter) -> DialCrossing.THICK
    isTickCrossing(cellBefore, cellAfter) -> DialCrossing.THIN
    else -> DialCrossing.NONE
}

/**
 * Whether a dial vibration of [kind] at [nowNanos] is played: the limits of the tick taps hold for all three kinds,
 * at most one per [TICK_MIN_INTERVAL_NANOS] ([tickTapDue]) and none on or right after a blind navigation pulse
 * ([tickClearOfPulse]); and a line is not played for [TICK_QUIET_AFTER_TARGET_NANOS] after the target's
 * vibration ([lastTargetNanos]). One that is not played is dropped, not kept for later.
 */
fun dialVibrationDue(
    kind: DialCrossing, nowNanos: Long, lastTapNanos: Long, lastPulse: Pulse?, lastPulseNanos: Long, lastTargetNanos: Long,
): Boolean = kind != DialCrossing.NONE && tickTapDue(nowNanos, lastTapNanos) && tickClearOfPulse(nowNanos, lastPulse, lastPulseNanos) &&
    (kind == DialCrossing.TARGET || nowNanos - lastTargetNanos >= TICK_QUIET_AFTER_TARGET_NANOS)

/**
 * The least time from a press's vibration to the confirming vibration of its save. A save that finishes sooner (an
 * instant save takes tens of milliseconds) has its confirming vibration wait, so that it does not cut the press's
 * short and both are felt.
 */
const val SAVE_CONFIRM_AFTER_PRESS_MS = 150L

/** [confirmDelayMs]'s press time when no press vibration was played. */
const val NO_PRESS = Long.MIN_VALUE

/**
 * How long the confirming vibration of a save waits at [nowMs], the press's vibration having been played at
 * [pressAtMs] ([NO_PRESS] for none): until [SAVE_CONFIRM_AFTER_PRESS_MS] after the press, and never longer than that.
 */
fun confirmDelayMs(nowMs: Long, pressAtMs: Long): Long {
    if (pressAtMs == NO_PRESS) return 0L
    return (pressAtMs + SAVE_CONFIRM_AFTER_PRESS_MS - nowMs).coerceIn(0L, SAVE_CONFIRM_AFTER_PRESS_MS)
}
