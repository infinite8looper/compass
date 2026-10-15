package com.infinite8looper.compass.nav

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)
data class Fix(val point: GeoPoint, val accuracyM: Float, val altitudeM: Float, val elapsedMs: Long)
enum class NorthRef { TRUE, MAGNETIC }
enum class Units { METRIC, IMPERIAL }
data class DisplayHeading(val deg: Float, val isMagnetic: Boolean)

private const val EARTH_RADIUS_M = 6_371_008.8
private const val FEET_PER_METER = 3.280839895
private const val METERS_PER_MILE = 1609.344
const val STALE_AFTER_MS = 20_000L
const val HOLD_FLAT_ENTER_DEG = 70f
const val HOLD_FLAT_LEAVE_DEG = 60f
/** The spacing of the dial's minor ticks. */
const val TICK_STEP_DEG = 2f
const val TICK_MARGIN_DEG = 0.3f
const val TICK_MIN_INTERVAL_NANOS = 40_000_000L
const val TICK_GAP_NANOS = 250_000_000L
const val TICK_REFERENCE_SHIFT_DEG = 0.5f
const val TICK_SETTLE_MIN_NANOS = 400_000_000L
const val TICK_SETTLE_MAX_NANOS = 1_200_000_000L
private const val TICK_CELLS = 180
const val FACING_ENTER_DEG = 2f
const val FACING_LEAVE_DEG = 3f

fun normalizeDeg(d: Float): Float = ((d % 360f) + 360f) % 360f

/** Signed smallest rotation from [from] to [to], in -180..180. */
fun shortestDelta(from: Float, to: Float): Float {
    val d = normalizeDeg(to - from)
    return if (d > 180f) d - 360f else d
}

/** Exponential approach to [target] with time constant [tau] seconds, along the shorter arc. */
fun smoothAngle(prev: Float, target: Float, dtSeconds: Float, tau: Float = 0.12f): Float {
    if (target.isNaN() || dtSeconds.isNaN() || dtSeconds <= 0f) return prev
    val alpha = 1f - exp(-dtSeconds / tau)
    return normalizeDeg(prev + shortestDelta(prev, target) * alpha)
}

/** Initial great-circle bearing from true north, 0..360. */
fun bearingDeg(from: GeoPoint, to: GeoPoint): Float {
    val p1 = Math.toRadians(from.lat); val p2 = Math.toRadians(to.lat)
    val dl = Math.toRadians(to.lon - from.lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return normalizeDeg(Math.toDegrees(atan2(y, x)).toFloat())
}

/** Haversine distance on the mean-radius sphere. */
fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
    val p1 = Math.toRadians(from.lat); val p2 = Math.toRadians(to.lat)
    val dp = p2 - p1; val dl = Math.toRadians(to.lon - from.lon)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/** Declination is positive east: true = magnetic + declination. */
fun displayHeading(magneticDeg: Float, declination: Float?, north: NorthRef): DisplayHeading =
    DisplayHeading(displayHeadingDeg(magneticDeg, declination, north), dialIsMagnetic(north, declination))

/** The degrees of [displayHeading] alone, as a number: for the frame loop, which asks on every frame and is to make nothing. */
fun displayHeadingDeg(magneticDeg: Float, declination: Float?, north: NorthRef): Float =
    if (north == NorthRef.TRUE && declination != null) normalizeDeg(magneticDeg + declination) else normalizeDeg(magneticDeg)

/** Where a true bearing sits on a dial whose zero is the displayed north. */
fun dialAngle(trueBearing: Float, declination: Float?, north: NorthRef): Float =
    if (north == NorthRef.TRUE || declination == null) normalizeDeg(trueBearing)
    else normalizeDeg(trueBearing - declination)

fun formatDistance(meters: Double, units: Units): String = when (units) {
    Units.METRIC ->
        if (meters.roundToInt() < 1000) "${meters.roundToInt()} m"
        else String.format(Locale.US, "%.1f km", meters / 1000.0)
    Units.IMPERIAL -> {
        val feet = meters * FEET_PER_METER
        if (feet.roundToInt() < 1000) "${feet.roundToInt()} ft"
        else String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)
    }
}

/** The arrival distance: 10 m, or 30 ft with feet and miles. */
const val ARRIVAL_METERS = 10.0
const val ARRIVAL_FEET = 30.0

fun hasArrived(meters: Double, units: Units): Boolean = when (units) {
    Units.METRIC -> meters < ARRIVAL_METERS
    Units.IMPERIAL -> meters * FEET_PER_METER < ARRIVAL_FEET
}

/** Arrival ends this many times the arrival distance away: beyond 12 m, or 36 ft. */
const val ARRIVAL_LEAVE_FACTOR = 1.2

/**
 * Whether the wearer counts as arrived at a point that is [meters] away, having counted as arrived ([current]) or
 * not before: arrival begins under the arrival distance ([hasArrived]: 10 m, or 30 ft) and ends only beyond
 * [ARRIVAL_LEAVE_FACTOR] times that distance, so a position that wavers at the line does not switch it back and
 * forth. A distance that is not a number is not an arrival.
 */
fun nextArrived(current: Boolean, meters: Double, units: Units): Boolean = when {
    meters.isNaN() -> false
    current -> when (units) {
        Units.METRIC -> meters <= ARRIVAL_METERS * ARRIVAL_LEAVE_FACTOR
        Units.IMPERIAL -> meters * FEET_PER_METER <= ARRIVAL_FEET * ARRIVAL_LEAVE_FACTOR
    }
    else -> hasArrived(meters, units)
}

/**
 * Place [index] of the numbering new waypoints' places are chosen from ([nextWaypointPlace]; the first is 0):
 * golden-angle steps round the 360 degrees of the full wheel ([wheelColor]) or the ring ([ringColor]) the colors come
 * from, so neighbors are far apart on it and no value repeats. The first is [FIRST_WAYPOINT_PLACE]. A waypoint keeps
 * the value it was given.
 */
fun hueForIndex(index: Int): Float = normalizeDeg(FIRST_WAYPOINT_PLACE + index * 137.508f)

/**
 * Where the first new waypoint is put on the wheel: the middle of the widest gap of hue between the app's red north
 * and blue pointer (108.3 degrees, a green), the hue furthest from both. The owner (2026-10-14): "in standard
 * (non-theme) colors use the full color wheel and start with something away from the existing red/blue colors."
 */
val FIRST_WAYPOINT_PLACE: Float = widestGapMiddle(FALLBACK_NORTH, FALLBACK_ACCENT)

/**
 * How far, in degrees either side, a new waypoint's place keeps from the hue of north's red ([FALLBACK_NORTH], 3.2)
 * and of the pointer's blue ([FALLBACK_ACCENT], 213.5) on the full wheel. The owner (2026-10-14): "New waypoints
 * never land within about ±25° of north's red or the pointer's blue; a place that would is moved on to the next
 * one. Existing waypoints keep their colors."
 */
const val SKIP_HUE_APART = 25f

/** How many places of the numbering [nextWaypointPlace] chooses among. */
const val PLACE_CHOICES = 8

/** How far into the numbering [nextWaypointPlace] looks for its choices at most. */
private const val PLACE_SEARCH_LIMIT = 4096

/** Two places closer than this, in degrees, are the same place. */
private const val SAME_PLACE = 0.01f

/**
 * The place a new waypoint is given, with the places of the waypoints there are in [taken]. The places of the
 * numbering ([hueForIndex]) are taken in turn; one within [SKIP_HUE_APART] of the red or the blue is passed over,
 * and so is one already taken. Of the first [PLACE_CHOICES] left, the one furthest from every taken place is given
 * (the earliest of those as far): with no waypoints that is the first, [FIRST_WAYPOINT_PLACE]. So on an install
 * whose waypoints were numbered differently (from 0, before 2026-10-14) or were given colors by hand, a new place
 * is not put next to an old one where another is free. Places stored are never changed.
 */
fun nextWaypointPlace(taken: FloatArray): Float {
    val red = hueOf(FALLBACK_NORTH)
    val blue = hueOf(FALLBACK_ACCENT)
    var best = FIRST_WAYPOINT_PLACE
    var bestApart = -1f
    var found = 0
    var index = 0
    while (found < PLACE_CHOICES && index < PLACE_SEARCH_LIMIT) {
        val place = hueForIndex(index++)
        if (abs(shortestDelta(place, red)) < SKIP_HUE_APART || abs(shortestDelta(place, blue)) < SKIP_HUE_APART) continue
        var least = Float.MAX_VALUE
        for (t in taken) least = minOf(least, abs(shortestDelta(place, t)))
        if (least < SAME_PLACE) continue
        found++
        if (least > bestApart) { best = place; bestApart = least }
    }
    return best
}

fun shouldHoldFlat(pitchDeg: Float): Boolean = pitchDeg > 70f
/**
 * Whether the hold-flat state applies after a reading with this pitch: it is entered above 70 degrees and
 * left only below 60, so a watch hovering near either limit does not switch back and forth. NaN changes nothing.
 */
fun nextHoldFlat(current: Boolean, pitchDeg: Float): Boolean = when {
    pitchDeg.isNaN() -> current
    current -> pitchDeg >= HOLD_FLAT_LEAVE_DEG
    else -> pitchDeg > HOLD_FLAT_ENTER_DEG
}

/**
 * Whether the wearer is facing the focused target after a frame: the shown heading comes within
 * [FACING_ENTER_DEG] of the target's place on the dial, and stays so until the difference exceeds
 * [FACING_LEAVE_DEG], so noise at either limit does not switch it back and forth. It is false with no focused
 * target or no bearing to it ([targetDialAngleDeg] null), when either value is not a finite number, and while the
 * shown heading is not live ([headingLive] false): the dial is frozen in the hold-flat state, or still catching
 * up with a heading it did not follow, and what it passes on the way is not where the wearer points.
 * When the focus has moved to another target since the last frame ([targetChanged]), the new target starts from
 * not facing and has to be within the entering limit.
 *
 * Anything built on this, such as a haptic pulse on turning onto the target, inherits the wait for a live heading:
 * after the app opens, the screen wakes, the hold-flat state ends or the dial's north moves, facing is not
 * reported for at least [TICK_SETTLE_MIN_NANOS] (0.4 s).
 */
fun nextFacing(
    current: Boolean, shownHeadingDeg: Float, targetDialAngleDeg: Float?, headingLive: Boolean = true,
    targetChanged: Boolean = false,
): Boolean {
    if (!headingLive || targetDialAngleDeg == null || !shownHeadingDeg.isFinite() || !targetDialAngleDeg.isFinite()) return false
    val off = abs(shortestDelta(shownHeadingDeg, targetDialAngleDeg))
    return if (current && !targetChanged) off <= FACING_LEAVE_DEG else off <= FACING_ENTER_DEG
}

/** Blind navigation: the time from the pulse on turning onto the target to the first soft pulse, and between soft pulses. */
const val BLIND_HEARTBEAT_NANOS = 1_000_000_000L
/**
 * How long the tick taps stay quiet after a pulse. The system stops a vibration that is playing when the same app
 * asks for another, so a tap that followed a pulse at once would cut the pulse short.
 */
const val TICK_QUIET_AFTER_LOCK_NANOS = 400_000_000L
const val TICK_QUIET_AFTER_HEARTBEAT_NANOS = 150_000_000L

/** A blind navigation pulse: [LOCK], the strong one on turning onto the target, and [HEARTBEAT], the soft one while still facing it. */
enum class Pulse { LOCK, HEARTBEAT }

/**
 * Where the blind navigation pulses stand: [Silent], or pulsing, with the time the latest pulse was due.
 * That time is on the beat that started at the lock, not the time of the frame that gave the pulse.
 */
@JvmInline
value class BlindPulses internal constructor(val lastNanos: Long) {
    val pulsing: Boolean get() = lastNanos != Long.MIN_VALUE
    companion object { val Silent = BlindPulses(Long.MIN_VALUE) }
}

/**
 * The pulses after a frame at [nowNanos]. They run while the wearer is [facing] the focused target (the one
 * facing state there is, [nextFacing]), the setting is on ([enabled]), the compass is the screen in view
 * ([inView]) and the position the target's bearing is worked out from is not old ([positionStale], which is
 * [isStale], the condition that marks the distance on screen as old); when any of the four is not so they are
 * [BlindPulses.Silent]. A wearer who is not looking cannot see that mark, and the heavy line, which is drawn
 * from [facing] alone, stays. [pulseBetween] says what the step from the state before to the state returned
 * gives the wearer.
 *
 * Going from silent to pulsing is the lock, whichever of the four came true: turning onto the target, the
 * compass coming back into view while the wearer still faces it, the setting being switched on, or a new
 * position arriving in place of an old one. The pulse
 * after any silence is therefore the strong one, and a soft one only ever follows a pulse that was felt.
 * The focus moving to another target that is faced too ([targetChanged]) starts the pulses again with a lock for
 * that target.
 *
 * While pulsing, a heartbeat is due each [BLIND_HEARTBEAT_NANOS] after the lock. A frame gives at most one: after
 * a gap of any length between frames the beats missed in it are not made up, and the beat stays where the lock
 * put it. A clock that has stepped back gives nothing.
 */
fun nextBlindPulses(
    current: BlindPulses, facing: Boolean, enabled: Boolean, inView: Boolean, nowNanos: Long, targetChanged: Boolean = false,
    positionStale: Boolean = false,
): BlindPulses {
    if (!facing || !enabled || !inView || positionStale) return BlindPulses.Silent
    if (!current.pulsing || targetChanged) return BlindPulses(nowNanos)
    val beats = (nowNanos - current.lastNanos) / BLIND_HEARTBEAT_NANOS
    return if (beats >= 1) BlindPulses(current.lastNanos + beats * BLIND_HEARTBEAT_NANOS) else current
}

/**
 * The pulse a frame gives, from the pulses [before] it and [after] it and the [targetChanged] that
 * [nextBlindPulses] was given; null for none.
 */
fun pulseBetween(before: BlindPulses, after: BlindPulses, targetChanged: Boolean = false): Pulse? = when {
    !after.pulsing -> null
    !before.pulsing || targetChanged -> Pulse.LOCK
    after != before -> Pulse.HEARTBEAT
    else -> null
}

/**
 * Whether a tick tap at [nowNanos] is clear of the latest pulse, [lastPulse] given at [lastPulseNanos] (null
 * when there has been none). On the frame of a pulse, and for [TICK_QUIET_AFTER_LOCK_NANOS] after a lock or
 * [TICK_QUIET_AFTER_HEARTBEAT_NANOS] after a heartbeat, it is not: the tap is dropped, not kept for later.
 */
fun tickClearOfPulse(nowNanos: Long, lastPulse: Pulse?, lastPulseNanos: Long): Boolean = when (lastPulse) {
    null -> true
    Pulse.LOCK -> nowNanos - lastPulseNanos >= TICK_QUIET_AFTER_LOCK_NANOS
    Pulse.HEARTBEAT -> nowNanos - lastPulseNanos >= TICK_QUIET_AFTER_HEARTBEAT_NANOS
}

/**
 * The 2-degree cell between two minor ticks that the shown heading counts as being in: cell `index` runs from
 * `2 * index` to `2 * index + 2` degrees, 0 to 179 around the dial. [Unset] until a first heading arrives.
 */
@JvmInline
value class TickCell internal constructor(val index: Int) {
    companion object { val Unset = TickCell(-1) }
}

/**
 * The cell after the heading moves to [headingDeg]. The heading stays in its cell until it is [TICK_MARGIN_DEG]
 * past one of the cell's two tick lines, so resting on a line or jittering around it changes nothing, and coming
 * back over the same line needs the same margin on the other side. A heading that is not a finite number
 * changes nothing.
 */
fun nextTickCell(current: TickCell, headingDeg: Float): TickCell {
    if (!headingDeg.isFinite()) return current
    if (current != TickCell.Unset) {
        val fromCenter = shortestDelta(current.index * TICK_STEP_DEG + TICK_STEP_DEG / 2f, headingDeg)
        if (abs(fromCenter) < TICK_STEP_DEG / 2f + TICK_MARGIN_DEG) return current
    }
    return TickCell((normalizeDeg(headingDeg) / TICK_STEP_DEG).toInt() % TICK_CELLS)
}

/** One tap for a move between two cells, however many tick lines lie between them. Arriving from [TickCell.Unset] is not a move. */
fun isTickCrossing(before: TickCell, after: TickCell): Boolean = before != TickCell.Unset && after != before

/** Taps are at least [TICK_MIN_INTERVAL_NANOS] apart. A tap that comes sooner is dropped, not kept for later. */
fun tickTapDue(nowNanos: Long, lastTapNanos: Long): Boolean = nowNanos - lastTapNanos >= TICK_MIN_INTERVAL_NANOS

/**
 * Whether the dial is catching up with a heading it did not follow, and since when. While that is so the dial
 * still turns smoothly, but the ticks it passes are not the wearer's doing and give no taps.
 */
@JvmInline
value class TickSettling internal constructor(val sinceNanos: Long) {
    val active: Boolean get() = sinceNanos != Long.MIN_VALUE
    companion object { val Settled = TickSettling(Long.MIN_VALUE) }
}

/**
 * Whether something other than the wearer turning may be about to move the dial on this frame:
 * the first heading; a reading that has not been replaced since a pause ([staleReading]); a gap of more than
 * [TICK_GAP_NANOS] since the last frame; the hold-flat state ending, which unfreezes the dial; or the dial's
 * north moving by more than [TICK_REFERENCE_SHIFT_DEG], as when the declination first arrives or the north
 * setting changes. The declination's own drift from one position fix to the next is far below that.
 */
fun isTickDisturbance(
    firstHeading: Boolean, staleReading: Boolean, frameGapNanos: Long, leftHoldFlat: Boolean, referenceShiftDeg: Float,
): Boolean = firstHeading || staleReading || leftHoldFlat || frameGapNanos > TICK_GAP_NANOS ||
    abs(referenceShiftDeg) > TICK_REFERENCE_SHIFT_DEG

/**
 * The settling state after a frame. A disturbance starts it, or starts it again. It ends when the dial has
 * arrived in the cell the sensor's heading is in ([shownCell] equals [targetCell]), so the rest of its way
 * crosses no tick, but not before [TICK_SETTLE_MIN_NANOS], because the heading right after a disturbance may not
 * be the true one yet. It always ends after [TICK_SETTLE_MAX_NANOS]: the longest catch-up, half a turn, takes
 * about 0.6 s, so a dial still behind after that is behind because the wearer keeps turning fast.
 */
fun nextTickSettling(
    current: TickSettling, disturbed: Boolean, nowNanos: Long, shownCell: TickCell, targetCell: TickCell,
): TickSettling {
    val settling = if (disturbed) TickSettling(nowNanos) else current
    if (!settling.active) return TickSettling.Settled
    val elapsed = nowNanos - settling.sinceNanos
    if (elapsed >= TICK_SETTLE_MAX_NANOS) return TickSettling.Settled
    if (elapsed >= TICK_SETTLE_MIN_NANOS && shownCell != TickCell.Unset && shownCell == targetCell) return TickSettling.Settled
    return settling
}

fun isFieldNormal(microTesla: Float): Boolean = microTesla in 25f..65f
/**
 * Whether the magnetometer's field strength says something magnetic is near: a strength that is known and is not
 * one the earth's field can have. A strength that is not known yet is not interference.
 */
fun isInterference(fieldMicroTesla: Float?): Boolean = fieldMicroTesla != null && !fieldMicroTesla.isNaN() && !isFieldNormal(fieldMicroTesla)
fun accuracyLabel(accuracy: Int?): String = when (accuracy) {
    3 -> "High"; 2 -> "Medium"; 1 -> "Low"; 0 -> "Unreliable"; else -> "Unknown"
}
/**
 * How many quarters of the calibration screen's frame the magnetometer's reported accuracy fills: none for
 * unreliable, one for low, three for medium and all four for high. An accuracy that is not known, or is not one of
 * the four the sensor defines, fills none.
 */
fun accuracyQuarters(accuracy: Int?): Int = when (accuracy) {
    3 -> 4; 2 -> 3; 1 -> 1; else -> 0
}
fun isStale(nowMs: Long, fixMs: Long?): Boolean = fixMs != null && nowMs - fixMs > STALE_AFTER_MS

/** How long the result of logging a waypoint stays on the compass before the distance comes back. */
const val LOG_SAVED_SHOW_MS = 2_000L
const val LOG_FAILED_SHOW_MS = 3_000L

/**
 * Whether [fix] was taken at or after the press at [pressedAtMs]. Both are on the clock that counts from boot
 * (`SystemClock.elapsedRealtime`). A fix from before the press says where the wearer was, not where they pressed.
 */
fun isFixSincePress(fix: Fix, pressedAtMs: Long): Boolean = fix.elapsedMs >= pressedAtMs

/** A fix is good enough to act on when its reported accuracy is this or better. */
const val GOOD_FIX_METERS = 15f

/**
 * The one accuracy rule: a fix whose reported accuracy is [goodEnoughM] or better is good enough to be saved as it
 * is ([acquireFix]) and to consume a crumb ([trailAfterFix]). An accuracy that is not a number is not.
 */
fun isGoodEnough(accuracyM: Float, goodEnoughM: Float = GOOD_FIX_METERS): Boolean = accuracyM <= goodEnoughM

/**
 * Collects [fixes] until one is at least [goodEnoughM] accurate, or [timeoutMs] passes.
 * On timeout, or if the flow ends, returns the most accurate fix seen, or null if none arrived.
 * A fix taken before [notBeforeMs] is passed over as if it had not arrived: it is neither returned nor reported
 * to [onProgress].
 */
suspend fun acquireFix(
    fixes: Flow<Fix>, goodEnoughM: Float = GOOD_FIX_METERS, timeoutMs: Long = 20_000, notBeforeMs: Long = Long.MIN_VALUE,
    onProgress: (Fix) -> Unit = {},
): Fix? {
    var best: Fix? = null
    val good = withTimeoutOrNull(timeoutMs) {
        fixes.firstOrNull { f ->
            if (!isFixSincePress(f, notBeforeMs)) return@firstOrNull false
            if (best == null || f.accuracyM < best!!.accuracyM) best = f
            onProgress(f)
            isGoodEnough(f.accuracyM, goodEnoughM)
        }
    }
    return good ?: best
}

/** A position the app holds may be saved at once at a press if it was taken this long before the press, or less. */
const val IN_HAND_MAX_AGE_MS = 10_000L

/** After a save from a position in hand, a more accurate position taken within this long of the press replaces it. */
const val REFINE_WITHIN_MS = 10_000L

/**
 * The position a press saves at once: [inHand], the latest position the app holds, if it was taken no more than
 * [IN_HAND_MAX_AGE_MS] before [pressedAtMs] and states an accuracy. Null when there is none such: the save then
 * waits for a position taken after the press ([acquireFix]). Both times are on the clock that counts from boot.
 * A position is saved with the accuracy it states, however poor that is.
 */
fun positionToSaveAtOnce(inHand: Fix?, pressedAtMs: Long): Fix? =
    inHand?.takeIf { !it.accuracyM.isNaN() && pressedAtMs - it.elapsedMs <= IN_HAND_MAX_AGE_MS }

/**
 * The position a press saves at once, from the two places a position can be in hand: [own], the latest the app's
 * own location updates gave, and [lastKnown], the location provider's last known position. Each counts only if
 * [positionToSaveAtOnce] takes it; the app's own comes first.
 */
fun positionInHand(own: Fix?, lastKnown: Fix?, pressedAtMs: Long): Fix? =
    positionToSaveAtOnce(own, pressedAtMs) ?: positionToSaveAtOnce(lastKnown, pressedAtMs)

/**
 * Whether [candidate] replaces [written], the position a save from a position in hand wrote (or the last that
 * replaced it): it is newer, states a smaller error, and was taken no later than [REFINE_WITHIN_MS] after the press.
 */
fun refines(written: Fix, candidate: Fix, pressedAtMs: Long): Boolean =
    candidate.elapsedMs >= written.elapsedMs && candidate.elapsedMs - pressedAtMs <= REFINE_WITHIN_MS &&
        candidate.accuracyM < written.accuracyM

/** How much longer, at [now], a save made at [pressedAtMs] listens for a more accurate position. */
fun refineWindowLeftMs(pressedAtMs: Long, now: Long): Long = (pressedAtMs + REFINE_WITHIN_MS - now).coerceAtLeast(0)

/** A stored row's position as a refinement looks at it. */
data class StoredPosition(val lat: Double, val lon: Double, val accuracyM: Float)

/**
 * Whether a refinement may write to a row that now holds [row] (null: the row is gone), having last written
 * [written]: only while the row exists and still holds that position. A deleted row is never written again, so it
 * never comes back, and a row whose position was changed by anyone else keeps that change. A change of the name, the
 * color or the target does not touch the position and so does not stop it.
 */
fun refinementMayWrite(row: StoredPosition?, written: StoredPosition): Boolean = row != null && row == written

/**
 * Whether true north is a reason for location to run: with true north selected and no position ever stored, which
 * is when the dial cannot show true north for want of a declination.
 */
fun trueNorthWantsPosition(north: NorthRef, hasStoredPosition: Boolean): Boolean = north == NorthRef.TRUE && !hasStoredPosition

/** Whether the dial shows magnetic north, which is when `MAG` shows: by choice, or for want of a declination. */
fun dialIsMagnetic(north: NorthRef, declination: Float?): Boolean = north == NorthRef.MAGNETIC || declination == null

enum class LogFailure { NO_FIX, LOCATION_OFF, NO_PERMISSION, SAVE_FAILED }

/** Where logging a waypoint with one press stands. */
sealed interface LogStatus {
    data object Idle : LogStatus
    /** Waiting for a fix that is good enough. [accuracyM] is that of the latest fix since the press, once there is one. */
    data class Saving(val accuracyM: Float?) : LogStatus
    /** [fixElapsedMs] is when the saved fix was taken, on the clock that counts from boot. */
    data class Saved(val name: String, val accuracyM: Float, val fixElapsedMs: Long) : LogStatus
    data class Failed(val reason: LogFailure) : LogStatus
    /** The last crumb of a trail has been reached. It is shown the way a result of logging is, in its place. */
    data object TrailComplete : LogStatus
}

/**
 * What the app says where it cannot go on without the precise location permission: one sentence, "Needs access to
 * precise location", broken where it is put on two lines. The logging status and the settings row both take it
 * from here.
 */
val NEEDS_PRECISE_LOCATION: Pair<String, String> = "Needs access to" to "precise location"

/** The two lines the compass shows for [status]: the status, and a smaller line under it where there is one. */
fun statusText(status: LogStatus, units: Units): Pair<String, String?> = when (status) {
    LogStatus.Idle -> "" to null
    LogStatus.TrailComplete -> "Trail complete" to null
    is LogStatus.Saving -> "Saving…" to status.accuracyM?.let { accuracyText(it, units) }
    is LogStatus.Saved -> "Saved ${status.name}" to accuracyText(status.accuracyM, units)
    is LogStatus.Failed -> when (status.reason) {
        LogFailure.NO_FIX -> "No GPS fix" to "Try again outdoors"
        LogFailure.LOCATION_OFF -> "Location is off" to null
        // One message, too long for a line of the status: the second line carries it on ([statusDetailContinues]).
        LogFailure.NO_PERMISSION -> NEEDS_PRECISE_LOCATION
        LogFailure.SAVE_FAILED -> "Couldn't save" to null
    }
}

internal fun accuracyText(accuracyM: Float, units: Units): String = "±" + formatDistance(accuracyM.toDouble(), units)

/** How long [status] is shown before the compass goes back to [LogStatus.Idle], or null if it does not end by itself. */
fun logStatusShowMs(status: LogStatus): Long? = when (status) {
    is LogStatus.Saved, LogStatus.TrailComplete -> LOG_SAVED_SHOW_MS
    is LogStatus.Failed -> LOG_FAILED_SHOW_MS
    LogStatus.Idle, is LogStatus.Saving -> null
}

/**
 * Whether the second line of [statusText] carries on the words of the first, and so is drawn in the first line's
 * color, rather than being a note under it, which is dim. That is so for the one message too long for a line.
 */
fun statusDetailContinues(status: LogStatus): Boolean = status is LogStatus.Failed && status.reason == LogFailure.NO_PERMISSION

/** Which of the shared colors the calibration screen's ring is drawn in. */
enum class RingRole { CONFIRM, ERROR }

/** The calibration screen's ring: how much of the frame is filled, from 0 to 1, and in which color. */
data class CalibrationRing(val fraction: Float, val role: RingRole)

/**
 * The calibration screen's ring for the magnetometer's reported [accuracy] and whether there is [interference].
 * During interference the whole ring is in the error color, whatever the accuracy, known or not. Otherwise it is
 * in the confirming color and as full as the accuracy makes it ([accuracyQuarters]).
 */
fun calibrationRing(accuracy: Int?, interference: Boolean): CalibrationRing =
    if (interference) CalibrationRing(1f, RingRole.ERROR) else CalibrationRing(accuracyQuarters(accuracy) / 4f, RingRole.CONFIRM)

/** What the magnetometer has said: its accuracy level and its field strength, each null while it has not said. */
data class MagnetReport(val accuracy: Int?, val fieldMicroTesla: Float?)

/**
 * What to show of the magnetometer when a reading carries [now] and the reading before it showed [last]. The
 * heading sensor is stopped and started as the screens change, and after a start its first headings come before
 * the magnetometer's first event, with nothing in them. Each of the two values is therefore kept as it last was
 * until the sensor reports it anew, and replaced by the first new report, so nothing blinks to unknown at a start
 * and nothing from before the stop outlasts the first report after it.
 */
fun carriedOver(last: MagnetReport?, now: MagnetReport): MagnetReport =
    MagnetReport(now.accuracy ?: last?.accuracy, now.fieldMicroTesla ?: last?.fieldMicroTesla)

// The watch's own position, for the compass's coordinates readout.

/**
 * A latitude as the compass shows it: degrees to four decimal places (one step is about 11 m) and the hemisphere's
 * letter, with no sign, as in `43.7022° N`. The fourth place is rounded half up on the number as it is written in
 * decimal, so 43.70225 is `43.7023° N`. The letter is the rounded number's: what rounds to zero is `0.0000° N`. A
 * latitude beyond a pole is the pole. The text does not depend on the device's language. Null for what is not a number.
 */
fun formatLatitude(deg: Double): String? =
    if (!deg.isFinite()) null else formatDegrees(deg.coerceIn(-90.0, 90.0), 'N', 'S')

/**
 * A longitude, written like [formatLatitude]: `72.2896° W`. A longitude beyond 180 degrees either way is brought
 * round. The antimeridian is `180.0000° E`, from whichever side it is reached. Null for what is not a number.
 */
fun formatLongitude(deg: Double): String? {
    if (!deg.isFinite()) return null
    val round = deg % 360.0
    val text = formatDegrees(if (round > 180.0) round - 360.0 else if (round < -180.0) round + 360.0 else round, 'E', 'W')
    return if (text == "180.0000° W") "180.0000° E" else text
}

private fun formatDegrees(deg: Double, positive: Char, negative: Char): String {
    // The shortest decimal that stands for the number, rounded there: no locale has a part in it.
    val rounded = BigDecimal.valueOf(abs(deg)).setScale(4, RoundingMode.HALF_UP)
    return rounded.toPlainString() + "° " + if (deg < 0.0 && rounded.signum() != 0) negative else positive
}

/**
 * Whether the coordinates readout is a reason for location to run: with the setting on, while the compass is on
 * screen. It is no reason to keep the screen awake.
 */
fun coordinatesWantLocation(show: Boolean, compassOnScreen: Boolean): Boolean = show && compassOnScreen
