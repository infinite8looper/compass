package com.infinite8looper.compass

import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.REFINE_WITHIN_MS
import com.infinite8looper.compass.nav.TrailMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A save with a position in hand (v3 spec, "Saving"), on the real view model, the app's own database and settings
 * file, with positions from the emulator's geo console: needs `scripts/geo-feed.sh -72.2896 43.7022` running and the
 * app on screen, as in CompassViewModelTest.
 *
 * The emulator's GNSS reports every position with an accuracy of 5 m (`geo fix` and `geo nmea` take no accuracy;
 * seen on the API 35 image), so no more accurate position ever comes from it. A more accurate one is handed to the
 * view model here, through the call its own location updates make for every position during the ten seconds
 * ([CompassViewModel.offerRefinement]), as other tests hand [CompassViewModel.logWaypoint] a position.
 */
@RunWith(AndroidJUnit4::class)
class InstantSaveTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = WaypointDatabase.get(app).waypoints()
    private val crumbDao = WaypointDatabase.get(app).crumbs()
    private val settings = SettingsStore.get(app)

    // Positions near the fed one, not whole numbers and no two alike in their stored bytes.
    private val better = GeoPoint(43.70231946613817, -72.28947208114311)
    private val best = GeoPoint(43.70215588412095, -72.28969903371446)

    private fun reset() = runBlocking {
        dao.observeAll().first().forEach { dao.delete(it.id) }
        settings.setFocused(null)
        crumbDao.clear()
        settings.setTrailMode(TrailMode.OFF)
        settings.setBreadcrumbTrail(false)
    }
    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private val collectors = ArrayList<Job>()
    @After fun stopCollecting() { collectors.forEach { it.cancel() } }

    /**
     * A view model of the test's own, told that the compass or the waypoints screen is on screen, so its location
     * runs: returned once it holds a position from the geo console.
     */
    private suspend fun holdingAPosition(): CompassViewModel {
        val vm = withContext(Dispatchers.Main) { CompassViewModel(app).also { it.setScreensWantLocation(true) } }
        collectors += CoroutineScope(Dispatchers.Main).launch { vm.nav.collect { } }
        withTimeout(60_000) { vm.nav.first { it.fixElapsedMs != null } }
        return vm
    }

    /** Waits until a new position has just arrived, so the next press comes with one in hand that is before it. */
    private suspend fun justAfterAPosition(vm: CompassViewModel): Long {
        val had = vm.nav.value.fixElapsedMs
        return withTimeout(30_000) { vm.nav.first { it.fixElapsedMs != null && it.fixElapsedMs != had } }.fixElapsedMs!!
    }

    /** Presses `Log here` (the view model's call), and returns the status right after the call and the job. */
    private suspend fun press(vm: CompassViewModel, sidePress: Boolean = false): Pair<LogStatus, Job> = withContext(Dispatchers.Main) {
        val job = if (sidePress) vm.sidePress() else vm.logHere()
        vm.logStatus.value to job
    }

    private suspend fun savedNow(vm: CompassViewModel, job: Job): LogStatus.Saved {
        withTimeout(10_000) { job.join() }
        return vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
    }

    private fun fixNow(at: GeoPoint, accuracyM: Float) = Fix(at, accuracyM, 160f, SystemClock.elapsedRealtime())

    @Test fun aPositionInHandIsWrittenAtOnceWithItsOwnAccuracy() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val seen = CopyOnWriteArrayList<LogStatus>()
            collectors += CoroutineScope(Dispatchers.Main).launch { vm.logStatus.collect { seen += it } }
            val inHandAt = justAfterAPosition(vm)
            val pressedAt = SystemClock.elapsedRealtime()
            val (atOnce, job) = press(vm)
            val saved = savedNow(vm, job)
            val tookMs = SystemClock.elapsedRealtime() - pressedAt
            Log.i("InstantSaveTest", "saved from the position in hand $tookMs ms after the press")
            // The status carries the accuracy in hand from the press on: no wait for a position without one.
            assertEquals(LogStatus.Saving(5f), atOnce)
            assertTrue("$seen", seen.none { it == LogStatus.Saving(null) })
            // What was saved is the position held at the press, from before it, which v2 never saved.
            assertEquals(inHandAt, saved.fixElapsedMs)
            assertTrue("saved a position from ${pressedAt - saved.fixElapsedMs} ms before the press", saved.fixElapsedMs < pressedAt)
            assertTrue("took $tookMs ms", tookMs < 2_000)
            val row = dao.observeAll().first().single()
            assertEquals(saved.name, row.name)
            assertEquals(5f, row.accuracyM, 0f)
            assertEquals(5f, saved.accuracyM, 0f)
            assertEquals(43.7022, row.lat, 0.001); assertEquals(-72.2896, row.lon, 0.001)
        }
    }

    /** With the position in hand more than ten seconds old the save is the save of v2: `Saving…`, then one taken after the press. */
    @Test fun aPositionInHandOlderThanTenSecondsIsWaitedPast() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            withContext(Dispatchers.Main) { vm.setScreensWantLocation(false) }
            withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            delay(11_000)
            val pressedAt = SystemClock.elapsedRealtime()
            val (atOnce, job) = press(vm)
            assertEquals(LogStatus.Saving(null), atOnce)
            withTimeout(60_000) { job.join() }
            val saved = vm.logStatus.value as? LogStatus.Saved ?: throw AssertionError("not saved: ${vm.logStatus.value}")
            assertTrue("saved a position from ${pressedAt - saved.fixElapsedMs} ms before the press", saved.fixElapsedMs >= pressedAt)
        }
    }

    @Test fun aMoreAccuratePositionWithinTenSecondsReplacesThePositionInPlace() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val saved = savedNow(vm, press(vm).second)
            val id = dao.observeAll().first().single().id
            vm.offerRefinement(fixNow(better, 3f))
            val once = dao.get(id)!!
            assertEquals(better.lat, once.lat, 0.0); assertEquals(better.lon, once.lon, 0.0); assertEquals(3f, once.accuracyM, 0f)
            assertEquals(saved.name, once.name)
            // The status of the press, still showing, says what the row now holds.
            assertEquals(3f, (vm.logStatus.value as LogStatus.Saved).accuracyM, 0f)
            // Once more, and a less accurate one changes nothing.
            vm.offerRefinement(fixNow(best, 2f))
            vm.offerRefinement(fixNow(better, 2.5f))
            val twice = dao.get(id)!!
            assertEquals(best.lat, twice.lat, 0.0); assertEquals(best.lon, twice.lon, 0.0); assertEquals(2f, twice.accuracyM, 0f)
            assertEquals(1, dao.observeAll().first().size)
        }
    }

    @Test fun aRenameDuringTheTenSecondsStaysAndThePositionStillImproves() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            savedNow(vm, press(vm).second)
            val id = dao.observeAll().first().single().id
            vm.rename(id, "Heronwick").join()
            vm.setHue(id, 200f).join()
            vm.offerRefinement(fixNow(better, 3f))
            val row = dao.get(id)!!
            assertEquals("Heronwick", row.name)
            assertEquals(200f, row.hue, 0f)
            assertEquals(better.lat, row.lat, 0.0); assertEquals(3f, row.accuracyM, 0f)
        }
    }

    /** Deleted during the ten seconds: it stays deleted, and nothing of it is left in the database's files. */
    @Test fun aDeleteDuringTheTenSecondsStaysDeletedAndLeavesNothingInTheFiles() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            savedNow(vm, press(vm).second)
            val first = dao.observeAll().first().single()
            vm.offerRefinement(fixNow(better, 3f))
            assertEquals(better.lat, dao.get(first.id)!!.lat, 0.0)
            vm.delete(first.id).join()
            vm.offerRefinement(fixNow(best, 2f))
            delay(500)
            assertNull(dao.get(first.id))
            assertTrue(dao.observeAll().first().isEmpty())
            // Control: a row that stays is found by the same search.
            val keptAt = GeoPoint(43.70300112233441, -72.28800998877661)
            vm.logWaypoint(Fix(keptAt, 4f, 160f, SystemClock.elapsedRealtime()))
            val files = listOf("", "-journal", "-wal").map { File(app.getDatabasePath(WaypointDatabase.FILE_NAME).path + it) }.filter { it.exists() }
            fun found(v: Double) = files.sumOf { occurrences(it.readBytes(), v) }
            for (v in listOf(first.lat, first.lon, better.lat, better.lon, best.lat, best.lon)) assertEquals("the deleted waypoint's $v", 0, found(v))
            assertEquals(1, found(keptAt.lat)); assertEquals(1, found(keptAt.lon))
        }
    }

    /** Moved by any other writer: the refinement leaves the moved position as it is. */
    @Test fun aRowWhosePositionChangedIsNotRefined() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            savedNow(vm, press(vm).second)
            val row = dao.observeAll().first().single()
            dao.update(row.copy(lat = best.lat, lon = best.lon, accuracyM = 4f))
            vm.offerRefinement(fixNow(better, 3f))
            val after = dao.get(row.id)!!
            assertEquals(best.lat, after.lat, 0.0); assertEquals(4f, after.accuracyM, 0f)
        }
    }

    /** A second save during the first's ten seconds is a save of its own; each row is refined, and neither is merged. */
    @Test fun aSecondSaveDuringTheTenSecondsIsItsOwnRow() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val a = savedNow(vm, press(vm).second)
            val b = savedNow(vm, press(vm).second)
            assertNotEquals(a.name, b.name)
            assertEquals(2, vm.logPress.value)
            vm.offerRefinement(fixNow(better, 3f))
            val rows = dao.observeAll().first()
            assertEquals(setOf(a.name, b.name), rows.map { it.name }.toSet())
            for (r in rows) { assertEquals(r.name, better.lat, r.lat, 0.0); assertEquals(r.name, 3f, r.accuracyM, 0f) }
            assertEquals(b.copy(accuracyM = 3f, fixElapsedMs = (vm.logStatus.value as LogStatus.Saved).fixElapsedMs), vm.logStatus.value)
        }
    }

    @Test fun nothingIsRefinedMoreThanTenSecondsAfterThePress() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val pressedAt = SystemClock.elapsedRealtime()
            savedNow(vm, press(vm).second)
            val row = dao.observeAll().first().single()
            delay(pressedAt + REFINE_WITHIN_MS + 500 - SystemClock.elapsedRealtime())
            vm.offerRefinement(fixNow(better, 1f))
            assertEquals(row, dao.get(row.id))
        }
    }

    /** A crumb follows the same rule; one consumed (deleted) during the ten seconds does not come back. */
    @Test fun aCrumbIsDroppedAtOnceRefinedAndNotBroughtBackOnceDeleted() = runBlocking {
        settings.setBreadcrumbTrail(true)
        settings.setTrailMode(TrailMode.LAYING)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val (atOnce, job) = press(vm, sidePress = true)
            assertEquals(LogStatus.Saving(5f), atOnce)
            val saved = savedNow(vm, job)
            assertEquals("Crumb 1", saved.name)
            val crumb = crumbDao.all().single()
            assertEquals(5f, crumb.accuracyM, 0f)
            vm.offerRefinement(fixNow(better, 3f))
            val refined = crumbDao.get(crumb.id)!!
            assertEquals(better.lat, refined.lat, 0.0); assertEquals(3f, refined.accuracyM, 0f)
            crumbDao.delete(crumb.id)
            vm.offerRefinement(fixNow(best, 2f))
            assertNull(crumbDao.get(crumb.id))
            assertTrue(crumbDao.all().isEmpty())
        }
    }

    /** One line per save in a build with diagnostics, with the times and the accuracy and no coordinates. */
    @Test fun eachSaveLogsOneLineWithoutCoordinates() = runBlocking {
        assertTrue("this suite runs on a debug build", BuildConfig.DIAGNOSTICS)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            // A save of an earlier test logs its line when its own ten seconds are over: those are let pass first.
            delay(REFINE_WITHIN_MS + 1_500)
            val marker = "marker-" + SystemClock.elapsedRealtime()
            Log.i("InstantSaveTest", marker)
            val pressedAt = SystemClock.elapsedRealtime()
            savedNow(vm, press(vm).second)
            vm.offerRefinement(fixNow(better, 3f))
            // The line is written when the ten seconds are over.
            delay(pressedAt + REFINE_WITHIN_MS + 1_500 - SystemClock.elapsedRealtime())
            val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "raw", "-s", "InstantSaveTest:I", "CompassSave:I"))
                .inputStream.bufferedReader().readLines()
            val lines = log.dropWhile { it != marker }.drop(1).filter { it.startsWith("save:") }
            assertEquals("$log", 1, lines.size)
            val line = lines.single()
            assertTrue(line, Regex("save: written [0-9]+ ms after the press, position in hand [0-9]+ ms old at the press, accuracy 5\\.0 m, refined 1 time\\(s\\) to 3\\.0 m").matches(line))
            for (c in listOf("43.7", "72.2", "43,7", "72,2")) assertFalse(line, line.contains(c))
        }
    }

    /** How often the eight bytes of [value] occur in [bytes], in either byte order (as DeletedWaypointTest searches). */
    private fun occurrences(bytes: ByteArray, value: Double): Int =
        listOf(java.nio.ByteOrder.BIG_ENDIAN, java.nio.ByteOrder.LITTLE_ENDIAN).sumOf { order ->
            val needle = java.nio.ByteBuffer.allocate(8).order(order).putDouble(value).array()
            (0..bytes.size - needle.size).count { i -> needle.indices.all { j -> bytes[i + j] == needle[j] } }
        }
}
