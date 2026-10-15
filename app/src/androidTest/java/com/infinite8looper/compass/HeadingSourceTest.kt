package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.nav.HeadingKind
import com.infinite8looper.compass.nav.shortestDelta
import com.infinite8looper.compass.sensors.HeadingReading
import com.infinite8looper.compass.sensors.HeadingSource
import com.infinite8looper.compass.sensors.MagneticMethod
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sqrt
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
import android.util.Log
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

    // The three sources (v3 spec, "Heading"). The emulator's virtual watch lies still (scripts/all-suites.sh lays it
    // flat at a magnetic heading of 40 through the sensor console), so the three must agree.

    private fun sensorDump(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys sensorservice"),
    ).use { it.readBytes().decodeToString() }

    /**
     * The sensors the heading source has registered right now: in dumpsys sensorservice each connection is a line
     * naming its listener's class, followed by one line per sensor it has: `<name> 0x<handle> | first flush ...`
     * on API 35 and 37, `<name> 0x<handle> | status: active ...` on API 33 and 34.
     */
    private val sensorLine = Regex("^.+ 0x[0-9a-fA-F]+ \\|")

    private fun registeredSensors(): Set<String> {
        val names = mutableSetOf<String>()
        var ours = false
        for (line in sensorDump().lineSequence()) {
            val t = line.trim()
            when {
                "WakeLockRefCount" in t -> ours = "sensors.HeadingSource" in t
                ours && sensorLine.containsMatchIn(t) -> names += t.substringBefore(" 0x").trim()
                ours && !t.startsWith("Session Start") -> ours = false
            }
        }
        return names
    }

    /** The heading the console set, when the run was given one (`-e expectedHeading <degrees>`, scripts/heading-sources-check.sh). */
    private val expectedHeading: Float? = InstrumentationRegistry.getArguments().getString("expectedHeading")?.toFloatOrNull()

    private suspend fun settled(source: HeadingSource, kind: HeadingKind, allThree: Boolean = false): HeadingReading =
        withTimeout(20_000) {
            // The first readings of a fusion that has just started can still be on their way; a few seconds of
            // readings are let pass, then the last is taken.
            var last: HeadingReading? = null
            val until = SystemClock.uptimeMillis() + 3_000
            source.readings(kind, allThree).first { last = it; SystemClock.uptimeMillis() > until }
            last!!
        }

    @Test fun eachKindGivesHeadingsInRange() = runBlocking {
        for (kind in HeadingKind.entries) {
            val got = withTimeout(10_000) { HeadingSource(ctx).readings(kind).take(5).toList() }
            assertEquals(kind.name, 5, got.size)
            for (r in got) {
                assertTrue("$kind: heading ${r.magneticDeg}", r.magneticDeg >= 0f && r.magneticDeg < 360f)
                assertTrue("$kind: pitch ${r.pitchDeg}", r.pitchDeg in 0f..90f)
                assertEquals(kind, r.kind)
            }
        }
    }

    /** The heading the compass is given is the one of the kind asked for. */
    @Test fun theHeadingShownIsTheOneOfTheKindAskedFor() = runBlocking {
        for (kind in HeadingKind.entries) {
            val r = settled(HeadingSource(ctx), kind, allThree = true)
            val chosen = when (kind) { HeadingKind.FUSED -> r.fusedDeg; HeadingKind.MAGNETIC -> r.geomagneticDeg; HeadingKind.BLEND -> r.blendDeg }
            assertEquals("$kind: $r", chosen, r.magneticDeg, 0f)
        }
    }

    /**
     * Lying still, the fused heading, the magnetic one and the blend agree within 3 degrees, with the magnetic
     * heading from the geomagnetic rotation vector and from getRotationMatrix alike; and when the run was given the
     * heading the console set, each is within 3 degrees of it.
     */
    @Test fun atRestTheThreeHeadingsAgree() = runBlocking {
        for (method in MagneticMethod.entries) {
            val source = HeadingSource(ctx, method)
            assertEquals(method, source.magneticMethod)
            val r = settled(source, HeadingKind.BLEND, allThree = true)
            Log.i("HeadingSourceTest", "$method: fused ${r.fusedDeg}, magnetic ${r.geomagneticDeg}, blend ${r.blendDeg}, pitch ${r.pitchDeg}, expected $expectedHeading")
            assertTrue("$method: $r", r.fusedDeg.isFinite() && r.geomagneticDeg.isFinite() && r.blendDeg.isFinite())
            assertTrue("$method: fused ${r.fusedDeg} and magnetic ${r.geomagneticDeg}", abs(shortestDelta(r.fusedDeg, r.geomagneticDeg)) < 3f)
            assertTrue("$method: fused ${r.fusedDeg} and blend ${r.blendDeg}", abs(shortestDelta(r.fusedDeg, r.blendDeg)) < 3f)
            expectedHeading?.let { e ->
                for ((name, deg) in listOf("fused" to r.fusedDeg, "magnetic" to r.geomagneticDeg, "blend" to r.blendDeg)) {
                    assertTrue("$method: $name $deg, the console set $e", abs(shortestDelta(deg, e)) < 3f)
                }
            }
        }
    }

    /** The watch's own choice: the geomagnetic rotation vector where it has one, as every emulator image does. */
    @Test fun theMagneticHeadingComesFromTheGeomagneticRotationVectorWhereThereIsOne() {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        val has = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null
        assertEquals(
            if (has) MagneticMethod.GEOMAGNETIC_ROTATION_VECTOR else MagneticMethod.ACCELEROMETER_AND_MAGNETOMETER,
            HeadingSource(ctx).magneticMethod,
        )
    }

    /**
     * The fused source runs the rotation vector and the magnetometer and nothing else, as before v3: the other
     * sensors are registered only for the magnetic heading and the blend, or when all three are asked for.
     */
    @Test fun theFusedSourceRegistersOnlyTheRotationVectorAndTheMagnetometer() = runBlocking {
        val source = HeadingSource(ctx)
        var fusedOnly = emptySet<String>()
        withTimeout(10_000) { source.readings(HeadingKind.FUSED).take(3).collect { fusedOnly = registeredSensors() } }
        var magnetic = emptySet<String>()
        withTimeout(10_000) { source.readings(HeadingKind.MAGNETIC).take(3).collect { magnetic = registeredSensors() } }
        Log.i("HeadingSourceTest", "fused registers $fusedOnly; magnetic registers $magnetic")
        assertTrue("fused: $fusedOnly", fusedOnly.any { "Rotation Vector" in it && "GeoMag" !in it && "Game" !in it })
        assertTrue("fused: $fusedOnly", fusedOnly.none { "GeoMag" in it || "Accelerometer" in it })
        assertTrue("magnetic: $magnetic", magnetic.any { "GeoMag" in it })
        val r = withTimeout(10_000) { source.readings(HeadingKind.FUSED).first() }
        assertTrue("fused alone computes no magnetic heading: $r", r.geomagneticDeg.isNaN() && r.blendDeg.isNaN())
    }

    /**
     * When a magnetic or blended collection ends, the sensors it added are taken off: no geomagnetic rotation vector
     * and no accelerometer of the heading source's stays registered, by either method. While it runs they are on
     * (checked first, so the check after the end looks at sensors that were there).
     */
    @Test fun theMagneticSensorsAreTakenOffWhenAMagneticOrBlendedCollectionEnds() = runBlocking {
        for (method in MagneticMethod.entries) for (kind in listOf(HeadingKind.MAGNETIC, HeadingKind.BLEND)) {
            val extra = if (method == MagneticMethod.GEOMAGNETIC_ROTATION_VECTOR) "GeoMag" else "Accelerometer"
            var during = emptySet<String>()
            withTimeout(10_000) { HeadingSource(ctx, method).readings(kind).take(3).collect { during = registeredSensors() } }
            val after = registeredSensors()
            Log.i("HeadingSourceTest", "$method $kind: during $during; after $after")
            assertTrue("$method $kind, while collected: $during", during.any { extra in it })
            assertTrue("$method $kind, after the end: $after", after.none { "GeoMag" in it || "Accelerometer" in it })
        }
    }

    /** The reading carries the magnetometer's field as a vector in the watch's frame, for the calibration ring. */
    @Test fun theReadingCarriesTheFieldVector() = runBlocking {
        val r = withTimeout(15_000) { HeadingSource(ctx).readings(HeadingKind.FUSED).first { it.fieldMicroTesla != null && it.fieldX.isFinite() } }
        val strength = sqrt(r.fieldX * r.fieldX + r.fieldY * r.fieldY + r.fieldZ * r.fieldZ)
        assertEquals(r.fieldMicroTesla!!, strength, 0.01f)
        assertTrue("strength $strength", strength > 0f)
    }

    /**
     * The estimated heading error is in degrees when the sensor gives one. The emulators' rotation vector (AOSP's)
     * gives none: its values[4] is 0 (see nav/Calibration.kt, headingErrorDeg), so there it is null.
     */
    @Test fun theHeadingErrorIsNoneOrDegrees() = runBlocking {
        val got = withTimeout(10_000) { HeadingSource(ctx).readings(HeadingKind.FUSED).take(10).toList() }
        Log.i("HeadingSourceTest", "estimated heading errors: ${got.map { it.headingErrorDeg }}")
        for (r in got) r.headingErrorDeg?.let { assertTrue("error $it", it > 0f && it <= 180f) }
    }
}
