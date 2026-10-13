package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

/** The lines of the compass's readout that say something about position, decided in one place ([readout]). */
class ReadoutTest {
    private val hanover = GeoPoint(43.7022, -72.2896)
    private val saved = LogStatus.Saved("Waypoint 3", 5f, 0L)

    private enum class FixAge { NONE, FRESH, STALE }

    /** Every status the compass can be showing. */
    private val statuses: List<LogStatus> = listOf(
        LogStatus.Idle, LogStatus.Saving(null), LogStatus.Saving(5f), saved, LogStatus.TrailComplete,
    ) + LogFailure.entries.map { LogStatus.Failed(it) }

    private data class Case(
        val coordinates: Boolean, val noPosition: LogFailure?, val fix: FixAge, val focus: FocusKind, val noTarget: Boolean,
        val arrived: Boolean, val status: LogStatus, val holdFlat: Boolean, val accuracyM: Float,
    ) {
        fun lines(): Readout = readout(
            status = status, units = Units.IMPERIAL, focus = focus,
            distanceM = if (focus != FocusKind.NONE && fix != FixAge.NONE) (if (arrived) 4.0 else 300.0) else null,
            arrived = arrived && focus != FocusKind.NONE && fix != FixAge.NONE, noTarget = noTarget && focus == FocusKind.NONE,
            position = if (fix == FixAge.NONE) null else GeoPoint(43.7022, -72.2896), stale = fix == FixAge.STALE,
            fixAccuracyM = if (fix == FixAge.NONE) null else accuracyM, noPosition = noPosition, showCoordinates = coordinates,
            holdFlat = holdFlat,
        )
    }

    /** Every combination of the things the lines depend on. */
    private fun cases(): Sequence<Case> = sequence {
        for (coordinates in listOf(false, true)) for (noPosition in listOf(null, LogFailure.NO_PERMISSION, LogFailure.LOCATION_OFF))
            for (fix in FixAge.entries) for (focus in FocusKind.entries) for (noTarget in listOf(false, true))
                for (arrived in listOf(false, true)) for (status in statuses) for (holdFlat in listOf(false, true))
                    for (accuracy in listOf(5f, 30f))
                        yield(Case(coordinates, noPosition, fix, focus, noTarget, arrived, status, holdFlat, accuracy))
    }

    private fun Readout.all(): List<ReadoutLine> = listOfNotNull(main, detail, latitude, longitude)
    private fun Readout.words(): List<String> = all().flatMap { listOfNotNull(it.words, it.note) }
    private fun count(words: List<String>, part: String) = words.sumOf { it.windowed(part.length).count { w -> w == part } }

    @Test fun theTableCoversEveryCombination() {
        // 2 settings x 3 reasons x 3 fixes x 3 kinds of focus x 2 x 2 x 9 statuses x 2 x 2 accuracies.
        assertEquals(2 * 3 * 3 * 3 * 2 * 2 * 9 * 2 * 2, cases().count())
    }

    @Test fun noTwoLinesSayTheSameThing() {
        for (case in cases()) {
            val words = case.lines().words()
            assertEquals("$case: $words", words.size, words.toSet().size)
            for (once in listOf("(old)", "Locating…", "Location is off", "Needs access to", "precise location")) {
                assertTrue("$case: '$once' in $words", count(words, once) <= 1)
            }
        }
    }

    @Test fun noTwoLinesContradictEachOther() {
        for (case in cases()) {
            val words = case.lines().words()
            val locating = words.any { "Locating…" in it }
            // Waiting for a position is not said beside a line that says none can come, or that none came.
            for (never in listOf("Location is off", "Needs access to", "No GPS fix")) {
                assertFalse("$case: $words", locating && words.any { never in it })
            }
            // And not beside a position.
            assertFalse("$case: $words", locating && (case.lines().latitude != null))
            // An arrival is not marked old or given an accuracy.
            if (words.any { it == "Arrived at target" }) assertNull("$case", case.lines().main?.note)
        }
    }

    /** L1: with the setting on, the two coordinates are there whenever there is a position, in every state but hold-flat. */
    @Test fun theCoordinatesAreShownWheneverThereIsAPositionExceptInTheHoldFlatState() {
        for (case in cases()) {
            val lines = case.lines()
            val expected = case.coordinates && !case.holdFlat && case.fix != FixAge.NONE && case.noPosition != LogFailure.NO_PERMISSION
            assertEquals("$case", expected, lines.latitude != null && lines.longitude?.words == "72.2896° W")
            if (expected) assertTrue("$case", lines.latitude!!.words.startsWith("43.7022° N"))
            if (!case.coordinates || case.holdFlat) { assertNull("$case", lines.latitude); assertNull("$case", lines.longitude) }
        }
    }

    /** The setting adds lines and changes none of the others. */
    @Test fun theSettingChangesNoOtherLine() {
        for (case in cases().filter { it.coordinates }) {
            val on = case.lines()
            val off = case.copy(coordinates = false).lines()
            assertEquals("$case", off.main, on.main); assertEquals("$case", off.detail, on.detail)
            assertEquals("$case", off.mainIsStatus, on.mainIsStatus)
        }
    }

    /** An old position is marked `(old)` once wherever a position is shown: on the distance if there is one, or on the latitude. */
    @Test fun anOldPositionIsMarkedOnce() {
        for (case in cases().filter { it.fix == FixAge.STALE }) {
            val lines = case.lines()
            val shownSomewhere = lines.latitude != null || (lines.main?.words?.endsWith("ft") == true) || lines.main?.words?.contains("ft (old)") == true
            val marks = count(lines.words(), "(old)")
            if (lines.latitude != null) assertEquals("$case: ${lines.words()}", 1, marks)
            if (!shownSomewhere) assertEquals("$case", 0, marks)
            // What is old is drawn faint or dim, never in ink.
            lines.latitude?.let { assertEquals("$case", ReadoutTone.FAINT, it.tone) }
            lines.longitude?.takeIf { lines.latitude != null }?.let { assertEquals("$case", ReadoutTone.FAINT, it.tone) }
        }
        for (case in cases().filter { it.fix != FixAge.STALE }) assertEquals("$case", 0, count(case.lines().words(), "(old)"))
    }

    // The rows one by one.

    private fun lines(
        status: LogStatus = LogStatus.Idle, focus: FocusKind = FocusKind.NONE, distanceM: Double? = null, arrived: Boolean = false,
        noTarget: Boolean = false, position: GeoPoint? = null, stale: Boolean = false, fixAccuracyM: Float? = null,
        noPosition: LogFailure? = null, showCoordinates: Boolean = true, holdFlat: Boolean = false, units: Units = Units.IMPERIAL,
    ) = readout(status, units, focus, distanceM, arrived, noTarget, position, stale, fixAccuracyM, noPosition, showCoordinates, holdFlat)

    @Test fun aCleanCompassHasNoLines() {
        assertEquals(Readout(null, null, null, null), lines(showCoordinates = false))
        assertEquals(Readout(null, null, null, null), lines(showCoordinates = false, position = hanover, fixAccuracyM = 5f))
    }

    @Test fun aWaypointTargetsDistance() {
        assertEquals(ReadoutLine("984 ft", ReadoutTone.INK), lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover).main)
        assertEquals(ReadoutLine("984 ft (old)", ReadoutTone.DIM), lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, stale = true).main)
        assertEquals(ReadoutLine("Arrived at target", ReadoutTone.INK), lines(focus = FocusKind.WAYPOINT, distanceM = 4.0, arrived = true, position = hanover).main)
        assertEquals(ReadoutLine("Arrived at target", ReadoutTone.DIM), lines(focus = FocusKind.WAYPOINT, distanceM = 4.0, arrived = true, position = hanover, stale = true).main)
        assertEquals(ReadoutLine("Locating…", ReadoutTone.INK), lines(focus = FocusKind.WAYPOINT).main)
        assertEquals(ReadoutLine("300 m", ReadoutTone.INK), lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, units = Units.METRIC).main)
        // A waypoint's distance never has an accuracy after it.
        assertNull(lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, fixAccuracyM = 100f).main?.note)
    }

    @Test fun theCrumbWalkedToHasAPoorFixesAccuracyAfterItsDistance() {
        assertEquals(ReadoutLine("984 ft", ReadoutTone.INK, note = "±328 ft"), lines(focus = FocusKind.CRUMB, distanceM = 300.0, position = hanover, fixAccuracyM = 100f).main)
        assertNull(lines(focus = FocusKind.CRUMB, distanceM = 300.0, position = hanover, fixAccuracyM = 5f).main?.note)
        assertNull(lines(focus = FocusKind.CRUMB, distanceM = 300.0, position = hanover, fixAccuracyM = 100f, stale = true).main?.note)
        assertNull(lines(focus = FocusKind.CRUMB, distanceM = 4.0, arrived = true, position = hanover, fixAccuracyM = 100f).main?.note)
    }

    @Test fun noTargetIsSaidOnlyWithWaypointsAndNoneATarget() {
        assertEquals(ReadoutLine("No target", ReadoutTone.DIM), lines(noTarget = true, showCoordinates = false).main)
        assertNull(lines(noTarget = false, showCoordinates = false).main)
    }

    @Test fun aStatusTakesTheDistancesRowAndItsSecondLineTheRowUnderIt() {
        val base = lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, fixAccuracyM = 5f, status = saved)
        assertEquals(ReadoutLine("Saved Waypoint 3", ReadoutTone.CONFIRM), base.main)
        assertEquals(ReadoutLine("±16 ft", ReadoutTone.DIM), base.detail)
        assertTrue(base.mainIsStatus)
        assertEquals(ReadoutLine("Saving…", ReadoutTone.INK), lines(status = LogStatus.Saving(null)).main)
        assertNull(lines(status = LogStatus.Saving(null)).detail)
        assertEquals(ReadoutLine("±16 ft", ReadoutTone.DIM), lines(status = LogStatus.Saving(5f)).detail)
        assertEquals(ReadoutLine("Trail complete", ReadoutTone.CONFIRM), lines(status = LogStatus.TrailComplete).main)
        val denied = lines(status = LogStatus.Failed(LogFailure.NO_PERMISSION))
        assertEquals(ReadoutLine("Needs access to", ReadoutTone.ERROR), denied.main)
        assertEquals(ReadoutLine("precise location", ReadoutTone.ERROR), denied.detail)
        val noFix = lines(status = LogStatus.Failed(LogFailure.NO_FIX))
        assertEquals(ReadoutLine("No GPS fix", ReadoutTone.ERROR), noFix.main)
        assertEquals(ReadoutLine("Try again outdoors", ReadoutTone.DIM), noFix.detail)
        assertFalse(lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover).mainIsStatus)
    }

    /** A status and the coordinates together: the whole of a save, and every failure. */
    @Test fun theCoordinatesStayThroughASaveAndThroughFailures() {
        for (status in statuses) {
            val l = lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, fixAccuracyM = 5f, status = status)
            assertEquals("$status", ReadoutLine("43.7022° N", ReadoutTone.DIM), l.latitude)
            assertEquals("$status", ReadoutLine("72.2896° W", ReadoutTone.DIM), l.longitude)
        }
    }

    @Test fun inTheHoldFlatStateThereAreNoCoordinatesAndTheOtherLinesStay() {
        val l = lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, holdFlat = true)
        assertNull(l.latitude); assertNull(l.longitude)
        assertEquals(ReadoutLine("984 ft", ReadoutTone.INK), l.main)
        assertNull(lines(holdFlat = true).longitude)
    }

    @Test fun anOldFixIsMarkedOnTheDistanceWhereThereIsOneAndOnTheLatitudeOtherwise() {
        val withTarget = lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, stale = true)
        assertEquals("984 ft (old)", withTarget.main?.words)
        assertEquals(ReadoutLine("43.7022° N", ReadoutTone.FAINT), withTarget.latitude)
        assertEquals(ReadoutLine("72.2896° W", ReadoutTone.FAINT), withTarget.longitude)
        val alone = lines(position = hanover, stale = true)
        assertEquals(ReadoutLine("43.7022° N (old)", ReadoutTone.FAINT), alone.latitude)
        assertEquals(ReadoutLine("72.2896° W", ReadoutTone.FAINT), alone.longitude)
        // Where a status has the distance's row, or the wearer has arrived, the distance does not say it.
        assertEquals("43.7022° N (old)", lines(focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, stale = true, status = saved).latitude?.words)
        assertEquals("43.7022° N (old)", lines(focus = FocusKind.WAYPOINT, distanceM = 4.0, arrived = true, position = hanover, stale = true).latitude?.words)
    }

    /** Before the first fix one line says so: the distance where there is a target, the coordinates' lower row otherwise. */
    @Test fun waitingForAPositionIsSaidOnce() {
        val alone = lines()
        assertNull(alone.main); assertNull(alone.latitude)
        assertEquals(ReadoutLine("Locating…", ReadoutTone.DIM), alone.longitude)
        val withTarget = lines(focus = FocusKind.WAYPOINT)
        assertEquals("Locating…", withTarget.main?.words)
        assertNull(withTarget.latitude); assertNull(withTarget.longitude)
        // While a save waits for its fix the coordinates wait too.
        assertEquals("Locating…", lines(status = LogStatus.Saving(null)).longitude?.words)
        // A press that found no fix has said so; nothing is added.
        assertNull(lines(status = LogStatus.Failed(LogFailure.NO_FIX)).longitude)
        // With the setting off nothing is said for the coordinates.
        assertNull(lines(showCoordinates = false).longitude)
    }

    /** That location is switched off is said once: by the status of a press, by the target's row, or by the coordinates' row. */
    @Test fun locationOffIsSaidOnce() {
        val off = LogFailure.LOCATION_OFF
        val alone = lines(noPosition = off)
        assertNull(alone.main)
        assertEquals(ReadoutLine("Location is off", ReadoutTone.DIM), alone.longitude)
        val pressed = lines(noPosition = off, status = LogStatus.Failed(off))
        assertEquals(ReadoutLine("Location is off", ReadoutTone.ERROR), pressed.main)
        assertNull(pressed.longitude)
        for (focus in listOf(FocusKind.WAYPOINT, FocusKind.CRUMB)) {
            val target = lines(noPosition = off, focus = focus)
            assertEquals("$focus", ReadoutLine("Location is off", ReadoutTone.ERROR), target.main)
            assertNull("$focus", target.longitude)
            // The same with the setting off: the target's own row says it.
            assertEquals("$focus", ReadoutLine("Location is off", ReadoutTone.ERROR), lines(noPosition = off, focus = focus, showCoordinates = false).main)
        }
        // A fix from before location was switched off is still shown, as old; a waypoint keeps its distance.
        val kept = lines(noPosition = off, focus = FocusKind.WAYPOINT, distanceM = 300.0, position = hanover, stale = true)
        assertEquals("984 ft (old)", kept.main?.words)
        assertEquals("43.7022° N", kept.latitude?.words)
    }

    /** Without the precise permission there are no coordinates and nothing in their place; a target's row says what is missing. */
    @Test fun withoutThePermissionThereAreNoCoordinates() {
        val denied = LogFailure.NO_PERMISSION
        assertEquals(Readout(null, null, null, null), lines(noPosition = denied))
        for (focus in listOf(FocusKind.WAYPOINT, FocusKind.CRUMB)) {
            val target = lines(noPosition = denied, focus = focus)
            assertEquals("$focus", ReadoutLine("Needs access to", ReadoutTone.ERROR), target.main)
            assertEquals("$focus", ReadoutLine("precise location", ReadoutTone.ERROR), target.detail)
            assertNull(target.latitude); assertNull(target.longitude)
        }
    }

    @Test fun aPositionThatIsNotANumberShowsNoCoordinates() {
        assertNull(lines(position = GeoPoint(Double.NaN, 0.0)).latitude)
        assertNull(lines(position = GeoPoint(Double.NaN, 0.0)).longitude)
        assertNull(lines(position = GeoPoint(0.0, Double.POSITIVE_INFINITY)).latitude)
    }
}
