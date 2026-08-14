package com.polymatic.meshify.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polymatic.meshify.mesh.Channel
import com.polymatic.meshify.mesh.ChannelMessage
import com.polymatic.meshify.mesh.MessageStatus
import com.polymatic.meshify.mesh.MeshProtocol
import com.polymatic.meshify.mesh.TextCompressionMode
import com.polymatic.meshify.ui.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelChatScreen(
    channel: Channel,
    messages: List<ChannelMessage>,
    onSendMessage: (String) -> Unit,
    onBack: () -> Unit,
    compressionMode: TextCompressionMode = TextCompressionMode.Off,
) {
    var inputText by rememberSaveable(channel.index) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler {
        if (imeVisible) keyboardController?.hide() else onBack()
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.repeatCount) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("#${channel.name}", fontWeight = FontWeight.Bold)
                        Text("Канал ${channel.index}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyChannelState(channel)
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(messages.asReversed(), key = { it.messageId }) { message -> ChannelMessageBubble(message) }
                    }
                }
            }
            MessageComposer(
                modifier = Modifier.imePadding().navigationBarsPadding(),
                value = inputText,
                placeholder = "Сообщение в #${channel.name}",
                maxBytes = MeshProtocol.maxChannelMessageBytes,
                compressionMode = compressionMode,
                onValueChange = { inputText = it },
                onSend = {
                    val text = inputText.trim()
                    if (text.isNotEmpty()) {
                        onSendMessage(text)
                        inputText = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyChannelState(channel: Channel) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Icon(Icons.Rounded.Tag, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
        Spacer(Modifier.height(12.dp))
        Text("В #${channel.name} пока нет сообщений", style = MaterialTheme.typography.titleMedium)
        Text("Новые сообщения появятся здесь", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ChannelMessageBubble(message: ChannelMessage) {
    var expanded by remember(message.messageId) { mutableStateOf(false) }
    val outgoing = message.isOutgoing
    val background = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(
            color = background,
            tonalElevation = if (outgoing) 2.dp else 1.dp,
            shape = RoundedCornerShape(
                topStart = if (outgoing) 24.dp else 12.dp,
                topEnd = if (outgoing) 12.dp else 24.dp,
                bottomStart = if (outgoing) 24.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 24.dp,
            ),
            modifier = Modifier.widthIn(min = 88.dp, max = 328.dp)
                .clickable { expanded = !expanded },
        ) {
            Column(Modifier.padding(start = 13.dp, end = 11.dp, top = 8.dp, bottom = 7.dp)) {
                if (!outgoing && message.senderName.isNotBlank()) {
                    Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = foreground)
                Spacer(Modifier.height(5.dp))
                RoutePathSummary(
                    pathLength = message.pathLength,
                    pathBytes = message.pathBytes,
                    hashWidth = message.pathHashWidth,
                    tint = foreground,
                    modifier = Modifier.align(Alignment.End),
                )
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.repeatCount > 0) {
                        Icon(Icons.Rounded.CellTower, null, Modifier.size(13.dp), tint = foreground.copy(alpha = .62f))
                        Text(
                            heardRelaysLabel(message.repeatCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = foreground.copy(alpha = .68f),
                        )
                    }
                    Text(formatTimestamp(message.timestamp), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .68f))
                    if (outgoing) ChannelStatusIcon(message.status, foreground)
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = tween(190), expandFrom = Alignment.Top) + fadeIn(tween(140)),
                    exit = shrinkVertically(animationSpec = tween(150), shrinkTowards = Alignment.Top) + fadeOut(tween(100)),
                ) {
                    RouteDetails(
                        pathLength = message.pathLength,
                        pathBytes = message.pathBytes,
                        hashWidth = message.pathHashWidth,
                        repeats = message.repeatCount,
                        snr = message.snr,
                        tripTimeMs = null,
                        status = message.status,
                        timestamp = message.timestamp,
                        direction = if (outgoing) "Исходящее" else "Входящее",
                        messageKind = "Сообщение канала #${message.channelIndex}",
                        relayNames = message.relayNames,
                        tint = foreground,
                    )
                }
            }
        }
    }
}

@Composable
private fun heardRelaysLabel(count: Int): String = uiText(
    "Услышано реле: $count",
    if (count == 1) "Heard 1 relay" else "Heard $count relays",
)

@Composable
private fun ChannelStatusIcon(status: MessageStatus, tint: Color) {
    val (icon, description) = when (status) {
        MessageStatus.Pending -> Icons.Rounded.Schedule to "Отправляется"
        MessageStatus.Sent -> Icons.Rounded.Done to "Отправлено"
        MessageStatus.Delivered -> Icons.Rounded.DoneAll to "Доставлено"
        MessageStatus.Failed -> Icons.Rounded.ErrorOutline to "Не доставлено"
    }
    Icon(icon, description, Modifier.size(14.dp), tint = if (status == MessageStatus.Failed) MaterialTheme.colorScheme.error else tint.copy(alpha = .75f))
}
