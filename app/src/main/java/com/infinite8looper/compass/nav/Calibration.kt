package com.infinite8looper.compass.nav

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The calibration screen's rules (v3 spec, "Calibration"): which regions of directions the measured magnetic field
// has pointed in, in the watch's own frame; the rotation vector's estimated heading error; when the compass asks for
// calibration; how long the screen is kept on.

/**
 * The directions a field can point in, in the watch's own frame (x to the right of the screen, y to its top, z out
 * of the face), are divided into [COVERAGE_REGIONS] regions of equal area: three bands by the share of the field
 * along z (below -1/3: through the back of the watch; between: across the face; above 1/3: out of the face; equal
 * steps of z cut a sphere into equal areas), each cut into [COVERAGE_SECTORS] sectors by the direction of the field
 * across the face, counted counterclockwise from x, with sector 0 centered on x. Region = band * 8 + sector.
 */
const val COVERAGE_BANDS = 3
const val COVERAGE_SECTORS = 8
const val COVERAGE_REGIONS = COVERAGE_BANDS * COVERAGE_SECTORS

private const val SECTOR_DEG = 360f / COVERAGE_SECTORS

/** The region the field (x, y, z) in the watch's frame points into, or -1 for a field with no direction (zero, or not a number). */
fun coverageRegion(x: Float, y: Float, z: Float): Int {
    val strength = sqrt(x * x + y * y + z * z)
    if (!strength.isFinite() || strength == 0f) return -1
    val share = z / strength
    val band = when { share < -1f / 3f -> 0; share > 1f / 3f -> 2; else -> 1 }
    val across = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
    val sector = (normalizeDeg(across + SECTOR_DEG / 2f) / SECTOR_DEG).toInt().coerceIn(0, COVERAGE_SECTORS - 1)
    return band * COVERAGE_SECTORS + sector
}

/** The band of a region: 0 through the back, 1 across the face, 2 out of the face. */
fun regionBand(region: Int): Int = region / COVERAGE_SECTORS

/** The sector of a region, 0 to 7 counterclockwise from the watch's x axis. */
fun regionSector(region: Int): Int = region % COVERAGE_SECTORS

/**
 * The regions covered, one bit each, after a field (x, y, z) in microtesla: the region it points into is added,
 * whatever the field's strength (v3 spec: a segment "lights when the field has been there"). A field whose strength
 * the earth's cannot have ([isInterference]) counts too: a watch that is badly calibrated can read such a strength,
 * and its field still turns with the wrist; the screen warns of the strength in its own place. A field with no
 * direction adds nothing.
 */
fun nextCoverage(covered: Int, x: Float, y: Float, z: Float): Int {
    val region = coverageRegion(x, y, z)
    return if (region < 0) covered else covered or (1 shl region)
}

fun coveredCount(covered: Int): Int = (covered and ALL_REGIONS).countOneBits()

fun coveredFraction(covered: Int): Float = coveredCount(covered).toFloat() / COVERAGE_REGIONS

fun isCovered(covered: Int, region: Int): Boolean = region in 0 until COVERAGE_REGIONS && covered and (1 shl region) != 0

/** The share of the regions covered, as the screen writes it beside `Covered`: `50%`. */
fun coveredText(covered: Int): String = "${(coveredFraction(covered) * 100f).roundToInt()}%"

private const val ALL_REGIONS = (1 shl COVERAGE_REGIONS) - 1

/**
 * The rotation vector's estimated heading error in degrees, from its event's values: values[4] in radians, "-1 if
 * unavailable" (SensorEvent). Null when it is not given: fewer than five values, -1 or any other value that is not
 * above 0, or not a number. A value of 0 is not taken as an estimate: the AOSP rotation vector the emulators run
 * copies the accelerometer's event and writes only values[0] to values[3], so its values[4] is always 0.
 */
fun headingErrorDeg(values: FloatArray): Float? {
    if (values.size < 5) return null
    val radians = values[4]
    if (!radians.isFinite() || radians <= 0f) return null
    return Math.toDegrees(radians.toDouble()).toFloat()
}

/** The estimated heading error as the calibration screen writes it: whole degrees, at least 1, `±12°`. */
fun headingErrorText(deg: Float): String = "±${max(1, deg.roundToInt())}°"

/** Above this estimated heading error, in degrees, the compass shows its `Calibrate` prompt whatever the magnetometer's level. */
const val LARGE_HEADING_ERROR_DEG = 30f

/** Once shown for a large error, the prompt goes only when the estimated heading error is below this, in degrees. */
const val LARGE_HEADING_ERROR_CLEAR_DEG = 25f

/**
 * Whether the estimated heading error [errorDeg] counts as large, given whether it did at the reading before
 * ([wasShown]): above [LARGE_HEADING_ERROR_DEG] it does; once it does, it stops only below
 * [LARGE_HEADING_ERROR_CLEAR_DEG], so an estimate that hovers at the threshold does not put the prompt on and off at
 * the sensor's rate. No estimate (null) is no large error.
 */
fun largeHeadingError(wasShown: Boolean, errorDeg: Float?): Boolean =
    errorDeg != null && (errorDeg > LARGE_HEADING_ERROR_DEG || (wasShown && errorDeg >= LARGE_HEADING_ERROR_CLEAR_DEG))

/**
 * Whether the compass shows its `Calibrate` prompt: the magnetometer reports low accuracy or worse (as before v3),
 * or the estimated heading error is large ([largeHeadingError], worked out reading by reading where the sensor
 * reports, [com.infinite8looper.compass.sensors.HeadingReading.largeHeadingError]).
 */
fun calibrationPromptDue(accuracy: Int?, largeHeadingError: Boolean): Boolean =
    (accuracy != null && accuracy <= ACCURACY_LOW) || largeHeadingError

/** SensorManager.SENSOR_STATUS_ACCURACY_LOW. */
private const val ACCURACY_LOW = 1

/** How long calibration keeps the screen on after it opens, and after each segment of its ring that lights. */
const val CALIBRATION_AWAKE_MS = 120_000L

/** When calibration stops keeping the screen on: two minutes after the opening or the last new segment, whichever is later. */
fun calibrationAwakeUntil(openedAtMs: Long, lastNewRegionAtMs: Long?): Long =
    max(openedAtMs, lastNewRegionAtMs ?: openedAtMs) + CALIBRATION_AWAKE_MS

fun calibrationKeepsAwake(nowMs: Long, openedAtMs: Long, lastNewRegionAtMs: Long?): Boolean =
    nowMs < calibrationAwakeUntil(openedAtMs, lastNewRegionAtMs)
