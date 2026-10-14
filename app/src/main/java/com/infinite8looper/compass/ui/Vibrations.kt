package com.infinite8looper.compass.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import com.infinite8looper.compass.BuildConfig
import com.infinite8looper.compass.nav.DialCrossing
import com.infinite8looper.compass.nav.NO_PRESS
import com.infinite8looper.compass.nav.confirmDelayMs
import java.util.WeakHashMap

/**
 * The app's vibrations, in one table: the feel of each is set here and nowhere else.
 *
 * Each row gives the composition primitives it plays, with a scale (0 to 1) and a delay before each, where the watch
 * says it supports all of them (`Vibrator.areAllPrimitivesSupported`); and the haptic feedback constant it plays
 * where the watch does not, or where the row has no primitives: [constant] below API 34, [constantFrom34] from it.
 * The dial's constants are, in the platform's own table (API 33 to 35), the texture tick (the clock tick below API
 * 34), the tick effect and the click effect, lightest first; a watch's system can replace entries. The long press,
 * the platform's heavy click, is not used: a vibrator need not support that effect, the emulators' does not, and
 * nothing plays. The constants cannot give the target's two knocks; there it is told from the lines by strength only.
 *
 * - [THIN]: a line of the dial every 2 degrees. Light.
 * - [THICK]: a line every 30 degrees, the cardinal points among them. Deeper.
 * - [TARGET]: the focused target's bearing, with `Blind navigation` off. In between, and two knocks.
 * - [PRESS]: a press that saves, at once ([Vibrations.press]).
 * - [SAVED]: a save that finished ([Vibrations.saved]), after the press has played out.
 * - [LOCK] and [HEARTBEAT]: `Blind navigation`'s strong pulse on turning onto the target and its soft one each second.
 */
enum class Feel(
    val primitives: IntArray, val scales: FloatArray, val delaysMs: IntArray, val constant: Int, val constantFrom34: Int = constant,
) {
    // The texture tick is API 34's; [Vibrations.play] plays [constantFrom34] only from API 34.
    @SuppressLint("InlinedApi")
    THIN(
        intArrayOf(Composition.PRIMITIVE_TICK), floatArrayOf(0.5f), intArrayOf(0),
        HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
    ),
    THICK(intArrayOf(Composition.PRIMITIVE_THUD), floatArrayOf(1f), intArrayOf(0), HapticFeedbackConstants.VIRTUAL_KEY),
    TARGET(
        intArrayOf(Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_CLICK), floatArrayOf(0.7f, 0.7f), intArrayOf(0, 50),
        HapticFeedbackConstants.CONTEXT_CLICK,
    ),
    PRESS(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.VIRTUAL_KEY),
    SAVED(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.CONFIRM),
    LOCK(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.CONFIRM),
    HEARTBEAT(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.CONTEXT_CLICK);

    companion object {
        /** The row for what the dial passed; null for nothing. */
        fun of(crossing: DialCrossing): Feel? = when (crossing) {
            DialCrossing.NONE -> null
            DialCrossing.THIN -> THIN
            DialCrossing.THICK -> THICK
            DialCrossing.TARGET -> TARGET
        }
    }
}

private val NONE_INT = IntArray(0)
private val NONE_FLOAT = FloatArray(0)

/** How a row of [Feel] is played on this watch. */
enum class VibrationBranch { PRIMITIVES, CONSTANT }

/**
 * Plays the rows of [Feel] for one window: as a composition of primitives through the vibrator, with the `VIBRATE`
 * permission and the touch usage (so it follows the watch's touch-vibration setting, as haptic feedback does), where
 * the watch supports every primitive of the row; else as the row's haptic feedback constant on the window's view.
 * Each composition is built once, here; playing one makes nothing new.
 *
 * Get it with [of]: there is one for each window, so the press and the confirming vibration of a save, played from
 * different places, see each other. Call on the main thread.
 */
class Vibrations private constructor(private val view: View) {
    private val vibrator: Vibrator? =
        view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    private val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    // The rows' primitives are Composition's PRIMITIVE_ constants (VibrationTableTest), which lint cannot see in an array.
    @SuppressLint("WrongConstant")
    private val effects: Array<VibrationEffect?> = Array(Feel.entries.size) { i ->
        val feel = Feel.entries[i]
        if (feel.primitives.isEmpty() || vibrator == null || !vibrator.areAllPrimitivesSupported(*feel.primitives)) null
        else VibrationEffect.startComposition().apply {
            for (p in feel.primitives.indices) addPrimitive(feel.primitives[p], feel.scales[p], feel.delaysMs[p])
        }.compose()
    }
    private var pressAtMs = NO_PRESS
    private val playSaved = Runnable { play(Feel.SAVED) }

    init {
        if (BuildConfig.DIAGNOSTICS) {
            Log.i(TAG, "dial vibrations: " + Feel.entries.joinToString { it.name + " " + branch(it).name.lowercase() })
        }
    }

    /** Whether [feel] plays as primitives or as its constant on this watch. */
    fun branch(feel: Feel): VibrationBranch = if (effects[feel.ordinal] != null) VibrationBranch.PRIMITIVES else VibrationBranch.CONSTANT

    /** Plays [feel] now. */
    fun play(feel: Feel) {
        val effect = effects[feel.ordinal]
        if (effect != null && vibrator != null) vibrator.vibrate(effect, attributes)
        else view.performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) feel.constantFrom34 else feel.constant)
    }

    /** Plays the vibration of a press that saves, and notes its time for [saved]. */
    fun press() {
        play(Feel.PRESS)
        pressAtMs = SystemClock.uptimeMillis()
    }

    /**
     * Plays the confirming vibration of a save: at once, or, when the save finished so soon after the press that it
     * would cut the press's vibration short, once that has played ([confirmDelayMs]).
     */
    fun saved() {
        view.removeCallbacks(playSaved)
        val wait = confirmDelayMs(SystemClock.uptimeMillis(), pressAtMs)
        if (wait == 0L) play(Feel.SAVED) else view.postDelayed(playSaved, wait)
    }

    companion object {
        private const val TAG = "Vibrations"
        private val forWindow = WeakHashMap<View, Vibrations>()

        /** The player for the window [view] is in. */
        fun of(view: View): Vibrations = view.rootView.let { root -> forWindow.getOrPut(root) { Vibrations(root) } }
    }
}
