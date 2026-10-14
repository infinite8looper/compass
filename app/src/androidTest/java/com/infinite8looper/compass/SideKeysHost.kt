package com.infinite8looper.compass

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.lifecycle.ViewModelProvider
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.ui.rememberSideButtonKeys
import com.infinite8looper.compass.ui.sideKeys
import java.util.concurrent.atomic.AtomicInteger

/**
 * The side button's handling exactly as the compass and the waypoints screen install it ([rememberSideButtonKeys]
 * and `sideKeys` on the outermost box, which takes the focus), told that the watch has a button, with the app's
 * real view model, put in place of the app's content in the real activity ([showIn]). What a single press does is
 * counted in [singles]; a double press goes to the view model as on the screens. For SideButtonKeysTest.
 */
object SideKeysHost {
    /** Single presses the handling has acted on. */
    val singles = AtomicInteger()
    /** What the handling is told the breadcrumb trail setting is. */
    @Volatile var trailOn = false

    /** Replaces [activity]'s content with the handling alone. On the main thread. */
    fun showIn(activity: MainActivity) {
        val vm = ViewModelProvider(activity)[CompassViewModel::class.java]
        activity.setContent { Content(vm) }
    }

    @Composable
    private fun Content(vm: CompassViewModel) {
        val focus = remember { FocusRequester() }
        val keys = rememberSideButtonKeys(
            vm, paneView = { PaneView.IN_VIEW }, doubleEnabled = { trailOn }, onSinglePress = { singles.incrementAndGet() },
            hasButton = true,
        )
        Box(Modifier.fillMaxSize().sideKeys(keys).focusRequester(focus).focusTarget())
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
