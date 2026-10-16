package com.infinite8looper.compass.nav

// The lines of the compass's readout that say something about position: the row under the heading, the smaller
// row under that, and the two rows of the coordinates. They are decided together, here, so that no fact is said
// twice and no two lines say different things about the same fact.

/** Which of the readout's shared colors a line is drawn in: by what the line is, not by a color's name. */
enum class ReadoutTone {
    /** What is current. */
    INK,
    /** Secondary: a note, something old, the coordinates. */
    DIM,
    /** Old coordinates. */
    FAINT,
    /** Something saved or completed. */
    CONFIRM,
    /** A failure. */
    ERROR,
}

/** One line: its [words], its color, and for a distance a dim [note] after the words (a poor fix's accuracy). */
data class ReadoutLine(val words: String, val tone: ReadoutTone, val note: String? = null)

/**
 * The four lines. [main] is the row under the heading: a status's words, the focused target's distance, or
 * `No target`. [detail] is the smaller row under it (at the top of the readout with the coordinates on), which only a
 * status has. [latitude] and [longitude] are the coordinates' rows, together under the row under the heading, the
 * first over the second; the second also carries the one line
 * that stands for the coordinates while there are none (`Locating…`, `Location is off`). [mainIsStatus] says [main]
 * holds a status, which is drawn smaller before it is cut short. [prompt] is which of the two prompts about the compass
 * itself shows; it and [detail] share one place, so at most one of them is there.
 */
data class Readout(
    val main: ReadoutLine?, val detail: ReadoutLine?, val latitude: ReadoutLine?, val longitude: ReadoutLine?,
    val mainIsStatus: Boolean = false,
    val prompt: ReadoutPrompt? = null,
)

/** The words about the compass itself that stand in the place of a status's second line: `Hold flat`, or `Calibrate`. */
enum class ReadoutPrompt { HOLD_FLAT, CALIBRATE }

/** What has the focus on the compass: nothing, a waypoint that is a target, or the crumb of a trail that is walked to. */
enum class FocusKind { NONE, WAYPOINT, CRUMB }

private const val LOCATING = "Locating…"

/**
 * The readout's lines.
 *
 * The row under the heading and the row under that:
 * - A logging [status] that is not idle has both, in its own words and colors ([statusText]).
 * - Otherwise, while something has the focus and no position can come at all ([noPosition]: the permission is not
 *   held, or location is switched off), the two rows say which, as a failed press does. For a crumb always; for a
 *   waypoint only while it has no distance, so a distance from before location was switched off stays, marked old.
 * - Otherwise the focused target's distance: `Locating…` before the first fix; `Arrived at target`; the distance,
 *   ending in ` (old)` when the fix is [stale], and for a crumb followed by a poor fix's accuracy ([poorFixNote]).
 * - With nothing focused: `No target` when there are waypoints and none is a target ([noTarget]), else nothing.
 *
 * The prompts, `Hold flat` ([holdFlat]) and `Calibrate` ([lowAccuracy], when the watch is not tilted too far), are
 * about the compass working at all and share the place of a status's second line (under the row under the heading,
 * or at the top of the readout with the coordinates on):
 * - The second line of a press's status has that place while it shows, and the prompt is back when the status ends.
 * - What a focused target says while no position can come stands for as long as that lasts, so it gives the place
 *   up: while a prompt is due, a message of two lines (`Needs access to` / `precise location`) is not shown, neither
 *   line, and returns when the prompt ends. Meanwhile the row under the heading has the target's distance if there
 *   is one, and otherwise nothing. A message of one line (`Location is off`) is not in the prompt's place and stays.
 *
 * The coordinates ([showCoordinates]) add their two rows and change none of the above. They are left out only in
 * the hold-flat state ([holdFlat]) and without the permission.
 * - With a [position]: the latitude and the longitude, fainter when the fix is old. That it is old is said once:
 *   by the distance if the row above shows one, and otherwise by ` (old)` after the latitude.
 * - With none: one line in the lower row, and only if no other line has said it. `Location is off` when it is;
 *   otherwise `Locating…`, unless the distance says that already or a press has just failed for want of a position.
 */
fun readout(
    status: LogStatus, units: Units, focus: FocusKind, distanceM: Double?, arrived: Boolean, noTarget: Boolean,
    position: GeoPoint?, stale: Boolean, fixAccuracyM: Float?, noPosition: LogFailure?, showCoordinates: Boolean, holdFlat: Boolean,
    lowAccuracy: Boolean = false,
): Readout {
    // The prompt that is due, before anything is said about its place.
    val due = when { holdFlat -> ReadoutPrompt.HOLD_FLAT; lowAccuracy -> ReadoutPrompt.CALIBRATE; else -> null }
    // The status that has the two rows: the press's, or what a focused target says when no position can come.
    // The second stands for as long as its cause does, so where it would need the prompt's place it gives way.
    val shown: LogStatus? = when {
        status != LogStatus.Idle -> status
        noPosition != null && (focus == FocusKind.CRUMB || (focus == FocusKind.WAYPOINT && distanceM == null)) ->
            LogStatus.Failed(noPosition).takeUnless { due != null && statusText(it, units).second != null }
        else -> null
    }
    var distanceSaysOld = false
    val main: ReadoutLine?
    val detail: ReadoutLine?
    if (shown != null) {
        val (words, second) = statusText(shown, units)
        val tone = statusTone(shown)
        main = ReadoutLine(words, tone)
        detail = second?.let { ReadoutLine(it, if (statusDetailContinues(shown)) tone else ReadoutTone.DIM) }
    } else {
        detail = null
        main = when {
            focus == FocusKind.NONE -> if (noTarget) ReadoutLine("No target", ReadoutTone.DIM) else null
            // Waiting for a position is said only where one can come and none is known. (A target with no distance
            // beside a known position does not happen today; the position is what is known.)
            distanceM == null -> if (position == null && noPosition == null) ReadoutLine(LOCATING, ReadoutTone.INK) else null
            arrived -> ReadoutLine("Arrived at target", if (stale) ReadoutTone.DIM else ReadoutTone.INK)
            stale -> { distanceSaysOld = true; ReadoutLine(formatDistance(distanceM, units) + " (old)", ReadoutTone.DIM) }
            else -> ReadoutLine(
                formatDistance(distanceM, units), ReadoutTone.INK,
                note = if (focus == FocusKind.CRUMB) poorFixNote(fixAccuracyM, stale, units) else null,
            )
        }
    }

    var latitude: ReadoutLine? = null
    var longitude: ReadoutLine? = null
    if (showCoordinates && !holdFlat && noPosition != LogFailure.NO_PERMISSION) {
        if (position != null) {
            val lat = formatLatitude(position.lat)
            val lon = formatLongitude(position.lon)
            if (lat != null && lon != null) {
                val tone = if (stale) ReadoutTone.FAINT else ReadoutTone.DIM
                latitude = ReadoutLine(if (stale && !distanceSaysOld) "$lat (old)" else lat, tone)
                longitude = ReadoutLine(lon, tone)
            }
        } else {
            val locationOff = statusText(LogStatus.Failed(LogFailure.LOCATION_OFF), units).first
            val failedForPosition = shown is LogStatus.Failed && shown.reason != LogFailure.SAVE_FAILED
            longitude = when {
                // A press, or the target's own row, has said why there is no position.
                failedForPosition -> null
                noPosition == LogFailure.LOCATION_OFF -> ReadoutLine(locationOff, ReadoutTone.DIM)
                main?.words == LOCATING -> null
                else -> ReadoutLine(LOCATING, ReadoutTone.DIM)
            }
        }
    }
    return Readout(main, detail, latitude, longitude, mainIsStatus = shown != null, prompt = if (detail == null) due else null)
}

/** The color of a logging status's words, as a tone. */
fun statusTone(status: LogStatus): ReadoutTone = when (status) {
    is LogStatus.Saved, LogStatus.TrailComplete -> ReadoutTone.CONFIRM
    is LogStatus.Failed -> ReadoutTone.ERROR
    LogStatus.Idle, is LogStatus.Saving -> ReadoutTone.INK
}
