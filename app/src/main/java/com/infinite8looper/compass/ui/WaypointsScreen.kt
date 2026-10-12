package com.infinite8looper.compass.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.ScrollArc
import com.infinite8looper.compass.nav.formatDistance
import com.infinite8looper.compass.nav.headerTapSetsAll
import com.infinite8looper.compass.nav.headerText
import com.infinite8looper.compass.nav.isStale
import com.infinite8looper.compass.nav.scrollArc
import com.infinite8looper.compass.nav.statusText
import kotlinx.coroutines.delay

/**
 * The waypoints, one of the three screens that sit side by side: a header that counts the targets, `Log here`, and
 * a row for each waypoint, newest first, inside the frame, which shows how far the list is scrolled.
 *
 * [paneView] says how much of the screen the wearer has in front of them. The pager may build this screen while it
 * is hidden, and twice, so what has a consequence asks it first: a tap or a long press acts only while the screen
 * is in view, the logging result's time is counted only then, and the clock that marks distances as old runs only
 * while some of the screen is showing.
 *
 * While a waypoint is being logged, from here or from the compass, the header shows the status in place of the
 * count: the same words, in the same colors, as on the compass.
 *
 * [onEdit] is what a long press on a waypoint's row does, and [onLogHere] what `Log here` does.
 */
@Composable
fun WaypointsScreen(vm: CompassViewModel, paneView: () -> PaneView, onEdit: (Long) -> Unit, onLogHere: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val onScreen by remember(paneView) { derivedStateOf { paneView() != PaneView.HIDDEN } }
    // The same check the compass uses: a fix that has stopped arriving makes the distances old.
    val stale by produceState(false, nav.fixElapsedMs, onScreen) {
        while (onScreen) { value = isStale(SystemClock.elapsedRealtime(), nav.fixElapsedMs); delay(1_000) }
    }
    val status by vm.logStatus.collectAsStateWithLifecycle()
    LogResultTimer(vm, paneView)
    // A touch that lands while the screens are still sliding does nothing.
    fun inView(act: () -> Unit) { if (paneView() == PaneView.IN_VIEW) act() }

    val r = screenRadius()
    val density = LocalDensity.current
    val headerStyle = remember(r, density) { density.instrumentText(r * 0.105f, align = TextAlign.Center) }
    val detailStyle = remember(r, density) { density.instrumentText(r * 0.085f, align = TextAlign.Center) }
    val actionStyle = remember(r, density) { density.instrumentText(r * 0.13f, FontWeight.Medium, TextAlign.Center) }
    val nameStyle = remember(r, density) { density.instrumentText(r * 0.13f) }
    val nameOnStyle = remember(r, density) { density.instrumentText(r * 0.13f, FontWeight.Medium) }
    val distanceStyle = remember(r, density) { density.instrumentText(r * 0.095f) }

    val total = nav.waypoints.size
    val targets = nav.waypoints.count { it.isTarget }
    val setsAll = headerTapSetsAll(targets, total)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScalingLazyColumn(
            state = listState,
            // The rows keep their size and their spacing all the way: a row is as easy to hit near the edge as in
            // the middle. They are cut off at a circle inside the frame and fade out toward its top and bottom,
            // so no row is drawn over the frame or stops at a hard edge.
            scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize().padding(r * (1f - LIST_RADIUS)).clip(CircleShape).fadeTopAndBottom(),
        ) {
            item(key = "header") {
                InstrumentRow(
                    Modifier.clickable(
                        enabled = total > 0, role = Role.Button,
                        onClickLabel = if (setsAll) "Set all as targets" else "Clear all targets",
                    ) { inView { vm.setAllTargets(setsAll) } },
                    centered = true,
                ) {
                    val current = status
                    if (current == LogStatus.Idle) {
                        Text(headerText(targets, total), color = Dim, style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        val (words, detail) = statusText(current, nav.units)
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(words, color = logStatusColor(current), style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (detail != null) Text(detail, color = Dim, style = detailStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "log") {
                InstrumentRow(Modifier.clickable(role = Role.Button) { inView(onLogHere) }, centered = true) {
                    Text("Log here", color = AccentColor, style = actionStyle, maxLines = 1)
                }
            }
            items(nav.waypoints, key = { it.id }) { w ->
                InstrumentRow(
                    Modifier
                        .combinedClickable(
                            role = Role.Switch,
                            onClickLabel = if (w.isTarget) "Clear target" else "Set target",
                            onLongClickLabel = "Edit",
                            onLongClick = { inView { onEdit(w.id) } },
                        ) { inView { vm.setTarget(w.id, !w.isTarget) } }
                        .semantics { stateDescription = if (w.isTarget) "Target" else "Not a target" },
                    trailing = { TickToggle(w.isTarget, color = waypointColor(w.hue)) },
                ) {
                    Column {
                        Text(
                            w.name, color = if (w.isTarget) Ink else Dim, style = if (w.isTarget) nameOnStyle else nameStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        nav.distances[w.id]?.let { d ->
                            Text(
                                if (stale) "${formatDistance(d, nav.units)} (old)" else formatDistance(d, nav.units),
                                color = if (stale) Faint else Dim, style = distanceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        FrameRing(paneIndex = Pane.WAYPOINTS.ordinal, scroll = { listScrollArc(listState) })
    }
}

/**
 * Where the frame's scroll arc is for a list: how far through its rows the list is, and how much of it is on
 * screen. The list starts with its second row in the middle, so that is the top; the last row in the middle is the
 * bottom. Read while the frame draws.
 */
internal fun listScrollArc(state: ScalingLazyListState): ScrollArc? {
    val info = state.layoutInfo
    val rows = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (rows.isEmpty() || total < 3) return null
    val rowHeight = rows.sumOf { it.unadjustedSize }.toFloat() / rows.size
    if (rowHeight <= 0f) return null
    val position = (state.centerItemIndex - 1 + state.centerItemScrollOffset / rowHeight) / (total - 2)
    return scrollArc(position, info.viewportSize.height / (rowHeight * total))
}
