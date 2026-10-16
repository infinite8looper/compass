package com.infinite8looper.compass

import android.os.Build
import android.os.Debug
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
import com.infinite8looper.compass.nav.targetQuietNanos
import com.infinite8looper.compass.ui.Feel
import com.infinite8looper.compass.ui.VibrationBranch
import com.infinite8looper.compass.ui.Vibrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /**
     * The player says when it has asked the vibrator ([Vibrations.asked]), so a screen that shows how each row plays
     * (the check screen) can show it when the answer comes, however late; and from then on each row's branch is
     * what this watch supports.
     */
    @Test fun thePlayerSaysWhenItHasAskedTheVibratorAndEachRowsBranchIsThenKnown() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vibrations: Vibrations
            scenario.onActivity { vibrations = Vibrations.of(it.window.decorView) }
            withTimeout(10_000) { Vibrations.asked.first { it } }
            for (feel in Feel.entries) {
                val expected = if (supportedOnThisWatch(feel)) VibrationBranch.PRIMITIVES else VibrationBranch.CONSTANT
                assertEquals("$feel", expected, vibrations.branch(feel))
            }
        }
        Unit
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
     * The dial asks the player on every frame how long the lines stay quiet after the target's vibration. That makes
     * nothing new (no per-frame allocation on the compass): counted with the runtime's own allocation counter for
     * this thread, over as many asks as about a minute of frames, once the compositions are ready.
     */
    @Test fun askingTheTargetsQuietOnEveryFrameMakesNothingNew() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vibrations: Vibrations
            scenario.onActivity { vibrations = Vibrations.of(it.window.decorView) }
            val expected = if (supportedOnThisWatch(Feel.TARGET)) VibrationBranch.PRIMITIVES else VibrationBranch.CONSTANT
            val until = SystemClock.uptimeMillis() + 5_000
            while (vibrations.branch(Feel.TARGET) != expected && SystemClock.uptimeMillis() < until) Thread.sleep(50)
            assertEquals(expected, vibrations.branch(Feel.TARGET))
            // What the frame loop asks (the quiet kept by the player), and the length it is made from.
            assertEquals(targetQuietNanos(vibrations.lengthMs(Feel.TARGET)), vibrations.targetQuietNanos())
            var sum = 0L
            // A Kotlin function type would return a boxed Long itself; this interface returns a plain long.
            for ((name, ask) in listOf(
                "targetQuietNanos()" to LongAsk { vibrations.targetQuietNanos() },
                "targetQuietNanos(lengthMs(TARGET))" to LongAsk { targetQuietNanos(vibrations.lengthMs(Feel.TARGET)) },
            )) {
                fun frames() { repeat(FRAMES) { sum += ask.ask() } }
                frames() // once before counting, so nothing made by the first call of a method is counted
                @Suppress("DEPRECATION")
                val made = run {
                    Debug.resetThreadAllocCount()
                    Debug.startAllocCounting()
                    frames()
                    Debug.stopAllocCounting()
                    Debug.getThreadAllocCount()
                }
                Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: $FRAMES asks of $name made $made objects (quiet ${vibrations.targetQuietNanos()} ns)")
                assertEquals("objects made by $FRAMES asks of $name", 0, made)
            }
            assertTrue(sum > 0)
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
            assertTrue("a press takes ${gapMs - 40} ms here: no second press fits before the confirm; $lone", gapMs < SAVE_CONFIRM_AFTER_PRESS_MS)
            // Press, an instant save 40 ms later, a second press after the gap: the confirm, due
            // SAVE_CONFIRM_AFTER_PRESS_MS after the first press, would cut the second press short. It waits for the
            // second instead.
            var firstAskedMs = 0L
            var secondAskedMs = 0L
            val lines = recordOf(scenario) { player ->
                firstAskedMs = SystemClock.uptimeMillis()
                player.press(); SystemClock.sleep(40); player.saved(); SystemClock.sleep(gapMs - 40)
                secondAskedMs = SystemClock.uptimeMillis()
                player.press()
            }
            // That the second press was asked for before the confirm was due, measured: the app notes the first
            // press's time after firstAskedMs, so the confirm is due no sooner than SAVE_CONFIRM_AFTER_PRESS_MS after it.
            // Until 2026-10-14 this was a margin of 20 ms kept under the gap, which left 11 ms at the worst lengths
            // recorded (re-review, Minor 4); the time measured leaves all the room there is.
            Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: press ${gapMs - 40} ms; second press asked ${secondAskedMs - firstAskedMs} ms after the first")
            assertTrue("the second press was asked for ${secondAskedMs - firstAskedMs} ms after the first: not before the confirm was due",
                secondAskedMs - firstAskedMs < SAVE_CONFIRM_AFTER_PRESS_MS)
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

    private fun interface LongAsk { fun ask(): Long }

    private companion object {
        /** About a minute of frames at 60 a second. */
        const val FRAMES = 3_600
    }
}
