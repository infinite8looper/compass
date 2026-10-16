package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The blended heading (v3 spec, "Heading": "a blend that follows the fused one from moment to moment and is steered
 * toward the magnetic one with a time constant"; "The blend is pure code in `nav/`, unit-tested").
 */
class HeadingBlendTest {
    private val step = 20_000_000L   // 50 readings a second, as SENSOR_DELAY_GAME gives on the emulators

    private fun off(a: Float, b: Float) = abs(shortestDelta(a, b))

    @Test fun theSourcesAreFusedMagneticAndBlendAndTheCompassStartsOnFused() {
        assertEquals(listOf(HeadingKind.FUSED, HeadingKind.MAGNETIC, HeadingKind.BLEND), HeadingKind.entries.toList())
        assertEquals(HeadingKind.FUSED, DEFAULT_HEADING_KIND)
        assertEquals(5f, BLEND_TIME_CONSTANT_S, 0f)
    }

    @Test fun itStartsAtTheMagneticHeadingWhenThereIsOneAndAtTheFusedOtherwise() {
        assertEquals(100f, HeadingBlend().update(130f, 100f, 0L), 1e-4f)
        assertEquals(130f, HeadingBlend().update(130f, Float.NaN, 0L), 1e-4f)
    }

    @Test fun nothingIsShownBeforeAFusedHeadingAndANonNumberIsIgnored() {
        val b = HeadingBlend()
        assertTrue(b.update(Float.NaN, 100f, 0L).isNaN())
        assertEquals(100f, b.update(100f, 100f, step), 1e-4f)
        assertEquals(100f, b.update(Float.NaN, 100f, 2 * step), 1e-4f)
        assertEquals(100f, b.value, 1e-4f)
    }

    /** A quick turn shows at once, as the fused heading shows it: the magnetic one is not waited for. */
    @Test fun itFollowsTheFusedHeadingFromMomentToMoment() {
        val b = HeadingBlend()
        b.update(100f, 100f, 0L)
        // 90 degrees in one reading, the magnetic heading not yet moved.
        val after = b.update(190f, 100f, step)
        assertTrue("blend $after, fused 190", off(after, 190f) < 1f)
        // Across north, the short way.
        val c = HeadingBlend(); c.update(350f, 350f, 0L)
        assertTrue(off(c.update(10f, 350f, step), 10f) < 0.5f)
    }

    /** Held still with the fused heading 20 degrees off the magnetic one, it moves to the magnetic one with the time constant. */
    @Test fun itIsSteeredToTheMagneticHeadingWithTheTimeConstant() {
        val b = HeadingBlend()
        b.update(100f, 100f, 0L)
        // The fused heading jumps 20 degrees with the watch still (a correction, or drift), and stays there.
        var t = step; var shown = b.update(120f, 100f, t)
        while (t < 5_000_000_000L) { t += step; shown = b.update(120f, 100f, t) }
        // After one time constant, 1 - 1/e of the way back: 120 - 20 * 0.632 = 107.4.
        assertEquals(107.36f, shown, 0.3f)
        while (t < 30_000_000_000L) { t += step; shown = b.update(120f, 100f, t) }
        assertEquals(100f, shown, 0.1f)
    }

    @Test fun withNoMagneticHeadingItIsTheFusedHeading() {
        val b = HeadingBlend()
        var t = 0L
        for (i in 0..500) { val f = normalizeDeg(i * 3.1f); assertEquals(f, b.update(f, Float.NaN, t), 1e-3f); t += step }
    }

    /**
     * The owner's case: turning round in place many times, with a fused heading that drifts with every turn. Thirty
     * full turns at 120 degrees a second, one way and then back, with the fused heading drifting 0.6 degrees a second
     * (54 degrees off by the end of the turning one way) and the magnetic heading true but noisy (up to 4 degrees, a
     * mix of fast wobbles). The blend stays within 5 degrees of the true heading once it has settled, all the way
     * round every turn and across north each time, and is within 3.5 degrees of it after 10 s at rest.
     */
    @Test fun manyFullTurnsWithADriftingFusedHeadingDoNotCarryTheBlendAway() {
        for (direction in listOf(1f, -1f)) {
            val b = HeadingBlend()
            var t = 0L
            var truth = 37f
            var drift = 0f
            var worst = 0f
            var fusedWorst = 0f
            val turning = 90_000_000_000L    // 30 turns at 120 degrees a second
            val end = turning + 10_000_000_000L
            var shown = Float.NaN
            var i = 0
            while (t <= end) {
                val seconds = t / 1e9f
                if (t <= turning) truth = normalizeDeg(truth + direction * 120f * step / 1e9f)
                drift = 0.6f * seconds
                val fused = normalizeDeg(truth + drift)
                val noise = 2.5f * sin(i * 0.9f) + 1.5f * sin(i * 2.3f + 1f)
                val magnetic = normalizeDeg(truth + noise)
                shown = b.update(fused, magnetic, t)
                if (seconds > 15f) worst = maxOf(worst, off(shown, truth))
                fusedWorst = maxOf(fusedWorst, off(fused, truth))
                t += step; i++
            }
            assertTrue("the fused heading should have drifted far for this test to mean anything: $fusedWorst", fusedWorst > 50f)
            assertTrue("direction $direction: blend off the true heading by up to $worst degrees", worst < 5f)
            assertTrue("direction $direction: blend $shown after 10 s at rest, true $truth", off(shown, truth) < 3.5f)
        }
    }

    /** The magnetic heading's noise is smoothed: held still on a true fused heading, a magnetic one jumping 10 degrees either way barely moves it. */
    @Test fun theMagneticHeadingsNoiseIsSmoothedAway() {
        val b = HeadingBlend()
        var t = 0L
        b.update(200f, 200f, t)
        var worst = 0f
        for (i in 1..3000) {
            t += step
            val magnetic = normalizeDeg(200f + if (i % 2 == 0) 10f else -10f)
            worst = maxOf(worst, off(b.update(200f, magnetic, t), 200f))
        }
        assertTrue("moved by up to $worst", worst < 0.5f)
    }

    /** A reading whose time is not after the last one's moves it with the fused heading and steers nothing. */
    @Test fun aReadingOutOfOrderSteersNothing() {
        val b = HeadingBlend()
        b.update(100f, 100f, 1_000_000_000L)
        assertEquals(110f, b.update(110f, 50f, 1_000_000_000L), 1e-3f)
        assertEquals(120f, b.update(120f, 50f, 500_000_000L), 1e-3f)
    }

    @Test fun itCanBeStartedAgain() {
        val b = HeadingBlend()
        b.update(100f, 100f, 0L); b.update(150f, 100f, step)
        b.reset()
        assertTrue(b.value.isNaN())
        assertEquals(300f, b.update(10f, 300f, 2 * step), 1e-4f)
    }
}
