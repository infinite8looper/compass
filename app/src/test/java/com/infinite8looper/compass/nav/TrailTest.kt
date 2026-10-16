package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class TrailTest {
    // Press timing. Times are in milliseconds on one clock; a press is a DOWN and, when it is let go as a short
    // press, a PRESSED.

    /** Feeds events and collects what they are reported as, in order. */
    private class Presses(val doubleEnabled: Boolean) {
        var waiting: WaitingPress? = null
        val kinds = mutableListOf<PressKind>()
        fun on(event: PressEvent, atMs: Long): PressKind? {
            val step = pressKind(waiting, event, atMs, doubleEnabled)
            waiting = step.waiting
            step.kind?.let { kinds += it }
            return step.kind
        }
        /** A short press: down at [atMs], let go [heldMs] later. */
        fun press(atMs: Long, heldMs: Long = 40): PressKind? {
            on(PressEvent.DOWN, atMs)
            return on(PressEvent.PRESSED, atMs + heldMs)
        }
    }

    @Test fun onePressIsASingleOnlyOnceTheWindowHasPassed() {
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        assertEquals(WaitingPress(1_040), p.waiting)
        assertEquals(1_040 + DOUBLE_PRESS_WINDOW_MS + 1, singleDueAtMs(p.waiting))
        // A timer that runs early, or exactly at the end of the window, reports nothing yet.
        assertNull(p.on(PressEvent.WINDOW_OVER, 1_200))
        assertNull(p.on(PressEvent.WINDOW_OVER, 1_040 + DOUBLE_PRESS_WINDOW_MS))
        assertEquals(PressKind.SINGLE, p.on(PressEvent.WINDOW_OVER, 1_040 + DOUBLE_PRESS_WINDOW_MS + 1))
        assertNull(p.waiting)
        // Nothing is left over: a timer that runs again reports nothing.
        assertNull(p.on(PressEvent.WINDOW_OVER, 5_000))
        assertEquals(listOf(PressKind.SINGLE), p.kinds)
    }

    @Test fun twoPressesWithinTheWindowAreOneDouble() {
        assertEquals(300L, DOUBLE_PRESS_WINDOW_MS)
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        // The second goes down exactly 300 ms after the first was let go.
        assertNull(p.on(PressEvent.DOWN, 1_340))
        assertEquals(WaitingPress(1_040, secondDown = true), p.waiting)
        // No single is due while the second press is down, however long the window has been over.
        assertNull(singleDueAtMs(p.waiting))
        assertNull(p.on(PressEvent.WINDOW_OVER, 1_400))
        assertEquals(PressKind.DOUBLE, p.on(PressEvent.PRESSED, 1_420))
        assertNull(p.waiting)
        assertNull(p.on(PressEvent.WINDOW_OVER, 2_000))
        assertEquals(listOf(PressKind.DOUBLE), p.kinds)
    }

    @Test fun twoPressesAtOnceAsAnInjectedPairAreOneDouble() {
        // `adb shell input keyevent` gives a press its down and its up at the same instant.
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000, heldMs = 0))
        assertEquals(PressKind.DOUBLE, p.press(1_005, heldMs = 0))
        assertEquals(listOf(PressKind.DOUBLE), p.kinds)
    }

    @Test fun twoPressesAt301MsAreTwoSingles() {
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        // The timer has not run yet when the second press goes down 301 ms after the first was let go: the first
        // is a single at that moment, and the second waits in its turn.
        assertEquals(PressKind.SINGLE, p.on(PressEvent.DOWN, 1_341))
        assertNull(p.waiting)
        assertNull(p.on(PressEvent.PRESSED, 1_380))
        assertEquals(WaitingPress(1_380), p.waiting)
        assertEquals(PressKind.SINGLE, p.on(PressEvent.WINDOW_OVER, 1_681))
        assertEquals(listOf(PressKind.SINGLE, PressKind.SINGLE), p.kinds)
    }

    @Test fun twoPressesAt301MsAreTwoSinglesWhenTheTimerRunsBetweenThem() {
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        assertEquals(PressKind.SINGLE, p.on(PressEvent.WINDOW_OVER, 1_341))
        assertNull(p.press(1_341))
        assertEquals(PressKind.SINGLE, p.on(PressEvent.WINDOW_OVER, 1_341 + 40 + 301))
        assertEquals(listOf(PressKind.SINGLE, PressKind.SINGLE), p.kinds)
    }

    @Test fun threeQuickPressesAreADoubleAndThenASingle() {
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        assertEquals(PressKind.DOUBLE, p.press(1_100))
        // The third starts over: it is not a second double with the second press.
        assertNull(p.press(1_200))
        assertEquals(WaitingPress(1_240), p.waiting)
        assertEquals(PressKind.SINGLE, p.on(PressEvent.WINDOW_OVER, 1_541))
        assertEquals(listOf(PressKind.DOUBLE, PressKind.SINGLE), p.kinds)
    }

    @Test fun fourQuickPressesAreTwoDoubles() {
        val p = Presses(doubleEnabled = true)
        p.press(1_000); p.press(1_100); p.press(1_200); p.press(1_300)
        assertEquals(listOf(PressKind.DOUBLE, PressKind.DOUBLE), p.kinds)
        assertNull(p.waiting)
    }

    @Test fun withTheSettingOffEveryPressIsASingleAtOnce() {
        val p = Presses(doubleEnabled = false)
        for (at in listOf(1_000L, 1_005L, 1_100L, 1_400L, 5_000L)) {
            assertNull(p.on(PressEvent.DOWN, at))
            assertEquals(PressKind.SINGLE, p.on(PressEvent.PRESSED, at))
            // Nothing waits, so there is nothing for a timer to do.
            assertNull(p.waiting)
            assertNull(singleDueAtMs(p.waiting))
            assertNull(p.on(PressEvent.WINDOW_OVER, at + 400))
        }
        assertEquals(List(5) { PressKind.SINGLE }, p.kinds)
    }

    @Test fun aPressThatIsHeldOrCanceledIsNoPress() {
        val p = Presses(doubleEnabled = true)
        p.on(PressEvent.DOWN, 1_000)
        assertNull(p.on(PressEvent.DROPPED, 1_900))
        assertNull(p.waiting)
        assertNull(p.on(PressEvent.WINDOW_OVER, 3_000))
        assertEquals(emptyList<PressKind>(), p.kinds)
        // With the setting off as well.
        val q = Presses(doubleEnabled = false)
        q.on(PressEvent.DOWN, 1_000)
        assertNull(q.on(PressEvent.DROPPED, 1_900))
        assertEquals(emptyList<PressKind>(), q.kinds)
    }

    @Test fun aSecondPressThatIsHeldLeavesTheFirstASingle() {
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        assertNull(p.on(PressEvent.DOWN, 1_200))
        assertEquals(PressKind.SINGLE, p.on(PressEvent.DROPPED, 2_100))
        assertNull(p.waiting)
        assertEquals(listOf(PressKind.SINGLE), p.kinds)
    }

    @Test fun aSecondReleaseSeenWithoutItsDownIsJudgedByItsOwnTime() {
        val p = Presses(doubleEnabled = true)
        assertNull(p.press(1_000))
        assertEquals(PressKind.DOUBLE, p.on(PressEvent.PRESSED, 1_340))
        assertNull(p.waiting)
        assertNull(p.press(2_000))
        // Too late for a double: the first is a single and this release waits in its turn.
        assertEquals(PressKind.SINGLE, p.on(PressEvent.PRESSED, 2_341))
        assertEquals(WaitingPress(2_341), p.waiting)
    }

    @Test fun aPressThatWaitsWhenTheSettingIsSwitchedOffIsStillReported() {
        val step = pressKind(WaitingPress(1_000), PressEvent.WINDOW_OVER, 1_100, doubleEnabled = false)
        assertEquals(PressStep(null, PressKind.SINGLE), step)
    }

    // The trail's modes.

    private fun trail(mode: TrailMode, vararg ids: Long) = Trail(mode, ids.toList())

    @Test fun aDoublePressWithNoTrailStartsLaying() {
        assertEquals(TrailChange(trail(TrailMode.LAYING)), nextTrail(trail(TrailMode.OFF), TrailEvent.DoublePress))
    }

    @Test fun aDoublePressWhileOffWithCrumbsLeftTakesUpLayingAndKeepsThem() {
        assertEquals(TrailChange(trail(TrailMode.LAYING, 3, 2, 1)), nextTrail(trail(TrailMode.OFF, 3, 2, 1), TrailEvent.DoublePress))
    }

    @Test fun aDoublePressWhileLayingStartsRetracingAndWalksToTheNewestCrumb() {
        val change = nextTrail(trail(TrailMode.LAYING, 5, 4, 2), TrailEvent.DoublePress)
        assertEquals(TrailChange(trail(TrailMode.RETRACING, 5, 4, 2)), change)
        assertEquals(5L, walkingTo(change.trail))
        // Only a trail that is being retraced has a crumb that is walked to.
        assertNull(walkingTo(trail(TrailMode.LAYING, 5, 4, 2)))
        assertNull(walkingTo(trail(TrailMode.OFF, 5, 4, 2)))
    }

    @Test fun anEmptyTrailCannotBeRetraced() {
        // The double press ends the empty trail instead.
        val change = nextTrail(trail(TrailMode.LAYING), TrailEvent.DoublePress)
        assertEquals(TrailChange(trail(TrailMode.OFF)), change)
        assertFalse(change.completed)
        assertNull(walkingTo(trail(TrailMode.RETRACING)))
        // A stored mode that says retracing with no crumbs to walk to is no trail.
        assertEquals(TrailMode.OFF, effectiveTrailMode(TrailMode.RETRACING, settingOn = true, crumbs = 0))
        assertEquals(TrailMode.RETRACING, effectiveTrailMode(TrailMode.RETRACING, settingOn = true, crumbs = 1))
        assertEquals(TrailMode.LAYING, effectiveTrailMode(TrailMode.LAYING, settingOn = true, crumbs = 0))
    }

    @Test fun aDoublePressWhileRetracingReturnsToLayingAndKeepsWhatRemains() {
        assertEquals(TrailChange(trail(TrailMode.LAYING, 2, 1)), nextTrail(trail(TrailMode.RETRACING, 2, 1), TrailEvent.DoublePress))
    }

    @Test fun withTheSettingOffThereIsNoModeWhateverIsStored() {
        for (stored in TrailMode.entries) for (crumbs in listOf(0, 4)) {
            assertEquals(TrailMode.OFF, effectiveTrailMode(stored, settingOn = false, crumbs = crumbs))
        }
    }

    @Test fun aCrumbIsDroppedOnlyWhileLayingAndBecomesTheNewest() {
        assertEquals(TrailChange(trail(TrailMode.LAYING, 9)), nextTrail(trail(TrailMode.LAYING), TrailEvent.CrumbDropped(9)))
        assertEquals(TrailChange(trail(TrailMode.LAYING, 9, 4, 2)), nextTrail(trail(TrailMode.LAYING, 4, 2), TrailEvent.CrumbDropped(9)))
        for (mode in listOf(TrailMode.OFF, TrailMode.RETRACING)) {
            assertEquals(TrailChange(trail(mode, 4, 2)), nextTrail(trail(mode, 4, 2), TrailEvent.CrumbDropped(9)))
        }
        assertEquals(SinglePress.CRUMB, singlePress(TrailMode.LAYING))
        assertEquals(SinglePress.WAYPOINT, singlePress(TrailMode.OFF))
        assertEquals(SinglePress.WAYPOINT, singlePress(TrailMode.RETRACING))
    }

    @Test fun arrivalConsumesExactlyOneCrumbNewestFirst() {
        var t = trail(TrailMode.RETRACING, 5, 4, 2, 1)
        val removed = mutableListOf<Long>()
        while (t.mode == TrailMode.RETRACING) {
            val before = t.crumbIds.size
            val change = nextTrail(t, TrailEvent.Arrived(walkingTo(t)!!))
            assertEquals(before - 1, change.trail.crumbIds.size)
            removed += change.removedId!!
            assertEquals(change.trail.crumbIds.isEmpty(), change.completed)
            t = change.trail
        }
        assertEquals(listOf(5L, 4L, 2L, 1L), removed)
    }

    @Test fun arrivingAtTheLastCrumbEndsTheTrail() {
        val change = nextTrail(trail(TrailMode.RETRACING, 7), TrailEvent.Arrived(7))
        assertEquals(TrailChange(trail(TrailMode.OFF), removedId = 7, completed = true), change)
        // With one more left it goes on.
        assertEquals(
            TrailChange(trail(TrailMode.RETRACING, 3), removedId = 7), nextTrail(trail(TrailMode.RETRACING, 7, 3), TrailEvent.Arrived(7)),
        )
    }

    @Test fun anArrivalAtACrumbThatIsNotTheOneWalkedToChangesNothing() {
        val t = trail(TrailMode.RETRACING, 5, 4, 2)
        // Said twice for the same crumb, as two position fixes close together would: the second finds it gone.
        val once = nextTrail(t, TrailEvent.Arrived(5))
        assertEquals(TrailChange(trail(TrailMode.RETRACING, 4, 2), removedId = 5), once)
        assertEquals(TrailChange(once.trail), nextTrail(once.trail, TrailEvent.Arrived(5)))
        // An older crumb is never consumed ahead of the newest, and nothing is consumed while laying or off.
        assertEquals(TrailChange(t), nextTrail(t, TrailEvent.Arrived(4)))
        assertEquals(TrailChange(t), nextTrail(t, TrailEvent.Arrived(2)))
        for (mode in listOf(TrailMode.OFF, TrailMode.LAYING)) {
            assertEquals(TrailChange(trail(mode, 5, 4)), nextTrail(trail(mode, 5, 4), TrailEvent.Arrived(5)))
        }
    }

    // Arrival from positions. One degree of latitude is 111 195 m on the app's sphere.

    private fun north(meters: Double) = GeoPoint(meters / 111_195.0, 0.0)
    private fun crumb(id: Long, metersNorth: Double) = CrumbPoint(id, north(metersNorth))

    /** A fix that may consume a crumb: accurate enough to be saved, with the compass in view. */
    private fun goodFix(trail: Trail, crumbs: List<CrumbPoint>, arrivedAt: Long?, position: GeoPoint, units: Units) =
        trailAfterFix(trail, crumbs, arrivedAt, position, units, accuracyM = 5f, compassInView = true)

    /** Walks the positions given, in meters north, one fix each, and returns the ids consumed after each fix. */
    private fun walk(crumbs: List<CrumbPoint>, positions: List<Double>, units: Units = Units.METRIC): List<List<Long>> {
        var t = Trail(TrailMode.RETRACING, crumbs.map { it.id })
        var left = crumbs
        var arrivedAt: Long? = null
        return positions.map { p ->
            val fix = goodFix(t, left, arrivedAt, north(p), units)
            arrivedAt = fix.arrivedAt
            t = fix.change.trail
            left = left.filter { it.id in t.crumbIds }
            crumbs.map { it.id } - t.crumbIds.toSet()
        }
    }

    @Test fun aPositionThatJittersAcrossTheLineConsumesOneCrumbNotTwo() {
        // The newest crumb is at 0 and the one before it 30 m on. The fixes waver around 10 m from the newest.
        val consumed = walk(listOf(crumb(2, 0.0), crumb(1, -30.0)), listOf(10.4, 9.8, 10.3, 9.7, 10.2, 9.9, 10.1))
        assertEquals(listOf(emptyList(), listOf(2L), listOf(2L), listOf(2L), listOf(2L), listOf(2L), listOf(2L)), consumed)
    }

    @Test fun oneFixConsumesAtMostOneCrumbEvenWhenSeveralAreWithinReach() {
        // Three crumbs dropped within a few meters of each other, and the wearer standing still among them.
        val consumed = walk(listOf(crumb(3, 0.0), crumb(2, 2.0), crumb(1, 4.0)), listOf(1.0, 1.0, 1.0, 1.0))
        assertEquals(listOf(listOf(3L), listOf(3L, 2L), listOf(3L, 2L, 1L), listOf(3L, 2L, 1L)), consumed)
    }

    @Test fun aCrumbFurtherBackIsNotConsumedWhileTheNewestIsStillAhead() {
        // The wearer stands on the older crumb; the newest is 50 m off. Nothing is consumed out of order.
        val consumed = walk(listOf(crumb(2, 50.0), crumb(1, 0.0)), listOf(0.0, 3.0, 0.0))
        assertEquals(listOf(emptyList<Long>(), emptyList(), emptyList()), consumed)
    }

    @Test fun arrivalAtACrumbUsesTheUnitsOfTheArrivalDistance() {
        // 9.5 m is under 10 m and over 30 ft (9.144 m).
        assertEquals(listOf(listOf(1L)), walk(listOf(crumb(1, 0.0)), listOf(9.5), Units.METRIC))
        assertEquals(listOf(emptyList<Long>()), walk(listOf(crumb(1, 0.0)), listOf(9.5), Units.IMPERIAL))
    }

    @Test fun aCrumbWhoseRemovalHasNotLandedYetIsStillArrivedAtInsideTheLeavingDistance() {
        // The store still lists crumb 2 when the next fix comes, 11 m from it: it stays arrived at (it left at 12 m),
        // so its removal is asked for again, and the crumb behind it is not touched.
        val crumbs = listOf(crumb(2, 0.0), crumb(1, -30.0))
        val t = Trail(TrailMode.RETRACING, listOf(2, 1))
        val first = goodFix(t, crumbs, null, north(9.8), Units.METRIC)
        assertEquals(2L, first.arrivedAt)
        assertEquals(2L, first.change.removedId)
        val again = goodFix(t, crumbs, first.arrivedAt, north(11.0), Units.METRIC)
        assertEquals(2L, again.change.removedId)
        // Without having arrived, 11 m is not an arrival.
        assertNull(goodFix(t, crumbs, null, north(11.0), Units.METRIC).change.removedId)
        // Beyond 12 m it is no longer arrived at.
        val gone = goodFix(t, crumbs, first.arrivedAt, north(12.5), Units.METRIC)
        assertNull(gone.change.removedId)
        assertNull(gone.arrivedAt)
    }

    @Test fun nothingIsConsumedWhileLayingOrOffOrWithNoCrumbs() {
        val crumbs = listOf(crumb(1, 0.0))
        for (mode in listOf(TrailMode.OFF, TrailMode.LAYING)) {
            val fix = goodFix(Trail(mode, listOf(1)), crumbs, null, north(0.0), Units.METRIC)
            assertEquals(TrailChange(Trail(mode, listOf(1))), fix.change)
        }
        val empty = goodFix(Trail(TrailMode.RETRACING, emptyList()), emptyList(), null, north(0.0), Units.METRIC)
        assertEquals(TrailChange(Trail(TrailMode.RETRACING, emptyList())), empty.change)
    }

    // What the screens show of the trail.

    private val east = GeoPoint(0.0, 400.0 / 111_195.0)

    @Test fun whileLayingEveryCrumbIsATickAndNoneIsWalkedTo() {
        val ui = buildTrail(TrailMode.LAYING, listOf(CrumbPoint(2, north(400.0)), CrumbPoint(1, east)), north(0.0), null, NorthRef.TRUE)
        assertEquals(TrailMode.LAYING, ui.mode)
        assertEquals(2, ui.count)
        assertNull(ui.current)
        assertEquals(listOf(0f, 90f), ui.tickAngles)
    }

    @Test fun whileRetracingTheNewestCrumbIsTheFocusedTargetAndTheRestAreTicks() {
        val ui = buildTrail(
            TrailMode.RETRACING, listOf(CrumbPoint(7, east), CrumbPoint(4, north(400.0)), CrumbPoint(2, north(-400.0))),
            north(0.0), declination = -14f, north = NorthRef.MAGNETIC,
        )
        val current = ui.current!!
        assertTrue(current.focused); assertTrue(current.isCrumb)
        // Its place in the trail is its number among the crumbs that are left, the oldest being 1.
        assertEquals("Crumb 3", current.name)
        assertEquals(crumbTargetId(7), current.id)
        assertEquals(104f, current.dialAngleDeg!!, 0.01f)
        assertEquals(400.0, current.distanceM!!, 0.5)
        assertFalse(current.arrived)
        assertEquals(listOf(14f, 194f), ui.tickAngles)
        assertEquals(3, ui.count)
    }

    @Test fun aCrumbsTargetIdIsNeverAWaypointsIdOrTheIdForNoTarget() {
        for (id in listOf(1L, 2L, 500L)) {
            assertTrue(crumbTargetId(id) < 0)
            assertNotEquals(crumbTargetId(id), crumbTargetId(id + 1))
        }
    }

    @Test fun withNoPositionThereAreNoTicksAndTheCrumbWalkedToHasNoBearing() {
        val ui = buildTrail(TrailMode.RETRACING, listOf(CrumbPoint(2, east), CrumbPoint(1, north(400.0))), null, null, NorthRef.TRUE)
        assertEquals(emptyList<Float>(), ui.tickAngles)
        assertNull(ui.current!!.dialAngleDeg); assertNull(ui.current!!.distanceM)
        assertEquals("Crumb 2", ui.current!!.name)
        assertEquals(2, ui.count)
    }

    @Test fun aCrumbWithinTheArrivalDistanceHasNoTickAndNoBearing() {
        val laying = buildTrail(TrailMode.LAYING, listOf(CrumbPoint(2, north(4.0)), CrumbPoint(1, east)), north(0.0), null, NorthRef.TRUE)
        assertEquals(listOf(90f), laying.tickAngles)
        val retracing = buildTrail(TrailMode.RETRACING, listOf(CrumbPoint(2, north(4.0))), north(0.0), null, NorthRef.TRUE)
        assertNull(retracing.current!!.dialAngleDeg)
        assertTrue(retracing.current!!.arrived)
        assertEquals(4.0, retracing.current!!.distanceM!!, 0.01)
    }

    @Test fun theCrumbWalkedToStaysArrivedUntilTheLeavingDistance() {
        val crumbs = listOf(CrumbPoint(2, north(11.0)))
        val fresh = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE)
        assertFalse(fresh.current!!.arrived)
        val held = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE, previouslyArrived = setOf(crumbTargetId(2)))
        assertTrue(held.current!!.arrived)
        assertNull(held.current!!.dialAngleDeg)
    }

    @Test fun ticksAreAtWholeDegreesAndCrumbsInOneDirectionShareOne() {
        // 500 crumbs strung out to the north-east, and one to the south.
        val line = (1..500).map { CrumbPoint(it.toLong(), GeoPoint(it * 20.0 / 111_195.0, it * 20.0 / 111_195.0)) }
        val ui = buildTrail(TrailMode.LAYING, line + CrumbPoint(900, north(-200.0)), north(0.0), null, NorthRef.TRUE)
        assertEquals(listOf(45f, 180f), ui.tickAngles)
        assertEquals(501, ui.count)
        // Whatever the trail, there are never more ticks than whole degrees.
        val ring = (0 until 3_000).map { i ->
            val a = Math.toRadians(i * 0.12)
            CrumbPoint(i + 1L, GeoPoint(300.0 * Math.cos(a) / 111_195.0, 300.0 * Math.sin(a) / 111_195.0))
        }
        val around = buildTrail(TrailMode.LAYING, ring, north(0.0), null, NorthRef.TRUE)
        assertEquals(360, around.tickAngles.size)
        assertEquals(around.tickAngles.sorted(), around.tickAngles)
        assertEquals(around.tickAngles.distinct(), around.tickAngles)
        assertTrue(around.tickAngles.all { it >= 0f && it < 360f && it == Math.round(it).toFloat() })
    }

    @Test fun aTrailThatIsOffStillHasItsCountAndItsTicks() {
        val ui = buildTrail(TrailMode.OFF, listOf(CrumbPoint(1, east)), north(0.0), null, NorthRef.TRUE)
        assertEquals(1, ui.count); assertNull(ui.current); assertEquals(listOf(90f), ui.tickAngles)
        assertEquals(TrailUi(), buildTrail(TrailMode.OFF, emptyList(), north(0.0), null, NorthRef.TRUE))
    }

    @Test fun theWordsOfTheTrail() {
        assertEquals("Trail 0", trailLabel(0))
        assertEquals("Trail 12", trailLabel(12))
        assertEquals("Crumb 3", crumbName(3))
        assertEquals("Off", trailRowDetail(TrailMode.OFF, 0))
        assertEquals("Off, 1 crumb", trailRowDetail(TrailMode.OFF, 1))
        assertEquals("Off, 4 crumbs", trailRowDetail(TrailMode.OFF, 4))
        assertEquals("Laying, 0 crumbs", trailRowDetail(TrailMode.LAYING, 0))
        assertEquals("Laying, 1 crumb", trailRowDetail(TrailMode.LAYING, 1))
        assertEquals("Laying, 5 crumbs", trailRowDetail(TrailMode.LAYING, 5))
        assertEquals("Retracing, 5 left", trailRowDetail(TrailMode.RETRACING, 5))
        // What a tap on the row is announced as doing is what the double press does.
        assertEquals("Start trail", trailRowAction(TrailMode.OFF, 0))
        assertEquals("Lay trail", trailRowAction(TrailMode.OFF, 3))
        assertEquals("End trail", trailRowAction(TrailMode.LAYING, 0))
        assertEquals("Retrace trail", trailRowAction(TrailMode.LAYING, 3))
        assertEquals("Lay trail", trailRowAction(TrailMode.RETRACING, 3))
    }

    @Test fun theTrailAsksForLocationOnlyWhileItIsLaidOrRetracedWithTheCompassOnScreen() {
        for (mode in TrailMode.entries) for (compass in listOf(true, false)) {
            assertEquals("$mode $compass", mode != TrailMode.OFF && compass, trailWantsLocation(mode, compass))
        }
        // Only retracing keeps the screen on, and only with the compass in view.
        for (mode in TrailMode.entries) for (compass in listOf(true, false)) {
            assertEquals("$mode $compass", mode == TrailMode.RETRACING && compass, trailKeepsAwake(mode, compass, locationCanRun = true))
        }
    }

    @Test fun theStoredModeIsReadBackAndAnythingElseIsOff() {
        for (mode in TrailMode.entries) assertEquals(mode, trailModeOf(mode.name))
        assertEquals(TrailMode.OFF, trailModeOf(null))
        assertEquals(TrailMode.OFF, trailModeOf(""))
        assertEquals(TrailMode.OFF, trailModeOf("WALKING"))
    }

    // Fix round 1.

    @Test fun retracingKeepsTheScreenOnOnlyWhileLocationCanRun() {
        assertTrue(trailKeepsAwake(TrailMode.RETRACING, compassInView = true, locationCanRun = true))
        assertFalse(trailKeepsAwake(TrailMode.RETRACING, compassInView = true, locationCanRun = false))
        for (mode in TrailMode.entries) assertFalse(trailKeepsAwake(mode, compassInView = false, locationCanRun = false))
        // Why there can be no position, in the words of the logging failures; the permission comes first.
        assertNull(noPositionReason(hasPermission = true, locationOn = true))
        assertEquals(LogFailure.NO_PERMISSION, noPositionReason(hasPermission = false, locationOn = true))
        assertEquals(LogFailure.NO_PERMISSION, noPositionReason(hasPermission = false, locationOn = false))
        assertEquals(LogFailure.LOCATION_OFF, noPositionReason(hasPermission = true, locationOn = false))
    }

    @Test fun aFixThatIsNotAccurateEnoughToSaveConsumesNoCrumb() {
        val crumbs = listOf(crumb(2, 0.0), crumb(1, -30.0))
        val t = Trail(TrailMode.RETRACING, listOf(2, 1))
        // The rule is the one a save uses: 15 m or better.
        assertEquals(15f, GOOD_FIX_METERS, 0f)
        for (accuracy in listOf(15.1f, 30f, 80f, Float.NaN)) {
            val fix = trailAfterFix(t, crumbs, null, north(3.0), Units.METRIC, accuracyM = accuracy, compassInView = true)
            assertEquals("$accuracy", TrailChange(t), fix.change)
            assertNull("$accuracy", fix.arrivedAt)
        }
        for (accuracy in listOf(0f, 5f, 15f)) {
            assertEquals("$accuracy", 2L, trailAfterFix(t, crumbs, null, north(3.0), Units.METRIC, accuracyM = accuracy, compassInView = true).change.removedId)
        }
        // A poor fix inside the distance, then a good one: the crumb goes with the good one, once.
        val poor = trailAfterFix(t, crumbs, null, north(3.0), Units.METRIC, accuracyM = 40f, compassInView = true)
        val good = trailAfterFix(poor.change.trail, crumbs, poor.arrivedAt, north(3.0), Units.METRIC, accuracyM = 8f, compassInView = true)
        assertEquals(TrailChange(Trail(TrailMode.RETRACING, listOf(1)), removedId = 2), good.change)
        // A crumb that had been arrived at is not kept so by a poor fix.
        assertNull(trailAfterFix(t, crumbs, 2, north(11.0), Units.METRIC, accuracyM = 40f, compassInView = true).arrivedAt)
    }

    @Test fun aCrumbIsConsumedOnlyWhileTheCompassIsInView() {
        val crumbs = listOf(crumb(2, 0.0), crumb(1, -30.0))
        val t = Trail(TrailMode.RETRACING, listOf(2, 1))
        val away = trailAfterFix(t, crumbs, null, north(1.0), Units.METRIC, accuracyM = 5f, compassInView = false)
        assertEquals(TrailChange(t), away.change)
        assertNull(away.arrivedAt)
        val back = trailAfterFix(t, crumbs, away.arrivedAt, north(1.0), Units.METRIC, accuracyM = 5f, compassInView = true)
        assertEquals(2L, back.change.removedId)
    }

    @Test fun aPoorFixShowsNoArrivalForTheCrumbWalkedTo() {
        val crumbs = listOf(CrumbPoint(2, north(4.0)))
        val poor = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE, fixGoodEnough = false)
        assertFalse(poor.current!!.arrived)
        assertEquals(0f, poor.current!!.dialAngleDeg!!, 0.01f)
        assertEquals(4.0, poor.current!!.distanceM!!, 0.01)
        assertFalse(crumbTargetId(2) in poor.arrivedIds)
        // Not even when it had counted as arrived at before.
        val held = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE, previouslyArrived = setOf(crumbTargetId(2)), fixGoodEnough = false)
        assertFalse(held.current!!.arrived)
        val good = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE, fixGoodEnough = true)
        assertTrue(good.current!!.arrived)
        assertEquals(setOf(crumbTargetId(2)), good.arrivedIds)
    }

    @Test fun aCrumbsTickComesAndGoesAtTheTwoArrivalDistances() {
        val at = { meters: Double, before: Set<Long> ->
            buildTrail(TrailMode.LAYING, listOf(CrumbPoint(7, north(meters)), CrumbPoint(3, east)), north(0.0), null, NorthRef.TRUE, previouslyArrived = before)
        }
        // Coming nearer: the tick is there at 11 m and gone under 10 m.
        assertEquals(listOf(0f, 90f), at(11.0, emptySet()).tickAngles)
        assertEquals(emptySet<Long>(), at(11.0, emptySet()).arrivedIds)
        val near = at(9.0, emptySet())
        assertEquals(listOf(90f), near.tickAngles)
        assertEquals(setOf(crumbTargetId(7)), near.arrivedIds)
        // Going away again: still gone at 11 m, back beyond 12 m.
        val wavering = at(11.0, near.arrivedIds)
        assertEquals(listOf(90f), wavering.tickAngles)
        assertEquals(setOf(crumbTargetId(7)), wavering.arrivedIds)
        val left = at(12.5, wavering.arrivedIds)
        assertEquals(listOf(0f, 90f), left.tickAngles)
        assertEquals(emptySet<Long>(), left.arrivedIds)
        // A position that wavers at the line does not make the tick blink.
        var before = emptySet<Long>()
        val shown = listOf(10.4, 9.9, 10.2, 9.8, 10.3, 11.9, 12.1, 10.5).map { m -> at(m, before).also { before = it.arrivedIds }.tickAngles.size == 2 }
        assertEquals(listOf(true, false, false, false, false, false, true, true), shown)
        // A tick does not depend on how accurate the fix is: it deletes nothing.
        assertEquals(listOf(90f), buildTrail(TrailMode.LAYING, listOf(CrumbPoint(7, north(9.0)), CrumbPoint(3, east)), north(0.0), null, NorthRef.TRUE, fixGoodEnough = false).tickAngles)
    }

    @Test fun trailCompleteWaitsForAStatusThatIsShowingAndIsShownOnce() {
        // Nothing showing: it is shown at once and nothing is owed.
        assertEquals(CompleteStep(LogStatus.TrailComplete, owed = false), trailCompleteOver(LogStatus.Idle))
        // A save in progress or its result keeps its place, and the words are owed.
        val saved = LogStatus.Saved("Waypoint 4", 5f, 1_000)
        for (showing in listOf(LogStatus.Saving(null), LogStatus.Saving(12f), saved, LogStatus.Failed(LogFailure.NO_FIX))) {
            assertEquals(CompleteStep(showing, owed = true), trailCompleteOver(showing))
        }
        // When that status ends, what is owed is shown in its place, and then nothing is owed.
        assertEquals(CompleteStep(LogStatus.TrailComplete, owed = false), statusEnded(owed = true))
        assertEquals(CompleteStep(LogStatus.Idle, owed = false), statusEnded(owed = false))
        // From a save through to the end: Saving, Saved, Trail complete, nothing. Once.
        var step = trailCompleteOver(LogStatus.Saving(null))
        val seen = mutableListOf(step.status)
        step = CompleteStep(saved, step.owed); seen += step.status
        step = statusEnded(step.owed); seen += step.status
        step = statusEnded(step.owed); seen += step.status
        assertEquals(listOf(LogStatus.Saving(null), saved, LogStatus.TrailComplete, LogStatus.Idle), seen)
    }

    // Fix round 2.

    @Test fun aPoorFixsAccuracyIsShownAfterTheCrumbsDistance() {
        // A fix good enough to consume a crumb adds nothing, up to and including 15 m.
        for (accuracy in listOf(0f, 5f, 14.9f, 15f)) for (u in Units.entries) assertNull("$accuracy $u", poorFixNote(accuracy, stale = false, units = u))
        // A poorer one is shown as the accuracy of a save is: the sign and the distance, in the wearer's units.
        assertEquals("±15 m", poorFixNote(15.1f, stale = false, units = Units.METRIC))
        assertEquals("±20 m", poorFixNote(20f, stale = false, units = Units.METRIC))
        assertEquals("±20 m", poorFixNote(20.4f, stale = false, units = Units.METRIC))
        assertEquals("±66 ft", poorFixNote(20f, stale = false, units = Units.IMPERIAL))
        assertEquals("±50 ft", poorFixNote(15.1f, stale = false, units = Units.IMPERIAL))
        // The distance's own rules: whole meters or feet, kilometers and miles with one decimal from 1000 on.
        assertEquals("±999 m", poorFixNote(999.4f, stale = false, units = Units.METRIC))
        assertEquals("±1.0 km", poorFixNote(999.6f, stale = false, units = Units.METRIC))
        assertEquals("±2.5 km", poorFixNote(2_500f, stale = false, units = Units.METRIC))
        assertEquals("±984 ft", poorFixNote(300f, stale = false, units = Units.IMPERIAL))
        assertEquals("±0.2 mi", poorFixNote(305f, stale = false, units = Units.IMPERIAL))
        for (u in Units.entries) assertEquals(statusText(LogStatus.Saving(40f), u).second, poorFixNote(40f, stale = false, units = u))
        // With no fix, or an accuracy that is not a number, there is nothing to show; an old fix is marked old instead.
        assertNull(poorFixNote(null, stale = false, units = Units.METRIC))
        assertNull(poorFixNote(Float.NaN, stale = false, units = Units.METRIC))
        assertNull(poorFixNote(40f, stale = true, units = Units.METRIC))
        // The line it stands in, with the distance before it.
        assertEquals("9 m ±20 m", listOfNotNull(formatDistance(9.0, Units.METRIC), poorFixNote(20f, false, Units.METRIC)).joinToString(" "))
        assertEquals("30 ft ±66 ft", listOfNotNull(formatDistance(9.0, Units.IMPERIAL), poorFixNote(20f, false, Units.IMPERIAL)).joinToString(" "))
    }

    @Test fun trailCompleteIsDroppedWhenTheCompassIsLeftOrANewTrailStarts() {
        val saved = LogStatus.Saved("Waypoint 4", 5f, 1_000)
        // Showing or waiting to be shown: it goes, and nothing is owed.
        assertEquals(CompleteStep(LogStatus.Idle, owed = false), trailCompleteDropped(LogStatus.TrailComplete, owed = false))
        // Owed behind another status: that status stays as it is and nothing is owed any more.
        for (showing in listOf(LogStatus.Saving(null), saved, LogStatus.Failed(LogFailure.NO_FIX))) {
            assertEquals(CompleteStep(showing, owed = false), trailCompleteDropped(showing, owed = true))
            assertEquals(CompleteStep(showing, owed = false), trailCompleteDropped(showing, owed = false))
        }
        assertEquals(CompleteStep(LogStatus.Idle, owed = false), trailCompleteDropped(LogStatus.Idle, owed = false))
        // So after it was dropped, the status that was showing ends in nothing, not in `Trail complete`.
        val dropped = trailCompleteDropped(saved, owed = true)
        assertEquals(CompleteStep(LogStatus.Idle, owed = false), statusEnded(dropped.owed))
        // It is kept only while the compass is on screen with the app in front.
        assertTrue(trailCompleteKept(compassOnScreen = true, appResumed = true))
        assertFalse(trailCompleteKept(compassOnScreen = false, appResumed = true))
        assertFalse(trailCompleteKept(compassOnScreen = true, appResumed = false))
        // A double press that starts a trail is a new trail; switching one that is under way is not.
        assertTrue(startsNewTrail(TrailMode.OFF, TrailMode.LAYING))
        assertFalse(startsNewTrail(TrailMode.LAYING, TrailMode.RETRACING))
        assertFalse(startsNewTrail(TrailMode.RETRACING, TrailMode.LAYING))
        assertFalse(startsNewTrail(TrailMode.LAYING, TrailMode.OFF))
        assertFalse(startsNewTrail(TrailMode.OFF, TrailMode.OFF))
    }

    @Test fun aCrumbIsConsumedOnlyWithTheCompassInViewAndTheAppInFront() {
        assertTrue(compassInViewForTrail(compassAtRest = true, appResumed = true))
        assertFalse(compassInViewForTrail(compassAtRest = true, appResumed = false))
        assertFalse(compassInViewForTrail(compassAtRest = false, appResumed = true))
        assertFalse(compassInViewForTrail(compassAtRest = false, appResumed = false))
    }

    @Test fun aFailedReadOfTheCrumbsIsTriedAgainSoonAndThenLessOften() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0L..6L).map { crumbsRetryDelayMs(it) })
        assertEquals(30_000L, crumbsRetryDelayMs(1_000))
        assertEquals(1_000L, crumbsRetryDelayMs(-1))
    }

    @Test fun theModeIsKnownWithoutTheCrumbsExceptWhereRetracingIsStoredWithTheSettingOn() {
        // With the setting off the trail is off, whatever is stored.
        for (stored in TrailMode.entries) assertEquals(TrailMode.OFF, trailModeUnread(stored, settingOn = false))
        assertEquals(TrailMode.OFF, trailModeUnread(TrailMode.OFF, settingOn = true))
        assertEquals(TrailMode.LAYING, trailModeUnread(TrailMode.LAYING, settingOn = true))
        // Retracing stands only while there is a crumb to walk to, which an unread list does not say.
        assertNull(trailModeUnread(TrailMode.RETRACING, settingOn = true))
        // Where the mode is known without the crumbs, it is what any number of them gives.
        for (stored in TrailMode.entries) for (on in listOf(false, true)) for (crumbs in listOf(0, 1, 40)) {
            trailModeUnread(stored, on)?.let { assertEquals("$stored $on $crumbs", effectiveTrailMode(stored, on, crumbs), it) }
        }
    }

    @Test fun anUnreadCrumbListStandsForNoCrumbsOnlyWithTheSettingOff() {
        // A list that has been read is the list, with the setting on or off.
        assertEquals(listOf(3, 2, 1), crumbsForScreens(listOf(3, 2, 1), settingOn = true))
        assertEquals(listOf(3, 2, 1), crumbsForScreens(listOf(3, 2, 1), settingOn = false))
        assertEquals(emptyList<Int>(), crumbsForScreens(emptyList<Int>(), settingOn = true))
        // Unread: no crumbs with the setting off, and not known (null) with it on.
        assertEquals(emptyList<Int>(), crumbsForScreens<Int>(null, settingOn = false))
        assertNull(crumbsForScreens<Int>(null, settingOn = true))
    }
    // Owner, 2026-10-16, after a crumb a few steps away was pointed at the wrong way indoors, on what the compass
    // does when the position is less certain than the target is near: "Hide the pointer (Recommended)": draw no
    // pointer when the position's accuracy is larger than the distance to the crumb or target, show the distance
    // with ±, and do not mark the crumb reached.

    @Test fun aCrumbThreeMetersAwayWithAThirtyMeterFixHasNoDialAngle() {
        val crumbs = listOf(CrumbPoint(2, north(3.0)), CrumbPoint(1, north(-200.0)))
        // A 30 m fix is too poor to consume a crumb, so the crumb 3 m north is not arrived at; nor is it pointed at.
        val t = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE, Units.METRIC, fixGoodEnough = false, fixAccuracyM = 30f)
        assertNull(t.current!!.dialAngleDeg)
        assertEquals(3.0, t.current!!.distanceM!!, 0.01)
        assertTrue(t.current!!.uncertain)
        assertFalse(t.current!!.arrived)
        assertFalse(crumbTargetId(2) in t.arrivedIds)
        // The crumb 200 m behind still has its tick: its direction is known.
        assertEquals(listOf(180f), t.tickAngles)
    }

    @Test fun aCrumbAHundredMetersAwayWithAThirtyMeterFixHasItsDialAngle() {
        val t = buildTrail(TrailMode.RETRACING, listOf(CrumbPoint(2, north(100.0))), north(0.0), null, NorthRef.TRUE, Units.METRIC, fixGoodEnough = false, fixAccuracyM = 30f)
        assertEquals(0f, t.current!!.dialAngleDeg!!, 0.01f)
        assertFalse(t.current!!.uncertain)
    }

    @Test fun aCrumbNearerThanTheAccuracyHasNoTickAndIsNotCountedArrivedAt() {
        val crumbs = listOf(CrumbPoint(3, north(500.0)), CrumbPoint(2, north(20.0)), CrumbPoint(1, north(-40.0)))
        val t = buildTrail(TrailMode.RETRACING, crumbs, north(0.0), null, NorthRef.TRUE, Units.METRIC, fixGoodEnough = false, fixAccuracyM = 30f)
        // Crumb 2 (20 m) is nearer than 30 m: no tick; crumb 1 (40 m) has its tick, behind.
        assertEquals(listOf(180f), t.tickAngles)
        assertEquals(emptySet<Long>(), t.arrivedIds)
    }

    @Test fun anUncertainCrumbIsNotConsumed() {
        // The crumb walked to is consumed only by arrival, which a fix this poor cannot give ([trailAfterFix]).
        val crumbs = listOf(crumb(2, 3.0), crumb(1, -30.0))
        val t = Trail(TrailMode.RETRACING, listOf(2, 1))
        val fix = trailAfterFix(t, crumbs, null, north(0.0), Units.METRIC, accuracyM = 30f, compassInView = true)
        assertEquals(TrailChange(t), fix.change); assertNull(fix.arrivedAt)
    }

    @Test fun aGoodFixWithinTheArrivalDistanceStillArrives() {
        // Arrival is decided first, as before: a 12 m fix and the crumb 4 m away.
        val t = buildTrail(TrailMode.RETRACING, listOf(CrumbPoint(2, north(4.0))), north(0.0), null, NorthRef.TRUE, Units.METRIC, fixGoodEnough = true, fixAccuracyM = 12f)
        assertTrue(t.current!!.arrived); assertFalse(t.current!!.uncertain)
        // Just beyond it, 11 m away with the same fix: not arrived, and nearer than 12 m, so uncertain.
        val beyond = buildTrail(TrailMode.RETRACING, listOf(CrumbPoint(2, north(11.0))), north(0.0), null, NorthRef.TRUE, Units.METRIC, fixGoodEnough = true, fixAccuracyM = 12f)
        assertFalse(beyond.current!!.arrived); assertTrue(beyond.current!!.uncertain); assertNull(beyond.current!!.dialAngleDeg)
    }
}
