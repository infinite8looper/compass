package com.infinite8looper.compass.nav

// Where the rows of the compass's readout stand, and how far apart their ink is. Everything is in dial radii,
// as the readout is laid out: a row's place is the middle of its line, measured from the center of the dial
// (negative is above it), and a size is the size of its text.

/** The text sizes of the readout: the heading, a row of words, the smaller rows, and the coordinates. */
const val READOUT_HEADING_SIZE = 0.30f
const val READOUT_BODY_SIZE = 0.097f
const val READOUT_DETAIL_SIZE = 0.079f
const val COORDINATES_SIZE = 0.070f

/**
 * Where the readout's rows stand: the latitude, the row of `MAG` and the trail's label, the name, the row under
 * the heading, the smaller row under that, the calibrate prompt and the words of the hold-flat hint, which share
 * a place, and the longitude. The two coordinate rows are used only with the coordinates switched on.
 */
class ReadoutRows(
    val latitude: Float, val top: Float, val name: Float, val main: Float, val detail: Float, val prompt: Float, val longitude: Float,
)

/** The rows as they stand with the coordinates switched off. */
private val ROWS = ReadoutRows(latitude = 0f, top = -0.31f, name = -0.195f, main = 0.195f, detail = 0.30f, prompt = 0.31f, longitude = 0f)

/**
 * The rows with the coordinates switched on: the latitude above the top row and the longitude below the prompts,
 * each far enough inside the circle the letters sweep to be whole at its widest (`179.9999° W`), and the rows
 * between a little closer to the heading. The longitude ends above the pane ticks.
 * Below the heading four things share a quarter of the radius: the heading's digits (or the hold-flat drawing),
 * a row of words that can have a descender, a prompt or a status's second line, and the longitude. The places
 * leave at least 2 px of air between each pair on the smaller round screen ([rowGap]); there is no more to
 * share out without moving the longitude into the pane ticks, over which a descender in its row has 1.5 px.
 */
private val ROWS_WITH_COORDINATES =
    ReadoutRows(latitude = -0.345f, top = -0.27f, name = -0.185f, main = 0.167f, detail = 0.267f, prompt = 0.275f, longitude = 0.351f)

/** Which set of rows it is depends on the setting alone, so no row moves while the compass is in use. */
fun readoutRows(coordinates: Boolean): ReadoutRows = if (coordinates) ROWS_WITH_COORDINATES else ROWS

// How far a line's ink reaches from the middle of its line, as a share of its text size. Measured in screenshots
// of the API 35 emulators (the system's font, sizes of 13 to 22 px): the top of a tall letter (`d`, `l`, `f`,
// `H`), the top of a digit, the baseline, and the bottom of a descender (`g`, `p`). The heading's digits are in
// the light weight and end a little higher.
private const val TALL_TOP = 0.42f
private const val DIGIT_TOP = 0.375f
private const val BASELINE = 0.40f
private const val DESCENDER = 0.58f
private const val HEADING_BASELINE = 0.37f

/**
 * The air between two rows: from the lowest ink of the upper one (the bottom of a descender if its words can
 * have one, its baseline if not) to the highest ink of the lower one (a tall letter, or a digit).
 * Negative when they overlap.
 */
fun rowGap(upper: Float, upperSize: Float, upperDescends: Boolean, lower: Float, lowerSize: Float, lowerHasTallLetters: Boolean): Float =
    (lower - (if (lowerHasTallLetters) TALL_TOP else DIGIT_TOP) * lowerSize) - (upper + (if (upperDescends) DESCENDER else BASELINE) * upperSize)

/** Where a row's ink ends: the bottom of a descender if its words can have one, its baseline if not. */
fun rowBottom(row: Float, size: Float, descends: Boolean): Float = row + (if (descends) DESCENDER else BASELINE) * size

/**
 * The three ticks that show which screen is in view, as the compass draws them under the longitude's row: where
 * their outer ends are (just inside the circle the letters sweep), their size there against the ticks at the
 * edge of the other screens, and the length of the longest of them, the current screen's, at full size.
 */
const val PANE_TICKS_ON_COMPASS = 0.465f
const val PANE_TICKS_SCALE = 0.75f
const val PANE_TICK_LENGTH_CURRENT = 0.085f

/** Where the longest pane tick on the compass begins, in dial radii below the middle. */
fun paneTicksTopOnCompass(): Float = PANE_TICKS_ON_COMPASS - PANE_TICK_LENGTH_CURRENT * PANE_TICKS_SCALE

/** The air between the heading's digits, which stand in the middle, and a row of words under them. */
fun headingGap(row: Float, rowSize: Float): Float = (row - TALL_TOP * rowSize) - HEADING_BASELINE * READOUT_HEADING_SIZE

/**
 * What a screen reader says for the heading, from the text the compass shows for it (`236°`, or `--` while there
 * is none): its name, the number and the unit, and `magnetic` while the dial is on magnetic north.
 */
fun headingSpoken(shown: String, magnetic: Boolean): String {
    val degrees = shown.removeSuffix("°").toIntOrNull() ?: return "Heading unknown"
    return "Heading $degrees ${if (degrees == 1) "degree" else "degrees"}${if (magnetic) ", magnetic" else ""}"
}
