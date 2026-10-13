package com.infinite8looper.compass.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import com.infinite8looper.compass.nav.HOLD_TO_DELETE_MS
import com.infinite8looper.compass.nav.holdProgress
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import com.infinite8looper.compass.nav.DROP_CRUMB
import com.infinite8looper.compass.nav.LogStatus
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.PaneView
import com.infinite8looper.compass.nav.PressKind
import com.infinite8looper.compass.nav.TrailMode
import com.infinite8looper.compass.nav.trailRowAction
import com.infinite8looper.compass.nav.trailRowDetail
import com.infinite8looper.compass.nav.ScrollArc
import com.infinite8looper.compass.nav.formatDistance
import com.infinite8looper.compass.nav.headerTapSetsAll
import com.infinite8looper.compass.nav.headerText
import com.infinite8looper.compass.nav.isStale
import com.infinite8looper.compass.nav.scrollArc
import com.infinite8looper.compass.nav.statusDetailContinues
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
 * A side button logs a waypoint here as it does on the compass, while this screen is in view.
 *
 * While a waypoint is being logged, from here or from the compass, the status stands at the top of the screen, over
 * the list and apart from it: the same words, in the same colors, as on the compass, wherever the list is scrolled
 * to. So whenever this screen is in view the status can be read, which is what its time is counted by.
 *
 * While the breadcrumb trail setting is on, or crumbs are stored, a row for the trail stands under `Log here`: it
 * says what the trail is doing and how long it is, and a tap on it does what a double press of the side button
 * does. Holding it clears the trail: once the touch has lasted the system's long-press time the frame shows a ring
 * in the error color that drains for a second, the row's smaller line reads `Hold to clear`, and when the ring is
 * empty every crumb is deleted. Letting go, or moving the finger away, before that clears nothing and is no tap. A
 * watch that passes no double press to the app has the whole trail there.
 *
 * While a trail is being laid, a row `Drop crumb` stands under the trail's row and drops a crumb, as a single press
 * of the side button does then ([onDropCrumb]).
 *
 * [onEdit] is what a long press on a waypoint's row does, [onLogHere] what `Log here` does, and [onSidePress] what
 * a single press of a side button does.
 */
@Composable
fun WaypointsScreen(
    vm: CompassViewModel, paneView: () -> PaneView, onEdit: (Long) -> Unit, onLogHere: () -> Unit, onSidePress: () -> Unit,
    onDropCrumb: () -> Unit,
) {
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

    val pressScope = rememberCoroutineScope()
    val sideButton = remember(pressScope) { SideButton(pressScope) }

    // How far a hold on the trail's row has come, from 0 to 1, as on the edit screen's `Hold to delete`. Written
    // every frame of a hold and read only where the frame is drawn.
    val view = LocalView.current
    // The row is not a `clickable`, which would take a hold that is let go early for a tap, so it shows its own
    // press the way the other rows show theirs.
    val trailPress = remember { MutableInteractionSource() }
    val clearHeld = remember { Animatable(0f) }
    var clearing by remember { mutableStateOf(false) }
    var clearStartedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(clearing) {
        if (!clearing) {
            // Let go before the end: nothing is cleared and the ring goes.
            if (clearHeld.value < 1f) clearHeld.animateTo(0f, tween(HOLD_REFILL_MS)) else clearHeld.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            val progress = holdProgress(withFrameMillis { SystemClock.uptimeMillis() } - clearStartedMs, HOLD_TO_DELETE_MS)
            clearHeld.snapTo(progress)
            if (progress >= 1f) {
                if (paneView() == PaneView.IN_VIEW) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    vm.clearTrail()
                }
                clearing = false
                return@LaunchedEffect
            }
        }
    }
    val navState = rememberUpdatedState(nav)
    val sidePress by rememberUpdatedState(onSidePress)
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // The list inside has the focus while this screen does, so a side button's press passes here first.
            // A press does here what it does on the compass.
            .onPreviewKeyEvent { e ->
                sideButton.onKey(
                    e.nativeKeyEvent, inView = { paneView() == PaneView.IN_VIEW }, doubleEnabled = { navState.value.breadcrumbTrail },
                ) { kind -> if (kind == PressKind.DOUBLE) vm.trailDoublePress() else sidePress() }
            }
    ) {
      // The list and what is pinned over it are cut off at one circle inside the frame.
      val statusShowing = status != LogStatus.Idle
      val bandPx = with(density) { (r * STATUS_BAND).toPx() }
      Box(
          Modifier.fillMaxSize().padding(r * (1f - LIST_RADIUS)).clip(CircleShape)
              // While the status band shows, a touch that goes down on it is no tap or long press on the row
              // under it, which cannot be seen: its first contact is taken here, before the list sees it, and
              // the rows then leave the touch alone. The rest of the touch is not taken, so a drag that starts
              // on the band still scrolls the list and still turns to the next screen.
              .pointerInput(statusShowing, bandPx) {
                  if (statusShowing) awaitEachGesture {
                      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                      if (down.position.y < bandPx) down.consume()
                  }
              }
      ) {
        ScalingLazyColumn(
            state = listState,
            // The rows keep their size and their spacing all the way: a row is as easy to hit near the edge as in
            // the middle. They are cut off at a circle inside the frame and fade out toward its top and bottom,
            // so no row is drawn over the frame or stops at a hard edge.
            scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize().fadeTopAndBottom(),
        ) {
            item(key = "header") {
                InstrumentRow(
                    Modifier.clickable(
                        // Not while a status stands over the top of the list, where the count cannot be read.
                        enabled = total > 0 && status == LogStatus.Idle, role = Role.Button,
                        onClickLabel = if (setsAll) "Set all as targets" else "Clear all targets",
                    ) { inView { vm.setAllTargets(setsAll) } },
                    centered = true,
                ) {
                    Text(headerText(targets, total), color = Dim, style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item(key = "log") {
                InstrumentRow(Modifier.clickable(role = Role.Button) { inView(onLogHere) }, centered = true) {
                    Text("Log here", color = AccentColor, style = actionStyle, maxLines = 1)
                }
            }
            val trail = nav.trail
            if (nav.breadcrumbTrail || trail.count > 0) item(key = "trail") {
                val active = trail.mode != TrailMode.OFF
                // There is something to clear while crumbs are stored or a trail is being laid.
                val canClear = trail.count > 0 || active
                val switches = nav.breadcrumbTrail
                InstrumentRow(
                    Modifier
                        .indication(trailPress, LocalIndication.current)
                        // For an accessibility service: the tap and the hold as named actions.
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            // With the setting off a tap does nothing, as a double press does nothing; the crumbs
                            // that are left can still be cleared.
                            if (switches) onClick(trailRowAction(trail.mode, trail.count)) { inView { vm.trailDoublePress() }; true }
                            if (canClear) onLongClick("Hold to clear the trail") { inView { vm.clearTrail() }; true }
                        }
                        .pointerInput(vm, paneView, canClear, switches) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val press = PressInteraction.Press(down.position)
                                pressScope.launch { trailPress.emit(press) }
                                var lifted = false
                                try {
                                // Lifted within the long-press time, it is a tap. A touch the list or the row of
                                // screens takes as a drag is neither a tap nor a hold.
                                var held = false
                                val up = try {
                                    withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                                } catch (e: PointerEventTimeoutCancellationException) {
                                    held = true
                                    null
                                }
                                if (!held) {
                                    lifted = up != null
                                    if (up != null && switches) { up.consume(); inView { vm.trailDoublePress() } }
                                    return@awaitEachGesture
                                }
                                if (!canClear || paneView() != PaneView.IN_VIEW) { lifted = waitForUpOrCancellation() != null; return@awaitEachGesture }
                                clearStartedMs = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                                clearing = true
                                // Ends when the finger lifts or the touch becomes a scroll or a swipe; the hold
                                // itself ends when the ring is empty.
                                try { lifted = waitForUpOrCancellation()?.also { it.consume() } != null } finally { clearing = false }
                                } finally {
                                    pressScope.launch { trailPress.emit(if (lifted) PressInteraction.Release(press) else PressInteraction.Cancel(press)) }
                                }
                            }
                        },
                    trailing = { TickToggle(active, color = { CrumbColor }) },
                ) {
                    Column {
                        Text("Trail", color = if (active) Ink else Dim, style = if (active) nameOnStyle else nameStyle, maxLines = 1)
                        Text(
                            if (clearing) TRAIL_HOLD_TO_CLEAR else trailRowDetail(trail.mode, trail.count),
                            color = if (clearing) ErrorColor else Dim, style = distanceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // What a single press of the side button does while a trail is being laid, for a watch that passes no
            // side button to apps: a crumb, with the same permission question, status, confirming tap and fix rules.
            // The row is there only while a trail is laid, and its call saves nothing at any other time, so it
            // never saves a waypoint.
            if (nav.breadcrumbTrail && trail.mode == TrailMode.LAYING) item(key = "drop") {
                InstrumentRow(Modifier.clickable(role = Role.Button) { inView(onDropCrumb) }, centered = true) {
                    Text(DROP_CRUMB, color = AccentColor, style = actionStyle, maxLines = 1)
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
                        .semantics {
                            toggleableState = ToggleableState(w.isTarget)
                            stateDescription = if (w.isTarget) "Target" else "Not a target"
                        },
                    trailing = { TickToggle(w.isTarget, color = { waypointColor(w.hue) }) },
                ) {
                    Column {
                        // A name too long for the row slides back and forth in it, while this screen is in view.
                        MarqueeText(
                            w.name, if (w.isTarget) nameOnStyle else nameStyle, { if (w.isTarget) Ink else Dim },
                            view = paneView,
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
        val current = status
        if (current != LogStatus.Idle) {
            val (words, detail) = statusText(current, nav.units)
            // A black band over the top of the list, soft at its lower edge, with the status on it. It takes no
            // touch itself; what a touch on it may do is decided above.
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(r * STATUS_BAND)
                    .background(Brush.verticalGradient(0f to Color.Black, STATUS_BAND_SOLID to Color.Black, 1f to Color.Transparent))
                    .padding(top = r * STATUS_TOP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusLine(words, logStatusColor(current), headerStyle, Modifier.width(r * STATUS_WIDTH), view = paneView)
                // A note under the status is dim; a second line that carries the status's words on has its color.
                if (detail != null) Text(
                    detail, color = if (statusDetailContinues(current)) logStatusColor(current) else Dim, style = detailStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
      }
        // While the trail's row is held the frame carries a ring in the color of deleting, which drains as the hold
        // goes on, as on the edit screen.
        FrameRing(
            paneIndex = Pane.WAYPOINTS.ordinal, scroll = { listScrollArc(listState) },
            fill = { if (clearHeld.value > 0f) 1f - clearHeld.value else 0f }, fillColor = ErrorColor, fillInner = TRAIL_CLEAR_RING_INNER,
        )
    }
}

/** What the trail's row says while it is held. */
private const val TRAIL_HOLD_TO_CLEAR = "Hold to clear"
/** The inner edge of the ring that drains while the trail's row is held: outside the list's circle. */
private const val TRAIL_CLEAR_RING_INNER = 0.90f

// The pinned status, in fractions of the screen's radius, measured down from the top of the list's circle: the
// band's height, how much of it is solid black, where the words start, and how wide a line may be there.
private const val STATUS_BAND = 0.58f
private const val STATUS_BAND_SOLID = 0.86f
private const val STATUS_TOP = 0.17f
private const val STATUS_WIDTH = 1.0f

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
