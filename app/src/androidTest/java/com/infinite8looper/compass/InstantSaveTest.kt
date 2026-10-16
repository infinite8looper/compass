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
 * `geo fix` and `geo nmea` take no accuracy. The API 33, 34 and 35 images report every position at 5 m, so no more
 * accurate one comes from them; the API 37 image reports accuracies that vary (3.0 to 3.8 m seen), so there the
 * view model's own updates do refine. A more accurate position than any emulator gives (well under 1 m) is handed
 * to the view model here, through the call its own location updates make for every position during the ten seconds
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

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().readText()

    /** Location off for 11 s and on again: no position from any source is 10 s old or newer at the press that follows. */
    private suspend fun noPositionInHand() {
        shell("settings put secure location_mode 0")
        try { delay(11_000) } finally { shell("settings put secure location_mode 3") }
    }

    /** The GPS provider's request as the system last set it, from `dumpsys location`: its interval and whose it is. */
    private fun gpsRequest(): String =
        shell("dumpsys location").lines().lastOrNull { "gps provider request = " in it }?.substringAfter("gps provider request = ")?.trim() ?: "none"

    private fun fixNow(at: GeoPoint, accuracyM: Float) = Fix(at, accuracyM, 160f, SystemClock.elapsedRealtime())

    @Test fun aPositionInHandIsWrittenAtOnceWithItsOwnAccuracy() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val seen = CopyOnWriteArrayList<LogStatus>()
            collectors += CoroutineScope(Dispatchers.Main).launch { vm.logStatus.collect { seen += it } }
            val inHandAt = justAfterAPosition(vm)
            val inHandAccuracy = vm.nav.value.fixAccuracyM!!
            val pressedAt = SystemClock.elapsedRealtime()
            val (atOnce, job) = press(vm)
            val saved = savedNow(vm, job)
            val tookMs = SystemClock.elapsedRealtime() - pressedAt
            Log.i("InstantSaveTest", "saved from the position in hand $tookMs ms after the press")
            // The status carries the accuracy in hand from the press on: no wait for a position without one.
            assertEquals(LogStatus.Saving(inHandAccuracy), atOnce)
            assertTrue("$seen", seen.none { it == LogStatus.Saving(null) })
            // What was saved is the position held at the press, from before it, which v2 never saved.
            assertEquals(inHandAt, saved.fixElapsedMs)
            assertTrue("saved a position from ${pressedAt - saved.fixElapsedMs} ms before the press", saved.fixElapsedMs < pressedAt)
            assertTrue("took $tookMs ms", tookMs < 2_000)
            val row = dao.observeAll().first().single()
            assertEquals(saved.name, row.name)
            assertEquals(inHandAccuracy, saved.accuracyM, 0f)
            // Written with the accuracy in hand, or a better one where the emulator's own updates have refined it.
            assertTrue("${row.accuracyM}", row.accuracyM <= inHandAccuracy)
            assertEquals(43.7022, row.lat, 0.001); assertEquals(-72.2896, row.lon, 0.001)
        }
    }

    /** With the position in hand more than ten seconds old the save is the save of v2: `Saving…`, then one taken after the press. */
    @Test fun aPositionInHandOlderThanTenSecondsIsWaitedPast() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            withContext(Dispatchers.Main) { vm.setScreensWantLocation(false) }
            withTimeout(10_000) { vm.nav.first { it.fixElapsedMs == null } }
            // No position from any source for over 10 s: the view model's own and the location provider's last known
            // position are both older than that at the press.
            noPositionInHand()
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
            vm.offerRefinement(fixNow(better, 0.5f))
            val once = dao.get(id)!!
            assertEquals(better.lat, once.lat, 0.0); assertEquals(better.lon, once.lon, 0.0); assertEquals(0.5f, once.accuracyM, 0f)
            assertEquals(saved.name, once.name)
            // The press's status says what the save wrote; it is not changed under a result that is showing.
            assertEquals(saved, vm.logStatus.value)
            // Once more, and a less accurate one changes nothing.
            vm.offerRefinement(fixNow(best, 0.3f))
            vm.offerRefinement(fixNow(better, 0.4f))
            val twice = dao.get(id)!!
            assertEquals(best.lat, twice.lat, 0.0); assertEquals(best.lon, twice.lon, 0.0); assertEquals(0.3f, twice.accuracyM, 0f)
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
            vm.offerRefinement(fixNow(better, 0.5f))
            val row = dao.get(id)!!
            assertEquals("Heronwick", row.name)
            assertEquals(200f, row.hue, 0f)
            assertEquals(better.lat, row.lat, 0.0); assertEquals(0.5f, row.accuracyM, 0f)
        }
    }

    /** Deleted during the ten seconds: it stays deleted, and nothing of it is left in the database's files. */
    @Test fun aDeleteDuringTheTenSecondsStaysDeletedAndLeavesNothingInTheFiles() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            savedNow(vm, press(vm).second)
            val first = dao.observeAll().first().single()
            vm.offerRefinement(fixNow(better, 0.5f))
            assertEquals(better.lat, dao.get(first.id)!!.lat, 0.0)
            vm.delete(first.id).join()
            vm.offerRefinement(fixNow(best, 0.3f))
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
            vm.offerRefinement(fixNow(better, 0.5f))
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
            vm.offerRefinement(fixNow(better, 0.5f))
            val rows = dao.observeAll().first()
            assertEquals(setOf(a.name, b.name), rows.map { it.name }.toSet())
            for (r in rows) { assertEquals(r.name, better.lat, r.lat, 0.0); assertEquals(r.name, 0.5f, r.accuracyM, 0f) }
            assertEquals(b, vm.logStatus.value)
        }
    }

    @Test fun nothingIsRefinedMoreThanTenSecondsAfterThePress() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val pressedAt = SystemClock.elapsedRealtime()
            savedNow(vm, press(vm).second)
            delay(pressedAt + REFINE_WITHIN_MS + 500 - SystemClock.elapsedRealtime())
            // Read after the ten seconds: within them the emulator's own updates may have refined it.
            val row = dao.observeAll().first().single()
            vm.offerRefinement(fixNow(better, 0.1f))
            assertEquals(row, dao.get(row.id))
        }
    }

    /** A crumb follows the same rule; one consumed (deleted) during the ten seconds does not come back. */
    @Test fun aCrumbIsDroppedAtOnceRefinedAndNotBroughtBackOnceDeleted() = runBlocking {
        settings.setBreadcrumbTrail(true)
        settings.setTrailMode(TrailMode.LAYING)
        ActivityScenario.launch(MainActivity::class.java).use {
            val vm = holdingAPosition()
            val inHandAccuracy = vm.nav.value.fixAccuracyM!!
            val (atOnce, job) = press(vm, sidePress = true)
            assertEquals(LogStatus.Saving(inHandAccuracy), atOnce)
            val saved = savedNow(vm, job)
            assertEquals("Crumb 1", saved.name)
            assertEquals(inHandAccuracy, saved.accuracyM, 0f)
            val crumb = crumbDao.all().single()
            assertTrue("${crumb.accuracyM}", crumb.accuracyM <= inHandAccuracy)
            vm.offerRefinement(fixNow(better, 0.5f))
            val refined = crumbDao.get(crumb.id)!!
            assertEquals(better.lat, refined.lat, 0.0); assertEquals(0.5f, refined.accuracyM, 0f)
            crumbDao.delete(crumb.id)
            vm.offerRefinement(fixNow(best, 0.3f))
            assertNull(crumbDao.get(crumb.id))
            assertTrue(crumbDao.all().isEmpty())
        }
    }

    /**
     * A view model that holds no position of its own (no screen has told it to run location) takes the location
     * provider's last known position, which the app's own activity keeps fresh on the compass, as in hand.
     */
    @Test fun theLocationProvidersLastKnownPositionIsInHand() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            // The activity's compass runs location, so the provider's last known position is a second or two old.
            delay(8_000)
            val vm = withContext(Dispatchers.Main) { CompassViewModel(app) }
            assertNull(vm.nav.value.fixElapsedMs)
            val seen = CopyOnWriteArrayList<LogStatus>()
            collectors += CoroutineScope(Dispatchers.Main).launch { vm.logStatus.collect { seen += it } }
            val pressedAt = SystemClock.elapsedRealtime()
            val saved = savedNow(vm, press(vm).second)
            val tookMs = SystemClock.elapsedRealtime() - pressedAt
            Log.i("InstantSaveTest", "saved from the last known position $tookMs ms after the press")
            // At most the wait for the provider's answer (CompassViewModel.LAST_KNOWN_WITHIN_MS, 500 ms) and the writes of a
            // save (allowed 300 ms on an emulator).
            assertTrue("written $tookMs ms after the press", tookMs <= 500 + 300)
            assertTrue("a position ${pressedAt - saved.fixElapsedMs} ms old at the press", saved.fixElapsedMs < pressedAt && pressedAt - saved.fixElapsedMs <= 10_000)
            // Saving without an accuracy until the last known position was read, then with its accuracy.
            assertTrue("$seen", LogStatus.Saving(saved.accuracyM) in seen)
        }
    }

    /**
     * The refinement's own listening, not a position handed to it: after a save from a position in hand the view
     * model asks for a position every second (the GPS provider's request, as `dumpsys location` shows it, is the
     * app's at 1 s), and after the ten seconds it asks only every 5 s again. On the API 37 image, whose positions vary
     * in accuracy, a more accurate one that comes in the ten seconds replaces the saved one; the others report every
     * position at 5 m, where none can be more accurate.
     */
    @Test fun afterASaveFromAPositionInHandTheViewModelListensEverySecondForTenSeconds() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            // An earlier test's save may still be listening: its ten seconds are let pass.
            delay(REFINE_WITHIN_MS + 1_000)
            val vm = holdingAPosition()
            var refined = false
            repeat(if (android.os.Build.VERSION.SDK_INT >= 37) 6 else 1) {
                if (refined) return@repeat
                justAfterAPosition(vm)
                val pressedAt = SystemClock.elapsedRealtime()
                val saved = savedNow(vm, press(vm).second)
                val id = dao.observeAll().first().maxBy { it.createdAt }.id
                delay(2_500)
                val during = gpsRequest()
                assertTrue("during the ten seconds: $during", "@+1s0ms" in during && "com.infinite8looper.compass" in during)
                // Watched until the ten seconds are over: does a position from the emulator replace the saved one?
                while (SystemClock.elapsedRealtime() < pressedAt + REFINE_WITHIN_MS + 500) {
                    if (dao.get(id)!!.accuracyM < saved.accuracyM) refined = true
                    delay(100)
                }
                delay(2_500)
                val after = gpsRequest()
                assertTrue("after the ten seconds: $after", "@+1s0ms" !in after && "com.infinite8looper.compass" in after)
            }
            if (android.os.Build.VERSION.SDK_INT >= 37) assertTrue("no save in six was refined by a position from the emulator", refined)
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
            vm.offerRefinement(fixNow(better, 0.5f))
            // The line is written when the ten seconds are over.
            delay(pressedAt + REFINE_WITHIN_MS + 1_500 - SystemClock.elapsedRealtime())
            val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "raw", "-s", "InstantSaveTest:I", "CompassSave:I"))
                .inputStream.bufferedReader().readLines()
            val lines = log.dropWhile { it != marker }.drop(1).filter { it.startsWith("save:") }
            assertEquals("$log", 1, lines.size)
            val line = lines.single()
            assertTrue(line, Regex("save: written [0-9]+ ms after the press, position in hand [0-9]+ ms old at the press, accuracy [0-9]+\\.[0-9] m, refined [0-9]+ time\\(s\\) to 0\\.5 m").matches(line))
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
