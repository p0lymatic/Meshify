package com.polymatic.meshify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import com.polymatic.meshify.ui.uiText
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
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 8.dp),
            ) {
                Icon(Icons.Rounded.Add, "Добавить канал")
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
                        "Каналы · ${activeChannels.size}",
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
                            Text("Каналов пока нет", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Нажмите +, чтобы добавить канал или считать QR-код",
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
                scope.launch { snackbarHostState.showSnackbar("Канал $name добавлен в слот $index") }
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Tag, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                    "Личные сообщения · ${chatContacts.size}",
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
                        Text("Чатов пока нет", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Контакты появятся здесь после обнаружения",
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
    ElevatedCard(onClick = { onOpenChat(contact) }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp)) {
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
                        "Сообщений пока нет",
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
    StubScreen(Icons.Rounded.Map, "Карта", "Карта нод появится в следующей версии")
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
fun SettingsTab(
    state: MeshUiState,
    onDisconnect: () -> Unit,
    onClearDebugLog: () -> Unit,
    onShowDebugLog: () -> Unit,
    onMonetChanged: (Boolean) -> Unit,
    onDarkModeChanged: (Boolean) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onSetNodeName: (String) -> Unit,
    onSendSelfAdvert: (Boolean) -> Unit,
    onSetRadioSettings: (Int, Int, Int, Int, Int) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeader(uiText("Нода", "Node")) }
        item { NodeInfoCard(state.node, onSetNodeName, onSendSelfAdvert, onSetRadioSettings) }
        item { SectionHeader(uiText("Оформление", "Appearance")) }
        item { ThemeCard(state.theme.useMonet, state.theme.darkMode, state.theme.languageTag, onMonetChanged, onDarkModeChanged, onLanguageChanged) }
        item { SectionHeader(uiText("Подключение", "Connection")) }
        item { ConnectionCard(state, onDisconnect) }
        item { SectionHeader(uiText("Отладка", "Debug")) }
        item { DebugCard(state.debugLog.size, onShowDebugLog, onClearDebugLog) }
        item { SectionHeader(uiText("О приложении", "About")) }
        item { AboutCard() }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ThemeCard(useMonet: Boolean, darkMode: Boolean, languageTag: String, onMonetChanged: (Boolean) -> Unit, onDarkModeChanged: (Boolean) -> Unit, onLanguageChanged: (String) -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            ThemeSwitchRow(Icons.Rounded.Palette, uiText("Цвета Monet", "Monet colors"), uiText("Использовать цвета обоев телефона", "Use colors from your phone wallpaper"), useMonet, onMonetChanged)
            HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ThemeSwitchRow(Icons.Rounded.DarkMode, uiText("Тёмная тема", "Dark mode"), uiText("Использовать тёмную палитру", "Use the dark surface palette"), darkMode, onDarkModeChanged)
            HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            LanguageRow(languageTag, onLanguageChanged)
        }
    }
}

@Composable
private fun LanguageRow(languageTag: String, onLanguageChanged: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = {}, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.Language, null, Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(uiText("Язык", "Language"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(uiText("Язык интерфейса приложения", "Application interface language"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = languageTag == "ru", onClick = { onLanguageChanged("ru") }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Русский") }
            SegmentedButton(selected = languageTag == "en", onClick = { onLanguageChanged("en") }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("English") }
        }
    }
}

@Composable
private fun ThemeSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { onCheckedChange(!checked) }, modifier = Modifier.size(42.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
private fun NodeInfoCard(
    node: NodeInfo,
    onSetNodeName: (String) -> Unit,
    onSendSelfAdvert: (Boolean) -> Unit,
    onSetRadioSettings: (Int, Int, Int, Int, Int) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRadioDialog by remember { mutableStateOf(false) }
    ElevatedCard(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Имя", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(node.name ?: "Неизвестно", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                FilledTonalIconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Rounded.Edit, "Переименовать ноду")
                }
            }
            SettingsRow("Модель", node.model ?: "Неизвестно")
            SettingsRow("Прошивка", node.firmware ?: "Неизвестно")
            node.batteryMv?.let { mv ->
                val volts = "%.2f V".format(mv / 1000.0)
                val pct = estimateBatteryPercent(mv)
                SettingsRow("Батарея", "$volts ($pct%)")
            }
            node.frequencyHz?.let { frequency ->
                SettingsRow("Радио", "${"%.3f".format(frequency / 1_000_000.0)} МГц")
            }
            node.txPowerDbm?.let { power ->
                SettingsRow("Мощность TX", "$power дБм${node.maxTxPowerDbm?.let { " / $it" }.orEmpty()}")
            }
            node.publicKey?.let { key ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Публичный ключ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${key.take(16)}…${key.takeLast(8)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                        Icon(Icons.Rounded.ContentCopy, "Скопировать ключ", modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(onClick = { showRadioDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.SettingsInputAntenna, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Настройки радио")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onSendSelfAdvert(false) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.NearMe, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Рядом")
                }
                Button(
                    onClick = { onSendSelfAdvert(true) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.CellTower, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Flood")
                }
            }
        }
    }
    if (showRenameDialog) {
        RenameNodeDialog(
            initialName = node.name.orEmpty(),
            onDismiss = { showRenameDialog = false },
            onConfirm = {
                onSetNodeName(it)
                showRenameDialog = false
            },
        )
    }
    if (showRadioDialog) {
        RadioSettingsDialog(
            node = node,
            onDismiss = { showRadioDialog = false },
            onConfirm = { frequencyHz, bandwidthHz, spreadingFactor, codingRate, txPowerDbm ->
                onSetRadioSettings(frequencyHz, bandwidthHz, spreadingFactor, codingRate, txPowerDbm)
                showRadioDialog = false
            },
        )
    }
}

@Composable
private fun RenameNodeDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val nameBytes = name.trim().encodeToByteArray().size
    val validName = nameBytes in 1..31
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, null) },
        title = { Text("Переименовать ноду") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Имя ноды") },
                supportingText = { Text("$nameBytes / 31 байт") },
                isError = name.isNotBlank() && !validName,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = validName) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun RadioSettingsDialog(
    node: NodeInfo,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int, Int) -> Unit,
) {
    var frequencyMHz by remember(node.frequencyHz) { mutableStateOf(node.frequencyHz?.let { "%.3f".format(it / 1_000_000.0) } ?: "915.000") }
    var txPower by remember(node.txPowerDbm) { mutableStateOf((node.txPowerDbm ?: 20).toString()) }
    var bandwidthHz by remember(node.bandwidthHz) { mutableIntStateOf(node.bandwidthHz?.takeIf { it in loraBandwidthsHz } ?: 125_000) }
    var spreadingFactor by remember(node.spreadingFactor) { mutableIntStateOf(node.spreadingFactor?.takeIf { it in 5..12 } ?: 7) }
    val initialCodingRate = node.codingRate?.let { if (it <= 4) it + 4 else it }
    var codingRate by remember(initialCodingRate) { mutableIntStateOf(initialCodingRate?.takeIf { it in 5..8 } ?: 5) }
    val frequencyHz = (frequencyMHz.replace(',', '.').toDoubleOrNull()?.times(1_000_000))?.toInt()
    val power = txPower.toIntOrNull()
    val maxTxPower = node.maxTxPowerDbm ?: 22
    val validFrequency = frequencyHz?.let { it in 300_000_000..2_500_000_000 } == true
    val validPower = power?.let { it in 0..maxTxPower } == true
    val valid = validFrequency && validPower

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SettingsInputAntenna, null) },
        title = { Text("Настройки радио") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = frequencyMHz,
                    onValueChange = { frequencyMHz = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Частота (МГц)") },
                    singleLine = true,
                    isError = frequencyMHz.isNotBlank() && !validFrequency,
                    supportingText = { Text("300.000-2500.000 МГц") },
                )
                RadioChoiceRow("Полоса", loraBandwidthsHz, bandwidthHz, { "${it / 1_000.0} кГц" }) { bandwidthHz = it }
                RadioChoiceRow("Коэффициент расширения", (5..12).toList(), spreadingFactor, { "SF$it" }) { spreadingFactor = it }
                RadioChoiceRow("Скорость кодирования", (5..8).toList(), codingRate, { "4/$it" }) { codingRate = it }
                OutlinedTextField(
                    value = txPower,
                    onValueChange = { txPower = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Мощность TX (дБм)") },
                    singleLine = true,
                    isError = txPower.isNotBlank() && !validPower,
                    supportingText = { Text("0-$maxTxPower дБм") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(frequencyHz!!, bandwidthHz, spreadingFactor, codingRate, power!!) }, enabled = valid) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun RadioChoiceRow(label: String, values: List<Int>, selected: Int, labelFor: (Int) -> String, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Text(labelFor(selected), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(labelFor(value)) },
                    onClick = { onSelected(value); expanded = false },
                )
            }
        }
    }
}

private val loraBandwidthsHz = listOf(7_800, 10_400, 15_600, 20_800, 31_250, 41_700, 62_500, 125_000, 250_000, 500_000)

@Composable
private fun ConnectionCard(state: MeshUiState, onDisconnect: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val connName = (state.connection as? com.polymatic.meshify.mesh.BleState.Connected)?.name ?: "—"
            SettingsRow("Подключено к", connName)
            state.connectedAddress?.let { SettingsRow("MAC-адрес", it) }
            SettingsRow("Синхронизировано контактов", "${state.contacts.size}")
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.LinkOff, null)
                Spacer(Modifier.width(8.dp))
                Text("Отключиться")
            }
        }
    }
}

@Composable
private fun DebugCard(logSize: Int, onShow: () -> Unit, onClear: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow("Записей журнала", "$logSize")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShow, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.BugReport, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть журнал")
                }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Очистить")
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow("Приложение", "Meshify")
            SettingsRow("Версия", "1.0")
            SettingsRow("Протокол", "MeshCore BLE (NUS)")
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
