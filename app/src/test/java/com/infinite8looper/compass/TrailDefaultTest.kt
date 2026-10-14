package com.infinite8looper.compass

import com.infinite8looper.compass.data.BREADCRUMB_TRAIL_DEFAULT
import com.infinite8looper.compass.data.Settings
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units
import org.junit.Assert.*
import org.junit.Test

/**
 * `Breadcrumb trail` where nothing has been stored or read yet. The v3 spec: "`Breadcrumb trail` is on by default,
 * also for an install that never touched the switch." The places that hold a default for it agree: the settings as
 * a value, what the screens share before and after the stores have answered, and the settings screen's switches
 * before the file has been read. What the settings file reads for a key that is not stored is in SettingsStoreTest.
 */
class TrailDefaultTest {
    @Test fun theTrailIsOnInEveryPlaceThatHoldsADefaultForIt() {
        assertTrue(BREADCRUMB_TRAIL_DEFAULT)
        assertTrue(Settings(NorthRef.TRUE, Units.METRIC, focusedId = null, lastPosition = null, lastAltitudeM = null).breadcrumbTrail)
        assertTrue(NavUi.EMPTY.breadcrumbTrail)
        assertTrue(NavUi(emptyList(), null, Units.METRIC, NorthRef.TRUE, null, null, emptyList()).breadcrumbTrail)
        assertTrue(SwitchesUi().breadcrumbTrail)
    }
}
