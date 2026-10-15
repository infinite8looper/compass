package com.infinite8looper.compass.nav

import kotlin.math.max
import kotlin.math.roundToInt

// The check screen (v3 spec, "Check screen"): reached from calibration only in builds with the diagnostics switch on;
// it shows the three headings, the estimated heading error, the magnetometer's level and field strength, what the
// watch answered for its colors, its buttons, the one-handed gestures and the vibration primitives, plays the three
// dial vibrations, and has a row that switches the heading source for the running app.

/**
 * The check screen's route in a build whose diagnostics switch (`BuildConfig.DIAGNOSTICS`) is [diagnostics], or
 * null: a build with it off (the release build) has no route to the screen and no row that opens it.
 */
fun checkRoute(diagnostics: Boolean): String? = if (diagnostics) CHECK_ROUTE else null

/** The source the check screen's row switches to from [kind]: fused, magnetic, blend, and round to fused again. */
fun nextHeadingKind(kind: HeadingKind): HeadingKind = when (kind) {
    HeadingKind.FUSED -> HeadingKind.MAGNETIC
    HeadingKind.MAGNETIC -> HeadingKind.BLEND
    HeadingKind.BLEND -> HeadingKind.FUSED
}

/**
 * When the check screen stops keeping the display on: two minutes ([CALIBRATION_AWAKE_MS], as calibration) after its
 * opening or after the last touch or turn of the crown on it, whichever is later.
 */
fun checkAwakeUntil(openedAtMs: Long, lastTouchAtMs: Long?): Long = max(openedAtMs, lastTouchAtMs ?: openedAtMs) + CALIBRATION_AWAKE_MS

/** A heading source's name on the check screen. */
fun headingKindName(kind: HeadingKind): String = when (kind) {
    HeadingKind.FUSED -> "Fused"
    HeadingKind.MAGNETIC -> "Magnetic"
    HeadingKind.BLEND -> "Blend"
}

/**
 * What the check screen shows of a reading, as text: the three headings in whole degrees as the compass shows its
 * heading (`--` while one is not worked out), the estimated heading error (`±12°`, or `--` when the sensor gives
 * none), the magnetometer's level as calibration names it and its field strength in whole microtesla. Two readings
 * that show the same are equal, so the screen changes only when what it shows does.
 */
data class CheckReadout(val fused: String, val magnetic: String, val blend: String, val error: String, val level: String, val field: String)

/** The [CheckReadout] of a reading's values. */
fun checkReadout(fusedDeg: Float, magneticDeg: Float, blendDeg: Float, errorDeg: Float?, accuracy: Int?, fieldMicroTesla: Float?): CheckReadout =
    CheckReadout(
        degreesOrNone(fusedDeg), degreesOrNone(magneticDeg), degreesOrNone(blendDeg),
        errorDeg?.let(::headingErrorText) ?: NONE_SHOWN, accuracyLabel(accuracy),
        fieldMicroTesla?.takeIf { it.isFinite() }?.let { "${it.roundToInt()} µT" } ?: NONE_SHOWN,
    )

private fun degreesOrNone(deg: Float): String = if (deg.isFinite()) headingText(shownDegree(deg)) else NONE_SHOWN

/** What is shown for a value there is none of, as the compass shows no heading. */
private const val NONE_SHOWN = "--"

/** A color the watch answered, as `#RRGGBB`; its alpha is left out. */
fun colorHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

fun yesNo(b: Boolean): String = if (b) "Yes" else "No"

/** The count of buttons the watch gave, or `Unknown` where it could not say ([BUTTON_COUNT_UNKNOWN] or below 0). */
fun buttonCountText(count: Int): String = if (count < 0) "Unknown" else count.toString()
