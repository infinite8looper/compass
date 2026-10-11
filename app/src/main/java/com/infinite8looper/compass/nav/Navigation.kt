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
fun isFieldNormal(microTesla: Float): Boolean = microTesla in 25f..65f
fun accuracyLabel(accuracy: Int?): String = when (accuracy) {
    3 -> "High"; 2 -> "Medium"; 1 -> "Low"; 0 -> "Unreliable"; else -> "Unknown"
}
fun isStale(nowMs: Long, fixMs: Long?): Boolean = fixMs != null && nowMs - fixMs > STALE_AFTER_MS

/**
 * Collects [fixes] until one is at least [goodEnoughM] accurate, or [timeoutMs] passes.
 * On timeout, or if the flow ends, returns the most accurate fix seen, or null if none arrived.
 */
suspend fun acquireFix(
    fixes: Flow<Fix>, goodEnoughM: Float = 15f, timeoutMs: Long = 20_000, onProgress: (Fix) -> Unit = {},
): Fix? {
    var best: Fix? = null
    val good = withTimeoutOrNull(timeoutMs) {
        fixes.firstOrNull { f ->
            if (best == null || f.accuracyM < best!!.accuracyM) best = f
            onProgress(f)
            f.accuracyM <= goodEnoughM
        }
    }
    return good ?: best
}
