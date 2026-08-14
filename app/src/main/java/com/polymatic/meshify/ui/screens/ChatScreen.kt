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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
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
import com.polymatic.meshify.mesh.Contact
import com.polymatic.meshify.mesh.Message
import com.polymatic.meshify.mesh.MessageStatus
import com.polymatic.meshify.ui.uiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contact: Contact,
    messages: List<Message>,
    onSendMessage: (String) -> Unit,
    onRetryMessage: (String) -> Unit,
    onBack: () -> Unit,
) {
    var inputText by rememberSaveable(contact.publicKey) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler {
        if (imeVisible) keyboardController?.hide() else onBack()
    }

    LaunchedEffect(messages.size) {
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
                        Text(contact.name, fontWeight = FontWeight.Bold)
                        Text(
                            contactRouteLabel(contact.hops),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyMessageState(contact)
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(messages.asReversed(), key = { it.messageId }) { message ->
                            MessageBubble(message, onRetry = { onRetryMessage(message.messageId) })
                        }
                    }
                }
            }
            MessageComposer(
                modifier = Modifier.imePadding().navigationBarsPadding(),
                value = inputText,
                placeholder = uiText("Сообщение для ${contact.name}", "Message ${contact.name}"),
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

private fun contactRouteLabel(hops: Int) = when {
    hops < 0 -> "Flood-маршрут"
    hops == 0 -> "Прямой маршрут"
    else -> "Реле: $hops"
}

@Composable
private fun EmptyMessageState(contact: Contact) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        Spacer(Modifier.height(12.dp))
        Text(uiText("Сообщений пока нет", "No messages yet"), style = MaterialTheme.typography.titleMedium)
        Text(uiText("Начните диалог с ${contact.name}", "Start a conversation with ${contact.name}"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MessageBubble(message: Message, onRetry: () -> Unit) {
    var expanded by remember(message.messageId) { mutableStateOf(false) }
    val outgoing = message.isOutgoing
    val background = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(
            color = background,
            tonalElevation = if (outgoing) 2.dp else 1.dp,
            shape = messageBubbleShape(outgoing),
            modifier = Modifier.widthIn(min = 76.dp, max = 328.dp)
                .clickable { expanded = !expanded },
        ) {
            Column(Modifier.padding(start = 13.dp, end = 11.dp, top = 9.dp, bottom = 7.dp)) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = foreground)
                Spacer(Modifier.height(5.dp))
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.pathLength?.let {
                        Icon(Icons.Rounded.Route, null, Modifier.size(13.dp), tint = foreground.copy(alpha = .62f))
                        Text(routeCountLabel(it), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .68f))
                    }
                    Text(formatTimestamp(message.timestamp), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .68f))
                    if (outgoing) {
                        MessageStatusIcon(message.status, foreground.copy(alpha = .75f))
                        if (message.status == MessageStatus.Failed) {
                            IconButton(onClick = onRetry, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Refresh, "Повторить", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = tween(190), expandFrom = Alignment.Top) + fadeIn(tween(140)),
                    exit = shrinkVertically(animationSpec = tween(150), shrinkTowards = Alignment.Top) + fadeOut(tween(100)),
                ) {
                    RouteDetails(
                        pathLength = message.pathLength,
                        pathBytes = message.pathBytes,
                        hashWidth = null,
                        repeats = 0,
                        snr = message.snr,
                        tripTimeMs = message.tripTimeMs,
                        estimatedTimeoutMs = message.estimatedTimeoutMs,
                        ackHash = message.ackHash,
                        status = message.status,
                        timestamp = message.timestamp,
                        direction = if (outgoing) "Исходящее" else "Входящее",
                        messageKind = "Личное сообщение",
                        relayNames = message.relayNames,
                        tint = foreground,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MessageComposer(
    modifier: Modifier = Modifier,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(26.dp),
                maxLines = 4,
            )
            Spacer(Modifier.width(7.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Отправить", Modifier.size(24.dp))
            }
        }
    }
}

@Composable
internal fun RouteDetails(
    pathLength: Int?,
    pathBytes: ByteArray,
    hashWidth: Int?,
    repeats: Int,
    snr: Float?,
    tripTimeMs: Long?,
    estimatedTimeoutMs: Long? = null,
    ackHash: Long? = null,
    status: MessageStatus? = null,
    timestamp: Long? = null,
    direction: String? = null,
    messageKind: String? = null,
    relayNames: List<String>,
    tint: Color,
) {
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 7.dp), color = tint.copy(alpha = .16f))
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(uiText("Информация о сообщении", "Message information"), style = MaterialTheme.typography.labelLarge, color = tint.copy(alpha = .9f))
        messageKind?.let { MessageInfoRow(uiText("Тип", "Type"), it, tint) }
        direction?.let { MessageInfoRow(uiText("Направление", "Direction"), it, tint) }
        MessageInfoRow(
            uiText("Маршрут", "Route"),
            when {
                pathLength == null -> uiText("нет данных", "unavailable")
                pathLength < 0 -> uiText("flood-маршрут", "flood route")
                pathLength == 0 -> uiText("прямое радио-соединение", "direct radio path")
                else -> uiText("$pathLength ${russianHopLabel(pathLength)}", "$pathLength relay hops")
            },
            tint,
        )
        if (pathLength != null && pathLength > 0) {
            val routeBytes = pathBytes.size
            val hashSize = hashWidth ?: if (routeBytes > 0) routeBytes / pathLength else 0
            MessageInfoRow(
                uiText("Данные маршрута", "Route data"),
                if (hashSize > 0) uiText("$routeBytes байт, хеш реле $hashSize байт", "$routeBytes bytes, $hashSize-byte relay hash") else uiText("$routeBytes байт", "$routeBytes bytes"),
                tint,
            )
        }
        if (repeats > 0) MessageInfoRow(uiText("Повторные приёмы", "Repeated receptions"), uiText("${repeats + 1} путей", "${repeats + 1} paths"), tint)
        relayNames.filterNot(::isRawRelayIdentifier).takeIf { it.isNotEmpty() }?.let {
            MessageInfoRow(uiText("Реле (${it.size})", "Relays (${it.size})"), it.joinToString("  ->  "), tint)
        }
        if (pathLength != null && pathLength > 0 && relayNames.filterNot(::isRawRelayIdentifier).isEmpty()) {
            MessageInfoRow(uiText("Реле", "Relays"), uiText("не удалось сопоставить с контактами", "could not match contacts"), tint)
        }
        snr?.let { MessageInfoRow(uiText("Качество сигнала", "Signal quality"), "${"%.1f".format(Locale.US, it)} dB SNR", tint) }
        tripTimeMs?.let { MessageInfoRow(uiText("Подтверждение", "Acknowledgement"), uiText("получено за $it мс", "received in $it ms"), tint) }
        estimatedTimeoutMs?.takeIf { tripTimeMs == null }?.let { MessageInfoRow(uiText("Подтверждение", "Acknowledgement"), uiText("ожидание до ${it / 1_000} с", "waiting up to ${it / 1_000}s"), tint) }
        status?.let { MessageInfoRow(uiText("Статус", "Status"), messageStatusLabel(it), tint) }
        timestamp?.let { MessageInfoRow(uiText("Время", "Time"), SimpleDateFormat("d MMMM, HH:mm:ss", if (uiText("ru", "en") == "ru") Locale("ru") else Locale.US).format(Date(it)), tint) }
        ackHash?.let { MessageInfoRow(uiText("ID пакета", "Packet ID"), it.toString(16).uppercase(Locale.US), tint) }
    }
}

@Composable
private fun MessageInfoRow(label: String, value: String, tint: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .64f))
        Spacer(Modifier.width(16.dp))
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .82f))
    }
}

private fun russianHopLabel(@Suppress("UNUSED_PARAMETER") count: Int): String = "реле"

@Composable
internal fun messageStatusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.Pending -> uiText("отправляется", "sending")
    MessageStatus.Sent -> uiText("отправлено в сеть", "sent to network")
    MessageStatus.Delivered -> uiText("доставлено", "delivered")
    MessageStatus.Failed -> uiText("не доставлено", "not delivered")
}

private fun isRawRelayIdentifier(value: String): Boolean =
    value.startsWith("Relay ") || value.matches(Regex("[0-9A-F]{2,}", RegexOption.IGNORE_CASE))

private fun messageBubbleShape(outgoing: Boolean) = RoundedCornerShape(
    topStart = if (outgoing) 24.dp else 12.dp,
    topEnd = if (outgoing) 12.dp else 24.dp,
    bottomStart = if (outgoing) 24.dp else 4.dp,
    bottomEnd = if (outgoing) 4.dp else 24.dp,
)

@Composable
private fun MessageStatusIcon(status: MessageStatus, tint: Color) {
    val (icon, description) = when (status) {
        MessageStatus.Pending -> Icons.Rounded.Schedule to "Отправляется"
        MessageStatus.Sent -> Icons.Rounded.Done to "Отправлено"
        MessageStatus.Delivered -> Icons.Rounded.DoneAll to "Доставлено"
        MessageStatus.Failed -> Icons.Rounded.ErrorOutline to "Не доставлено"
    }
    Icon(icon, description, Modifier.size(14.dp), tint = if (status == MessageStatus.Failed) MaterialTheme.colorScheme.error else tint)
}

internal fun routeCountLabel(pathLength: Int): String = when {
    pathLength < 0 -> "flood"
    pathLength == 0 -> "напрямую"
    else -> "$pathLength реле"
}

internal fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff in 0..<60_000 -> "Сейчас"
        diff in 60_000..<3_600_000 -> "${diff / 60_000} мин"
        diff in 0..<86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
