package com.infinite8looper.compass

/**
 * A test that waits minutes for the screen's keep-on to run out. scripts/all-suites.sh runs these on Wear_OS_Newest
 * (API 37) alone, by the owner's decisions of 2026-10-15, and gives every other image `notAnnotation` with this
 * class, so there they are neither run nor counted.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class TakesMinutes
