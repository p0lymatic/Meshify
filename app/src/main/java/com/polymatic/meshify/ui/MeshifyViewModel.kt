package com.polymatic.meshify.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polymatic.meshify.debug.BleDebugLog
import com.polymatic.meshify.debug.DebugEntry
import com.polymatic.meshify.mesh.*
import com.polymatic.meshify.storage.MeshStateStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ── Models ─────────────────────────────────────────────────────────────────

data class RecentMac(val address: String, val name: String, val timestamp: Long)

enum class AppThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Monet("Monet"),
}

data class MeshUiState(
    val connection: BleState = BleState.Idle,
    val devices: List<BleDevice> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val node: NodeInfo = NodeInfo(),
    val isSyncingContacts: Boolean = false,
    val debugLog: List<DebugEntry> = emptyList(),
    val recentMacs: List<RecentMac> = emptyList(),
    val connectedAddress: String? = null,
    val activeContact: Contact? = null,
    val messages: Map<String, List<Message>> = emptyMap(),
    val activeChannel: Channel? = null,
    val channels: List<Channel> = emptyList(),
    val channelMessages: Map<Int, List<ChannelMessage>> = emptyMap(),
    val contactUnread: Map<String, Int> = emptyMap(),
    val channelUnread: Map<Int, Int> = emptyMap(),
    val themeMode: AppThemeMode = AppThemeMode.System,
)

// ── ViewModel ──────────────────────────────────────────────────────────────

class MeshifyViewModel(application: Application) : AndroidViewModel(application) {
    private sealed interface PendingTransmission {
        data class Direct(val contactKey: String, val messageId: String) : PendingTransmission
        data class Channel(val channelIndex: Int, val messageId: String) : PendingTransmission
    }

    private val contacts = linkedMapOf<String, Contact>()
    private val messages = mutableMapOf<String, MutableList<Message>>()
    private val channels = mutableMapOf<Int, Channel>()
    private val channelMessages = mutableMapOf<Int, MutableList<ChannelMessage>>()
    private val pendingTransmissions = ArrayDeque<PendingTransmission>()
    private val directMessagesByAck = mutableMapOf<Long, Pair<String, String>>()
    private val pendingInboundByPrefix = mutableMapOf<String, MutableList<Message>>()
    private val retryJobs = mutableMapOf<String, Job>()
    private var contactsSyncTimeout: Job? = null
    private var queuedMessageTimeout: Job? = null
    private var queuedMessageSyncActive = false
    private var queuedMessageSyncRequested = false
    private var deviceScope: String? = null
    private var contactUnread = mutableMapOf<String, Int>()
    private var channelUnread = mutableMapOf<Int, Int>()
    private val client = BleMeshClient(application.applicationContext, ::handleFrame)
    private val prefs = application.getSharedPreferences("meshify", Context.MODE_PRIVATE)
    private val stateStore = MeshStateStore(application.applicationContext)
    private val _state = MutableStateFlow(MeshUiState())
    val state: StateFlow<MeshUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(recentMacs = loadRecentMacs(), themeMode = loadThemeMode()) }
        // Initialize channels 0-7, with public channel pre-configured
        channels[0] = Channel.fromHex(0, "Public", "8b3387e9c5cdea6ac9e5edbaa115cd72")
        for (i in 1..7) {
            channels[i] = Channel.empty(i)
        }
        publishChannels()
        viewModelScope.launch {
            client.state.collect { status ->
                _state.update { it.copy(connection = status) }
                if (status is BleState.Connected) synchronize()
            }
        }
        viewModelScope.launch { client.devices.collect { devices -> _state.update { it.copy(devices = devices) } } }
        viewModelScope.launch { BleDebugLog.entries.collect { entries -> _state.update { it.copy(debugLog = entries) } } }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun toggleScan() = client.toggleScan()

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(PREF_THEME_MODE, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun connect(device: BleDevice) {
        saveRecentMac(device.address, device.name)
        _state.update { it.copy(connectedAddress = device.address) }
        client.connect(device)
    }

    fun connectByAddress(address: String) {
        val mac = address.trim().uppercase()
        saveRecentMac(mac, "")
        _state.update { it.copy(connectedAddress = mac) }
        client.connectByAddress(mac)
    }

    fun disconnect() {
        cancelTransientWork()
        deviceScope = null
        contacts.clear()
        messages.clear()
        pendingInboundByPrefix.clear()
        channelMessages.clear()
        channels.clear()
        resetDefaultChannels()
        _state.update { it.copy(
            contacts = emptyList(),
            messages = emptyMap(),
            channelMessages = emptyMap(),
            activeContact = null,
            activeChannel = null,
            node = NodeInfo(),
            isSyncingContacts = false,
            connectedAddress = null,
            contactUnread = emptyMap(),
            channelUnread = emptyMap(),
        ) }
        client.disconnect()
    }

    fun openChat(contact: Contact) {
        markContactRead(contact.publicKey)
        _state.update { it.copy(activeContact = contact, activeChannel = null) }
    }

    fun closeChat() {
        _state.update { it.copy(activeContact = null) }
    }

    fun openChannel(channel: Channel) {
        markChannelRead(channel.index)
        _state.update { it.copy(activeChannel = channel, activeContact = null) }
    }

    fun closeChannel() {
        _state.update { it.copy(activeChannel = null) }
    }

    fun sendMessage(recipientKey: String, text: String) {
        if (text.encodeToByteArray().size > MeshProtocol.maxDirectMessageBytes) {
            BleDebugLog.add("Direct message rejected: exceeds ${MeshProtocol.maxDirectMessageBytes} UTF-8 bytes")
            return
        }
        val message = Message(
            messageId = "${System.currentTimeMillis()}_${recipientKey}_${text.hashCode()}",
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.Pending,
            senderKey = recipientKey,
            pathLength = contacts[recipientKey]?.hops,
            pathBytes = contacts[recipientKey]?.pathBytes ?: ByteArray(0),
            relayNames = contacts[recipientKey]?.let { resolveRelayNames(it.pathBytes, it.pathHashWidth) } ?: emptyList(),
        )
        messages.getOrPut(recipientKey) { mutableListOf() }.add(message)
        transmitDirect(recipientKey, message)
    }

    fun sendChannelMessage(channelIndex: Int, text: String) {
        if (text.encodeToByteArray().size > MeshProtocol.maxChannelMessageBytes) {
            BleDebugLog.add("Channel message rejected: exceeds ${MeshProtocol.maxChannelMessageBytes} UTF-8 bytes")
            return
        }
        val message = ChannelMessage(
            messageId = "${System.currentTimeMillis()}_ch${channelIndex}_${text.hashCode()}",
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.Pending,
            senderName = "You",
            channelIndex = channelIndex,
        )
        channelMessages.getOrPut(channelIndex) { mutableListOf() }.add(message)
        publishChannelMessages()
        pendingTransmissions.addLast(PendingTransmission.Channel(channelIndex, message.messageId))
        client.write(MeshProtocol.sendChannelMessage(channelIndex, text))
    }

    fun setChannel(channelIndex: Int, name: String, pskHex: String) {
        try {
            val channel = Channel.fromHex(channelIndex, name, pskHex)
            channels[channelIndex] = channel
            publishChannels()
            client.write(MeshProtocol.setChannel(channelIndex, name, channel.psk))
        } catch (e: Exception) {
            BleDebugLog.add("Failed to set channel: ${e.message}")
        }
    }

    fun retryMessage(contactKey: String, messageId: String) {
        val list = messages[contactKey] ?: return
        val index = list.indexOfFirst { it.messageId == messageId && it.isOutgoing && it.status == MessageStatus.Failed }
        if (index < 0 || _state.value.connection !is BleState.Connected) return
        val retry = list[index].copy(status = MessageStatus.Pending, retryCount = 0, ackHash = null, estimatedTimeoutMs = null, tripTimeMs = null, sentAt = null)
        list[index] = retry
        transmitDirect(contactKey, retry)
    }

    fun clearDebugLog() = BleDebugLog.clear()

    fun removeRecentMac(address: String) {
        val updated = _state.value.recentMacs.filter { it.address != address }
        persistRecentMacs(updated)
        _state.update { it.copy(recentMacs = updated) }
    }

    fun clearRecentMacs() {
        persistRecentMacs(emptyList())
        _state.update { it.copy(recentMacs = emptyList()) }
    }

    // ── Recent MACs persistence ────────────────────────────────────────────

    private fun saveRecentMac(address: String, name: String) {
        val current = _state.value.recentMacs.toMutableList()
        // Remove duplicate (if exists) so it moves to the top.
        current.removeAll { it.address.equals(address, ignoreCase = true) }
        // Add at the beginning (most recent first).
        current.add(0, RecentMac(address, name.ifBlank { address }, System.currentTimeMillis()))
        // Keep only the last 8.
        val trimmed = current.take(8)
        persistRecentMacs(trimmed)
        _state.update { it.copy(recentMacs = trimmed) }
    }

    private fun persistRecentMacs(macs: List<RecentMac>) {
        val array = JSONArray()
        macs.forEach { mac ->
            array.put(JSONObject().apply {
                put("address", mac.address)
                put("name", mac.name)
                put("ts", mac.timestamp)
            })
        }
        prefs.edit().putString("recent_macs", array.toString()).apply()
    }

    private fun loadRecentMacs(): List<RecentMac> {
        val json = prefs.getString("recent_macs", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                RecentMac(obj.getString("address"), obj.optString("name", ""), obj.optLong("ts", 0))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadThemeMode(): AppThemeMode = prefs.getString(PREF_THEME_MODE, null)
        ?.let { value -> AppThemeMode.entries.firstOrNull { it.name == value } }
        ?: AppThemeMode.System

    // ── Protocol ───────────────────────────────────────────────────────────

    private fun synchronize() {
        _state.update { it.copy(isSyncingContacts = true) }
        // Update the recent MAC name once we know the node name (after self-info arrives).
        client.write(MeshProtocol.appStart())
        client.write(MeshProtocol.deviceQuery())
        client.write(MeshProtocol.getBattery())
        client.write(MeshProtocol.getContacts())
        client.write(MeshProtocol.setTime(System.currentTimeMillis() / 1_000))
        for (index in 0..7) client.write(MeshProtocol.getChannel(index))
        contactsSyncTimeout?.cancel()
        contactsSyncTimeout = viewModelScope.launch {
            delay(CONTACT_SYNC_TIMEOUT_MS)
            if (_state.value.isSyncingContacts) {
                BleDebugLog.add("Contact sync timed out; keeping ${contacts.size} received contacts")
                _state.update { it.copy(isSyncingContacts = false) }
                startQueuedMessageSync()
            }
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        when (val event = MeshProtocol.parse(bytes)) {
            is MeshEvent.ContactsStarted -> {
                BleDebugLog.add("Contact sync started")
                contacts.clear()
                _state.update { it.copy(isSyncingContacts = true, contacts = emptyList()) }
            }
            is MeshEvent.ContactReceived -> {
                contacts[event.contact.publicKey] = event.contact
                pendingInboundByPrefix.remove(event.contact.publicKey.take(12))?.let { waiting ->
                    val list = messages.getOrPut(event.contact.publicKey) { mutableListOf() }
                    list.addAll(waiting.map { it.copy(senderKey = event.contact.publicKey) })
                    BleDebugLog.add("Attached ${waiting.size} incoming message(s) to ${event.contact.name}")
                    if (_state.value.activeContact?.publicKey != event.contact.publicKey) {
                        repeat(waiting.size) { incrementContactUnread(event.contact.publicKey) }
                    }
                    publishMessages()
                }
                _state.update {
                    it.copy(contacts = contacts.values.sortedWith(
                        compareByDescending<Contact> { c -> c.favorite }.thenBy { c -> c.name.lowercase() }
                    ))
                }
            }
            is MeshEvent.ContactsFinished -> {
                contactsSyncTimeout?.cancel()
                BleDebugLog.add("Contact sync finished: ${contacts.size} contacts")
                _state.update { it.copy(isSyncingContacts = false) }
                startQueuedMessageSync()
            }
            is MeshEvent.NodeUpdated -> {
                _state.update {
                    it.copy(node = it.node.copy(
                        name = event.node.name ?: it.node.name,
                        model = event.node.model ?: it.node.model,
                        firmware = event.node.firmware ?: it.node.firmware,
                        publicKey = event.node.publicKey ?: it.node.publicKey,
                    ))
                }
                event.node.publicKey?.let(::restoreDeviceState)
                // Update the recent MAC entry with the real node name.
                val addr = _state.value.connectedAddress
                val nodeName = event.node.name
                if (addr != null && !nodeName.isNullOrBlank()) {
                    val current = _state.value.recentMacs.toMutableList()
                    val idx = current.indexOfFirst { it.address.equals(addr, ignoreCase = true) }
                    if (idx >= 0) {
                        current[idx] = current[idx].copy(name = nodeName)
                        persistRecentMacs(current)
                        _state.update { it.copy(recentMacs = current) }
                    }
                }
            }
            is MeshEvent.BatteryUpdated -> _state.update { it.copy(node = it.node.copy(batteryMv = event.millivolts)) }
            is MeshEvent.MessageReceived -> {
                val fullKey = contacts.keys.firstOrNull { it.startsWith(event.message.senderKey, ignoreCase = true) }
                if (fullKey == null) {
                    pendingInboundByPrefix.getOrPut(event.message.senderKey) { mutableListOf() }.add(event.message)
                    BleDebugLog.add("Incoming message from unknown prefix ${event.message.senderKey}; refreshing contacts")
                    client.write(MeshProtocol.getContacts())
                } else {
                    messages.getOrPut(fullKey) { mutableListOf() }.add(
                        event.message.copy(senderKey = fullKey, relayNames = resolveRelayNames(event.message.pathBytes, 1))
                    )
                    if (_state.value.activeContact?.publicKey != fullKey) incrementContactUnread(fullKey)
                    publishMessages()
                }
                requestNextQueuedMessage()
            }
            is MeshEvent.MessageStatusUpdated -> {
                messages.values.flatten().find { it.messageId == event.messageId }?.let { old ->
                    val key = old.senderKey
                    messages[key]?.let { list ->
                        val idx = list.indexOfFirst { it.messageId == event.messageId }
                        if (idx >= 0) {
                            list[idx] = old.copy(status = event.status)
                            publishMessages()
                        }
                    }
                }
            }
            is MeshEvent.ChannelMessageReceived -> {
                val isNew = mergeChannelMessage(event.message)
                if (isNew && _state.value.activeChannel?.index != event.message.channelIndex && !event.message.isOutgoing) incrementChannelUnread(event.message.channelIndex)
                publishChannelMessages()
                requestNextQueuedMessage()
            }
            is MeshEvent.MessageSent -> handleMessageSent(event)
            is MeshEvent.MessageConfirmed -> handleMessageConfirmed(event)
            is MeshEvent.QueuedMessagesFinished -> finishQueuedMessageSync()
            is MeshEvent.MessagesWaiting -> startQueuedMessageSync()
            is MeshEvent.ChannelUpdated -> {
                channels[event.channel.index] = event.channel
                publishChannels()
            }
            null -> Unit
        }
    }

    private fun restoreDeviceState(publicKey: String) {
        val scope = publicKey.take(10)
        if (scope.length < 10 || deviceScope == scope) return
        deviceScope = scope
        val storedChannels = stateStore.loadChannels(scope)
        if (storedChannels.isNotEmpty()) {
            channels.clear()
            storedChannels.forEach { channels[it.index] = it }
            for (index in 0..7) channels.putIfAbsent(index, Channel.empty(index))
            publishChannels()
        }
        messages.clear()
        stateStore.loadMessages(scope).forEach { (key, saved) ->
            messages[key] = saved.map { message ->
                if (message.isOutgoing && message.status in setOf(MessageStatus.Pending, MessageStatus.Sent)) message.copy(status = MessageStatus.Failed)
                else message
            }.toMutableList()
        }
        channelMessages.clear()
        stateStore.loadChannelMessages(scope).forEach { (index, saved) -> channelMessages[index] = saved.toMutableList() }
        val unread = stateStore.loadUnread(scope)
        contactUnread = unread.contacts.toMutableMap()
        channelUnread = unread.channels.toMutableMap()
        publishMessages()
        publishChannelMessages()
        publishUnread()
        persistAll()
        BleDebugLog.add("Restored MeshCore state for ${scope.take(6)}: ${messages.size} chats, ${channelMessages.size} channels")
    }

    private fun startQueuedMessageSync() {
        if (_state.value.connection !is BleState.Connected) return
        if (_state.value.isSyncingContacts) {
            queuedMessageSyncRequested = true
            return
        }
        if (queuedMessageSyncActive) return
        queuedMessageSyncRequested = false
        queuedMessageSyncActive = true
        BleDebugLog.add("Starting queued-message sync")
        requestNextQueuedMessage()
    }

    private fun requestNextQueuedMessage() {
        if (!queuedMessageSyncActive || _state.value.connection !is BleState.Connected) return
        queuedMessageTimeout?.cancel()
        client.write(MeshProtocol.syncNextMessage())
        queuedMessageTimeout = viewModelScope.launch {
            delay(QUEUE_SYNC_TIMEOUT_MS)
            if (queuedMessageSyncActive) {
                BleDebugLog.add("Queued-message sync timed out")
                finishQueuedMessageSync()
            }
        }
    }

    private fun finishQueuedMessageSync() {
        queuedMessageTimeout?.cancel()
        if (queuedMessageSyncActive) BleDebugLog.add("Queued-message sync finished")
        queuedMessageSyncActive = false
        if (queuedMessageSyncRequested) startQueuedMessageSync()
    }

    private fun transmitDirect(contactKey: String, message: Message) {
        messages.getOrPut(contactKey) { mutableListOf() }.let { list ->
            val index = list.indexOfFirst { it.messageId == message.messageId }
            if (index >= 0) list[index] = message
        }
        publishMessages()
        pendingTransmissions.addLast(PendingTransmission.Direct(contactKey, message.messageId))
        client.write(
            MeshProtocol.sendTextMessage(
                recipientKey = contactKey,
                text = message.text,
                attempt = message.retryCount,
                timestampSeconds = message.timestamp / 1_000,
            )
        )
    }

    private fun handleMessageSent(event: MeshEvent.MessageSent) {
        when (val pending = pendingTransmissions.removeFirstOrNull()) {
            is PendingTransmission.Direct -> {
                val list = messages[pending.contactKey] ?: return
                val index = list.indexOfFirst { it.messageId == pending.messageId }
                if (index < 0) return
                list[index] = list[index].copy(
                    status = MessageStatus.Sent,
                    ackHash = event.ackHash,
                    estimatedTimeoutMs = event.estimatedTimeoutMs,
                    sentAt = System.currentTimeMillis(),
                )
                BleDebugLog.add("Message accepted: flood=${event.isFlood} ack=${event.ackHash.toString(16)}")
                directMessagesByAck[event.ackHash] = pending.contactKey to pending.messageId
                publishMessages()
                scheduleAckTimeout(pending.contactKey, pending.messageId, event.estimatedTimeoutMs)
            }
            is PendingTransmission.Channel -> {
                val list = channelMessages[pending.channelIndex] ?: return
                val index = list.indexOfFirst { it.messageId == pending.messageId }
                if (index >= 0) {
                    list[index] = list[index].copy(status = MessageStatus.Sent)
                    publishChannelMessages()
                }
            }
            null -> Unit
        }
    }

    private fun handleMessageConfirmed(event: MeshEvent.MessageConfirmed) {
        val (contactKey, messageId) = directMessagesByAck.remove(event.ackHash) ?: return
        retryJobs.remove(messageId)?.cancel()
        val list = messages[contactKey] ?: return
        val index = list.indexOfFirst { it.messageId == messageId }
        if (index >= 0) {
            list[index] = list[index].copy(status = MessageStatus.Delivered, tripTimeMs = event.tripTimeMs)
            publishMessages()
        }
    }

    private fun scheduleAckTimeout(contactKey: String, messageId: String, timeoutMs: Long) {
        retryJobs.remove(messageId)?.cancel()
        retryJobs[messageId] = viewModelScope.launch {
            delay(timeoutMs.coerceIn(MIN_ACK_TIMEOUT_MS, MAX_ACK_TIMEOUT_MS))
            val list = messages[contactKey] ?: return@launch
            val index = list.indexOfFirst { it.messageId == messageId }
            if (index < 0 || list[index].status != MessageStatus.Sent) return@launch
            val message = list[index]
            if (message.retryCount >= MAX_DIRECT_RETRIES - 1 || _state.value.connection !is BleState.Connected) {
                list[index] = message.copy(status = MessageStatus.Failed)
                publishMessages()
                BleDebugLog.add("Delivery failed after ${message.retryCount + 1} attempts: ${messageId.take(12)}")
                return@launch
            }
            val retryDelay = RETRY_BACKOFF_MS[message.retryCount]
            list[index] = message.copy(status = MessageStatus.Pending, retryCount = message.retryCount + 1, ackHash = null, estimatedTimeoutMs = null)
            publishMessages()
            BleDebugLog.add("Retry ${message.retryCount + 1}/$MAX_DIRECT_RETRIES in ${retryDelay}ms: ${messageId.take(12)}")
            delay(retryDelay)
            val latest = messages[contactKey]?.firstOrNull { it.messageId == messageId } ?: return@launch
            if (latest.status == MessageStatus.Pending && _state.value.connection is BleState.Connected) transmitDirect(contactKey, latest)
        }
    }

    private fun mergeChannelMessage(incoming: ChannelMessage): Boolean {
        val enriched = incoming.copy(relayNames = resolveRelayNames(incoming.pathBytes, incoming.pathHashWidth ?: 1))
        val list = channelMessages.getOrPut(enriched.channelIndex) { mutableListOf() }
        val selfName = _state.value.node.name
        val incomingIsSelf = !selfName.isNullOrBlank() && enriched.senderName.equals(selfName, ignoreCase = true)
        val existingIndex = list.indexOfLast { existing ->
            existing.text == enriched.text &&
                kotlin.math.abs(existing.timestamp - enriched.timestamp) < 5_000 &&
                (existing.senderName.equals(enriched.senderName, ignoreCase = true) || (existing.isOutgoing && incomingIsSelf))
        }
        if (existingIndex < 0) {
            list.add(enriched)
            return true
        }
        val existing = list[existingIndex]
        val variants = buildList<ByteArray> {
            existing.pathVariants.forEach { old -> if (none { it.contentEquals(old) }) add(old) }
            if (existing.pathBytes.isNotEmpty() && none { it.contentEquals(existing.pathBytes) }) add(existing.pathBytes)
            if (enriched.pathBytes.isNotEmpty() && none { it.contentEquals(enriched.pathBytes) }) add(enriched.pathBytes)
        }
        list[existingIndex] = existing.copy(
            status = if (existing.status == MessageStatus.Pending) MessageStatus.Sent else existing.status,
            repeatCount = existing.repeatCount + 1,
            pathLength = sequenceOf(existing.pathLength, enriched.pathLength).filterNotNull().maxOrNull(),
            pathHashWidth = existing.pathHashWidth ?: enriched.pathHashWidth,
            pathBytes = if (enriched.pathBytes.size > existing.pathBytes.size) enriched.pathBytes else existing.pathBytes,
            pathVariants = variants,
            snr = enriched.snr ?: existing.snr,
            relayNames = (existing.relayNames + enriched.relayNames).distinct(),
        )
        return false
    }

    private fun resolveRelayNames(pathBytes: ByteArray, hashWidth: Int): List<String> {
        if (pathBytes.isEmpty() || hashWidth !in 1..4) return emptyList()
        return pathBytes.asList().chunked(hashWidth).map { hash ->
            val prefix = hash.joinToString("") { "%02X".format(it) }
            contacts.values.filter { it.type == ContactType.Repeater }
                .singleOrNull { it.publicKey.startsWith(prefix, ignoreCase = true) }
                ?.name ?: "Relay $prefix"
        }
    }

    private fun publishMessages() {
        _state.update { it.copy(messages = messages.mapValues { (_, list) -> list.toList() }) }
        deviceScope?.let { scope -> messages.forEach { (key, list) -> stateStore.saveMessages(scope, key, list) } }
    }

    private fun publishChannelMessages() {
        _state.update { it.copy(channelMessages = channelMessages.mapValues { (_, list) -> list.toList() }) }
        deviceScope?.let { scope -> channelMessages.forEach { (index, list) -> stateStore.saveChannelMessages(scope, index, list) } }
    }

    private fun publishChannels() {
        _state.update { it.copy(channels = channels.values.sortedBy { it.index }) }
        deviceScope?.let { stateStore.saveChannels(it, channels.values) }
    }

    private fun incrementContactUnread(contactKey: String) {
        contactUnread[contactKey] = (contactUnread[contactKey] ?: 0) + 1
        publishUnread()
    }

    private fun incrementChannelUnread(index: Int) {
        channelUnread[index] = (channelUnread[index] ?: 0) + 1
        publishUnread()
    }

    private fun markContactRead(contactKey: String) {
        if (contactUnread.remove(contactKey) != null) publishUnread()
    }

    private fun markChannelRead(index: Int) {
        if (channelUnread.remove(index) != null) publishUnread()
    }

    private fun publishUnread() {
        _state.update { it.copy(contactUnread = contactUnread.toMap(), channelUnread = channelUnread.toMap()) }
        deviceScope?.let { stateStore.saveUnread(it, MeshStateStore.UnreadState(contactUnread, channelUnread)) }
    }

    private fun persistAll() {
        publishChannels()
        publishMessages()
        publishChannelMessages()
        publishUnread()
    }

    private fun cancelTransientWork() {
        contactsSyncTimeout?.cancel()
        contactsSyncTimeout = null
        queuedMessageTimeout?.cancel()
        queuedMessageTimeout = null
        queuedMessageSyncActive = false
        queuedMessageSyncRequested = false
        retryJobs.values.forEach(Job::cancel)
        retryJobs.clear()
        pendingTransmissions.clear()
        directMessagesByAck.clear()
    }

    private fun resetDefaultChannels() {
        channels[0] = Channel.fromHex(0, "Public", "8b3387e9c5cdea6ac9e5edbaa115cd72")
        for (index in 1..7) channels[index] = Channel.empty(index)
        publishChannels()
    }

    override fun onCleared() {
        cancelTransientWork()
        client.disconnect()
    }

    private companion object {
        const val PREF_THEME_MODE = "theme_mode"
        const val CONTACT_SYNC_TIMEOUT_MS = 20_000L
        const val QUEUE_SYNC_TIMEOUT_MS = 5_000L
        const val MAX_DIRECT_RETRIES = 3
        const val MIN_ACK_TIMEOUT_MS = 2_000L
        const val MAX_ACK_TIMEOUT_MS = 60_000L
        val RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }
}
