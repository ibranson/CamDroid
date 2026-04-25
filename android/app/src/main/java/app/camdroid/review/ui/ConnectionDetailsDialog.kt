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
import app.camdroid.review.data.DiscoveryMethod
import app.camdroid.review.data.EventStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionDetailsDialog(
    wsState: EventStream.ConnectionState,
    piHost: String?,
    piPort: Int?,
    discoveryMethod: DiscoveryMethod?,
    lastPongRttMs: Long?,
    lastImageTs: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pi connection") },
        text = {
            Column {
                LabelValue("WS", wsState.name)
                val addr = if (piHost != null && piPort != null) "$piHost:$piPort" else "(unknown)"
                LabelValue("Address", addr)
                LabelValue(
                    "Found via",
                    discoveryMethod?.let { methodLabel(it) } ?: "(pending)",
                )
                LabelValue(
                    "Latency",
                    lastPongRttMs?.let { "$it ms" } ?: "(no ping yet)",
                )
                LabelValue(
                    "Last image",
                    lastImageTs?.let { humanizeTs(it) } ?: "(none yet)",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Latency is measured from the WebSocket ping/pong cycle (~10s interval).",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun methodLabel(m: DiscoveryMethod): String = when (m) {
    DiscoveryMethod.GATEWAY -> "Gateway probe"
    DiscoveryMethod.NSD -> "mDNS / Bonjour"
    DiscoveryMethod.FALLBACK -> "Fallback IP (discovery failed)"
}

private fun humanizeTs(iso: String): String {
    val tsMillis = try { Instant.parse(iso).toEpochMilli() } catch (e: Exception) { return iso }
    val nowMillis = System.currentTimeMillis()
    val ageSeconds = (nowMillis - tsMillis) / 1000
    val ago = when {
        ageSeconds < 60 -> "${ageSeconds}s ago"
        ageSeconds < 3600 -> "${ageSeconds / 60}m ago"
        else -> "${ageSeconds / 3600}h ago"
    }
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(tsMillis))
    return "$clock ($ago)"
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row {
        Text(
            text = "$label  ",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.width(90.dp),
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
    Spacer(Modifier.height(4.dp))
}
