package com.infinite8looper.compass

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.distanceMeters
import com.infinite8looper.compass.sensors.LocationSource
import com.infinite8looper.compass.sensors.declinationAt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationSourceTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hanover = GeoPoint(43.7022, -72.2896)

    /** Needs `scripts/geo-feed.sh -72.2896 43.7022` running. */
    @Test fun freshFixMatchesTheFedPosition() = runBlocking {
        val src = LocationSource(ctx)
        assertTrue(src.hasPermission())
        val fix = withTimeout(60_000) { src.updates(1000).first() }
        assertTrue("off by ${distanceMeters(hanover, fix.point)} m", distanceMeters(hanover, fix.point) < 100.0)
        assertTrue(fix.accuracyM > 0f)
        assertTrue(SystemClock.elapsedRealtime() - fix.elapsedMs < 15_000)
    }
    @Test fun withoutPermissionTheFlowEndsQuietly() = runBlocking {
        val src = LocationSource(ctx) { false }
        assertFalse(src.hasPermission())
        assertTrue(withTimeout(5_000) { src.updates(1000).toList() }.isEmpty())
    }
    @Test fun declinationIsWestInNewEnglandAndEastInSeattle() {
        val now = System.currentTimeMillis()
        val nh = declinationAt(hanover, 160f, now)
        assertTrue("Hanover declination $nh", nh in -16f..-11f)
        assertTrue(declinationAt(GeoPoint(47.6062, -122.3321), 50f, now) in 12f..17f)
    }
}
