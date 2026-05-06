package app.camdroid.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Picker dialog for the aspect-ratio overlay.
 *
 * Tapping a ratio picks it AND turns the overlay on (so the user doesn't
 * also have to remember to tap the chrome icon afterwards). Tapping the
 * already-selected ratio turns the overlay off.
 *
 * "Off" is also offered explicitly at the top so a user who knows they
 * want it gone can do it from the picker without having to dismiss and
 * re-tap the chrome icon.
 */
@Composable
fun AspectRatioPicker(
    currentRatio: AspectRatio?,
    overlayActive: Boolean,
    onSelect: (AspectRatio) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Find the currently-selected entry's index. Item 0 is the "Off" row;
    // ratio entries follow in AspectRatios.ALL order.
    val selectedIndex = if (!overlayActive) {
        0
    } else {
        val idx = AspectRatios.ALL.indexOfFirst { it == currentRatio }
        if (idx < 0) 0 else idx + 1
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        // Auto-scroll to the selected ratio when the dialog opens, so the
        // user sees their current choice without manual scrolling.
        if (selectedIndex > 0) {
            listState.scrollToItem(selectedIndex)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aspect ratio overlay") },
        text = {
            Column {
                LazyColumn(
                    state = listState,
                    // Cap the picker's vertical extent so it stays usable in
                    // landscape / cramped heights without spilling the dialog
                    // off-screen. Content above this max scrolls.
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    item(key = "off") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onTurnOff(); onDismiss() }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionMark(selected = !overlayActive)
                            Spacer(Modifier.width(12.dp))
                            Text("Off", fontSize = 14.sp)
                        }
                    }
                    items(AspectRatios.ALL, key = { it.label }) { r ->
                        val isSelected = overlayActive && currentRatio == r
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (isSelected) {
                                        onTurnOff()
                                    } else {
                                        onSelect(r)
                                    }
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionMark(selected = isSelected)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                r.label,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.width(70.dp),
                            )
                            // Mini visual hint of the ratio's shape (a tiny
                            // rectangle proportional to the ratio).
                            RatioGlyph(r)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: tap the aspect-ratio chrome icon to toggle the overlay on/off; long-press to open this picker.",
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SelectionMark(selected: Boolean) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Selected",
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        Spacer(Modifier.width(24.dp))
    }
}

@Composable
private fun RatioGlyph(r: AspectRatio) {
    // Draw a small rectangle whose proportions match the ratio. Useful as a
    // visual disambiguator between "5:4" and "4:5" at a glance.
    val maxEdge = 22.dp
    val (boxW, boxH) = if (r.w >= r.h) {
        maxEdge to (maxEdge * (r.h / r.w))
    } else {
        (maxEdge * (r.w / r.h)) to maxEdge
    }
    Box(
        modifier = Modifier
            .width(maxEdge)
            .height(maxEdge),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(boxW)
                .height(boxH)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}
