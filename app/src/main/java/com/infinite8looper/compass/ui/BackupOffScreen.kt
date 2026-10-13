package com.infinite8looper.compass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.nav.BACKUP_OFF_CONFIRM
import com.infinite8looper.compass.nav.BACKUP_OFF_DETAIL
import com.infinite8looper.compass.nav.BACKUP_OFF_KEEP
import com.infinite8looper.compass.nav.BACKUP_OFF_QUESTION

/**
 * The question asked before `Back up waypoints` is turned off, opened from that switch while it is on: what is
 * asked, a smaller line saying what turning it off does to the stored backup, and the two answers as rows of a list
 * like the settings', inside the frame. The answer that leaves the switch on stands in the middle when the screen
 * opens, and the one that turns it off is under it in the error color.
 *
 * [onTurnOff] is what the answer that turns it off does, and [onKeep] what the other does. A right swipe and Back
 * leave with nothing changed, which is done where the screen is put into the navigation host (ui/CompassApp.kt).
 * A side button does nothing here.
 */
@Composable
fun BackupOffScreen(onTurnOff: () -> Unit, onKeep: () -> Unit) {
    // The first answer is the row in the middle, with the question above it.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val r = screenRadius()
    val density = LocalDensity.current
    val questionStyle = remember(r, density) { density.instrumentText(r * 0.105f, FontWeight.Medium, TextAlign.Center) }
    val detailStyle = remember(r, density) { density.instrumentText(r * 0.085f, align = TextAlign.Center) }
    val answerStyle = remember(r, density) { density.instrumentText(r * 0.13f, FontWeight.Medium, TextAlign.Center) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().padding(r * (1f - LIST_RADIUS)).clip(CircleShape)) {
            ScalingLazyColumn(
                state = listState,
                scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
                verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxSize().fadeTopAndBottom(),
            ) {
                item(key = "question") {
                    InstrumentRow(centered = true) {
                        // Narrower than a row: the lines stand above the middle of the screen, where the circle is narrower.
                        Column(Modifier.width(r * QUESTION_WIDTH).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(BACKUP_OFF_QUESTION, color = Ink, style = questionStyle)
                            Text(BACKUP_OFF_DETAIL, color = Dim, style = detailStyle)
                        }
                    }
                }
                item(key = "keep") {
                    InstrumentRow(Modifier.clickable(role = Role.Button, onClick = onKeep), centered = true) {
                        Text(BACKUP_OFF_KEEP, color = AccentColor, style = answerStyle, maxLines = 1)
                    }
                }
                item(key = "off") {
                    InstrumentRow(Modifier.clickable(role = Role.Button, onClick = onTurnOff), centered = true) {
                        Text(BACKUP_OFF_CONFIRM, color = ErrorColor, style = answerStyle, maxLines = 1)
                    }
                }
            }
        }
        FrameRing(scroll = { listScrollArc(listState) })
    }
}

/** How wide the question and its smaller line may be, as a share of the screen's radius. */
private const val QUESTION_WIDTH = 1.25f
