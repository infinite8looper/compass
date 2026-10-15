package com.infinite8looper.compass

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect.Composition
import android.os.VibratorManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.VibrationRecord.constantOf
import com.infinite8looper.compass.VibrationRecord.playedOf
import com.infinite8looper.compass.VibrationRecord.primitivesOf
import com.infinite8looper.compass.VibrationRecord.startMsOf
import com.infinite8looper.compass.VibrationRecord.statusOf
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.SAVE_CONFIRM_AFTER_PRESS_MS
import com.infinite8looper.compass.ui.Feel
import com.infinite8looper.compass.ui.VibrationBranch
import com.infinite8looper.compass.ui.Vibrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's vibrations as the system records them ([VibrationRecord]), played by the real player on the real
 * activity's window. Where the watch's vibrator supports every primitive of a row (`areAllPrimitivesSupported`) the
 * row plays as those primitives; where it does not, as its haptic feedback constant (v3 spec, "Vibrations"). Which
 * branch ran is logged (tag DialVibrationsTest) for each row.
 */
@RunWith(AndroidJUnit4::class)
class DialVibrationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val dial = listOf(Feel.THIN, Feel.THICK, Feel.TARGET)

    /** No target, so no blind navigation pulse and no target's vibration plays while the rows are played. */
    @Before fun noTargets() = runBlocking {
        val dao = WaypointDatabase.get(app).waypoints()
        dao.observeAll().first().forEach { dao.delete(it.id) }
        SettingsStore.get(app).setFocused(null)
    }

    private val primitiveNames = mapOf(
        Composition.PRIMITIVE_TICK to "TICK", Composition.PRIMITIVE_THUD to "THUD", Composition.PRIMITIVE_CLICK to "CLICK",
    )

    private fun supportedOnThisWatch(feel: Feel): Boolean {
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        return vibrator.hasVibrator() && feel.primitives.isNotEmpty() && vibrator.areAllPrimitivesSupported(*feel.primitives)
    }

    /** Plays one row with [how] on the activity's player and returns what the system recorded for it. */
    private fun recordOf(scenario: ActivityScenario<MainActivity>, how: (Vibrations) -> Unit): List<String> {
        val mark = System.currentTimeMillis()
        scenario.onActivity { how(Vibrations.of(it.window.decorView)) }
        // The system adds a vibration to its record when it has ended.
        Thread.sleep(1_500)
        return VibrationRecord.lines(mark)
    }

    @Test fun eachOfTheThreePlaysOnceAsTheTableSaysAndTheyDiffer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            val recorded = mutableMapOf<Feel, String>()
            for (feel in dial) {
                var branch: VibrationBranch? = null
                val lines = recordOf(scenario) { branch = it.branch(feel); it.play(feel) }
                Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: $feel played as $branch; recorded $lines")
                assertEquals("$feel: ${lines.size} recorded: $lines", 1, lines.size)
                val line = lines.single()
                assertEquals("$feel: $line", "finished", statusOf(line))
                assertEquals("$feel", if (supportedOnThisWatch(feel)) VibrationBranch.PRIMITIVES else VibrationBranch.CONSTANT, branch)
                if (branch == VibrationBranch.PRIMITIVES) {
                    assertEquals("$feel: $line", feel.primitives.map { primitiveNames.getValue(it) }, primitivesOf(line))
                    assertFalse("$feel: $line", "performHapticFeedback" in line)
                } else if (Build.VERSION.SDK_INT >= 35) {
                    assertEquals("$feel: $line", feel.constantFrom34, constantOf(line))
                }
                recorded[feel] = playedOf(line)
                Thread.sleep(300)
            }
            Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: played $recorded")
            if (dial.all { supportedOnThisWatch(it) }) {
                // As primitives the three are told apart in what was played, not only in what was asked for.
                assertEquals("$recorded", 3, recorded.values.toSet().size)
            }
        }
    }

    /**
     * The branch for a watch without the primitives, which no emulator is: each dial row's haptic feedback constant,
     * played as `play` plays it there. The three play, and play three effects that differ from each other and from the
     * press's.
     */
    @Test fun theConstantsPlayThreeDifferentEffectsDifferentFromThePress() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            val played = mutableMapOf<Feel, String>()
            for (feel in dial + Feel.PRESS) {
                val lines = recordOf(scenario) { it.playConstant(feel) }
                Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: $feel as its constant; recorded $lines")
                assertEquals("$feel: $lines", 1, lines.size)
                assertEquals("$feel: ${lines.single()}", "finished", statusOf(lines.single()))
                played[feel] = playedOf(lines.single())
                Thread.sleep(300)
            }
            assertEquals("$played", 4, played.values.toSet().size)
        }
    }

    @Test fun theSaveBlindNavigationAndLongPressRowsPlayAsTheSystemsHapticFeedback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            for (feel in listOf(Feel.PRESS, Feel.LOCK, Feel.HEARTBEAT, Feel.LONG_PRESS)) {
                var branch: VibrationBranch? = null
                val lines = recordOf(scenario) { branch = it.branch(feel); it.play(feel) }
                assertEquals("$feel", VibrationBranch.CONSTANT, branch)
                assertEquals("$feel: $lines", 1, lines.size)
                if (Build.VERSION.SDK_INT >= 35) assertEquals("$feel: $lines", feel.constant, constantOf(lines.single()))
                Thread.sleep(300)
            }
        }
    }

    /** The confirming vibration on the record: CONFIRM, which the emulator images play as two thuds. */
    private fun isConfirm(line: String) =
        if (Build.VERSION.SDK_INT >= 35) constantOf(line) == Feel.SAVED.constant else primitivesOf(line) == listOf("THUD", "THUD")
    private fun isPress(line: String) =
        if (Build.VERSION.SDK_INT >= 35) constantOf(line) == Feel.PRESS.constant else primitivesOf(line) == listOf("TICK") && "1.0" in playedOf(line)

    @Test fun aConfirmWaitingForOnePressWaitsForASecondPressToo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            // A newer press may cut an older press's vibration short: "The system stops a vibration that is playing
            // when the same app asks for another" (v2 spec), and the owner (v2): "haptic overlap is ok". Only the
            // confirm is held back for a press. So the second press comes once the first has played out: after how
            // long a press takes here as the system records it (its length and the time to start it) and 40 ms more.
            // Until 2026-10-14 it came 120 ms after the first, and on API 34 a press that took 122 ms was cut short by it.
            val lone = recordOf(scenario) { it.press() }
            assertEquals("a press alone: $lone", 1, lone.size)
            val gapMs = VibrationRecord.durationMsOf(lone.single()) + 40
            // The second press has to come while the confirm still waits for the first.
            assertTrue("a press takes ${gapMs - 40} ms here: no second press fits before the confirm; $lone", gapMs <= SAVE_CONFIRM_AFTER_PRESS_MS - 20)
            // Press, an instant save 40 ms later, a second press after the gap: the confirm, due
            // SAVE_CONFIRM_AFTER_PRESS_MS after the first press, would cut the second press short. It waits for the
            // second instead.
            val lines = recordOf(scenario) { player ->
                player.press(); SystemClock.sleep(40); player.saved(); SystemClock.sleep(gapMs - 40); player.press()
            }
            val all = "recorded: $lines"
            assertEquals(all, 3, lines.size)
            assertTrue("none cut short; $all", lines.all { statusOf(it) == "finished" })
            val presses = lines.filter(::isPress)
            val confirm = lines.single(::isConfirm)
            assertEquals(all, 2, presses.size)
            assertTrue("the confirm came ${startMsOf(confirm) - startMsOf(presses[1])} ms after the second press; $all",
                startMsOf(confirm) - startMsOf(presses[1]) >= SAVE_CONFIRM_AFTER_PRESS_MS - 10)
        }
    }

    @Test fun aConfirmStillWaitingWhenTheWindowGoesIsPlayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(2_000)
        val mark = System.currentTimeMillis()
        // An instant save, then the activity is finished at once (as Back would), while the confirm still waits.
        scenario.onActivity { activity ->
            val player = Vibrations.of(activity.window.decorView)
            player.press(); player.saved()
            activity.finish()
        }
        Thread.sleep(2_500)
        scenario.close()
        val lines = VibrationRecord.lines(mark)
        assertTrue("the confirm was played; recorded: $lines", lines.any(::isConfirm))
    }
}
