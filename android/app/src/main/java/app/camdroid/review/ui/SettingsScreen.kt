package app.camdroid.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.camdroid.review.data.DiscoveryMethod
import app.camdroid.review.data.EventStream
import app.camdroid.review.data.ThemeMode

private const val APP_VERSION = "v0.0.1 (development)"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: UiState,
    onBack: () -> Unit,
    onFindBridge: () -> Unit,
    onSetManualAddress: (host: String, port: Int) -> Unit,
    onToggleEventLog: () -> Unit,
    onToggleExifPanel: () -> Unit,
    onToggleAspectOverlay: () -> Unit,
    onToggleRotationSnap: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAutoShowOnCapture: (Boolean) -> Unit,
    onReconnectWebSocket: () -> Unit,
) {
    var manualDialogOpen by remember { mutableStateOf(false) }

    if (manualDialogOpen) {
        ManualAddressDialog(
            initialHost = ui.bridgeHost.orEmpty(),
            initialPort = ui.bridgePort ?: 8080,
            onConfirm = { host, port ->
                onSetManualAddress(host, port)
                manualDialogOpen = false
            },
            onDismiss = { manualDialogOpen = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BridgeCard(
                    ui = ui,
                    onFindBridge = onFindBridge,
                    onOpenManualDialog = { manualDialogOpen = true },
                )
            }
            item {
                DisplayCard(
                    ui = ui,
                    onToggleEventLog = onToggleEventLog,
                    onToggleExifPanel = onToggleExifPanel,
                    onToggleAspectOverlay = onToggleAspectOverlay,
                    onToggleRotationSnap = onToggleRotationSnap,
                    onSetThemeMode = onSetThemeMode,
                )
            }
            item {
                CaptureCard(
                    ui = ui,
                    onSetAutoShowOnCapture = onSetAutoShowOnCapture,
                )
            }
            item {
                DiagnosticsCard(
                    ui = ui,
                    onReconnectWebSocket = onReconnectWebSocket,
                )
            }
            item {
                AboutCard()
            }
        }
    }
}

/* ---------- Section: Bridge ---------- */

@Composable
private fun BridgeCard(
    ui: UiState,
    onFindBridge: () -> Unit,
    onOpenManualDialog: () -> Unit,
) {
    SectionCard(title = "Bridge") {
        val statusLabel = when (ui.wsState) {
            EventStream.ConnectionState.CONNECTED -> "Connected"
            EventStream.ConnectionState.CONNECTING -> "Connecting"
            EventStream.ConnectionState.UNCONNECTED -> "Disconnected"
            EventStream.ConnectionState.FAILED -> "Failed"
        }
        LabelValueRow("Status", statusLabel)
        val addr = if (ui.bridgeHost != null && ui.bridgePort != null) {
            "${ui.bridgeHost}:${ui.bridgePort}"
        } else "(unknown)"
        LabelValueRow("Address", addr, monospace = true)
        LabelValueRow("Discovery", methodLabel(ui.discoveryMethod))
        LabelValueRow("Camera", ui.cameraModel.ifEmpty { "(not detected)" })
        LabelValueRow(
            "Bridge session",
            ui.sessionId?.take(8)?.let { "$it…" } ?: "(none)",
            monospace = true,
        )

        if (ui.bridgeProbeMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                ui.bridgeProbeMessage,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onFindBridge,
                enabled = !ui.bridgeProbing,
            ) {
                if (ui.bridgeProbing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (ui.bridgeProbing) "Probing…" else "Find bridge")
            }
            OutlinedButton(onClick = onOpenManualDialog) {
                Text("Set address…")
            }
        }
    }
}

private fun methodLabel(m: DiscoveryMethod?): String = when (m) {
    DiscoveryMethod.GATEWAY -> "Gateway probe"
    DiscoveryMethod.NSD -> "mDNS / Bonjour"
    DiscoveryMethod.MANUAL -> "Manual override"
    DiscoveryMethod.FALLBACK -> "Fallback IP"
    null -> "(pending)"
}

@Composable
private fun ManualAddressDialog(
    initialHost: String,
    initialPort: Int,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }

    val canSubmit = host.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } == true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set bridge address") },
        text = {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Host or IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pinned address takes priority over auto-discovery. Tap \"Find bridge\" to clear it.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(host.trim(), port.toInt()) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/* ---------- Section: Display ---------- */

@Composable
private fun DisplayCard(
    ui: UiState,
    onToggleEventLog: () -> Unit,
    onToggleExifPanel: () -> Unit,
    onToggleAspectOverlay: () -> Unit,
    onToggleRotationSnap: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    SectionCard(title = "Display") {
        SwitchRow(
            label = "EXIF strip default",
            description = "Show the EXIF strip whenever an image is on screen.",
            checked = ui.exifPanelToggled,
            onToggle = { onToggleExifPanel() },
        )
        SwitchRow(
            label = "Aspect overlay default",
            description = "Show the framing overlay (last-used ratio: ${ui.aspectRatio.label}).",
            checked = ui.aspectOverlayActive,
            onToggle = { onToggleAspectOverlay() },
        )
        SwitchRow(
            label = "Rotation snap",
            description = "Soft-snap two-finger twist to the nearest 90°.",
            checked = ui.rotationSnapEnabled,
            onToggle = { onToggleRotationSnap() },
        )
        SwitchRow(
            label = "Event-log overlay",
            description = "Floating dev log over the image.",
            checked = ui.showEventLog,
            onToggle = { onToggleEventLog() },
        )
        ThemePickerRow(
            current = ui.themeMode,
            onSelect = onSetThemeMode,
        )
    }
}

@Composable
private fun ThemePickerRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Theme", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Override the system light/dark choice.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(themeLabel(current))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(themeLabel(mode)) },
                        onClick = {
                            expanded = false
                            onSelect(mode)
                        },
                    )
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/* ---------- Section: Capture ---------- */

@Composable
private fun CaptureCard(
    ui: UiState,
    onSetAutoShowOnCapture: (Boolean) -> Unit,
) {
    SectionCard(title = "Capture behavior") {
        SwitchRow(
            label = "Auto-show new captures",
            description = "When unlocked, jump to the newest shot as it arrives. Has no effect while locked or half-locked.",
            checked = ui.autoShowOnCapture,
            onToggle = { onSetAutoShowOnCapture(!ui.autoShowOnCapture) },
        )
    }
}

/* ---------- Section: Diagnostics ---------- */

@Composable
private fun DiagnosticsCard(
    ui: UiState,
    onReconnectWebSocket: () -> Unit,
) {
    val clipboard: ClipboardManager = LocalClipboardManager.current
    SectionCard(title = "Diagnostics") {
        LabelValueRow("App version", APP_VERSION)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bridge session",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.width(120.dp),
            )
            Text(
                ui.sessionId ?: "(none)",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (ui.sessionId != null) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(ui.sessionId)) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy session id",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        LabelValueRow(
            "Last ping RTT",
            ui.lastPongRttMs?.let { "$it ms" } ?: "(no ping yet)",
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReconnectWebSocket) {
                Text("Reconnect WebSocket")
            }
            OutlinedButton(
                onClick = {
                    val joined = ui.eventLog.joinToString("\n")
                    clipboard.setText(AnnotatedString(joined))
                },
                enabled = ui.eventLog.isNotEmpty(),
            ) {
                Text("Copy event log")
            }
        }
        if (ui.eventLog.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(8.dp),
            ) {
                LazyColumn {
                    items(ui.eventLog) { line ->
                        Text(
                            line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/* ---------- Section: About ---------- */

@Composable
private fun AboutCard() {
    SectionCard(title = "About") {
        Text("CamDroid Review", fontWeight = FontWeight.SemiBold)
        Text(APP_VERSION, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bridge-side configuration (Wi-Fi SSID, passphrase, retention) lives in the bridge's web admin UI when that ships.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------- Shared atoms ---------- */

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String, monospace: Boolean = false) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
