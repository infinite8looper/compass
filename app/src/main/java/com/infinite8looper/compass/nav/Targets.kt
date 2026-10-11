package com.infinite8looper.compass.nav

data class WaypointInfo(val id: Long, val name: String, val point: GeoPoint, val hue: Float, val isTarget: Boolean)

data class TargetUi(
    val id: Long, val name: String, val hue: Float,
    val dialAngleDeg: Float?, val distanceM: Double?, val focused: Boolean,
)

fun resolveFocus(targetIds: List<Long>, focusedId: Long?): Long? =
    if (focusedId != null && focusedId in targetIds) focusedId else targetIds.firstOrNull()

fun stepFocus(targetIds: List<Long>, current: Long?, step: Int): Long? {
    if (targetIds.isEmpty()) return null
    val i = targetIds.indexOf(current)
    return if (i < 0) targetIds.first() else targetIds[(i + step).mod(targetIds.size)]
}

/** Targets in the order given (the store returns newest first). */
fun buildTargets(
    waypoints: List<WaypointInfo>, focusedId: Long?, position: GeoPoint?, declination: Float?, north: NorthRef,
): List<TargetUi> {
    val targets = waypoints.filter { it.isTarget }
    val focus = resolveFocus(targets.map { it.id }, focusedId)
    return targets.map { w ->
        TargetUi(
            id = w.id, name = w.name, hue = w.hue,
            dialAngleDeg = position?.let { dialAngle(bearingDeg(it, w.point), declination, north) },
            distanceM = position?.let { distanceMeters(it, w.point) },
            focused = w.id == focus,
        )
    }
}

fun cleanName(input: String?, fallback: String): String {
    val cleaned = input?.trim()?.replace(Regex("\\s+"), " ")?.take(40).orEmpty()
    return cleaned.ifEmpty { fallback }
}
