package com.infinite8looper.compass

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.nav.ColorMode
import com.infinite8looper.compass.ui.CompassApp
import com.infinite8looper.compass.ui.Vibrations
import com.infinite8looper.compass.ui.WithoutFontScale
import com.infinite8looper.compass.ui.followFontScale
import com.infinite8looper.compass.ui.followWatchColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore.get(this)
        // The `Colors` setting is read before anything is shown, so the first frame is in the chosen colors. DataStore
        // reads on its own thread; a file that cannot be read gives `Standard` at once. The wait is bounded: a read
        // that takes longer than [COLORS_READ_WITHIN_MS] (the first after an upgrade also moves the old settings
        // over) or that fails gives `Standard` for now, and the collector below puts the colors right once it reads.
        followWatchColors(this, colorModeQuickly(settings))
        // Then the colors follow the setting while the app is on screen, and are made again each time it comes back
        // on screen: the watch's color scheme can change while the app is out of sight.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { settings.colorMode.collect { followWatchColors(this@MainActivity, it) } }
        }
        // The vibrator is asked which vibrations it supports on a thread of its own, before anything plays.
        Vibrations.prepare(this)
        // The font size is read here; a change of it makes the activity again.
        followFontScale(this)
        setContent { WithoutFontScale { CompassApp() } }
    }

    private fun colorModeQuickly(settings: SettingsStore): ColorMode = try {
        runBlocking { withTimeoutOrNull(COLORS_READ_WITHIN_MS) { settings.colorMode.first() } } ?: ColorMode.STANDARD
    } catch (e: Exception) {
        Log.w(TAG, "could not read the colors setting (" + e.javaClass.simpleName + ")")
        ColorMode.STANDARD
    }

    private companion object {
        const val TAG = "MainActivity"
        /** How long the first frame waits for the `Colors` setting at most. */
        const val COLORS_READ_WITHIN_MS = 500L
    }
}
