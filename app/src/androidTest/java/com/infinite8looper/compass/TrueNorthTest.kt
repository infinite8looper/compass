package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.dialIsMagnetic
import com.infinite8looper.compass.sensors.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * True north gets a position by itself (v3 spec, "Location while the app is on screen"): with true north selected,
 * the permission held and no position ever stored, one is asked for at once, and `MAG` goes when the declination is
 * known. A view model of the test's own on a settings file of its own, so that no position is stored, and no screen
 * tells it anything: true north is the only reason it has for location. Needs `scripts/geo-feed.sh -72.2896 43.7022`
 * running.
 */
@RunWith(AndroidJUnit4::class)
class TrueNorthTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val file = "true-north-test-${UUID.randomUUID()}"
    private val storeJob = SupervisorJob()
    private val store = SettingsStore(app, file, CoroutineScope(Dispatchers.IO + storeJob))
    private val collectors = ArrayList<Job>()

    @Before fun setUp() = runBlocking {
        // No waypoint is a target, and the trail and the coordinates are off, in this file.
        dao.observeAll().first().forEach { dao.delete(it.id) }
        store.setBreadcrumbTrail(false); store.setTrailMode(TrailMode.OFF); store.setShowCoordinates(false)
        assertNull(store.settings.first().lastPosition)
    }

    @After fun tearDown() = runBlocking {
        collectors.forEach { it.cancel() }
        storeJob.cancelAndJoin()
        app.preferencesDataStoreFile(file).delete()
        app.preferencesDataStoreFile("$file-position").delete()
        Unit
    }

    private suspend fun viewModel(): CompassViewModel {
        val vm = withContext(Dispatchers.Main) { CompassViewModel(app, LocationSource(app), store) }
        collectors += CoroutineScope(Dispatchers.Main).launch { vm.nav.collect { } }
        return vm
    }

    @Test fun withTrueNorthAndNoPositionStoredOneIsAskedForAndMagGoes() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            store.setNorth(NorthRef.TRUE)
            val vm = viewModel()
            val first = withTimeout(10_000) { vm.nav.first { it.loaded } }
            assertTrue("MAG shows while no declination is known", dialIsMagnetic(first.north, first.declination))
            val known = withTimeout(60_000) { vm.nav.first { it.declination != null } }
            assertFalse("MAG still shows with the declination known", dialIsMagnetic(known.north, known.declination))
            assertNotNull(store.settings.first { it.lastPosition != null }.lastPosition)
            // With a position stored true north is no reason any more, and nothing else asks: location stops, and the
            // declination stays known from the stored position.
            val after = withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            assertNotNull(after.declination)
            assertFalse(dialIsMagnetic(after.north, after.declination))
        }
    }

    @Test fun withMagneticNorthNoPositionIsAskedForUntilTrueIsSelected() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            store.setNorth(NorthRef.MAGNETIC)
            val vm = viewModel()
            withTimeout(10_000) { vm.nav.first { it.loaded && it.north == NorthRef.MAGNETIC } }
            withTimeoutOrNull(6_000) { vm.nav.collect { assertNull(it.fixElapsedMs); assertTrue(dialIsMagnetic(it.north, it.declination)) } }
            assertNull(store.settings.first().lastPosition)
            store.setNorth(NorthRef.TRUE)
            val known = withTimeout(60_000) { vm.nav.first { it.north == NorthRef.TRUE && it.declination != null } }
            assertFalse(dialIsMagnetic(known.north, known.declination))
        }
    }
}
