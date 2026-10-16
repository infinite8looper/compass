package com.infinite8looper.compass.nav

data class WaypointInfo(val id: Long, val name: String, val point: GeoPoint, val hue: Float, val isTarget: Boolean)

/**
 * A target as the compass shows it. [arrived] says the wearer counts as being at it ([nextArrived]); it then has no
 * [dialAngleDeg]. [isCrumb] marks the crumb of a breadcrumb trail that is walked to, which is shown as a focused
 * target is, in the breadcrumb color; its [id] is not a waypoint's ([crumbTargetId]).
 */
data class TargetUi(
    val id: Long, val name: String, val hue: Float,
    val dialAngleDeg: Float?, val distanceM: Double?, val focused: Boolean,
    val arrived: Boolean = false, val isCrumb: Boolean = false,
)

fun resolveFocus(targetIds: List<Long>, focusedId: Long?): Long? =
    if (focusedId != null && focusedId in targetIds) focusedId else targetIds.firstOrNull()

fun stepFocus(targetIds: List<Long>, current: Long?, step: Int): Long? {
    if (targetIds.isEmpty()) return null
    val i = targetIds.indexOf(current)
    return if (i < 0) targetIds.first() else targetIds[(i + step).mod(targetIds.size)]
}

/**
 * Targets in the order given (the store returns newest first).
 * A target the wearer has arrived at has a distance but no dial angle: the direction to a point the wearer is
 * standing on means nothing, so the dial draws no line or arrow for it and it is not faced. Arrival begins under
 * the arrival distance, which depends on [units], and ends only beyond the leaving distance ([nextArrived]):
 * [previouslyArrived] holds the ids of the targets that had arrived the last time this was worked out. With no
 * [position] nothing has arrived.
 *
 * With [focusSuspended] no target has the focus, whatever [focusedId] says: a breadcrumb trail is being retraced,
 * and the crumb that is walked to stands where the focused target does. The targets are all still there.
 */
fun buildTargets(
    waypoints: List<WaypointInfo>, focusedId: Long?, position: GeoPoint?, declination: Float?, north: NorthRef,
    units: Units = Units.METRIC, previouslyArrived: Set<Long> = emptySet(), focusSuspended: Boolean = false,
): List<TargetUi> {
    val targets = waypoints.filter { it.isTarget }
    val focus = if (focusSuspended) null else resolveFocus(targets.map { it.id }, focusedId)
    return targets.map { w ->
        val distance = position?.let { distanceMeters(it, w.point) }
        val arrived = distance != null && nextArrived(w.id in previouslyArrived, distance, units)
        TargetUi(
            id = w.id, name = w.name, hue = w.hue,
            dialAngleDeg = position?.takeUnless { arrived }?.let { dialAngle(bearingDeg(it, w.point), declination, north) },
            distanceM = distance,
            focused = w.id == focus,
            arrived = arrived,
        )
    }
}

fun cleanName(input: String?, fallback: String): String {
    val cleaned = input?.trim()?.replace(Regex("\\s+"), " ")?.take(40).orEmpty()
    return cleaned.ifEmpty { fallback }
}
