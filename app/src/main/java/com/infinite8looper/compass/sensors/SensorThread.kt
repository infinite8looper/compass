package com.infinite8looper.compass.sensors

import android.os.Handler
import android.os.HandlerThread

/**
 * The thread the app's sensor listeners are called on, so that a sensor's events are not handled on the main
 * thread, between the frames. Every source that listens to a sensor takes it here ([acquire]) and gives the
 * handler to `SensorManager.registerListener`; one thread serves them all. It is started for the first listener
 * and stopped when the last has let go ([release]), so it runs only while a sensor does.
 *
 * What a listener does there must be safe off the main thread: it works on values of its own and hands the result
 * on through something made for that (a channel, a flow).
 */
object SensorThread {
    /** The thread's name, as a thread dump and the tests see it. */
    const val NAME = "compass-sensors"

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var users = 0

    /** A handler on the sensor thread, which is started if it is not running. Every call is to be paired with one [release]. */
    @Synchronized fun acquire(): Handler {
        users++
        return handler ?: HandlerThread(NAME).also { it.start(); thread = it }.let { Handler(it.looper) }.also { handler = it }
    }

    /** Gives the thread back; with the last listener gone it ends once it has done what it was already given. */
    @Synchronized fun release() {
        if (users == 0) return
        if (--users == 0) {
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }
}
