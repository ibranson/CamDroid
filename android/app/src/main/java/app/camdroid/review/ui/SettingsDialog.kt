package app.camdroid.review.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.camdroid.review.Config

/**
 * Placeholder settings dialog. Real settings (Pi address, Wi-Fi, image cache
 * retention, primary tablet client, etc.) live in the Pi's admin web UI when
 * that ships; this dialog is a holding spot showing the current effective
 * configuration plus a few diagnostics.
 */
@Composable
fun SettingsDialog(
    sessionId: String?,
    cameraModel: String,
    eventLogVisible: Boolean,
    onToggleEventLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CamDroid Review") },
        text = {
            Column {
                LabelValue("App", "v0.0.1 (development)")
                LabelValue("Pi", Config.BASE_URL)
                LabelValue("Camera", cameraModel.ifEmpty { "(not detected)" })
                LabelValue("Session", sessionId?.take(8)?.let { "$it…" } ?: "(none)")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    onToggleEventLog()
                    onDismiss()
                }) {
                    Text(if (eventLogVisible) "Hide event log overlay" else "Show event log overlay")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Full configuration moves to the Pi's admin web UI in a later milestone (Wi-Fi setup, retention, restart, log tail, etc.).",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Text(
        text = "$label  ",
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )
    Text(
        text = value,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(4.dp))
}
