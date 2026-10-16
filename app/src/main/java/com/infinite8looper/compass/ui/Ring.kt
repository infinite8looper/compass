package com.infinite8looper.compass.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.pager.PagerDefaults
import com.infinite8looper.compass.nav.PANE_PAGES
import com.infinite8looper.compass.nav.Pane
import com.infinite8looper.compass.nav.headedPage
import com.infinite8looper.compass.nav.nearestPage
import com.infinite8looper.compass.nav.pageToward
import com.infinite8looper.compass.nav.paneOf
import com.infinite8looper.compass.nav.restPage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where the ring of three screens stands, counted as the long row of pages of nav/Panes.kt in which the three
 * repeat: the page nearest its resting place, how far it has moved from there, where it is headed, and whether it
 * is being moved. These are the four things the rules of nav/Panes.kt ask about, under the names a pager gives
 * them. Each is snapshot state, so a place that reads one through a derived state is told when the answer changes
 * and not on every frame of a swipe.
 *
 * It is the scrolling state of [Ring], which a finger moves through `Modifier.scrollable`; the app moves it with
 * [animateScrollToPage]. Which page is nearest, which a finger is carrying the row to and which a let-go swipe
 * comes to rest on are decided in nav/Panes.kt ([nearestPage], [headedPage], [restPage]). One movement runs at a time: a finger stops a slide the app started, and a new slide
 * stops the one before it.
 */
@Stable
class RingState(startPage: Int) : ScrollableState {
    /** The place in the row, in pages. A page number of millions and a fraction of a pixel both fit a Double. */
    private var position = startPage.toDouble()

    /** The page nearest the row's resting place. */
    var currentPage by mutableIntStateOf(startPage)
        private set
    /** How far the row has moved from [currentPage], in pages: from -0.5 to 0.5, positive toward the next page. */
    var currentPageOffsetFraction by mutableFloatStateOf(0f)
        private set

    /** The page a slide or a let-go swipe is running to, or [NO_PAGE]. */
    private var slidingTo by mutableIntStateOf(NO_PAGE)
    private var lastMovedForward by mutableStateOf(true)

    /** The width of a page in pixels, and the part of it a finger has to carry the row before it is headed for the next page. */
    internal var pageWidth = 0
    internal var headedAfter = 0f
    /** The slowest let-go, in pixels a second, that turns the ring a page on whatever the distance covered. */
    internal var flingAbove = 0f

    private val scrolling = ScrollableState { delta -> moveBy(delta) }

    override val isScrollInProgress: Boolean get() = scrolling.isScrollInProgress
    override fun dispatchRawDelta(delta: Float): Float = scrolling.dispatchRawDelta(delta)
    override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) = scrolling.scroll(scrollPriority, block)

    /**
     * The page the row is headed for: the page it rests on when it is not being moved, the end of a slide while one
     * runs, and under a finger the page on the side the finger is carrying it to, once it has left its place.
     */
    val targetPage: Int by derivedStateOf {
        when {
            !isScrollInProgress -> currentPage
            slidingTo != NO_PAGE -> slidingTo
            else -> headedPage(currentPage, currentPageOffsetFraction, lastMovedForward, headedAfter)
        }
    }

    /** Moves the row by [pixels], positive toward the next page, and says how many of them it took. */
    private fun moveBy(pixels: Float): Float {
        if (pageWidth <= 0 || pixels == 0f) return 0f
        val to = (position + pixels.toDouble() / pageWidth).coerceIn(0.0, (PANE_PAGES - 1).toDouble())
        val taken = if (to == position + pixels.toDouble() / pageWidth) pixels else ((to - position) * pageWidth).toFloat()
        lastMovedForward = pixels > 0f
        stand(to)
        return taken
    }

    private fun stand(at: Double) {
        position = at
        val page = nearestPage(at)
        currentPage = page
        currentPageOffsetFraction = (at - page).toFloat()
    }

    /** Slides to [page], and returns when the row rests there. A touch, or a later slide, ends it where it is. */
    suspend fun animateScrollToPage(page: Int) {
        val to = page.coerceIn(0, PANE_PAGES - 1)
        scroll(MutatePriority.Default) { slide(to, 0f, spring()) }
    }

    /**
     * What a swipe that is let go at [velocity] (pixels a second, positive toward the next page) does: a quick one
     * turns the ring to the next page on its side, a slow one lets it settle on the nearest page.
     */
    internal suspend fun ScrollScope.settle(velocity: Float): Float {
        if (pageWidth <= 0) return velocity
        slide(restPage(position, velocity, flingAbove), velocity, PagerDefaults.SnapAnimationSpec)
        return 0f
    }

    private suspend fun ScrollScope.slide(to: Int, velocity: Float, spec: AnimationSpec<Float>) {
        slidingTo = to
        try {
            val distance = ((to - position) * pageWidth).toFloat()
            var gone = 0f
            if (distance != 0f) AnimationState(0f, velocity).animateTo(distance, spec) {
                // A spring that starts fast runs past its end; the row stops at the page.
                val arrived = if (distance > 0f) value >= distance else value <= distance
                val next = if (arrived) distance else value
                scrollBy(next - gone)
                gone = next
                if (arrived) cancelAnimation()
            }
            // On the page to the pixel, whatever the steps added up to.
            stand(to.toDouble())
        } finally {
            slidingTo = NO_PAGE
        }
    }

    private companion object {
        const val NO_PAGE = -1
    }
}

/**
 * The ring of the three side-by-side screens: [screen] is composed once for each [Pane], and the three stay for as
 * long as the ring does. Turning the ring moves them and builds nothing: each screen is on a layer of its own and
 * is put where the row's place says, read while the layer is placed, so a swipe neither composes nor measures nor
 * draws a screen again. A screen's lists and whatever else it remembers are therefore where they were when the
 * ring comes round to it.
 *
 * Each screen stands at the page of the long row nearest the one in view that shows it ([pageToward]), which in a
 * ring of three is that page or the one on either side, so the screen to the right of waypoints is settings and
 * the one to the left of settings is waypoints.
 *
 * A sideways swipe anywhere on the ring turns it, wherever it starts; let go, the ring comes to rest on a page.
 * The screen nearest the resting place is the active one, which is how that screen and no other gets the focus,
 * and with it the crown and the side buttons. An accessibility service is given the ring as something that pages
 * left and right without end.
 */
@Composable
fun Ring(state: RingState, modifier: Modifier = Modifier, screen: @Composable (Pane) -> Unit) {
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fling = remember(state) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = with(state) { settle(initialVelocity) }
        }
    }
    val turn: (Int) -> Boolean = remember(state, scope) {
        { pages -> scope.launch { state.animateScrollToPage(state.targetPage + pages) }; true }
    }
    val leftIsBack = direction == LayoutDirection.Ltr
    // A ring that is not being moved and stands between two pages comes to rest on the nearer. A let-go swipe
    // settles by itself; this is for a movement that ends without one: a slide cut off half way (the three screens
    // leaving the composition while a Back slides them), or a scroll the system makes. The answer is looked at
    // again a frame later, so the instant between a finger lifting and its fling starting is not taken for one.
    LaunchedEffect(state) {
        // `collect`, not `collectLatest`: the slide itself makes the ring scroll, which must not cut it off.
        snapshotFlow { !state.isScrollInProgress && state.currentPageOffsetFraction != 0f }.collect { stranded ->
            if (!stranded) return@collect
            withFrameNanos { }
            if (!state.isScrollInProgress && state.currentPageOffsetFraction != 0f) state.animateScrollToPage(state.currentPage)
        }
    }
    Layout(
        content = { for (pane in Pane.entries) key(pane) { RingSlot(state, pane, direction, screen) } },
        modifier = modifier
            .clipToBounds()
            // Before `scrollable` in the chain, so applied after it: the scroll action here, which turns the ring a
            // whole screen through a slide, replaces the one `scrollable` gives, which moves it by a width of pixels.
            // A screen reader's scroll forward, backward, left and right come to the page actions (seen on the API
            // 35 emulator), so the ring turns through [turn] whichever it uses.
            .semantics {
                // A ring: from every screen there is one further on, either way.
                horizontalScrollAxisRange = ScrollAxisRange(value = { 1f }, maxValue = { 2f })
                scrollBy { x, _ -> if (x == 0f) false else turn(if (x > 0f) 1 else -1) }
                pageLeft { turn(if (leftIsBack) -1 else 1) }
                pageRight { turn(if (leftIsBack) 1 else -1) }
            }
            .scrollable(
                state, Orientation.Horizontal, flingBehavior = fling,
                reverseDirection = ScrollableDefaults.reverseDirection(direction, Orientation.Horizontal, reverseScrolling = false),
            ),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        state.pageWidth = width
        with(density) {
            state.headedAfter = if (width > 0) minOf(HEADED_AFTER.toPx(), width / 2f) / width else 0f
            state.flingAbove = FLING_ABOVE.toPx()
        }
        val screens = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) { screens.forEach { it.place(0, 0) } }
    }
}

/** One of the ring's three screens, on its layer. */
@Composable
private fun RingSlot(state: RingState, pane: Pane, direction: LayoutDirection, screen: @Composable (Pane) -> Unit) {
    // Read through a derived state: this is composed again when another screen becomes the nearest, and the screen
    // inside, whose inputs are the same, is not.
    val active by remember(state, pane) { derivedStateOf { paneOf(state.currentPage) == pane } }
    val sign = if (direction == LayoutDirection.Ltr) 1 else -1
    Box(
        Modifier.hierarchicalFocusGroup(active).graphicsLayer {
            val nearest = state.currentPage
            val pages = pageToward(nearest, pane) - nearest - state.currentPageOffsetFraction
            translationX = (pages * size.width).roundToInt().toFloat() * sign
        }
    ) { screen(pane) }
}

/** How far a finger carries the ring before it counts as headed for the next page, where the page is wide enough. */
private val HEADED_AFTER = 5.dp
/** A swipe let go faster than this turns the ring to the next page; slower, the ring settles on the nearest. */
private val FLING_ABOVE = 400.dp
