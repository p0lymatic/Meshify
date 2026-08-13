package com.polymatic.meshify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polymatic.meshify.debug.DebugEntry
import com.polymatic.meshify.mesh.Channel
import com.polymatic.meshify.mesh.Contact
import com.polymatic.meshify.mesh.NodeInfo
import com.polymatic.meshify.ui.MeshUiState
import com.polymatic.meshify.ui.AppThemeMode
import kotlinx.coroutines.launch

// ── Stub tabs ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsTab(state: MeshUiState, onOpenChannel: (Channel) -> Unit, onAddChannel: (Int, String, String) -> Unit) {
    val activeChannels = state.channels.filter { !it.isEmpty }
    var showAddDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nextFreeIndex = state.channels.firstOrNull { it.isEmpty }?.index ?: -1

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Rounded.Add, "Add Channel")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Channels · ${activeChannels.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (activeChannels.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.Tag,
                                null,
                                Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("No channels configured", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tap + to add a channel or scan a QR code",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(activeChannels, key = { it.index }) { channel ->
                    ChannelCard(channel, state.channelMessages[channel.index]?.lastOrNull(), state.channelUnread[channel.index] ?: 0, onOpenChannel)
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddChannelDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { index, name, psk ->
                onAddChannel(index, name, psk)
            },
            onScanQr = {
                showAddDialog = false
                showQrScanner = true
            }
        )
    }

    if (showQrScanner) {
        QrChannelScanner(
            defaultIndex = nextFreeIndex,
            onDismiss = { showQrScanner = false },
            onScanned = { index, name, psk ->
                onAddChannel(index, name, psk)
                showQrScanner = false
                scope.launch { snackbarHostState.showSnackbar("Channel $name added to slot $index") }
            }
        )
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    lastMessage: com.polymatic.meshify.mesh.ChannelMessage?,
    unreadCount: Int,
    onOpenChannel: (Channel) -> Unit,
) {
    ElevatedCard(
        onClick = { onOpenChannel(channel) },
        shape = RoundedCornerShape(26.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Tag, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (lastMessage != null) {
                    Text(
                        "${lastMessage.senderName}: ${lastMessage.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        "Channel ${channel.index}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (unreadCount > 0) Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MessagesTab(state: MeshUiState, onOpenChat: (Contact) -> Unit) {
    val chatContacts = state.contacts.filter { it.type == com.polymatic.meshify.mesh.ContactType.Chat }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Direct Messages · ${chatContacts.size}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (chatContacts.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.ChatBubbleOutline,
                            null,
                            Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("No chats yet", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Contacts will appear here once they're discovered",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(chatContacts, key = { it.publicKey }) { contact ->
                val messageList = state.messages[contact.publicKey] ?: emptyList()
                val lastMessage = messageList.lastOrNull()
                DirectMessageCard(contact, lastMessage, state.contactUnread[contact.publicKey] ?: 0, onOpenChat)
            }
        }
    }
}

@Composable
private fun DirectMessageCard(contact: Contact, lastMessage: com.polymatic.meshify.mesh.Message?, unreadCount: Int, onOpenChat: (Contact) -> Unit) {
    ElevatedCard(onClick = { onOpenChat(contact) }, shape = RoundedCornerShape(26.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (lastMessage != null) {
                    Text(
                        lastMessage.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        "No messages yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            if (unreadCount > 0) Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MapTab() {
    StubScreen(Icons.Rounded.Map, "Map", "Node map coming soon")
}

@Composable
private fun StubScreen(icon: ImageVector, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .5f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Settings tab ───────────────────────────────────────────────────────────

@Composable
fun SettingsTab(state: MeshUiState, onDisconnect: () -> Unit, onClearDebugLog: () -> Unit, onShowDebugLog: () -> Unit, onThemeModeChanged: (AppThemeMode) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeader("Node") }
        item { NodeInfoCard(state.node) }
        item { SectionHeader("Appearance") }
        item { ThemeCard(state.themeMode, onThemeModeChanged) }
        item { SectionHeader("Connection") }
        item { ConnectionCard(state, onDisconnect) }
        item { SectionHeader("Debug") }
        item { DebugCard(state.debugLog.size, onShowDebugLog, onClearDebugLog) }
        item { SectionHeader("About") }
        item { AboutCard() }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ThemeCard(selected: AppThemeMode, onThemeModeChanged: (AppThemeMode) -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            AppThemeMode.entries.forEach { mode ->
                val description = when (mode) {
                    AppThemeMode.System -> "Follow the phone light or dark mode"
                    AppThemeMode.Light -> "Always use the light palette"
                    AppThemeMode.Dark -> "Always use the dark palette"
                    AppThemeMode.Monet -> "Use colors from your phone wallpaper"
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onThemeModeChanged(mode) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == mode, onClick = { onThemeModeChanged(mode) })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (mode == AppThemeMode.Monet) {
                        Icon(Icons.Rounded.Palette, "Phone colors", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun NodeInfoCard(node: NodeInfo) {
    val clipboard = LocalClipboardManager.current
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow("Name", node.name ?: "Unknown")
            SettingsRow("Model", node.model ?: "Unknown")
            SettingsRow("Firmware", node.firmware ?: "Unknown")
            node.batteryMv?.let { mv ->
                val volts = "%.2f V".format(mv / 1000.0)
                val pct = estimateBatteryPercent(mv)
                SettingsRow("Battery", "$volts ($pct%)")
            }
            node.publicKey?.let { key ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Public key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${key.take(16)}…${key.takeLast(8)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                        Icon(Icons.Rounded.ContentCopy, "Copy key", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: MeshUiState, onDisconnect: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val connName = (state.connection as? com.polymatic.meshify.mesh.BleState.Connected)?.name ?: "—"
            SettingsRow("Connected to", connName)
            state.connectedAddress?.let { SettingsRow("MAC address", it) }
            SettingsRow("Contacts synced", "${state.contacts.size}")
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.LinkOff, null)
                Spacer(Modifier.width(8.dp))
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun DebugCard(logSize: Int, onShow: () -> Unit, onClear: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow("Log entries", "$logSize")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShow, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.BugReport, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View log")
                }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow("App", "Meshify")
            SettingsRow("Version", "1.0")
            SettingsRow("Protocol", "MeshCore BLE (NUS)")
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Simple LiPo/NMC estimate: 3000mV=0%, 4200mV=100%. */
private fun estimateBatteryPercent(mv: Int): Int =
    ((mv - 3000).coerceIn(0, 1200) * 100 / 1200)
