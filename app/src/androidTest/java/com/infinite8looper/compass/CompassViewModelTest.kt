package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.Crumb
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.LogFailure
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.TrailUi
import com.infinite8looper.compass.nav.crumbTargetId
import com.infinite8looper.compass.nav.distanceMeters
import com.infinite8looper.compass.nav.logStatusShowMs
import com.infinite8looper.compass.sensors.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
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
import java.util.concurrent.CopyOnWriteArrayList

/** Runs the real view model against the app's own database and settings file on the device. */
@RunWith(AndroidJUnit4::class)
class CompassViewModelTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val settings = SettingsStore.get(app)
    private val hanover = Fix(GeoPoint(43.7022, -72.2896), 5f, 160f, 0L)

    private val crumbDao = WaypointDatabase.get(app).crumbs()

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        // Off is how the breadcrumb trail comes: every test that wants it switches it on.
        settings.setBreadcrumbTrail(false)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private suspend fun viewModel() = withContext(Dispatchers.Main) { CompassViewModel(app) }

    @Test fun loggingASecondWaypointKeepsTheFocusOnTheFirst() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        val nav = withTimeout(10_000) { vm.nav.first { it.waypoints.size == 2 } }
        assertEquals(listOf(second.id, first.id), nav.targets.map { it.id })
        assertEquals(first.id, nav.focused?.id)
        assertEquals(first.id, settings.settings.first().focusedId)
    }

    @Test fun loggingKeepsAFocusTheWearerChose() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        settings.setFocused(second.id)
        val third = vm.logWaypoint(hanover.copy(point = GeoPoint(43.6950, -72.3000)))
        val nav = withTimeout(10_000) { vm.nav.first { it.waypoints.size == 3 } }
        assertEquals(listOf(third.id, second.id, first.id), nav.targets.map { it.id })
        assertEquals(second.id, nav.focused?.id)
    }

    @Test fun moveFocusWithNoTargetsLeavesTheStoredFocusUntouched() = runBlocking {
        settings.setFocused(42L)
        val vm = viewModel()
        vm.moveFocus(1).join()
        assertEquals(42L, settings.settings.first().focusedId)
    }

    @Test fun moveFocusStepsThroughTheTargetsAndWraps() = runBlocking {
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
        // Targets are newest first: [second, first], and the focus is on first.
        vm.moveFocus(1).join()
        assertEquals(second.id, settings.settings.first().focusedId)
        vm.moveFocus(1).join()
        assertEquals(first.id, settings.settings.first().focusedId)
        vm.moveFocus(-1).join()
        assertEquals(second.id, settings.settings.first().focusedId)
    }

    @Test fun blindNavigationAndTheBreadcrumbTrailReachTheScreenStateAndFollowTheirSetters() = runBlocking {
        val vm = viewModel()
        try {
            settings.setBlindNavigation(true); settings.setBreadcrumbTrail(false)
            var nav = withTimeout(10_000) { vm.nav.first { it.loaded && it.blindNavigation && !it.breadcrumbTrail } }
            assertTrue(nav.blindNavigation); assertFalse(nav.breadcrumbTrail)
            vm.setBlindNavigation(false).join(); vm.setBreadcrumbTrail(true).join()
            nav = withTimeout(10_000) { vm.nav.first { !it.blindNavigation && it.breadcrumbTrail } }
            assertFalse(nav.blindNavigation); assertTrue(nav.breadcrumbTrail)
            // Stored, not only shown.
            assertFalse(settings.settings.first().blindNavigation); assertTrue(settings.settings.first().breadcrumbTrail)
            vm.setBlindNavigation(true).join(); vm.setBreadcrumbTrail(false).join()
            nav = withTimeout(10_000) { vm.nav.first { it.blindNavigation && !it.breadcrumbTrail } }
            assertTrue(nav.blindNavigation); assertFalse(nav.breadcrumbTrail)
        } finally {
            settings.setBlindNavigation(true); settings.setBreadcrumbTrail(false)
        }
    }

    @Test fun navIsNotLoadedUntilTheStoresHaveAnswered() = runBlocking {
        assertFalse(NavUi.EMPTY.loaded)
        val vm = viewModel()
        val nav = withTimeout(10_000) { vm.nav.first { it.loaded } }
        assertTrue(nav.waypoints.isEmpty())
    }

    /** Needs `scripts/geo-feed.sh -72.2896 43.7022` running and the app on screen, as in LocationSourceTest. */
    @Test fun everyWaypointGetsADistanceWhileAFixIsLive() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            val near = vm.logWaypoint(hanover)
            val far = vm.logWaypoint(hanover.copy(point = GeoPoint(43.7067, -72.2896)))
            vm.setTarget(far.id, false).join()
            val nav = withTimeout(60_000) { vm.nav.first { it.distances.size == 2 } }
            assertEquals(setOf(near.id, far.id), nav.distances.keys)
            val expected = distanceMeters(hanover.point, GeoPoint(43.7067, -72.2896))
            assertEquals(expected, nav.distances.getValue(far.id), 60.0)
            assertTrue(nav.distances.getValue(near.id) < 60.0)
            assertFalse(nav.targets.any { it.id == far.id })
        }
    }

    @Test fun noDistancesAreShownWhenNothingIsATargetAndSoNoFixIsLive() = runBlocking {
        val vm = viewModel()
        vm.logWaypoint(hanover)
        vm.setAllTargets(false).join()
        withTimeout(10_000) { vm.nav.first { it.loaded && it.waypoints.size == 1 && it.targets.isEmpty() } }
        // Watch for a few seconds, with the screen state being collected, so a fix that arrived late would show.
        withTimeoutOrNull(3_000) { vm.nav.collect { assertTrue(it.distances.isEmpty()) } }
        Unit
    }

    /** Needs the feed running and the app on screen. A fix that has arrived must not outlive the location updates. */
    @Test fun clearingTheTargetsRemovesTheDistancesAndTheFix() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            vm.logWaypoint(hanover)
            val live = withTimeout(60_000) { vm.nav.first { it.distances.isNotEmpty() && it.fixElapsedMs != null } }
            assertTrue(live.targets.isNotEmpty())
            val declinationWithFix = live.declination
            assertNotNull(declinationWithFix)
            vm.setAllTargets(false).join()
            val after = withTimeout(10_000) { vm.nav.first { it.targets.isEmpty() && it.distances.isEmpty() } }
            assertNull(after.fixElapsedMs)
            // The declination comes from the stored last position, so it stays.
            assertNotNull(after.declination)
        }
    }

    // One-press logging. The tests that save need the feed running and the app on screen, like the ones above.

    /** A view model whose location source answers that the permission is not held, as before the wearer grants it. */
    @Test fun aColorChangeAndATargetChangeMadeTogetherBothLand() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        // As the edit screen makes them: on the main thread, one straight after the other, many times over.
        val jobs = withContext(Dispatchers.Main) {
            (0 until 20).flatMap { i -> listOf(vm.setHue(w.id, 10f + i), vm.setTarget(w.id, i % 2 == 0)) }
        }
        withTimeout(10_000) { jobs.joinAll() }
        val stored = dao.get(w.id)!!
        assertEquals(29f, stored.hue, 0f)
        assertFalse(stored.isTarget)
    }

    @Test fun aRenameAndAColorChangeMadeTogetherBothLand() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        val jobs = withContext(Dispatchers.Main) { listOf(vm.rename(w.id, "Camp"), vm.setHue(w.id, 200f), vm.setTarget(w.id, false)) }
        withTimeout(10_000) { jobs.joinAll() }
        val stored = dao.get(w.id)!!
        assertEquals("Camp", stored.name)
        assertEquals(200f, stored.hue, 0f)
        assertFalse(stored.isTarget)
    }

    @Test fun colorChangesInQuickSuccessionEndOnTheLast() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        val jobs = withContext(Dispatchers.Main) { (1..40).map { vm.setHue(w.id, it * 9f) } }
        withTimeout(10_000) { jobs.joinAll() }
        // 360 is the same color as 0, and is stored as 0.
        assertEquals(0f, dao.get(w.id)!!.hue, 0f)
    }

    @Test fun aRenameToNothingOrOnlySpacesKeepsTheName() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        withTimeout(10_000) { vm.rename(w.id, "Camp").join() }
        assertEquals("Camp", dao.get(w.id)!!.name)
        for (input in listOf("", "   ", " \n\t ", null)) {
            withTimeout(10_000) { vm.rename(w.id, input).join() }
            assertEquals("Camp", dao.get(w.id)!!.name)
        }
    }

    @Test fun aColorChangeForAWaypointThatWasDeletedBringsNothingBack() = runBlocking {
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        val jobs = withContext(Dispatchers.Main) { listOf(vm.setHue(w.id, 50f), vm.delete(w.id), vm.setHue(w.id, 80f), vm.rename(w.id, "Back")) }
        withTimeout(10_000) { jobs.joinAll() }
        assertNull(dao.get(w.id))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    private suspend fun viewModelWithoutPermission() =
        withContext(Dispatchers.Main) { CompassViewModel(app, LocationSource(app) { false }) }

    /** Presses, waits for that press's save to end, and returns its result, which has to be Saved. */
    private suspend fun pressAndAwaitSaved(vm: CompassViewModel): LogStatus.Saved {
        withTimeout(60_000) { vm.logHere().join() }
        return vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
    }

    /** Waits, without collecting the status, until it is Idle again, and returns how long that took. */
    private suspend fun msUntilIdle(vm: CompassViewModel, limitMs: Long = 10_000): Long {
        val start = SystemClock.elapsedRealtime()
        while (vm.logStatus.value != LogStatus.Idle) {
            assertTrue("still ${vm.logStatus.value} after $limitMs ms", SystemClock.elapsedRealtime() - start < limitMs)
            delay(25)
        }
        return SystemClock.elapsedRealtime() - start
    }

    /** The view model the app's own screens use: the one the real activity holds. */
    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CompassViewModel {
        lateinit var vm: CompassViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[CompassViewModel::class.java] }
        return vm
    }

    @Test fun logHereGoesFromSavingToSavedAndStoresOneWaypoint() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            val seen = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { seen += it } }
            val saved = pressAndAwaitSaved(vm)

            val rows = dao.observeAll().first()
            assertEquals(1, rows.size)
            val row = rows.single()
            assertTrue(row.name, Regex("Waypoint [1-9][0-9]*").matches(row.name))
            assertEquals(row.name, saved.name)
            assertEquals(row.accuracyM, saved.accuracyM, 0f)
            assertTrue(row.isTarget)
            assertTrue("off by ${distanceMeters(hanover.point, GeoPoint(row.lat, row.lon))} m", distanceMeters(hanover.point, GeoPoint(row.lat, row.lon)) < 100.0)

            // The result goes once a screen reports that it has shown it for its time.
            vm.logResultShown(vm.logPress.value)
            withTimeout(10_000) { vm.logStatus.first { it == LogStatus.Idle } }
            withTimeout(10_000) { while (seen.last() != LogStatus.Idle) delay(10) }
            watching.cancel()
            // Idle, then Saving with no accuracy yet, then only Saving until the one Saved, then Idle again.
            assertEquals(LogStatus.Idle, seen.first())
            assertEquals(LogStatus.Saving(null), seen[1])
            val savedAt = seen.indexOf(saved)
            assertTrue("$seen", savedAt >= 2 && seen.subList(1, savedAt).all { it is LogStatus.Saving })
            assertEquals(listOf(saved, LogStatus.Idle), seen.subList(savedAt, seen.size))
        }
    }

    @Test fun pressesWhileASaveIsInProgressSaveOneWaypoint() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // Two presses with nothing between them, and a third once the save is visibly under way.
            val first = withContext(Dispatchers.Main) { vm.logHere().also { vm.logHere() } }
            withTimeout(60_000) { vm.logStatus.first { it != LogStatus.Idle } }
            if (vm.logStatus.value is LogStatus.Saving) vm.logHere()
            withTimeout(60_000) { first.join() }
            val saved = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
            // A second save would need a fix of its own: leave it the time to arrive before counting.
            delay(3_000)
            assertEquals(saved, vm.logStatus.value)
            assertEquals(1, vm.logPress.value)
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
        }
    }

    @Test fun logHereWithoutThePermissionReportsItAndSavesNothing() = runBlocking {
        val vm = viewModelWithoutPermission()
        withTimeout(10_000) { vm.logHere().join() }
        assertEquals(LogStatus.Failed(LogFailure.NO_PERMISSION), vm.logStatus.value)
        assertTrue(dao.observeAll().first().isEmpty())
    }

    /** No screen shows this view model's status, as when the wearer is on another screen or the watch is asleep. */
    @Test fun aResultNothingHasShownStaysUntilItIsReportedShown() = runBlocking {
        val vm = viewModelWithoutPermission()
        withTimeout(10_000) { vm.logHere().join() }
        val failed = LogStatus.Failed(LogFailure.NO_PERMISSION)
        assertEquals(failed, vm.logStatus.value)
        // Well over the three seconds a failure is shown for, checked all the way.
        val until = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < until) {
            assertEquals(failed, vm.logStatus.value)
            delay(50)
        }
        vm.logResultShown(vm.logPress.value)
        assertEquals(LogStatus.Idle, vm.logStatus.value)
    }

    @Test fun aNewPressReplacesAWaitingResultAndTheOldOnesClearCannotRemoveIt() = runBlocking {
        val vm = viewModelWithoutPermission()
        val failed = LogStatus.Failed(LogFailure.NO_PERMISSION)
        withTimeout(10_000) { vm.logHere().join() }
        val first = vm.logPress.value
        assertEquals(failed, vm.logStatus.value)
        // The first result is seen once, and is still waiting to be reported shown when the second press comes.
        assertTrue(vm.logResultFirstSeen(first))
        assertFalse(vm.logResultFirstSeen(first))
        withTimeout(10_000) { vm.logHere().join() }
        val second = vm.logPress.value
        assertNotEquals(first, second)
        assertEquals(failed, vm.logStatus.value)
        // The second result reads the same as the first and is its own result all the same.
        assertFalse(vm.logResultFirstSeen(first))
        assertTrue(vm.logResultFirstSeen(second))
        assertFalse(vm.logResultFirstSeen(second))
        vm.logResultShown(first)
        assertEquals(failed, vm.logStatus.value)
        vm.logResultShown(second)
        assertEquals(LogStatus.Idle, vm.logStatus.value)
        // With nothing showing there is nothing to see or clear.
        assertFalse(vm.logResultFirstSeen(second))
    }

    @Test fun aPressWhileASavedResultWaitsStartsANewSave() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            val first = pressAndAwaitSaved(vm)
            val firstPress = vm.logPress.value
            // Well over the two seconds a saved result is shown for: nothing has shown it, so it waits.
            delay(4_000)
            assertEquals(first, vm.logStatus.value)
            val second = pressAndAwaitSaved(vm)
            assertNotEquals(first.name, second.name)
            assertTrue(second.fixElapsedMs > first.fixElapsedMs)
            assertEquals(setOf(first.name, second.name), dao.observeAll().first().map { it.name }.toSet())
            vm.logResultShown(firstPress)
            assertEquals(second, vm.logStatus.value)
            vm.logResultShown(vm.logPress.value)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
        }
    }

    /** The real compass screen, in view, shows the result of its own view model for about two seconds. */
    @Test fun theCompassInViewShowsASavedResultForItsTimeThenClearsIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val saved = pressAndAwaitSaved(vm)
            val shownFor = msUntilIdle(vm)
            assertTrue("shown for $shownFor ms", shownFor in 1_500..4_500)
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
        }
    }

    /**
     * The press comes with the app's screen not in view. Whatever the save ends in (the system gives an app that
     * is not on screen no location, so it is likely "No GPS fix" after the 20 seconds) waits, and is shown for its
     * full time once the compass is in view again.
     */
    @Test fun aResultThatArrivesWhileTheCompassIsNotInViewWaitsForIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(60_000) { vm.logHere().join() }
            val result = vm.logStatus.value
            val showMs = logStatusShowMs(result) ?: throw AssertionError("no result: $result")
            val until = SystemClock.elapsedRealtime() + showMs + 3_000
            while (SystemClock.elapsedRealtime() < until) {
                assertEquals(result, vm.logStatus.value)
                delay(50)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            val shownFor = msUntilIdle(vm)
            assertTrue("$result shown for $shownFor ms", shownFor in (showMs - 500)..(showMs + 2_500))
        }
    }

    /**
     * Real location updates run before each press, so fixes from before it exist and the location service has a
     * recent one to hand to a new request. Each press comes straight after such a fix. What is saved must be newer.
     */
    @Test fun aFixFromBeforeThePressIsNeverSaved() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            val earlier = CopyOnWriteArrayList<Fix>()
            val running = launch(Dispatchers.Main) { LocationSource(app).updates(1_000).collect { earlier += it } }
            repeat(3) {
                val had = earlier.size
                withTimeout(60_000) { while (earlier.size < had + 2) delay(10) }
                val newestBefore = earlier.last().elapsedMs
                val pressedAt = SystemClock.elapsedRealtime()
                val saved = pressAndAwaitSaved(vm)
                assertTrue("the press came ${pressedAt - newestBefore} ms after a fix", newestBefore <= pressedAt)
                assertTrue(
                    "saved a fix from ${pressedAt - saved.fixElapsedMs} ms before the press",
                    saved.fixElapsedMs >= pressedAt,
                )
            }
            running.cancel()
            assertEquals(3, dao.observeAll().first().size)
        }
    }

    /** Whether the system's sensor service lists a listener of the app's heading source as an active connection. */
    private fun headingSensorRegistered(): Boolean =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys sensorservice"),
        ).use { it.readBytes().decodeToString() }.lineSequence().any { "sensors.HeadingSource" in it && "WakeLockRefCount" in it }

    @Test fun theHeadingSensorRunsOnlyWhileItIsWantedAndStopsTwoSecondsAfter() = runBlocking {
        val vm = viewModel()
        // Not asked for: nothing is registered and there is no reading.
        delay(500)
        assertFalse(headingSensorRegistered())
        assertNull(vm.heading.value)
        assertNull(vm.lastAccuracy.value)

        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        val reading = withTimeout(10_000) { vm.heading.first { it != null } }!!
        assertTrue(headingSensorRegistered())
        assertTrue("heading ${reading.magneticDeg}", reading.magneticDeg >= 0f && reading.magneticDeg < 360f)

        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        // It keeps running through the stop delay, so a quick change of screen does not stop and start it.
        delay(1_000)
        assertTrue(headingSensorRegistered())
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        delay(2_500)
        assertTrue(headingSensorRegistered())

        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        delay(3_500)
        assertFalse(headingSensorRegistered())
        // The last reading stays for the screens to show.
        assertNotNull(vm.heading.value)
    }

    @Test fun theLastAccuracyOutlivesTheSensor() = runBlocking {
        val vm = viewModel()
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        // The magnetometer's accuracy comes with its own first event, a little after the first heading.
        val accuracy = withTimeout(15_000) { vm.lastAccuracy.first { it != null } }!!
        assertTrue("accuracy $accuracy", accuracy in 0..3)
        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        delay(3_500)
        assertFalse(headingSensorRegistered())
        assertEquals(accuracy, vm.lastAccuracy.value)
    }

    /**
     * The first reading that arrives after the sensor is stopped and started again has an accuracy and a field
     * strength. The view model's flow first gives the reading from before the stop again, so that one is set aside
     * and the test waits for the next, which a new sensor event has made.
     *
     * What this shows on an emulator: a restart loses neither value. It does not show that the view model's
     * carry-over is what keeps them: on the API 35 emulator this test passed with the carry-over switched off as
     * well, because there the first new reading already came with both values. The gap the carry-over closes
     * (headings that arrive before the magnetometer's first event) is tested as a rule in NavigationTest
     * (carriedOver) and would make this test fail on a device that has it.
     */
    @Test fun theFirstNewReadingAfterARestartHasAnAccuracyAndAFieldStrength() = runBlocking {
        val vm = viewModel()
        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        withTimeout(15_000) { vm.heading.first { it?.accuracy != null && it.fieldMicroTesla != null } }
        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
        delay(3_500)
        assertFalse(headingSensorRegistered())
        val beforeTheStop = vm.heading.value
        assertNotNull(beforeTheStop)

        withContext(Dispatchers.Main) { vm.setHeadingWanted(true) }
        // A reading that is not the one from before the stop: the flow holds one value and gives a new one only
        // when it differs, so this is a reading the restarted sensor produced.
        val fresh = withTimeout(20_000) { vm.heading.first { it != null && it != beforeTheStop } }!!
        assertTrue(headingSensorRegistered())
        assertNotNull("the first new reading has no accuracy", fresh.accuracy)
        assertNotNull("the first new reading has no field strength", fresh.fieldMicroTesla)
        withContext(Dispatchers.Main) { vm.setHeadingWanted(false) }
    }

    // The breadcrumb trail. The tests that drop or reach a crumb need the feed running and the app on screen.

    private fun crumbAt(point: GeoPoint, t: Long = 1) = Crumb(lat = point.lat, lon = point.lon, accuracyM = 5f, createdAt = t)
    private val farNorth = GeoPoint(43.7067, -72.2896)

    private suspend fun trailOf(vm: CompassViewModel, wanted: (TrailUi) -> Boolean): TrailUi =
        withTimeout(10_000) { vm.nav.first { it.loaded && wanted(it.trail) } }.trail

    @Test fun doublePressesStartATrailRetraceItAndReturnToLayingAndTheModeIsStored() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        assertEquals(TrailUi(), trailOf(vm) { true })
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailUi(TrailMode.LAYING, 0), trailOf(vm) { it.mode == TrailMode.LAYING })
        assertEquals(TrailMode.LAYING, settings.settings.first().trailMode)

        val older = crumbDao.insert(crumbAt(farNorth)); val newest = crumbDao.insert(crumbAt(hanover.point, 2))
        assertEquals(2, trailOf(vm) { it.count == 2 }.count)
        withTimeout(10_000) { vm.trailDoublePress().join() }
        val retracing = trailOf(vm) { it.mode == TrailMode.RETRACING }
        assertEquals(crumbTargetId(newest), retracing.current?.id)
        assertEquals("Crumb 2", retracing.current?.name)
        assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
        // A view model made afresh, as after the app was closed, finds the trail as it was left.
        val later = trailOf(viewModel()) { it.count == 2 }
        assertEquals(TrailMode.RETRACING, later.mode); assertEquals(crumbTargetId(newest), later.current?.id)

        withTimeout(10_000) { vm.trailDoublePress().join() }
        val laying = trailOf(vm) { it.mode == TrailMode.LAYING }
        assertNull(laying.current); assertEquals(2, laying.count)
        assertEquals(listOf(newest, older), crumbDao.all().map { it.id })
    }

    @Test fun aDoublePressOnAnEmptyTrailEndsIt() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailMode.LAYING, trailOf(vm) { it.mode == TrailMode.LAYING }.mode)
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailUi(), trailOf(vm) { it.mode == TrailMode.OFF })
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        // Nothing was completed, so nothing says so.
        assertEquals(LogStatus.Idle, vm.logStatus.value)
    }

    @Test fun withTheSettingOffADoublePressDoesNothingAndSwitchingItOffEndsTheModeButKeepsTheCrumbs() = runBlocking {
        val vm = viewModel()
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        assertEquals(TrailUi(), trailOf(vm) { true })

        settings.setBreadcrumbTrail(true)
        val id = crumbDao.insert(crumbAt(farNorth))
        withTimeout(10_000) { vm.trailDoublePress().join(); vm.trailDoublePress().join() }
        assertEquals(TrailMode.RETRACING, trailOf(vm) { it.mode == TrailMode.RETRACING }.mode)
        withTimeout(10_000) { vm.setBreadcrumbTrail(false).join() }
        val off = withTimeout(10_000) { vm.nav.first { !it.breadcrumbTrail } }
        assertEquals(TrailMode.OFF, off.trail.mode); assertNull(off.trail.current); assertEquals(1, off.trail.count)
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        assertEquals(listOf(id), crumbDao.all().map { it.id })
        // On again, the trail is not taken up by itself.
        withTimeout(10_000) { vm.setBreadcrumbTrail(true).join() }
        assertEquals(TrailMode.OFF, withTimeout(10_000) { vm.nav.first { it.breadcrumbTrail } }.trail.mode)
    }

    @Test fun clearingTheTrailRemovesEveryCrumbEndsTheTrailAndLeavesTheWaypoints() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        val w = vm.logWaypoint(hanover)
        repeat(4) { crumbDao.insert(crumbAt(GeoPoint(43.70 + it / 1000.0, -72.2896), it.toLong())) }
        withTimeout(10_000) { vm.trailDoublePress().join() }
        assertEquals(4, trailOf(vm) { it.mode == TrailMode.LAYING && it.count == 4 }.count)
        withTimeout(10_000) { vm.clearTrail().join() }
        assertEquals(TrailUi(), trailOf(vm) { it.count == 0 })
        assertTrue(crumbDao.all().isEmpty())
        assertEquals(TrailMode.OFF, settings.settings.first().trailMode)
        assertEquals(listOf(w.id), dao.observeAll().first().map { it.id })
    }

    @Test fun whileRetracingNoWaypointHasTheFocusItCannotBeMovedAndItReturnsAfterwards() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val vm = viewModel()
        val first = vm.logWaypoint(hanover)
        val second = vm.logWaypoint(hanover.copy(point = farNorth))
        settings.setFocused(first.id)
        val crumb = crumbDao.insert(crumbAt(GeoPoint(43.6950, -72.3000)))
        withTimeout(10_000) { vm.trailDoublePress().join(); vm.trailDoublePress().join() }
        val nav = withTimeout(10_000) { vm.nav.first { it.trail.mode == TrailMode.RETRACING } }
        // The waypoints are still targets, none of them focused; the crumb stands where the focused target does.
        assertEquals(listOf(second.id, first.id), nav.targets.map { it.id })
        assertTrue(nav.targets.none { it.focused })
        assertEquals(crumbTargetId(crumb), nav.focused?.id)
        assertTrue(nav.focused!!.isCrumb)
        withTimeout(10_000) { vm.moveFocus(1).join() }
        assertEquals(first.id, settings.settings.first().focusedId)

        withTimeout(10_000) { vm.trailDoublePress().join() }
        val after = withTimeout(10_000) { vm.nav.first { it.trail.mode == TrailMode.LAYING } }
        assertEquals(first.id, after.focused?.id)
        assertFalse(after.focused!!.isCrumb)
        withTimeout(10_000) { vm.moveFocus(1).join() }
        assertEquals(second.id, settings.settings.first().focusedId)
    }

    @Test fun aSinglePressWhileLayingDropsACrumbAndLogHereStillSavesAWaypoint() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            withTimeout(10_000) { vm.trailDoublePress().join() }
            withTimeout(60_000) { vm.sidePress().join() }
            val dropped = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
            assertEquals("Crumb 1", dropped.name)
            val crumbs = crumbDao.all()
            assertEquals(1, crumbs.size)
            assertTrue("off by ${distanceMeters(hanover.point, crumbs[0].toPoint().point)} m", distanceMeters(hanover.point, crumbs[0].toPoint().point) < 100.0)
            assertEquals(dropped.accuracyM, crumbs[0].accuracyM, 0f)
            assertTrue(dao.observeAll().first().isEmpty())

            withTimeout(60_000) { vm.sidePress().join() }
            assertEquals("Crumb 2", (vm.logStatus.value as LogStatus.Saved).name)
            assertEquals(2, crumbDao.all().size)
            assertTrue(crumbDao.all()[0].id > crumbDao.all()[1].id)
            assertTrue(dao.observeAll().first().isEmpty())

            // `Log here` saves a named waypoint as always, and no crumb.
            val saved = pressAndAwaitSaved(vm)
            assertTrue(saved.name, Regex("Waypoint [1-9][0-9]*").matches(saved.name))
            assertEquals(listOf(saved.name), dao.observeAll().first().map { it.name })
            assertEquals(2, crumbDao.all().size)
        }
    }

    @Test fun aSinglePressSavesAWaypointWhenNoTrailIsBeingLaid() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            // The setting is off; then on with no trail started; then retracing.
            for (prepare in listOf<suspend () -> Unit>(
                { },
                { settings.setBreadcrumbTrail(true) },
                { crumbDao.insert(crumbAt(farNorth)); settings.setTrailMode(TrailMode.RETRACING) },
            )) {
                prepare()
                val before = dao.observeAll().first().size
                withTimeout(60_000) { vm.sidePress().join() }
                val saved = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
                assertTrue(saved.name, saved.name.startsWith("Waypoint "))
                assertEquals(before + 1, dao.observeAll().first().size)
            }
            assertEquals(1, crumbDao.all().size)
        }
    }

    @Test fun aCrumbWhoseFixArrivesAfterTheTrailStoppedBeingLaidIsNotStored() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            withTimeout(10_000) { vm.trailDoublePress().join() }
            // The press is taken while laying; the trail is switched off before a fix taken since the press can arrive.
            val press = withContext(Dispatchers.Main) { vm.sidePress().also { assertTrue(vm.logStatus.value is LogStatus.Saving) } }
            settings.setBreadcrumbTrail(false)
            withTimeout(60_000) { press.join() }
            assertEquals(LogStatus.Idle, vm.logStatus.value)
            assertTrue(crumbDao.all().isEmpty())
            assertTrue(dao.observeAll().first().isEmpty())
        }
    }

    // The three tests below use the view model of the real activity, with its compass on screen: every view model
    // that gets fixes while a trail is retraced consumes crumbs, and there should be one of them.

    @Test fun retracingConsumesTheCrumbTheWearerStandsOnOnceAndGoesOnToTheNext() = runBlocking {
        settings.setBreadcrumbTrail(true)
        // Oldest first: one far to the north, then two at the fed position, a meter or two apart.
        val far = crumbDao.insert(crumbAt(farNorth))
        val near = crumbDao.insert(crumbAt(GeoPoint(43.70221, -72.2896), 2))
        val nearest = crumbDao.insert(crumbAt(hanover.point, 3))
        settings.setTrailMode(TrailMode.RETRACING)
        // Watched from before the app is on screen, since the first fix can come within a moment of that.
        val seen = CopyOnWriteArrayList<List<Long>>()
        val watching = launch(Dispatchers.Main) { crumbDao.observeAll().collect { seen += it.map { c -> c.id } } }
        withTimeout(10_000) { while (seen.isEmpty()) delay(10) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val left = withTimeout(60_000) { vm.nav.first { it.loaded && it.trail.count == 1 } }
            // Each of the two near ones went by itself, the newest first, and the far one is now walked to.
            withTimeout(10_000) { while (seen.last() != listOf(far)) delay(10) }
            assertEquals(listOf(listOf(nearest, near, far), listOf(near, far), listOf(far)), seen.toList())
            assertEquals(TrailMode.RETRACING, left.trail.mode)
            assertEquals(crumbTargetId(far), left.focused?.id)
            assertEquals("Crumb 1", left.focused?.name)
            // Standing still for several more fixes consumes nothing more.
            delay(12_000)
            watching.cancel()
            assertEquals(listOf(far), crumbDao.all().map { it.id })
            assertEquals(TrailMode.RETRACING, settings.settings.first().trailMode)
            val now = withTimeout(10_000) { vm.nav.first { it.focused?.distanceM != null } }
            assertEquals(distanceMeters(hanover.point, farNorth), now.focused!!.distanceM!!, 60.0)
            assertFalse(now.focused!!.arrived)
            assertEquals(LogStatus.Idle, vm.logStatus.value)
        }
    }

    @Test fun aCrumbFurtherBackIsNotConsumedWhileTheNewestIsStillAhead() = runBlocking {
        settings.setBreadcrumbTrail(true)
        val here = crumbDao.insert(crumbAt(hanover.point))
        val ahead = crumbDao.insert(crumbAt(farNorth, 2))
        settings.setTrailMode(TrailMode.RETRACING)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            // Fixes are arriving: the crumb walked to has a distance. The wearer stands on the older crumb.
            val nav = withTimeout(60_000) { vm.nav.first { it.focused?.distanceM != null } }
            assertEquals(crumbTargetId(ahead), nav.focused?.id)
            delay(12_000)
            assertEquals(listOf(ahead, here), crumbDao.all().map { it.id })
        }
    }

    @Test fun arrivingAtTheLastCrumbCompletesTheTrailSaysSoAndGivesTheFocusBack() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            val w = vm.logWaypoint(hanover.copy(point = farNorth))
            val statuses = CopyOnWriteArrayList<LogStatus>()
            val watching = launch(Dispatchers.Main) { vm.logStatus.collect { statuses += it } }
            val counts = CopyOnWriteArrayList<Int>()
            val counting = launch(Dispatchers.Main) { crumbDao.observeAll().collect { counts += it.size } }
            withTimeout(10_000) { while (counts.isEmpty()) delay(10) }
            crumbDao.insert(crumbAt(hanover.point))
            settings.setTrailMode(TrailMode.RETRACING)
            // The crumb is there, and then it is not.
            withTimeout(60_000) { while (counts.toList() != listOf(0, 1, 0)) delay(10) }
            counting.cancel()
            val done = withTimeout(10_000) { vm.nav.first { it.trail.count == 0 && it.trail.mode == TrailMode.OFF && it.focused != null } }
            assertTrue(crumbDao.all().isEmpty())
            // The stored mode is written straight after the crumb is deleted; the trail counts as off from the deletion.
            withTimeout(10_000) { while (settings.settings.first().trailMode != TrailMode.OFF) delay(10) }
            // The waypoint is untouched and has the focus again.
            assertEquals(listOf(w.id), dao.observeAll().first().map { it.id })
            assertEquals(w.id, done.focused?.id)
            assertNull(done.trail.current)
            // `Trail complete` is shown by the compass, which is in view, for about two seconds, and then cleared.
            withTimeout(10_000) { while (LogStatus.TrailComplete !in statuses) delay(10) }
            val shownFor = msUntilIdle(vm)
            watching.cancel()
            assertTrue("shown for $shownFor ms", shownFor in 1_000..4_500)
            assertEquals(listOf(LogStatus.Idle, LogStatus.TrailComplete, LogStatus.Idle), statuses.toList())
        }
    }

    @Test fun aTrailBringsPositionFixesOnlyWhileTheCompassIsOnScreen() = runBlocking {
        settings.setBreadcrumbTrail(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            // A view model of the test's own: no screen tells it where the compass is.
            val vm = viewModel()
            crumbDao.insert(crumbAt(farNorth))
            settings.setTrailMode(TrailMode.LAYING)
            withTimeout(10_000) { vm.nav.first { it.loaded && it.trail.mode == TrailMode.LAYING } }
            // No waypoint is a target and the compass is not on screen: no fix, watched for a few seconds.
            withTimeoutOrNull(4_000) { vm.nav.collect { assertNull(it.fixElapsedMs); assertTrue(it.trail.tickAngles.isEmpty()) } }
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
            val live = withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null } }
            // The crumb to the north is a tick at the top of a dial that shows true north.
            assertEquals(listOf(0f), withTimeout(10_000) { vm.nav.first { it.declination != null && it.trail.tickAngles.isNotEmpty() } }.trail.tickAngles)
            assertNotNull(live.fixElapsedMs)
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(false) }
            val gone = withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            assertTrue(gone.trail.tickAngles.isEmpty())
            // And not at all once the trail is off.
            withContext(Dispatchers.Main) { vm.setCompassOnScreen(true) }
            withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null } }
            settings.setTrailMode(TrailMode.OFF)
            withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            Unit
        }
    }

    @Test fun aTargetTheWearerStandsOnHasArrivedAndHasNoBearing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = viewModel()
            vm.logWaypoint(hanover)
            val nav = withTimeout(60_000) { vm.nav.first { it.focused?.distanceM != null } }
            assertTrue("${nav.focused!!.distanceM} m", nav.focused!!.arrived)
            assertNull(nav.focused!!.dialAngleDeg)
        }
    }
}
