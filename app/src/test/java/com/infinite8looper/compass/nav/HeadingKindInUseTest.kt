package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/**
 * The heading source a watch can run. The magnetic heading needs the geomagnetic rotation vector, or the
 * accelerometer with the magnetometer; on a watch with neither, the magnetic source and the blend would give no
 * heading at all, so the running app is on the fused source instead and the check screen says so.
 */
class HeadingKindInUseTest {
    @Test fun aWatchThatCanWorkOutTheMagneticHeadingRunsTheSourceAskedFor() {
        for (kind in HeadingKind.entries) assertEquals(kind, headingKindInUse(kind, magneticAvailable = true))
    }

    @Test fun aWatchThatCannotRunsTheFusedSourceWhicheverIsAskedFor() {
        for (kind in HeadingKind.entries) assertEquals(HeadingKind.FUSED, headingKindInUse(kind, magneticAvailable = false))
    }

    @Test fun theCheckScreenSaysWhichSourceIsUnavailable() {
        assertEquals("Magnetic unavailable", headingSourceNote(asked = HeadingKind.MAGNETIC, inUse = HeadingKind.FUSED))
        assertEquals("Blend unavailable", headingSourceNote(asked = HeadingKind.BLEND, inUse = HeadingKind.FUSED))
        for (kind in HeadingKind.entries) assertNull(headingSourceNote(asked = kind, inUse = kind))
    }
}
