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
        val arrived: Boolean, val status: LogStatus, val holdFlat: Boolean, val accuracyM: Float, val lowAccuracy: Boolean,
    ) {
        fun lines(): Readout = readout(
            status = status, units = Units.IMPERIAL, focus = focus,
            distanceM = if (focus != FocusKind.NONE && fix != FixAge.NONE) (if (arrived) 4.0 else 300.0) else null,
            arrived = arrived && focus != FocusKind.NONE && fix != FixAge.NONE, noTarget = noTarget && focus == FocusKind.NONE,
            position = if (fix == FixAge.NONE) null else GeoPoint(43.7022, -72.2896), stale = fix == FixAge.STALE,
            fixAccuracyM = if (fix == FixAge.NONE) null else accuracyM, noPosition = noPosition, showCoordinates = coordinates,
            holdFlat = holdFlat, lowAccuracy = lowAccuracy,
        )
    }

    /** Every combination of the things the lines depend on. */
    private fun cases(): Sequence<Case> = sequence {
        for (coordinates in listOf(false, true)) for (noPosition in listOf(null, LogFailure.NO_PERMISSION, LogFailure.LOCATION_OFF))
            for (fix in FixAge.entries) for (focus in FocusKind.entries) for (noTarget in listOf(false, true))
                for (arrived in listOf(false, true)) for (status in statuses) for (holdFlat in listOf(false, true))
                    for (accuracy in listOf(5f, 30f)) for (lowAccuracy in listOf(false, true))
                        yield(Case(coordinates, noPosition, fix, focus, noTarget, arrived, status, holdFlat, accuracy, lowAccuracy))
    }

    private fun Readout.all(): List<ReadoutLine> = listOfNotNull(main, detail, latitude, longitude)
    private fun Readout.words(): List<String> = all().flatMap { listOfNotNull(it.words, it.note) }
    private fun count(words: List<String>, part: String) = words.sumOf { it.windowed(part.length).count { w -> w == part } }

    @Test fun theTableCoversEveryCombination() {
        // 2 settings x 3 reasons x 3 fixes x 3 kinds of focus x 2 x 2 x 9 statuses x 2 x 2 accuracies x 2 sensor accuracies.
        assertEquals(2 * 3 * 3 * 3 * 2 * 2 * 9 * 2 * 2 * 2, cases().count())
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
            assertEquals("$case", off.mainIsStatus, on.mainIsStatus); assertEquals("$case", off.prompt, on.prompt)
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
        lowAccuracy: Boolean = false,
    ) = readout(status, units, focus, distanceM, arrived, noTarget, position, stale, fixAccuracyM, noPosition, showCoordinates, holdFlat, lowAccuracy)

    @Test fun aCleanCompassHasNoLines() {
        assertEquals(Readout(null, null, null, null), lines(showCoordinates = false))
        assertEquals(Readout(null, null, null, null), lines(showCoordinates = false, position = hanover, fixAccuracyM = 5f))
    }

    // The prompts about the compass itself, and what gives way to what in their place.

    /**
     * `Hold flat` and `Calibrate` are about the compass working at all. They show whenever their state holds, for
     * every position state, with a waypoint or a crumb in focus or nothing: the only thing that has their place
     * before them is the second line of a press's own status, which ends by itself.
     */
    @Test fun holdFlatAndCalibrateShowInEveryPositionStateUnlessAPressesStatusHasTheirPlace() {
        for (case in cases()) {
            val lines = case.lines()
            val pressDetail = statusText(case.status, Units.IMPERIAL).second != null
            val expected = when {
                pressDetail -> null
                case.holdFlat -> ReadoutPrompt.HOLD_FLAT
                case.lowAccuracy -> ReadoutPrompt.CALIBRATE
                else -> null
            }
            assertEquals("$case", expected, lines.prompt)
            // The place holds one thing: a prompt, or a status's second line.
            assertFalse("$case", lines.prompt != null && lines.detail != null)
        }
    }

    /** The standing message of a target that can get no position gives its place up to a prompt, both lines, and has it back after. */
    @Test fun aTargetsStandingMessageGivesWayToAPromptAndReturns() {
        for (focus in listOf(FocusKind.WAYPOINT, FocusKind.CRUMB)) {
            val plain = lines(focus = focus, noPosition = LogFailure.NO_PERMISSION, showCoordinates = false)
            assertEquals("$focus", "Needs access to", plain.main?.words); assertEquals("$focus", "precise location", plain.detail?.words)
            assertNull(plain.prompt)
            for ((holdFlat, low, prompt) in listOf(
                Triple(true, false, ReadoutPrompt.HOLD_FLAT), Triple(false, true, ReadoutPrompt.CALIBRATE), Triple(true, true, ReadoutPrompt.HOLD_FLAT),
            )) {
                val l = lines(focus = focus, noPosition = LogFailure.NO_PERMISSION, showCoordinates = false, holdFlat = holdFlat, lowAccuracy = low)
                assertEquals("$focus $holdFlat $low", prompt, l.prompt)
                // Neither half of the sentence stands alone, and nothing is said in its place that is not so.
                assertNull("$focus $holdFlat $low", l.main); assertNull("$focus $holdFlat $low", l.detail)
            }
            // A message of one line has no part in the prompts' place and stays.
            val off = lines(focus = focus, noPosition = LogFailure.LOCATION_OFF, showCoordinates = false, lowAccuracy = true)
            assertEquals("$focus", ReadoutLine("Location is off", ReadoutTone.ERROR), off.main)
            assertEquals("$focus", ReadoutPrompt.CALIBRATE, off.prompt)
        }
        // A crumb that still has a distance from before shows it while the message has given way.
        val crumb = lines(focus = FocusKind.CRUMB, noPosition = LogFailure.NO_PERMISSION, distanceM = 300.0, position = hanover, stale = true, holdFlat = true, showCoordinates = false)
        assertEquals("984 ft (old)", crumb.main?.words)
        assertEquals(ReadoutPrompt.HOLD_FLAT, crumb.prompt)
    }

    /** A press's status keeps the place for its second line, as before, and the prompt is back when it ends. */
    @Test fun aPressesSecondLineHasThePromptsPlaceWhileItShows() {
        val saving = lines(status = LogStatus.Saving(5f), lowAccuracy = true)
        assertEquals("±16 ft", saving.detail?.words); assertNull(saving.prompt)
        assertEquals(ReadoutPrompt.CALIBRATE, lines(status = LogStatus.Saving(null), lowAccuracy = true).prompt)
        val denied = lines(status = LogStatus.Failed(LogFailure.NO_PERMISSION), holdFlat = true, focus = FocusKind.WAYPOINT, noPosition = LogFailure.NO_PERMISSION)
        assertEquals("precise location", denied.detail?.words); assertNull(denied.prompt)
        assertEquals(ReadoutPrompt.HOLD_FLAT, lines(holdFlat = true, lowAccuracy = true).prompt)
        assertNull(lines().prompt)
    }

    // Edges.

    @Test fun theSouthernAndEasternHemispheresGoThroughTheReadout() {
        val sydney = lines(position = GeoPoint(-33.8688, 151.2093))
        assertEquals(ReadoutLine("33.8688° S", ReadoutTone.DIM), sydney.latitude)
        assertEquals(ReadoutLine("151.2093° E", ReadoutTone.DIM), sydney.longitude)
        val old = lines(position = GeoPoint(-33.8688, 151.2093), stale = true)
        assertEquals(ReadoutLine("33.8688° S (old)", ReadoutTone.FAINT), old.latitude)
        assertEquals(ReadoutLine("151.2093° E", ReadoutTone.FAINT), old.longitude)
    }

    /** Old cannot be said of a position there is none of: with no position, `stale` changes nothing. */
    @Test fun staleWithNoPositionChangesNothing() {
        for (focus in FocusKind.entries) for (noPosition in listOf(null, LogFailure.LOCATION_OFF, LogFailure.NO_PERMISSION)) for (status in statuses) {
            assertEquals(
                "$focus $noPosition $status", lines(focus = focus, noPosition = noPosition, status = status),
                lines(focus = focus, noPosition = noPosition, status = status, stale = true),
            )
        }
        assertEquals(ReadoutLine("Locating…", ReadoutTone.DIM), lines(stale = true).longitude)
        assertEquals(ReadoutLine("Locating…", ReadoutTone.INK), lines(focus = FocusKind.WAYPOINT, stale = true).main)
    }

    /**
     * `Locating…` is never beside a position. A target with no distance while a position is known does not happen
     * today (the distance comes from the position); if it did, the position is what is known and is what is shown.
     */
    @Test fun locatingIsNeverBesideAPositionEvenWhereADistanceIsMissing() {
        for (focus in listOf(FocusKind.WAYPOINT, FocusKind.CRUMB)) for (coordinates in listOf(true, false)) for (stale in listOf(false, true))
            for (noPosition in listOf(null, LogFailure.LOCATION_OFF, LogFailure.NO_PERMISSION)) for (status in statuses) {
                val l = lines(focus = focus, distanceM = null, position = hanover, showCoordinates = coordinates, stale = stale, noPosition = noPosition, status = status)
                val words = l.words()
                assertFalse("$focus $coordinates $stale $noPosition $status: $words", words.any { "Locating…" in it } && l.latitude != null)
                if (coordinates && noPosition != LogFailure.NO_PERMISSION) assertTrue("$focus $stale $noPosition $status", l.latitude!!.words.startsWith("43.7022° N"))
            }
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
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (point in listOf(GeoPoint(bad, -72.2896), GeoPoint(43.7022, bad), GeoPoint(bad, bad))) {
                val l = lines(position = point)
                assertNull("$point", l.latitude); assertNull("$point", l.longitude)
            }
        }
    }
}
