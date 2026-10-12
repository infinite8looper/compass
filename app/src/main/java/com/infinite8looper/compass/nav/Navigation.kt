package com.infinite8looper.compass.nav

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
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
    if (north == NorthRef.TRUE && declination != null) DisplayHeading(normalizeDeg(magneticDeg + declination), false)
    else DisplayHeading(normalizeDeg(magneticDeg), true)

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

fun hasArrived(meters: Double, units: Units): Boolean = when (units) {
    Units.METRIC -> meters < 10.0
    Units.IMPERIAL -> meters * FEET_PER_METER < 30.0
}

/** Golden-angle steps around the hue wheel: neighbors are far apart and no hue repeats. */
fun hueForIndex(index: Int): Float = normalizeDeg(index * 137.508f)

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

/**
 * Collects [fixes] until one is at least [goodEnoughM] accurate, or [timeoutMs] passes.
 * On timeout, or if the flow ends, returns the most accurate fix seen, or null if none arrived.
 * A fix taken before [notBeforeMs] is passed over as if it had not arrived: it is neither returned nor reported
 * to [onProgress].
 */
suspend fun acquireFix(
    fixes: Flow<Fix>, goodEnoughM: Float = 15f, timeoutMs: Long = 20_000, notBeforeMs: Long = Long.MIN_VALUE,
    onProgress: (Fix) -> Unit = {},
): Fix? {
    var best: Fix? = null
    val good = withTimeoutOrNull(timeoutMs) {
        fixes.firstOrNull { f ->
            if (!isFixSincePress(f, notBeforeMs)) return@firstOrNull false
            if (best == null || f.accuracyM < best!!.accuracyM) best = f
            onProgress(f)
            f.accuracyM <= goodEnoughM
        }
    }
    return good ?: best
}

enum class LogFailure { NO_FIX, LOCATION_OFF, NO_PERMISSION, SAVE_FAILED }

/** Where logging a waypoint with one press stands. */
sealed interface LogStatus {
    data object Idle : LogStatus
    /** Waiting for a fix that is good enough. [accuracyM] is that of the latest fix since the press, once there is one. */
    data class Saving(val accuracyM: Float?) : LogStatus
    /** [fixElapsedMs] is when the saved fix was taken, on the clock that counts from boot. */
    data class Saved(val name: String, val accuracyM: Float, val fixElapsedMs: Long) : LogStatus
    data class Failed(val reason: LogFailure) : LogStatus
}

/** The two lines the compass shows for [status]: the status, and a smaller line under it where there is one. */
fun statusText(status: LogStatus, units: Units): Pair<String, String?> = when (status) {
    LogStatus.Idle -> "" to null
    is LogStatus.Saving -> "Saving…" to status.accuracyM?.let { accuracyText(it, units) }
    is LogStatus.Saved -> "Saved ${status.name}" to accuracyText(status.accuracyM, units)
    is LogStatus.Failed -> when (status.reason) {
        LogFailure.NO_FIX -> "No GPS fix" to "Try again outdoors"
        LogFailure.LOCATION_OFF -> "Location is off" to null
        LogFailure.NO_PERMISSION -> "Needs location access" to null
        LogFailure.SAVE_FAILED -> "Couldn't save" to null
    }
}

private fun accuracyText(accuracyM: Float, units: Units): String = "±" + formatDistance(accuracyM.toDouble(), units)

/** How long [status] is shown before the compass goes back to [LogStatus.Idle], or null if it does not end by itself. */
fun logStatusShowMs(status: LogStatus): Long? = when (status) {
    is LogStatus.Saved -> LOG_SAVED_SHOW_MS
    is LogStatus.Failed -> LOG_FAILED_SHOW_MS
    LogStatus.Idle, is LogStatus.Saving -> null
}
