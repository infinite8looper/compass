package com.infinite8looper.compass.nav

import kotlin.math.roundToInt

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
 * the heading, a status's second line, the calibrate prompt and the words of the hold-flat hint (the last three
 * share a place: under the heading's row with the coordinates off, at the top with them on), and the longitude.
 * The two coordinate rows are used only with the coordinates switched on. [detailAlone] is where a status's second
 * line stands while neither coordinate row shows anything ([detailRow]): right under the first line.
 */
class ReadoutRows(
    val latitude: Float, val top: Float, val name: Float, val main: Float, val detail: Float, val prompt: Float, val longitude: Float,
    val detailAlone: Float = detail,
)

/** The rows as they stand with the coordinates switched off. */
private val ROWS = ReadoutRows(latitude = 0f, top = -0.31f, name = -0.195f, main = 0.195f, detail = 0.30f, prompt = 0.31f, longitude = 0f)

/**
 * The rows with the coordinates switched on. The latitude and the longitude stand together in the lower half, under
 * the row under the heading; the prompt (`Calibrate`, `Hold flat`) and a status's second line stand at the top, above
 * `MAG` and the trail's label, about where the latitude stood before (-0.345): at -0.361, two clear rows of pixels
 * above `MAG` on both round screens ([clearRows]). While neither coordinate row shows anything, a status's second line
 * stands right under its first instead, in the latitude's row (0.268, two clear rows under a descender of the first).
 * `MAG` and the name are where they were; the row under the heading is a little closer to it (0.163, from 0.167),
 * 2.2 px under its digits on the smaller round screen.
 * Under the heading's row three gaps share what is left above the pane ticks' ink: that row's descender to the
 * latitude, the latitude (which can end in ` (old)`, whose brackets reach as low as a descender) to the longitude's
 * row (which can say `Locating…`), and that row's descender to the ticks. Each is 2.2 to 2.3 px of air on the smaller
 * round screen ([rowGap]); the longitude's digits, which have no descender, end 4.7 px above the ticks.
 */
private val ROWS_WITH_COORDINATES =
    ReadoutRows(
        latitude = 0.26f, top = -0.27f, name = -0.185f, main = 0.163f, detail = -0.361f, prompt = -0.361f, longitude = 0.342f,
        detailAlone = 0.268f,
    )

/** Nothing but the readout is drawn inside this fraction of the radius: the cardinal letters' inner edge is at about 0.48. */
const val READOUT_RADIUS = 0.47f

/** The width of the slot of a status's second line under the heading. */
const val DETAIL_WIDTH = 0.62f

/**
 * Where a status's second line stands for the [lines] the readout shows: in the latitude's row, right under the first
 * line ([ReadoutRows.detailAlone]), while neither coordinate row shows anything (the coordinates switched off, or no
 * position to show and no `Locating…` or `Location is off` in its place); at the top ([ReadoutRows.detail]) while one
 * does. With the coordinates switched off the two are the same place, under the row under the heading.
 */
fun detailRow(rows: ReadoutRows, lines: Readout): Float =
    if (lines.latitude == null && lines.longitude == null) rows.detailAlone else rows.detail

/**
 * The width of the slot of a status's second line whose row is [row]: [DETAIL_WIDTH], or less where the readout's
 * circle is narrower than that at the top of the line's tall letters or the bottom of its descenders, so the slot's
 * corners stay a pixel inside the circle on the smaller round screen. Words wider than the slot are drawn smaller.
 */
fun detailWidth(row: Float): Float {
    val far = maxOf(kotlin.math.abs(row - TALL_TOP * READOUT_DETAIL_SIZE), kotlin.math.abs(row + DESCENDER * READOUT_DETAIL_SIZE))
    val inside = READOUT_RADIUS - SLOT_MARGIN
    return minOf(DETAIL_WIDTH, 2 * kotlin.math.sqrt(inside * inside - far * far))
}

/** How far inside the readout's circle a slot's corners stay: 1 px on the smaller round screen (radius 192 px). */
private const val SLOT_MARGIN = 1f / 192f

/**
 * The whole rows of pixels with no ink between two lines on a screen of [radiusPx] pixels whose middle is the dial's,
 * the upper line's ink ending at [upperBottom] and the lower one's beginning at [lowerTop] (dial radii from the
 * middle). Ink reaches into every row it touches, so it is fewer than the air in pixels: 2.5 px of air can leave one
 * clear row. Negative when they overlap by more than a row.
 */
fun clearRows(upperBottom: Float, lowerTop: Float, radiusPx: Int): Int {
    val bottom = radiusPx * (1.0 + upperBottom)
    val top = radiusPx * (1.0 + lowerTop)
    return (kotlin.math.floor(top + 1e-4) - kotlin.math.ceil(bottom - 1e-4)).toInt()
}

/** Which set of rows it is depends on the setting alone, so no row moves while the compass is in use. */
fun readoutRows(coordinates: Boolean): ReadoutRows = if (coordinates) ROWS_WITH_COORDINATES else ROWS

// How far a line's ink reaches from the middle of its line, as a share of its text size. Measured in screenshots
// of the API 35 emulators (the system's font, sizes of 13 to 22 px): the top of a tall letter (`d`, `l`, `f`,
// `H`), the top of a digit, the baseline, and the bottom of a descender (`g`, `p`). The heading's digits are in
// the light weight and end a little higher. These are typical, not bounds: in the pictures of v3 Task 8 a line's ink
// reached more than 0.6 px beyond them (the top of `Arrived at target` under the heading with the coordinates off on the
// smaller round screen) and stopped more than 1 px short of them (the descender of `No target` with the coordinates on,
// same screen), by the font's rendering at that size and the layout's whole-pixel places. The air they give is a
// continuous distance, and ink reaches into every row of pixels it touches, so 2 px of air is one or two clear rows
// ([clearRows]); the pictures, measured with scripts/readout-gaps.py, are the check.
const val TALL_LETTER_TOP = 0.42f
private const val TALL_TOP = TALL_LETTER_TOP
private const val DIGIT_TOP = 0.375f
private const val BASELINE = 0.40f
const val DESCENDER_BOTTOM = 0.58f
private const val DESCENDER = DESCENDER_BOTTOM
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
/** The width of a pane tick at full size; the ticks are drawn with round caps, which reach half of it beyond each end. */
const val PANE_TICK_WIDTH = 0.018f

/** Where the ink of the longest pane tick on the compass begins, its round cap included, in dial radii below the middle. */
fun paneTicksTopOnCompass(): Float = PANE_TICKS_ON_COMPASS - (PANE_TICK_LENGTH_CURRENT + PANE_TICK_WIDTH / 2) * PANE_TICKS_SCALE

/** How far the top of the heading's digits stands above the middle, as a share of the heading's size. */
const val HEADING_DIGIT_TOP = DIGIT_TOP

/** The calibrate prompt's tap area, in dial radii from the middle: how far beyond the middle of its word its near edge is, and its height. */
const val PROMPT_TAP_BEYOND_WORD = 0.055f
const val PROMPT_TAP_HEIGHT = 0.52f

/** Where the calibrate prompt's tap area stands: its [top] and [bottom] in dial radii, and whether it reaches up from its word. */
class PromptTapArea(val top: Float, val bottom: Float, val reachesUp: Boolean)

/**
 * The calibrate prompt's tap area for [rows]. It covers the word and reaches away from the middle of the dial, where
 * a double tap saves: down from the top of the word when the word is under the heading, up from its bottom when it
 * is above (the coordinates on).
 */
fun promptTapArea(rows: ReadoutRows): PromptTapArea =
    if (rows.prompt < 0f) {
        val bottom = rows.prompt + PROMPT_TAP_BEYOND_WORD
        PromptTapArea(bottom - PROMPT_TAP_HEIGHT, bottom, reachesUp = true)
    } else {
        val top = rows.prompt - PROMPT_TAP_BEYOND_WORD
        PromptTapArea(top, top + PROMPT_TAP_HEIGHT, reachesUp = false)
    }

/** The air between the heading's digits, which stand in the middle, and a row of words under them. */
fun headingGap(row: Float, rowSize: Float): Float = (row - TALL_TOP * rowSize) - HEADING_BASELINE * READOUT_HEADING_SIZE

/** Stands for no whole degree: there is no heading to show. */
const val NO_DEGREE = -1

/**
 * The whole degree the heading number shows for the heading [shownDeg] the dial is drawn at, 0 to 359, or
 * [NO_DEGREE] while there is none (NaN). The last half degree before north reads 0, never 360.
 */
fun shownDegree(shownDeg: Float): Int = if (shownDeg.isNaN()) NO_DEGREE else shownDeg.roundToInt().mod(360)

/** The heading number's text for a whole [degree]: `236°`, or `--` for [NO_DEGREE]. */
fun headingText(degree: Int): String = if (degree == NO_DEGREE) "--" else "$degree°"

/**
 * What a screen reader says for the heading, from the text the compass shows for it (`236°`, or `--` while there
 * is none): its name, the number and the unit, and `magnetic` while the dial is on magnetic north.
 */
fun headingSpoken(shown: String, magnetic: Boolean): String {
    val degrees = shown.removeSuffix("°").toIntOrNull() ?: return "Heading unknown"
    return "Heading $degrees ${if (degrees == 1) "degree" else "degrees"}${if (magnetic) ", magnetic" else ""}"
}
