package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.sensors.HeadingSource
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
    @Test fun collectingTwiceWorks() = runBlocking {
        val s = HeadingSource(ctx)
        withTimeout(10_000) { s.readings().take(1).toList() }
        assertEquals(1, withTimeout(10_000) { s.readings().take(1).toList() }.size)
    }
}
