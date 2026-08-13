package com.polymatic.meshify.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
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
                placeholder = "Message ${contact.name}",
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
    hops < 0 -> "Flood route"
    hops == 0 -> "Direct route"
    else -> "$hops relay hops"
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
        Text("No messages yet", style = MaterialTheme.typography.titleMedium)
        Text("Start a conversation with ${contact.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            tonalElevation = if (outgoing) 1.dp else 0.dp,
            shape = messageBubbleShape(outgoing),
            modifier = Modifier.widthIn(min = 76.dp, max = 328.dp).clickable { expanded = !expanded },
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
                                Icon(Icons.Rounded.Refresh, "Retry", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                AnimatedVisibility(expanded) {
                    RouteDetails(
                        pathLength = message.pathLength,
                        pathBytes = message.pathBytes,
                        hashWidth = null,
                        repeats = 0,
                        snr = message.snr,
                        tripTimeMs = message.tripTimeMs,
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
    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
            )
            Spacer(Modifier.width(7.dp))
            FilledIconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
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
    relayNames: List<String>,
    tint: Color,
) {
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 7.dp), color = tint.copy(alpha = .16f))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            when {
                pathLength == null -> "Route information unavailable"
                pathLength < 0 -> "Flood route"
                pathLength == 0 -> "Direct radio path"
                else -> "$pathLength relay ${if (pathLength == 1) "hop" else "hops"}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = tint.copy(alpha = .86f),
        )
        if (repeats > 0) Text("Heard again by $repeats ${if (repeats == 1) "relay/path" else "relays/paths"}", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .72f))
        pathLabels(pathBytes, hashWidth).takeIf { it.isNotEmpty() }?.let {
            Text("Path: ${it.joinToString("  ->  ")}", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .72f))
        }
        if (relayNames.isNotEmpty()) Text("Relays: ${relayNames.joinToString("  ->  ")}", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .72f))
        snr?.let { Text("SNR ${"%.1f".format(Locale.US, it)} dB", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .72f)) }
        tripTimeMs?.let { Text("ACK in $it ms", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = .72f)) }
    }
}

private fun pathLabels(bytes: ByteArray, width: Int?): List<String> {
    if (bytes.isEmpty()) return emptyList()
    val chunkSize = width?.takeIf { it in 1..4 } ?: 1
    return bytes.asList().chunked(chunkSize).map { chunk -> chunk.joinToString("") { "%02X".format(it) } }
}

private fun messageBubbleShape(outgoing: Boolean) = RoundedCornerShape(
    topStart = 17.dp,
    topEnd = 17.dp,
    bottomStart = if (outgoing) 17.dp else 3.dp,
    bottomEnd = if (outgoing) 3.dp else 17.dp,
)

@Composable
private fun MessageStatusIcon(status: MessageStatus, tint: Color) {
    val (icon, description) = when (status) {
        MessageStatus.Pending -> Icons.Rounded.Schedule to "Pending"
        MessageStatus.Sent -> Icons.Rounded.Done to "Sent"
        MessageStatus.Delivered -> Icons.Rounded.DoneAll to "Delivered"
        MessageStatus.Failed -> Icons.Rounded.ErrorOutline to "Failed"
    }
    Icon(icon, description, Modifier.size(14.dp), tint = if (status == MessageStatus.Failed) MaterialTheme.colorScheme.error else tint)
}

internal fun routeCountLabel(pathLength: Int): String = when {
    pathLength < 0 -> "flood"
    pathLength == 0 -> "direct"
    else -> "${pathLength}h"
}

internal fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff in 0..<60_000 -> "Now"
        diff in 60_000..<3_600_000 -> "${diff / 60_000} min"
        diff in 0..<86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
