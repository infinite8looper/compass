package com.infinite8looper.compass.ui

import com.infinite8looper.compass.BuildConfig
import com.infinite8looper.compass.nav.Pane

/**
 * How often each of the three side-by-side screens has been built and how often it has been composed, counted in
 * the screens themselves and kept by a [Pane]'s place in their order. A screen is built when it enters the
 * composition: its state starts from nothing and everything in it is laid out anew. It is composed when it is
 * built and again each time its own function runs for a change.
 *
 * The screen functions themselves are counted here (and, on the compass, the readout's content); a part of a screen
 * that is composed apart from it is not, except the parts of the waypoints list ([waypointsParts]).
 *
 * The instrumented tests read these to show that turning the ring builds no screen and composes none. The screens
 * write them on the main thread, in builds with `BuildConfig.DIAGNOSTICS` on (the debug and the watch build), and
 * nothing in the app reads them.
 */
object ScreenCompositions {
    val built = IntArray(Pane.entries.size)
    val composed = IntArray(Pane.entries.size)

    /** Counts one build of [pane]'s screen and returns the count, which the screen keeps (`remember`) and does not use. */
    fun countBuilt(pane: Pane): Int { if (BuildConfig.DIAGNOSTICS) built[pane.ordinal]++; return built[pane.ordinal] }
    fun countComposed(pane: Pane) { if (BuildConfig.DIAGNOSTICS) composed[pane.ordinal]++ }

    /** How often each part of the waypoints list has been composed, by [WaypointsPart]: every row of a kind counts in one. */
    val waypointsParts = IntArray(WaypointsPart.entries.size)
    fun countPart(part: WaypointsPart) { if (BuildConfig.DIAGNOSTICS) waypointsParts[part.ordinal]++ }
}

/** The parts of the waypoints list counted in [ScreenCompositions.waypointsParts]: [ROW] is a waypoint's row, [DISTANCE] the distance line in it. */
enum class WaypointsPart { HEADER, LOG_HERE, TRAIL, DROP_CRUMB, ROW, DISTANCE }
