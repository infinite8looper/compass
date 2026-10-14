package com.infinite8looper.compass.ui

import com.infinite8looper.compass.nav.Pane

/**
 * How often each of the three side-by-side screens has been built and how often it has been composed, counted in
 * the screens themselves and kept by a [Pane]'s place in their order. A screen is built when it enters the
 * composition: its state starts from nothing and everything in it is laid out anew. It is composed when it is
 * built and again each time its own function runs for a change.
 *
 * The instrumented tests read these to show that turning the ring builds no screen and composes none. The screens
 * write them on the main thread, and nothing in the app reads them.
 */
object ScreenCompositions {
    val built = IntArray(Pane.entries.size)
    val composed = IntArray(Pane.entries.size)
}
