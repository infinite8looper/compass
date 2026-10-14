package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.sensors.HeadingSource
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadingSourceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun deviceHasTheSensor() { assertTrue(HeadingSource(ctx).isSupported) }

    @Test fun readingsArriveAndAreInRange() = runBlocking {
        val got = withTimeout(10_000) { HeadingSource(ctx).readings().take(5).toList() }
        assertEquals(5, got.size)
        for (r in got) {
            assertTrue("heading ${r.magneticDeg}", r.magneticDeg >= 0f && r.magneticDeg < 360f)
            assertTrue("pitch ${r.pitchDeg}", r.pitchDeg in 0f..90f)
            r.accuracy?.let { assertTrue(it in 0..3) }
            r.fieldMicroTesla?.let { assertTrue(it > 0f) }
        }
    }
    /**
     * The sensor's events do not come on the main thread: with the main thread held up, readings still arrive.
     * (Registered without a thread of its own, a listener is called on the main thread, and nothing arrives.)
     */
    @Test fun readingsArriveWhileTheMainThreadIsHeldUp() = runBlocking {
        val held = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { held.countDown(); letGo.await(30, TimeUnit.SECONDS) }
        try {
            assertTrue("the main thread did not take the task that holds it up", held.await(10, TimeUnit.SECONDS))
            val got = withTimeoutOrNull(8_000) { HeadingSource(ctx).readings().take(5).toList() }
            assertEquals("readings that arrived in 8 s while the main thread was held up", 5, got?.size ?: 0)
        } finally {
            letGo.countDown()
        }
    }

    private fun sensorThreads() = Thread.getAllStackTraces().keys.filter { it.name == "compass-sensors" && it.isAlive }

    /** The thread the events come on runs while a source is collected and ends when the last collection has. */
    @Test fun theSensorThreadRunsOnlyWhileASourceIsCollected() = runBlocking {
        val s = HeadingSource(ctx)
        var during = -1
        withTimeout(10_000) { s.readings().take(1).collect { during = sensorThreads().size } }
        assertEquals("sensor threads while a reading was taken", 1, during)
        // Two collections at once share the one thread.
        withTimeout(10_000) { s.readings().take(1).collect { s.readings().take(1).collect { during = sensorThreads().size } } }
        assertEquals("sensor threads while two collections ran", 1, during)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (sensorThreads().isNotEmpty() && SystemClock.uptimeMillis() < deadline) delay(50)
        assertEquals("sensor threads left 5 s after the last collection ended", 0, sensorThreads().size)
    }

    @Test fun collectingTwiceWorks() = runBlocking {
        val s = HeadingSource(ctx)
        withTimeout(10_000) { s.readings().take(1).toList() }
        assertEquals(1, withTimeout(10_000) { s.readings().take(1).toList() }.size)
    }
}
