package com.polymatic.meshify.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polymatic.meshify.mesh.*
import com.polymatic.meshify.ui.screens.*

private enum class Tab(val label: String, val icon: ImageVector) {
    Contacts("Contacts", Icons.Rounded.People),
    Channels("Channels", Icons.Rounded.Forum),
    Messages("Messages", Icons.Rounded.ChatBubbleOutline),
    Map("Map", Icons.Rounded.Map),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable fun MeshifyApp(
    state: MeshUiState,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onConnectByAddress: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRemoveRecentMac: (String) -> Unit,
    onClearDebugLog: () -> Unit,
    onOpenChat: (Contact) -> Unit,
    onCloseChat: () -> Unit,
    onSendMessage: (String, String) -> Unit,
    onRetryMessage: (String, String) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onCloseChannel: () -> Unit,
    onSendChannelMessage: (Int, String) -> Unit,
    onAddChannel: (Int, String, String) -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
) {
    var showDebugLog by remember { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (state.themeMode) {
        AppThemeMode.System, AppThemeMode.Monet -> systemDark
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    val colorScheme = when {
        state.themeMode == AppThemeMode.Monet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(androidx.compose.ui.platform.LocalContext.current) else dynamicLightColorScheme(androidx.compose.ui.platform.LocalContext.current)
        dark -> meshifyDarkColorScheme()
        else -> meshifyLightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.activeChannel != null -> {
                    ChannelChatScreen(
                        channel = state.activeChannel,
                        messages = state.channelMessages[state.activeChannel.index] ?: emptyList(),
                        onSendMessage = { text -> onSendChannelMessage(state.activeChannel.index, text) },
                        onBack = onCloseChannel,
                    )
                }
                state.activeContact != null -> {
                    ChatScreen(
                        contact = state.activeContact,
                        messages = state.messages[state.activeContact.publicKey] ?: emptyList(),
                        onSendMessage = { text -> onSendMessage(state.activeContact.publicKey, text) },
                        onRetryMessage = { messageId -> onRetryMessage(state.activeContact.publicKey, messageId) },
                        onBack = onCloseChat,
                    )
                }
                state.connection is BleState.Connected -> {
                    ConnectedShell(state, onDisconnect, onClearDebugLog, showDebugLog, { showDebugLog = it }, onOpenChat, onOpenChannel, onAddChannel, onThemeModeChanged)
                }
                else -> {
                    ScannerScreen(state, onRequestPermissions, onToggleScan, onConnect, onConnectByAddress, onRemoveRecentMac, { showDebugLog = true })
                }
            }
        }
        if (showDebugLog) DebugLogDialog(state.debugLog) { showDebugLog = false }
    }
}

// ── Connected shell with tabs ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ConnectedShell(state: MeshUiState, onDisconnect: () -> Unit, onClearDebugLog: () -> Unit, showDebugLog: Boolean, setShowDebug: (Boolean) -> Unit, onOpenChat: (Contact) -> Unit, onOpenChannel: (Channel) -> Unit, onAddChannel: (Int, String, String) -> Unit, onThemeModeChanged: (AppThemeMode) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    val unread = when (t) {
                        Tab.Channels -> state.channelUnread.values.sum()
                        Tab.Messages -> state.contactUnread.values.sum()
                        else -> 0
                    }
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { BadgedBox(badge = { if (unread > 0) Badge { Text(unreadBadgeText(unread)) } }) { Icon(t.icon, null) } },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (Tab.entries[tab]) {
                Tab.Contacts -> ContactsTab(state, onOpenChat)
                Tab.Channels -> ChannelsTab(state, onOpenChannel, onAddChannel)
                Tab.Messages -> MessagesTab(state, onOpenChat)
                Tab.Map -> MapTab()
                Tab.Settings -> SettingsTab(state, onDisconnect, onClearDebugLog, { setShowDebug(true) }, onThemeModeChanged)
            }
        }
    }
}

private fun meshifyLightColorScheme() = lightColorScheme(
    primary = Color(0xFF005DAA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF176A48),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA4F3C4),
    onSecondaryContainer = Color(0xFF002112),
    tertiary = Color(0xFF914D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC1),
    onTertiaryContainer = Color(0xFF301400),
)

private fun meshifyDarkColorScheme() = darkColorScheme(
    primary = Color(0xFF9CCAFF),
    secondary = Color(0xFF9AD0B1),
    tertiary = Color(0xFFFFB77D),
)

private fun unreadBadgeText(count: Int) = if (count > 99) "99+" else count.toString()

// ── Contacts tab ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ContactsTab(state: MeshUiState, onOpenChat: (Contact) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column { Text(state.node.name ?: "MeshCore", fontWeight = FontWeight.Bold); Text(state.node.model ?: "Connected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { NodeSummary(state.node, state.contacts.size) }
            item { Text(if (state.isSyncingContacts) "Synchronizing contacts…" else "Contacts · ${state.contacts.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            if (!state.isSyncingContacts && state.contacts.isEmpty()) item { EmptyMsg(Icons.Rounded.PeopleOutline, "No known contacts yet", "The node will add contacts as it hears them.") }
            items(state.contacts, key = { it.publicKey }) { ContactCard(it, onOpenChat) }
        }
    }
}

@Composable private fun NodeSummary(node: NodeInfo, count: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Hub, null, Modifier.size(32.dp)); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(node.firmware ?: "Node ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("$count known nodes", style = MaterialTheme.typography.bodyMedium) }
            node.batteryMv?.let { AssistChip(onClick = {}, label = { Text("${it / 1000.0} V") }, leadingIcon = { Icon(Icons.Rounded.BatteryFull, null) }) }
        }
    }
}

@Composable private fun ContactCard(c: Contact, onOpenChat: (Contact) -> Unit) {
    val accent = when (c.type) { ContactType.Chat -> Color(0xFF8AB4F8); ContactType.Repeater -> Color(0xFFFFB86C); ContactType.Room -> Color(0xFFE5A5FF); ContactType.Sensor -> Color(0xFF7AD9C4) }
    val icon = when (c.type) { ContactType.Chat -> Icons.Rounded.Person; ContactType.Repeater -> Icons.Rounded.CellTower; ContactType.Room -> Icons.Rounded.Forum; ContactType.Sensor -> Icons.Rounded.Sensors }
    ElevatedCard(onClick = { if (c.type == ContactType.Chat) onOpenChat(c) }, shape = RoundedCornerShape(26.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(accent.copy(alpha = .2f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent) }; Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (c.favorite) { Spacer(Modifier.width(5.dp)); Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC34F), modifier = Modifier.size(17.dp)) } }
            Text("${c.type.label} · ${contactRouteLabel(c.hops)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${c.publicKey.take(8)}…${c.publicKey.takeLast(6)}  ·  ${relativeTime(c.lastSeenEpoch)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }; if (c.latitude != 0.0 || c.longitude != 0.0) Icon(Icons.Rounded.LocationOn, "Location", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

private fun contactRouteLabel(hops: Int) = when {
    hops < 0 -> "Flood route"
    hops == 0 -> "Direct"
    else -> "$hops hops"
}

// ── Scanner screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScannerScreen(state: MeshUiState, onPerms: () -> Unit, onToggle: () -> Unit, onConnect: (BleDevice) -> Unit, onConnectAddr: (String) -> Unit, onRemoveRecent: (String) -> Unit, showDebug: () -> Unit) {
    val isScanning = state.connection is BleState.Scanning
    Scaffold(
        topBar = { TopAppBar(title = { Text("Meshify", fontWeight = FontWeight.Bold) }, actions = { IconButton(onClick = showDebug) { Icon(Icons.Rounded.BugReport, "Log") } }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { if (isScanning) onToggle() else onPerms() }, containerColor = if (isScanning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                if (isScanning) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Stop") }
                else { Icon(Icons.Rounded.BluetoothSearching, null); Spacer(Modifier.width(10.dp)); Text("Scan") }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StatusCard(state.connection) }
            item { ManualConnectCard(state.recentMacs, onConnectAddr, onRemoveRecent) }
            val mesh = state.devices.filter { it.isMeshCore }; val other = state.devices.filter { !it.isMeshCore }
            if (mesh.isNotEmpty()) { item { Text("MeshCore nodes · ${mesh.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }; items(mesh, key = { it.address }) { DeviceCard(it, onConnect) } }
            if (other.isNotEmpty()) { item { Text("Other BLE devices · ${other.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(other, key = { it.address }) { DeviceCard(it, onConnect) } }
            if (state.devices.isEmpty()) item { EmptyMsg(Icons.Rounded.Radar, if (isScanning) "Looking nearby…" else "Tap Scan to find your node", "Meshify detects MeshCore nodes by NUS and known firmware name prefixes.") }
        }
    }
}

@Composable private fun StatusCard(s: BleState) {
    val (text, color) = when (s) { BleState.Scanning -> "Searching…" to MaterialTheme.colorScheme.primaryContainer; is BleState.Connecting -> "Connecting to ${s.name}…" to MaterialTheme.colorScheme.tertiaryContainer; is BleState.Failed -> s.reason to MaterialTheme.colorScheme.errorContainer; else -> "Ready to discover a MeshCore node" to MaterialTheme.colorScheme.secondaryContainer }
    Surface(color = color, shape = RoundedCornerShape(28.dp)) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s is BleState.Scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(if (s is BleState.Failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Bluetooth, null)
        Spacer(Modifier.width(14.dp)); Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    } }
}

@Composable private fun ManualConnectCard(recentMacs: List<RecentMac>, onConnect: (String) -> Unit, onRemove: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    ElevatedCard(shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Direct connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = address, onValueChange = { address = it.uppercase().filter { c -> c in "0123456789ABCDEF:" } }, modifier = Modifier.weight(1f), placeholder = { Text("AA:BB:CC:DD:EE:FF", fontFamily = FontFamily.Monospace) }, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), singleLine = true, shape = RoundedCornerShape(16.dp))
                FilledTonalButton(onClick = { onConnect(address) }, enabled = address.length >= 17) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(6.dp)); Text("Connect") }
            }
            if (recentMacs.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentMacs, key = { it.address }) { mac ->
                        InputChip(selected = false, onClick = { onConnect(mac.address) }, label = { Text(mac.name.ifBlank { mac.address }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp).clickable { onRemove(mac.address) }) })
                    }
                }
            }
        }
    }
}

@Composable private fun DeviceCard(d: BleDevice, connect: (BleDevice) -> Unit) {
    ElevatedCard(onClick = { connect(d) }, shape = RoundedCornerShape(26.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        SignalIcon(d.rssi, d.isMeshCore); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${d.address}  ·  ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when { d.hasNordicUart && d.hasKnownName -> "MeshCore · NUS"; d.hasNordicUart -> "NUS advertised"; d.hasKnownName -> "Known MeshCore firmware"; else -> "BLE device · tap to try" }, style = MaterialTheme.typography.labelSmall, color = if (d.isMeshCore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable private fun SignalIcon(rssi: Int, mesh: Boolean) {
    val c = when { rssi >= -60 -> Color(0xFF69D499); rssi >= -75 -> Color(0xFFFFD36B); else -> MaterialTheme.colorScheme.error }
    Box(Modifier.size(44.dp).clip(CircleShape).background(c.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(if (mesh) Icons.Rounded.Router else Icons.Rounded.NetworkCell, null, tint = c) }
}

// ── Shared ──────────────────────────────────────────────────────────────────

@Composable private fun EmptyMsg(icon: ImageVector, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun relativeTime(epoch: Long): String { if (epoch <= 0) return "Unknown"; val s = (System.currentTimeMillis() / 1000 - epoch).coerceAtLeast(0); return when { s < 60 -> "Now"; s < 3600 -> "${s / 60} min"; s < 86400 -> "${s / 3600} h"; else -> "${s / 86400} d" } }

@Composable private fun DebugLogDialog(entries: List<com.polymatic.meshify.debug.DebugEntry>, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("BLE debug log") }, text = { SelectionContainer { LazyColumn(Modifier.heightIn(max = 440.dp)) { if (entries.isEmpty()) item { Text("No BLE events yet.") }; items(entries) { e -> Text("${e.timestamp}  ${e.message}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) } } } }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
