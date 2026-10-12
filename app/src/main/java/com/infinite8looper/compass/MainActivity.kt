package com.infinite8looper.compass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.infinite8looper.compass.ui.CompassApp
import com.infinite8looper.compass.ui.followWatchColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followWatchColors(this)
        setContent { CompassApp() }
    }

    // The watch's color scheme can change while the app is out of sight.
    override fun onStart() {
        super.onStart()
        followWatchColors(this)
    }
}
