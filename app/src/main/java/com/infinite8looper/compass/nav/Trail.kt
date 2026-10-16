package com.infinite8looper.compass.nav

import kotlin.math.roundToInt

// The breadcrumb trail: a second kind of stored point, laid with single presses of the side button and walked back
// newest first. The decisions are here: what a press is, what each mode does with it, when a crumb is arrived at,
// and what the screens show.

/** Off: no trail is being laid or retraced (crumbs may still be stored). Laying: a press drops a crumb. Retracing: the newest crumb is walked to. */
enum class TrailMode { OFF, LAYING, RETRACING }

// Presses of the side button.

/** A second press that goes down within this long of the first being let go makes the two a double press. */
const val DOUBLE_PRESS_WINDOW_MS = 300L

enum class PressKind { SINGLE, DOUBLE }

/** What a side button does: it goes down; it is let go as a short press; its press is held or canceled; the wait for a second press runs out. */
enum class PressEvent { DOWN, PRESSED, DROPPED, WINDOW_OVER }

/**
 * A first press that was let go at [releasedAtMs] and waits to see whether a second follows. [secondDown] once a
 * second press has gone down in time: from then on the wait is for that press to be let go, however long it takes.
 */
data class WaitingPress(val releasedAtMs: Long, val secondDown: Boolean = false)

/** After an event: the press that waits now, and what the event made known, if anything. */
data class PressStep(val waiting: WaitingPress?, val kind: PressKind?)

/**
 * What the side button's presses are, one event at a time: [waiting] is the press that waited before [event], which
 * comes at [atMs]. All times are on one clock.
 *
 * With [doubleEnabled] off (the breadcrumb trail setting is off) every short press is a single press the moment it
 * is let go, and nothing ever waits.
 *
 * With it on, a short press waits. A second press that goes down within [DOUBLE_PRESS_WINDOW_MS] of the first
 * being let go makes a double press when it is let go in its turn; if it is held or canceled instead, the first
 * stays a single press. When the window passes with no second press ([PressEvent.WINDOW_OVER], from a timer set
 * for [singleDueAtMs]), the first is a single press; a timer that runs early changes nothing. A press that goes
 * down after the window, before the timer has run, makes the first a single press there and then and waits in its
 * turn. A third press never pairs with the second: after a double press nothing waits.
 */
fun pressKind(waiting: WaitingPress?, event: PressEvent, atMs: Long, doubleEnabled: Boolean): PressStep {
    if (!doubleEnabled) {
        // A press that was waiting when the setting was switched off is not lost.
        val single = event == PressEvent.PRESSED || (event == PressEvent.WINDOW_OVER && waiting != null)
        return PressStep(null, if (single) PressKind.SINGLE else null)
    }
    if (waiting == null) return PressStep(if (event == PressEvent.PRESSED) WaitingPress(atMs) else null, null)
    val inTime = atMs - waiting.releasedAtMs <= DOUBLE_PRESS_WINDOW_MS
    return when (event) {
        PressEvent.DOWN -> when {
            waiting.secondDown -> PressStep(waiting, null)
            inTime -> PressStep(waiting.copy(secondDown = true), null)
            else -> PressStep(null, PressKind.SINGLE)
        }
        PressEvent.PRESSED -> when {
            waiting.secondDown || inTime -> PressStep(null, PressKind.DOUBLE)
            else -> PressStep(WaitingPress(atMs), PressKind.SINGLE)
        }
        PressEvent.DROPPED -> if (waiting.secondDown) PressStep(null, PressKind.SINGLE) else PressStep(waiting, null)
        PressEvent.WINDOW_OVER -> if (!waiting.secondDown && !inTime) PressStep(null, PressKind.SINGLE) else PressStep(waiting, null)
    }
}

/** When the timer for [waiting] has to run for it to become a single press: just after its window. Null when no timer is needed. */
fun singleDueAtMs(waiting: WaitingPress?): Long? =
    if (waiting == null || waiting.secondDown) null else waiting.releasedAtMs + DOUBLE_PRESS_WINDOW_MS + 1

/** What a single press of the side button saves. */
enum class SinglePress { WAYPOINT, CRUMB }

/** A single press drops a crumb while a trail is being laid, and saves a waypoint at every other time. */
fun singlePress(mode: TrailMode): SinglePress = if (mode == TrailMode.LAYING) SinglePress.CRUMB else SinglePress.WAYPOINT

// The trail's modes.

/** The trail: its mode and the ids of its crumbs, newest first. */
data class Trail(val mode: TrailMode, val crumbIds: List<Long>)

sealed interface TrailEvent {
    /** A double press of the side button, or a tap on the trail's row, which does the same. */
    data object DoublePress : TrailEvent
    /** A crumb with this id is to be added. */
    data class CrumbDropped(val id: Long) : TrailEvent
    /** The wearer has arrived at the crumb with this id. */
    data class Arrived(val id: Long) : TrailEvent
}

/** The trail after an event: [removedId] is the crumb the event consumed, and [completed] says it was the last one. */
data class TrailChange(val trail: Trail, val removedId: Long? = null, val completed: Boolean = false)

/**
 * The trail after [event].
 *
 * A double press with no trail being laid or retraced starts laying, keeping any crumbs that are stored. While
 * laying it starts retracing, unless there is no crumb to walk to: an empty trail cannot be retraced, and the double
 * press ends it instead. While retracing it returns to laying, keeping the crumbs that remain.
 *
 * A crumb is added only while laying, as the newest.
 *
 * An arrival consumes exactly the crumb that is walked to ([walkingTo]), and only while retracing: said again for
 * a crumb that is already gone, or for any other crumb, it changes nothing. After the last crumb the trail is off
 * and [TrailChange.completed].
 */
fun nextTrail(trail: Trail, event: TrailEvent): TrailChange = when (event) {
    TrailEvent.DoublePress -> TrailChange(
        trail.copy(
            mode = when (trail.mode) {
                TrailMode.OFF -> TrailMode.LAYING
                TrailMode.LAYING -> if (trail.crumbIds.isEmpty()) TrailMode.OFF else TrailMode.RETRACING
                TrailMode.RETRACING -> TrailMode.LAYING
            },
        ),
    )
    is TrailEvent.CrumbDropped ->
        if (trail.mode == TrailMode.LAYING) TrailChange(trail.copy(crumbIds = listOf(event.id) + trail.crumbIds)) else TrailChange(trail)
    is TrailEvent.Arrived ->
        if (walkingTo(trail) != event.id) TrailChange(trail)
        else {
            val left = trail.crumbIds.drop(1)
            TrailChange(Trail(if (left.isEmpty()) TrailMode.OFF else TrailMode.RETRACING, left), removedId = event.id, completed = left.isEmpty())
        }
}

/** The crumb that is walked to: the newest, while the trail is being retraced. */
fun walkingTo(trail: Trail): Long? = if (trail.mode == TrailMode.RETRACING) trail.crumbIds.firstOrNull() else null

/**
 * The mode the trail is in, from the mode that is [stored]: off whenever the breadcrumb trail setting is off, and
 * off when the stored mode says retracing and there is no crumb to walk to.
 */
fun effectiveTrailMode(stored: TrailMode, settingOn: Boolean, crumbs: Int): TrailMode = when {
    !settingOn -> TrailMode.OFF
    stored == TrailMode.RETRACING && crumbs <= 0 -> TrailMode.OFF
    else -> stored
}

/**
 * The mode the trail is in while its crumbs have not been read, where that does not depend on them: off with the
 * setting off, and the [stored] mode when that is off or laying. Where it is not null it is what
 * [effectiveTrailMode] gives for any number of crumbs. Null with the setting on and retracing stored: retracing
 * stands only while there is a crumb to walk to, which an unread list does not say.
 */
fun trailModeUnread(stored: TrailMode, settingOn: Boolean): TrailMode? = when {
    !settingOn -> TrailMode.OFF
    stored == TrailMode.RETRACING -> null
    else -> stored
}

/**
 * The crumbs the screens are given, from the list that is [held] (null while no read of it has worked): the list
 * once it has been read. Unread, it stands for no crumbs with the setting off, where a trail is neither laid nor
 * retraced, so nothing on the screens waits for it; with the setting on it is not known (null), and an unread trail
 * is not shown as an empty one.
 */
fun <T> crumbsForScreens(held: List<T>?, settingOn: Boolean): List<T>? = held ?: if (settingOn) null else emptyList()

/** The mode a stored name stands for; anything that is not a mode's name, or nothing, is off. */
fun trailModeOf(stored: String?): TrailMode = TrailMode.entries.firstOrNull { it.name == stored } ?: TrailMode.OFF

// Arriving at a crumb.

data class CrumbPoint(val id: Long, val point: GeoPoint)

/** After a position fix: what it did to the trail, and the crumb the wearer counts as arrived at now, if any. */
data class TrailFix(val change: TrailChange, val arrivedAt: Long?)

/**
 * What a position fix at [position] does to a trail whose crumbs are at [crumbs]. Only the crumb that is walked to
 * is looked at, so one fix consumes at most one crumb, and never one further back ahead of the newest. It is
 * arrived at under the arrival distance and, once arrived at, stays so until the leaving distance ([nextArrived]):
 * [arrivedAt] is the crumb the fix before this one arrived at. A crumb is removed when it is arrived at, so the
 * leaving distance comes into it only for a crumb whose removal has not happened yet when the next fix comes; the
 * crumb after it starts from not arrived.
 *
 * Consuming a crumb cannot be undone, so two things have to hold for a fix to do it. Its reported accuracy
 * ([accuracyM]) has to pass the rule a save uses for the fix it stores ([isGoodEnough]): a poorer fix arrives at
 * nothing, however near it puts the wearer. And the compass has to be in view ([compassInView]), where the wearer
 * sees the crumb change: on any other screen the trail does not advance, whatever else keeps location running.
 */
fun trailAfterFix(
    trail: Trail, crumbs: List<CrumbPoint>, arrivedAt: Long?, position: GeoPoint, units: Units, accuracyM: Float,
    compassInView: Boolean,
): TrailFix {
    val id = walkingTo(trail)
    val crumb = crumbs.firstOrNull { it.id == id }
    if (crumb == null || !compassInView || !isGoodEnough(accuracyM)) return TrailFix(TrailChange(trail), null)
    return if (nextArrived(arrivedAt == crumb.id, distanceMeters(position, crumb.point), units)) {
        TrailFix(nextTrail(trail, TrailEvent.Arrived(crumb.id)), crumb.id)
    } else TrailFix(TrailChange(trail), null)
}

// What the screens show.

/**
 * The trail as the screens show it: its [mode], how many crumbs it has, the crumb that is walked to while it is
 * retraced ([current], which the compass treats as the focused target), and where the other crumbs' ticks are on
 * the dial ([tickAngles]). [arrivedIds] are the target ids ([crumbTargetId]) of the crumbs the wearer counts as
 * arrived at, to be handed back the next time the trail is built.
 */
data class TrailUi(
    val mode: TrailMode = TrailMode.OFF, val count: Int = 0, val current: TargetUi? = null, val tickAngles: List<Float> = emptyList(),
    val arrivedIds: Set<Long> = emptySet(),
)

/** The id the crumb with [crumbId] has as a target on the compass: below zero, where no waypoint's id is, and never zero. */
fun crumbTargetId(crumbId: Long): Long = -crumbId

/**
 * The trail for the screens, from its [crumbs], newest first.
 *
 * While it is retraced the newest crumb is the one walked to: a focused target named by its place in the trail,
 * which is its number among the crumbs that are left, the oldest being 1. Like any target it has a distance, and a
 * dial angle unless it is arrived at. It is shown as arrived at only by a fix that could also consume it
 * ([fixGoodEnough], see [trailAfterFix]). Not arrived at, and nearer than the position's reported accuracy
 * [fixAccuracyM] ([directionUncertain]), it has no dial angle and is marked [TargetUi.uncertain]: its distance is
 * shown with the accuracy after it, and it is not consumed (owner, 2026-10-16, "Hide the pointer (Recommended)").
 * Until then a poorer fix showed its direction and distance, however near.
 *
 * Every other crumb is a tick at its bearing, at the whole degree nearest to it, and crumbs in the same direction
 * share one: however long the trail, there are at most 360 ticks, in rising order. A crumb the wearer has arrived
 * at has no tick, as a target there has no line, and nor has a crumb nearer than the accuracy ([directionUncertain]),
 * which is not counted as arrived at. With no [position] there are no ticks.
 *
 * Arrival, for the tick as for the crumb walked to, begins under the arrival distance and ends beyond the leaving
 * distance ([nextArrived]): [previouslyArrived] holds the [TrailUi.arrivedIds] of the time before.
 */
fun buildTrail(
    mode: TrailMode, crumbs: List<CrumbPoint>, position: GeoPoint?, declination: Float?, north: NorthRef,
    units: Units = Units.METRIC, previouslyArrived: Set<Long> = emptySet(), fixGoodEnough: Boolean = true,
    fixAccuracyM: Float? = null,
): TrailUi {
    val walked = if (mode == TrailMode.RETRACING) crumbs.firstOrNull() else null
    var arrivedIds: MutableSet<Long>? = null
    val current = walked?.let { c ->
        val id = crumbTargetId(c.id)
        val distance = position?.let { distanceMeters(it, c.point) }
        val arrived = distance != null && fixGoodEnough && nextArrived(id in previouslyArrived, distance, units)
        if (arrived) arrivedIds = mutableSetOf(id)
        val uncertain = !arrived && directionUncertain(distance, fixAccuracyM)
        TargetUi(
            id = id, name = crumbName(crumbs.size), hue = 0f,
            dialAngleDeg = position?.takeUnless { arrived || uncertain }?.let { dialAngle(bearingDeg(it, c.point), declination, north) },
            distanceM = distance, focused = true, arrived = arrived, isCrumb = true, uncertain = uncertain,
        )
    }
    val ticks = if (position == null) emptyList() else {
        val taken = BooleanArray(360)
        for (c in crumbs) {
            if (c === walked) continue
            val id = crumbTargetId(c.id)
            val distance = distanceMeters(position, c.point)
            if (nextArrived(previouslyArrived.isNotEmpty() && id in previouslyArrived, distance, units)) {
                (arrivedIds ?: mutableSetOf<Long>().also { arrivedIds = it }) += id
                continue
            }
            if (directionUncertain(distance, fixAccuracyM)) continue
            taken[dialAngle(bearingDeg(position, c.point), declination, north).roundToInt().mod(360)] = true
        }
        (0 until 360).filter { taken[it] }.map { it.toFloat() }
    }
    return TrailUi(mode, crumbs.size, current, ticks, arrivedIds ?: emptySet())
}

/** What the compass shows while a trail is being laid. */
fun trailLabel(count: Int): String = "Trail $count"

/** The name of the crumb at [place] in the trail, the oldest being 1. */
fun crumbName(place: Int): String = "Crumb $place"

/** The smaller line of the trail's row on the waypoints screen: what the trail is doing and how long it is. */
fun trailRowDetail(mode: TrailMode, count: Int): String {
    val crumbs = "$count ${if (count == 1) "crumb" else "crumbs"}"
    return when (mode) {
        TrailMode.OFF -> if (count <= 0) "Off" else "Off, $crumbs"
        TrailMode.LAYING -> "Laying, $crumbs"
        TrailMode.RETRACING -> "Retracing, $count left"
    }
}

/** What a tap on the trail's row does, in words: the same as a double press ([nextTrail]). */
fun trailRowAction(mode: TrailMode, count: Int): String = when (nextTrail(Trail(mode, List(count.coerceAtLeast(0)) { it.toLong() }), TrailEvent.DoublePress).trail.mode) {
    TrailMode.OFF -> "End trail"
    TrailMode.LAYING -> if (mode == TrailMode.OFF && count <= 0) "Start trail" else "Lay trail"
    TrailMode.RETRACING -> "Retrace trail"
}

/** Whether the trail is a reason for location to run: while it is laid or retraced and the compass is on screen. */
fun trailWantsLocation(mode: TrailMode, compassOnScreen: Boolean): Boolean = mode != TrailMode.OFF && compassOnScreen

/**
 * Whether the trail is a reason to keep the screen on: while it is retraced with the compass in view, as for a
 * target, and only while location can run at all ([locationCanRun]: the permission is held and location is switched
 * on). Without that no position will come, and the screen would be kept on over a message.
 */
fun trailKeepsAwake(mode: TrailMode, compassInView: Boolean, locationCanRun: Boolean): Boolean =
    mode == TrailMode.RETRACING && compassInView && locationCanRun

/**
 * Why no position can come, in the terms of the logging failures, or null when one can: the permission is not held,
 * or location is switched off. The compass says so, in the words of [statusText], where the distance to the crumb
 * walked to would be.
 */
fun noPositionReason(hasPermission: Boolean, locationOn: Boolean): LogFailure? = when {
    !hasPermission -> LogFailure.NO_PERMISSION
    !locationOn -> LogFailure.LOCATION_OFF
    else -> null
}

// `Trail complete` and the logging status share one place.

/** The status to show, and whether `Trail complete` is still owed a showing. */
data class CompleteStep(val status: LogStatus, val owed: Boolean)

/**
 * A trail has just been completed while [current] is the status: with nothing showing, `Trail complete` is shown
 * at once. A save in progress, or a result that is showing or waiting to be shown, keeps its place, and
 * `Trail complete` is owed.
 */
fun trailCompleteOver(current: LogStatus): CompleteStep =
    if (current == LogStatus.Idle) CompleteStep(LogStatus.TrailComplete, owed = false) else CompleteStep(current, owed = true)

/** A status has ended (its result was shown for its time, or its save stored nothing): what is owed is shown now, once. */
fun statusEnded(owed: Boolean): CompleteStep = CompleteStep(if (owed) LogStatus.TrailComplete else LogStatus.Idle, owed = false)

/**
 * `Trail complete` belongs to the moment the last crumb is reached. When that moment is gone before it could be
 * shown, or shown out, it is dropped, so it never appears on a later visit that has nothing to do with it: the
 * status that is [current] loses it if it is `Trail complete`, and nothing is [CompleteStep.owed] any more. Any
 * other status stays as it is.
 */
fun trailCompleteDropped(current: LogStatus, owed: Boolean): CompleteStep =
    CompleteStep(if (current == LogStatus.TrailComplete) LogStatus.Idle else current, owed = false)

/** `Trail complete` is kept, to be shown or while it shows, only while the compass is on screen with the app in front. */
fun trailCompleteKept(compassOnScreen: Boolean, appResumed: Boolean): Boolean = compassOnScreen && appResumed

/** Whether a change of mode is a new trail being started, which also drops a `Trail complete` left from the one before. */
fun startsNewTrail(before: TrailMode, after: TrailMode): Boolean = before == TrailMode.OFF && after == TrailMode.LAYING

/**
 * Whether the compass counts as in view for consuming a crumb ([trailAfterFix]): it is the screen at rest in view
 * and the app is in front (resumed), as for counting a result's time. At Home, or under one of the system's own
 * screens, nothing is consumed.
 */
fun compassInViewForTrail(compassAtRest: Boolean, appResumed: Boolean): Boolean = compassAtRest && appResumed

/**
 * What is put after the distance to the crumb walked to when the fix is too poor to consume it ([isGoodEnough]):
 * the fix's reported accuracy, written as the accuracy of a save is (`±66 ft`), so the wearer standing on a crumb
 * that stays sees why. Null with a fix good enough, with no fix or no accuracy, and with an old fix, which is
 * marked old instead.
 */
fun poorFixNote(accuracyM: Float?, stale: Boolean, units: Units): String? =
    if (accuracyM == null || accuracyM.isNaN() || stale || isGoodEnough(accuracyM)) null else accuracyText(accuracyM, units)

/** How long to wait before reading the crumbs again after a read that failed, the first failure being attempt 0: 1 s, doubling, 30 s at most. */
fun crumbsRetryDelayMs(attempt: Long): Long = if (attempt >= 5) 30_000L else 1_000L shl attempt.coerceAtLeast(0).toInt()
