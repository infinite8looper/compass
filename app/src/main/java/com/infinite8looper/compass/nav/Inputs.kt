package com.infinite8looper.compass.nav

// The inputs that save a position from the compass without a side button, and whether the watch has a side button
// an app can use at all. The decisions are here; the watch is asked in sensors/WatchButtons.kt and the touches are
// taken in ui/CompassScreen.kt.

/** The count a watch gives when it cannot say how many buttons it has. */
const val BUTTON_COUNT_UNKNOWN = -1

/**
 * Whether the watch has a button the app can act on, from what the watch says of its buttons.
 *
 * [buttonCount] is how many buttons it says it has, the power button included, or a negative number when it cannot
 * say; [sideKeysKnown] is for how many of the three side keys the app acts on (stem 1, 2 and 3) it describes a
 * button. There is one when it describes at least one of those keys and its count leaves room for a button beside
 * the power button: a count of one or none is believed over a description, and with no count the description
 * decides alone.
 *
 * This is what the watch says, not what it does: a watch can describe a button and keep its presses for itself.
 */
fun hasAssignableButton(buttonCount: Int, sideKeysKnown: Int): Boolean =
    sideKeysKnown > 0 && (buttonCount > 1 || buttonCount < 0)

/**
 * Whether two taps on the dial, one after the other within the system's double-tap time, are the double tap that
 * saves. The compass has to be the screen in view at both ([firstInView] when the first tap went down,
 * [secondInView] when the second was let go): a tap that stops the screens while they slide, or that lands as they
 * start to, is not half of one. And the two have to land near each other: [apartPx] is how far apart they went
 * down, [slopPx] the system's own limit for a double tap.
 */
fun doubleTapSaves(firstInView: Boolean, secondInView: Boolean, apartPx: Float, slopPx: Float): Boolean =
    firstInView && secondInView && apartPx <= slopPx

/**
 * A tap on the dial: when it went down and was let go ([downMs], [upMs], on one clock), where it went down, and
 * whether the compass was the screen in view when it went down and when it was let go.
 */
data class DialTap(val downMs: Long, val upMs: Long, val x: Float, val y: Float, val inViewAtDown: Boolean, val inViewAtUp: Boolean)

/** After a tap: whether it saves, and the tap that waits for a partner now. */
data class TapStep(val saves: Boolean, val waiting: DialTap?)

/**
 * What [tap] makes of the taps on the dial, given the lone tap [waiting] before it, if any.
 *
 * Two taps are a pair when the second goes down within [timeoutMs] of the first being let go, and no sooner than
 * [minMs], and the two are no further apart than [slopPx]. A pair saves when [doubleTapSaves] says so, and leaves
 * nothing waiting, so a third tap close behind is not a second double tap. A tap that makes no pair waits in its
 * turn: a stray tap before a double tap (too long before, or too far away) does not use up the double tap's first
 * tap.
 */
fun nextTap(waiting: DialTap?, tap: DialTap, timeoutMs: Long, minMs: Long, slopPx: Float): TapStep {
    if (waiting != null) {
        val gap = tap.downMs - waiting.upMs
        val apart = kotlin.math.hypot(tap.x - waiting.x, tap.y - waiting.y)
        if (gap in minMs..timeoutMs && apart <= slopPx) {
            return TapStep(doubleTapSaves(waiting.inViewAtDown, tap.inViewAtUp, apart, slopPx), null)
        }
    }
    return TapStep(false, tap)
}
