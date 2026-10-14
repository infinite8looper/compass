package com.infinite8looper.compass

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The system's own record of the app's vibrations (`dumpsys vibrator_manager`, as scripts/haptics.sh reads it), for
 * the device tests. Two layouts:
 * - API 35 and later: `MM-DD HH:MM:SS.mmm | effect | <status> | duration: ... | reason: performHapticFeedback(constant=N): ... | played: <effect> | original: ...`
 *   (the reason names the haptic feedback constant; a vibration asked of the vibrator directly has `reason: null`);
 * - API 33 and 34: `startTime: MM-DD HH:MM:SS.mmm, endTime: ..., status: <status>, effect: <effect>, originalEffect: ...`
 *   on API 33, the API 35 layout without the constant on API 34.
 * A primitive is written `Primitive=TICK(...)` (API 34 and later) or `Primitive{primitive=PRIMITIVE_TICK, ...}` (API 33).
 * The system adds a vibration to its record when it has ended (played out, or cut short by the next one).
 */
object VibrationRecord {
    private const val PACKAGE = "com.infinite8looper.compass"
    private val time = Regex("^\\s*(?:startTime: )?(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)")

    /** The device's clock as the record writes it. */
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    fun stampOf(ms: Long): String = stamp.format(Date(ms))

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().readText()

    /** The app's vibrations recorded as started from [fromMs] to [toMs] (wall clock; null for no end), each as its line, oldest first. */
    fun lines(fromMs: Long, toMs: Long? = null): List<String> {
        val from = stampOf(fromMs); val to = toMs?.let(::stampOf)
        return shell("dumpsys vibrator_manager").lines().filter { PACKAGE in it }.mapNotNull { line ->
            val t = time.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            line.takeIf { t >= from && (to == null || t <= to) }
        }.distinct().sortedBy { startOf(it) }
    }

    /** The status of a recorded vibration, lower case: `finished`, `cancelled_superseded`, `ignored_unsupported`, ... */
    fun statusOf(line: String): String =
        (Regex("\\|\\s*([a-z_]+)\\s*\\| duration").find(line) ?: Regex("status: ([A-Za-z_]+)").find(line))?.groupValues?.get(1)?.lowercase() ?: "?"

    /** When it started, as the record writes it. */
    fun startOf(line: String): String = time.find(line)!!.groupValues[1]

    /** When it started, in milliseconds on the record's clock (comparable between lines only). */
    fun startMsOf(line: String): Long = stamp.parse(startOf(line))!!.time

    /** What was played, without the time, status or the original effect. */
    fun playedOf(line: String): String =
        if ("played:" in line) line.substringAfter("played:").substringBefore("| original").trim()
        else line.substringAfter("effect: ").substringBefore(", originalEffect").trim()

    /** The primitives played, by name (TICK, THUD, CLICK...), in order. */
    fun primitivesOf(line: String): List<String> =
        Regex("Primitive[={](?:primitive=)?(?:PRIMITIVE_)?([A-Z_]+)").findAll(playedOf(line)).map { it.groupValues[1] }.toList()

    /** The haptic feedback constant the line names (API 35 and later), or null. */
    fun constantOf(line: String): Int? = Regex("performHapticFeedback\\(constant=(\\d+)\\)").find(line)?.groupValues?.get(1)?.toInt()
}
