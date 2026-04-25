package app.camdroid.review.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tap-to-show details for the camera. Shows what we currently know — model,
 * firmware, serial — plus the live state machine value. Battery, lens, and
 * shots-on-card are TBD; they'll fill in once the Pi-side daemon starts
 * polling and broadcasting them as separate WebSocket events.
 */
@Composable
fun CameraDetailsDialog(
    state: String,
    model: String,
    firmware: String,
    serial: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera") },
        text = {
            Column {
                LabelValue("Model", model.ifEmpty { "(not detected)" })
                LabelValue("State", state)
                LabelValue("Firmware", firmware.ifEmpty { "(not reported)" })
                LabelValue("Serial", serial.ifEmpty { "(not reported)" })
                Spacer(Modifier.height(12.dp))
                Text(
                    "Battery, lens, and shots-on-card will appear here once the Pi daemon starts polling and broadcasting them.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row {
        Text(
            text = "$label  ",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
    Spacer(Modifier.height(4.dp))
}
