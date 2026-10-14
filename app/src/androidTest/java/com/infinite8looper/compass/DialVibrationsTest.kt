package com.infinite8looper.compass

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect.Composition
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinite8looper.compass.ui.Feel
import com.infinite8looper.compass.ui.VibrationBranch
import com.infinite8looper.compass.ui.Vibrations
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.WaypointDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dial's three vibrations as the system records them (`dumpsys vibrator_manager`, as scripts/haptics.sh reads
 * it), played by the real player on the real activity's window. Where the watch's vibrator supports every primitive of
 * a row (`areAllPrimitivesSupported`) the row plays as those primitives; where it does not, as its haptic feedback
 * constant (v3 spec, "Vibrations"). Which branch ran is logged (tag DialVibrationsTest) for each row.
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().readText()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** The app's vibrations the system recorded from [fromMs] on (wall clock), each as its line. */
    private fun vibrations(fromMs: Long): List<String> {
        val from = stamp.format(Date(fromMs))
        return shell("dumpsys vibrator_manager").lines().filter { "com.infinite8looper.compass" in it }.mapNotNull { line ->
            val t = Regex("^\\s*(?:startTime: )?(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            line.takeIf { t >= from }
        }.distinct()
    }
    private fun statusOf(line: String): String =
        (Regex("\\|\\s*([a-z_]+)\\s*\\| duration").find(line) ?: Regex("status: ([A-Za-z_]+)").find(line))?.groupValues?.get(1)?.lowercase() ?: "?"

    private val primitiveNames = mapOf(
        Composition.PRIMITIVE_TICK to "TICK", Composition.PRIMITIVE_THUD to "THUD", Composition.PRIMITIVE_CLICK to "CLICK",
    )
    /** The primitives a recorded line says were played, by name, in order (the line's effect only, not the original). */
    private fun playedPrimitives(line: String): List<String> {
        val effect = line.substringBefore("original")
        return Regex("Primitive[={](?:primitive=)?([A-Z_]+)").findAll(effect).map { it.groupValues[1] }.toList()
    }

    private fun supportedOnThisWatch(feel: Feel): Boolean {
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        return vibrator.hasVibrator() && feel.primitives.isNotEmpty() && vibrator.areAllPrimitivesSupported(*feel.primitives)
    }

    @Test fun eachOfTheThreePlaysOnceAsTheTableSaysAndTheyDiffer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            val recorded = mutableMapOf<Feel, String>()
            for (feel in dial) {
                var branch: VibrationBranch? = null
                val mark = System.currentTimeMillis()
                scenario.onActivity { activity ->
                    val player = Vibrations.of(activity.window.decorView)
                    branch = player.branch(feel)
                    player.play(feel)
                }
                // The system adds a vibration to its record when it has ended.
                Thread.sleep(1_500)
                val lines = vibrations(mark)
                Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: $feel played as $branch; recorded $lines")
                assertEquals("$feel: ${lines.size} recorded: $lines", 1, lines.size)
                val line = lines.single()
                assertEquals("$feel: $line", "finished", statusOf(line))
                assertEquals("$feel", if (supportedOnThisWatch(feel)) VibrationBranch.PRIMITIVES else VibrationBranch.CONSTANT, branch)
                if (branch == VibrationBranch.PRIMITIVES) {
                    assertEquals("$feel: $line", feel.primitives.map { primitiveNames.getValue(it) }, playedPrimitives(line))
                    assertFalse("$feel: $line", "performHapticFeedback" in line)
                } else if (Build.VERSION.SDK_INT >= 35) {
                    assertTrue("$feel: $line", "performHapticFeedback(constant=${feel.constantFrom34})" in line)
                }
                recorded[feel] = line.substringAfter("played").substringBefore("original")
                Thread.sleep(300)
            }
            Log.i("DialVibrationsTest", "API ${Build.VERSION.SDK_INT}: played $recorded")
            if (dial.all { supportedOnThisWatch(it) }) {
                // As primitives the three are told apart in what was played, not only in what was asked for.
                assertEquals("$recorded", 3, recorded.values.toSet().size)
            }
        }
    }

    @Test fun theSaveAndBlindNavigationRowsPlayAsTheSystemsHapticFeedback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(2_000)
            for (feel in listOf(Feel.PRESS, Feel.LOCK, Feel.HEARTBEAT)) {
                var branch: VibrationBranch? = null
                val mark = System.currentTimeMillis()
                scenario.onActivity { activity ->
                    val player = Vibrations.of(activity.window.decorView)
                    branch = player.branch(feel)
                    player.play(feel)
                }
                Thread.sleep(1_500)
                val lines = vibrations(mark)
                assertEquals("$feel", VibrationBranch.CONSTANT, branch)
                assertEquals("$feel: $lines", 1, lines.size)
                if (Build.VERSION.SDK_INT >= 35) assertTrue("$feel: $lines", "performHapticFeedback(constant=${feel.constant})" in lines.single())
                Thread.sleep(300)
            }
            assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Feel.PRESS.constant)
        }
    }
}
