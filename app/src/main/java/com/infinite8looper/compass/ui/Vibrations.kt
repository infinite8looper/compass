package com.infinite8looper.compass.ui

import android.annotation.SuppressLint
import android.content.Context
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
import com.infinite8looper.compass.R
import com.infinite8looper.compass.nav.DialCrossing
import com.infinite8looper.compass.nav.NO_PRESS
import com.infinite8looper.compass.nav.confirmDelayMs
import com.infinite8looper.compass.nav.targetQuietNanos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's vibrations, in one table: the feel of each is set here and nowhere else (every vibration the app asks
 * for is played through [Vibrations.play] with a row of this table).
 *
 * Each row gives the composition primitives it plays, with a scale (0 to 1) and a delay before each, where the watch
 * says it supports all of them (`Vibrator.areAllPrimitivesSupported`); and the haptic feedback constant it plays
 * where the watch does not, or where the row has no primitives: [constant] below API 34, [constantFrom34] from it.
 * The dial's constants were chosen for what the emulator images play for them (read from `dumpsys vibrator_manager`
 * on API 33, 34, 35 and 37, 2026-10-14): the clock tick and SEGMENT_FREQUENT_TICK the texture tick effect (the tick
 * effect on API 37, which has no texture tick), GESTURE_START the click effect, CONFIRM two thuds 100 ms apart; the
 * press's VIRTUAL_KEY a full tick primitive. So the three differ from each other and from the press there, and the
 * target keeps a shape of two. A watch's system can map the constants to other effects. The long press, the
 * platform's heavy click, is not used for the dial: a vibrator need not support that effect, the emulators' does not,
 * and nothing plays.
 *
 * - [THIN]: a line of the dial every 2 degrees. Light.
 * - [THICK]: a line every 30 degrees, the cardinal points among them. Deeper.
 * - [TARGET]: the focused target's bearing, with `Blind navigation` off. In between, and two knocks.
 * - [PRESS]: a press that saves, at once ([Vibrations.press]).
 * - [SAVED]: a save that finished ([Vibrations.saved]), after the press has played out.
 * - [LOCK] and [HEARTBEAT]: `Blind navigation`'s strong pulse on turning onto the target and its soft one each second.
 * - [LONG_PRESS]: a long press that opens something (the waypoints from the compass, a row's menu).
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
    THICK(intArrayOf(Composition.PRIMITIVE_THUD), floatArrayOf(1f), intArrayOf(0), HapticFeedbackConstants.GESTURE_START),
    TARGET(
        intArrayOf(Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_CLICK), floatArrayOf(0.7f, 0.7f), intArrayOf(0, 50),
        HapticFeedbackConstants.CONFIRM,
    ),
    PRESS(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.VIRTUAL_KEY),
    SAVED(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.CONFIRM),
    LOCK(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.CONFIRM),
    HEARTBEAT(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.CONTEXT_CLICK),
    LONG_PRESS(NONE_INT, NONE_FLOAT, NONE_INT, HapticFeedbackConstants.LONG_PRESS);

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
 *
 * The compositions are built once for the process, on a thread of their own ([prepare], called when the activity is
 * made), so the vibrator is never asked on the main thread. Until they are ready a row plays its constant. Playing
 * one makes nothing new.
 *
 * Get it with [of]: there is one for each window, so the press and the confirming vibration of a save, played from
 * different places, see each other. Call on the main thread.
 */
class Vibrations private constructor(private val view: View) {
    private val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    private var pressAtMs = NO_PRESS
    private var savedPending = false
    private val playSaved = Runnable { savedPending = false; play(Feel.SAVED) }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            // A confirming vibration still waiting when the window goes (Back right after an instant save) is played
            // now, while the view can still ask for it; it may cut the press's short, which is better than none.
            override fun onViewDetachedFromWindow(v: View) {
                if (savedPending) { view.removeCallbacks(playSaved); playSaved.run() }
            }
        })
    }

    /** Whether [feel] plays as primitives or as its constant on this watch, now. */
    fun branch(feel: Feel): VibrationBranch =
        if (prepared?.effects?.get(feel.ordinal) != null) VibrationBranch.PRIMITIVES else VibrationBranch.CONSTANT

    /**
     * How long [feel] lasts as it plays here, in milliseconds: its primitives' durations (`getPrimitiveDurations`)
     * and delays where it plays as primitives, else [CONSTANT_LENGTH_MS], what the emulators' CONFIRM lasts.
     */
    fun lengthMs(feel: Feel): Int {
        // Read without a nullable Int, which would make a new Integer for each call (the dial asks on every frame).
        val lengths = prepared?.lengthsMs ?: return CONSTANT_LENGTH_MS
        val length = lengths[feel.ordinal]
        return if (length >= 0) length else CONSTANT_LENGTH_MS
    }

    /**
     * How long the dial stays quiet after the target's vibration on this watch ([targetQuietNanos] of the target's
     * [lengthMs]): worked out once, when the compositions are made, and read on every frame without making anything.
     */
    fun targetQuietNanos(): Long {
        val ready = prepared ?: return CONSTANT_TARGET_QUIET_NANOS
        return ready.targetQuietNanos
    }

    /** Plays [feel] now. */
    fun play(feel: Feel) {
        val ready = prepared
        val effect = ready?.effects?.get(feel.ordinal)
        if (effect != null) ready.vibrator.vibrate(effect, attributes) else playConstant(feel)
    }

    /** Plays [feel] as its haptic feedback constant, as [play] does where the watch lacks the row's primitives. */
    fun playConstant(feel: Feel) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) feel.constantFrom34 else feel.constant)
    }

    /**
     * Plays the vibration of a press that saves, and notes its time for [saved]. A confirming vibration still
     * waiting for an earlier press waits for this one too, so it does not cut this one short.
     */
    fun press() {
        play(Feel.PRESS)
        pressAtMs = SystemClock.uptimeMillis()
        if (savedPending) {
            view.removeCallbacks(playSaved)
            view.postDelayed(playSaved, confirmDelayMs(pressAtMs, pressAtMs))
        }
    }

    /**
     * Plays the confirming vibration of a save: at once, or, when the save finished so soon after the press that it
     * would cut the press's vibration short, once that has played ([confirmDelayMs]).
     */
    fun saved() {
        view.removeCallbacks(playSaved)
        val wait = confirmDelayMs(SystemClock.uptimeMillis(), pressAtMs)
        if (wait == 0L) { savedPending = false; play(Feel.SAVED) } else { savedPending = true; view.postDelayed(playSaved, wait) }
    }

    /** What is asked of the vibrator once for the process: it, and each row's composition where it supports the row. */
    private class Prepared(val vibrator: Vibrator, val effects: Array<VibrationEffect?>, val lengthsMs: IntArray) {
        val targetQuietNanos: Long = lengthsMs[Feel.TARGET.ordinal].let { targetQuietNanos(if (it >= 0) it else CONSTANT_LENGTH_MS) }
    }

    companion object {
        private const val TAG = "Vibrations"
        /** The length taken for a row played as its constant, whose length the app cannot ask: the emulators' CONFIRM, 300 ms. */
        const val CONSTANT_LENGTH_MS = 300
        private val CONSTANT_TARGET_QUIET_NANOS = targetQuietNanos(CONSTANT_LENGTH_MS)
        @Volatile private var prepared: Prepared? = null
        @Volatile private var preparing = false
        private val askedNow = MutableStateFlow(false)

        /**
         * Whether the vibrator has been asked ([prepare]'s thread has ended): from then on [branch] says how each row
         * plays on this watch. Set once for the process.
         */
        val asked: StateFlow<Boolean> = askedNow

        /**
         * Asks the vibrator which rows it supports and builds their compositions, once for the process, on a thread
         * of its own. With `BuildConfig.DIAGNOSTICS` it logs each row's branch.
         */
        fun prepare(context: Context) {
            if (preparing) return
            preparing = true
            val app = context.applicationContext
            Thread({
                val vibrator = app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
                if (vibrator != null) {
                    val effects = Array(Feel.entries.size) { i -> composition(vibrator, Feel.entries[i]) }
                    val lengths = IntArray(Feel.entries.size) { i -> if (effects[i] == null) -1 else lengthOf(vibrator, Feel.entries[i]) }
                    prepared = Prepared(vibrator, effects, lengths)
                }
                if (BuildConfig.DIAGNOSTICS) {
                    val effects = prepared?.effects
                    Log.i(TAG, "dial vibrations: " + Feel.entries.joinToString {
                        it.name + " " + (if (effects?.get(it.ordinal) != null) "primitives" else "constant")
                    })
                }
                askedNow.value = true
            }, "vibrations").start()
        }

        // The rows' primitives are Composition's PRIMITIVE_ constants (VibrationTableTest), which lint cannot see in an array.
        @SuppressLint("WrongConstant")
        private fun composition(vibrator: Vibrator, feel: Feel): VibrationEffect? =
            if (feel.primitives.isEmpty() || !vibrator.areAllPrimitivesSupported(*feel.primitives)) null
            else VibrationEffect.startComposition().apply {
                for (p in feel.primitives.indices) addPrimitive(feel.primitives[p], feel.scales[p], feel.delaysMs[p])
            }.compose()

        @SuppressLint("WrongConstant")
        private fun lengthOf(vibrator: Vibrator, feel: Feel): Int = vibrator.getPrimitiveDurations(*feel.primitives).sum() + feel.delaysMs.sum()

        /** The player for the window [view] is in, kept as a tag of the window's root view and gone with it. */
        fun of(view: View): Vibrations {
            prepare(view.context)
            val root = view.rootView
            return root.getTag(R.id.vibrations) as? Vibrations ?: Vibrations(root).also { root.setTag(R.id.vibrations, it) }
        }
    }
}
