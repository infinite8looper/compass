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
