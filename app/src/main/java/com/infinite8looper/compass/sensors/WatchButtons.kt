package com.infinite8looper.compass.sensors

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.wear.input.WearableButtons
import com.infinite8looper.compass.nav.BUTTON_COUNT_UNKNOWN
import com.infinite8looper.compass.nav.hasAssignableButton

/**
 * What the watch says of its buttons, asked once for the life of the process: a watch's buttons do not change
 * while it runs. The screens ask [assignable] before they install any handling of a side button; the decision
 * itself is [hasAssignableButton].
 */
object WatchButtons {
    /** The side keys the app acts on. The crown's own press (KEYCODE_STEM_PRIMARY) is the system's and is not one of them. */
    val SIDE_KEYS = intArrayOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)

    /** What the watch answered: its count of buttons (the power button included; negative when it cannot say) and the side keys it describes a button for. */
    data class Report(val buttonCount: Int, val sideKeys: List<Int>) {
        val assignable: Boolean get() = hasAssignableButton(buttonCount, sideKeys.size)
    }

    @Volatile private var held: Report? = null

    /** The watch's answer, asked the first time this is called and kept. */
    fun report(context: Context): Report = held ?: read(context).also { held = it }

    /** Whether the watch has a button the app can act on. */
    fun assignable(context: Context): Boolean = report(context).assignable

    private fun read(context: Context): Report {
        // A watch whose system lacks the part these come from answers with no count and no keys, not with a crash.
        val count = try { WearableButtons.getButtonCount(context) } catch (e: Exception) { failed(e); BUTTON_COUNT_UNKNOWN } catch (e: LinkageError) { failed(e); BUTTON_COUNT_UNKNOWN }
        val keys = SIDE_KEYS.filter { key ->
            try { WearableButtons.getButtonInfo(context, key) != null } catch (e: Exception) { failed(e); false } catch (e: LinkageError) { failed(e); false }
        }
        return Report(count, keys)
    }

    private fun failed(e: Throwable) { Log.w("WatchButtons", "the watch's buttons could not be read (" + e.javaClass.simpleName + ")") }
}
