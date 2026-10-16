package com.infinite8looper.compass.nav

// How the text of the menus (the waypoints list, the settings, the edit screen and calibration) follows the
// system's font size. The compass does not: its text is sized from the dial's radius alone.

/**
 * The smallest and the largest scale the menus' text is drawn at. The font size setting of the Wear OS emulators
 * (API 35 and 37) has six steps, from 0.94 to 1.24. The largest is the upper end: the edit screen and calibration
 * keep everything in a fixed place and are full at 1.24, so a scale beyond it is drawn at 1.24. The lower end is
 * below the setting's smallest step, and is a guard only.
 */
const val MENU_TEXT_SCALE_MIN = 0.85f
const val MENU_TEXT_SCALE_MAX = 1.24f

/**
 * The scale the menus' text is drawn at for the system's [fontScale]: the font scale itself, kept within
 * [MENU_TEXT_SCALE_MIN] and [MENU_TEXT_SCALE_MAX]. A font scale that is no size (zero, negative, not a number,
 * without end) counts as the default, 1.
 */
fun menuTextScale(fontScale: Float): Float =
    if (fontScale.isNaN() || fontScale.isInfinite() || fontScale <= 0f) 1f else fontScale.coerceIn(MENU_TEXT_SCALE_MIN, MENU_TEXT_SCALE_MAX)

/** The height of a menu's text whose height at the default font size is [size] (which comes from the screen's radius), at the system's [fontScale]. */
fun menuTextSize(size: Float, fontScale: Float): Float = size * menuTextScale(fontScale)
