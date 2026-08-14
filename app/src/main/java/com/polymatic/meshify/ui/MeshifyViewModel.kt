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

data class AppThemeSettings(
    val useMonet: Boolean = false,
    val darkMode: Boolean = false,
    val languageTag: String = "ru",
)

data class ClientSpecificSettings(
    val textCompression: TextCompressionMode = TextCompressionMode.Off,
)

enum class ContactSort { RecentMessages, LastSeen, Name }
enum class ChatSort { RecentMessages, Unread, Name }
enum class ChannelSort { Custom, RecentMessages, Unread, Name, Slot }

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
    val theme: AppThemeSettings = AppThemeSettings(),
    val contactSort: ContactSort = ContactSort.LastSeen,
    val chatSort: ChatSort = ChatSort.RecentMessages,
    val channelSort: ChannelSort = ChannelSort.RecentMessages,
    val clientSpecific: ClientSpecificSettings = ClientSpecificSettings(),
)

// ── ViewModel ──────────────────────────────────────────────────────────────

class MeshifyViewModel(application: Application) : AndroidViewModel(application) {
    private data class DirectTransmission(
        val contactKey: String,
        val messageId: String,
        val message: Message,
    )
    private data class ChannelTransmission(val channelIndex: Int, val messageId: String)

    private val contacts = linkedMapOf<String, Contact>()
    private val messages = mutableMapOf<String, MutableList<Message>>()
    private val channels = mutableMapOf<Int, Channel>()
    private val channelMessages = mutableMapOf<Int, MutableList<ChannelMessage>>()
    private val channelOrder = (0..7).toMutableList()
    // Direct sends are identified by the firmware's deterministic ACK hash. Channel sends
    // have no delivery ACK and therefore keep their own acceptance queue.
    private val directMessagesByExpectedAck = mutableMapOf<Long, ArrayDeque<DirectTransmission>>()
    // Kept until delivery so PUSH_CODE_SEND_CONFIRMED can still resolve a message if it
    // reaches Android before the corresponding RESP_CODE_SENT notification.
    private val directMessagesByKnownAck = mutableMapOf<Long, ArrayDeque<DirectTransmission>>()
    private val pendingDirectAwaitingIdentity = ArrayDeque<DirectTransmission>()
    private val directSendQueues = mutableMapOf<String, ArrayDeque<DirectTransmission>>()
    private val activeDirectMessages = mutableMapOf<String, String>()
    private val pendingChannelTransmissions = ArrayDeque<ChannelTransmission>()
    private val directMessagesByAck = mutableMapOf<Long, ArrayDeque<DirectTransmission>>()
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
        _state.update { it.copy(
            recentMacs = loadRecentMacs(),
            theme = loadThemeSettings(),
            contactSort = loadContactSort(),
            chatSort = loadChatSort(),
            channelSort = loadChannelSort(),
            clientSpecific = loadClientSpecificSettings(),
        ) }
        channelOrder.clear()
        channelOrder.addAll(loadChannelOrder())
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

    fun setMonetEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_USE_MONET, enabled).apply()
        _state.update { it.copy(theme = it.theme.copy(useMonet = enabled)) }
    }

    fun setDarkModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_DARK_MODE, enabled).apply()
        _state.update { it.copy(theme = it.theme.copy(darkMode = enabled)) }
    }

    fun setLanguage(languageTag: String) {
        val normalized = languageTag.takeIf { it in supportedLanguageTags } ?: DEFAULT_LANGUAGE_TAG
        prefs.edit().putString(PREF_LANGUAGE, normalized).apply()
        _state.update { it.copy(theme = it.theme.copy(languageTag = normalized)) }
    }

    fun setContactSort(sort: ContactSort) {
        prefs.edit().putString(PREF_CONTACT_SORT, sort.name).apply()
        publishContacts()
    }

    fun setChatSort(sort: ChatSort) {
        prefs.edit().putString(PREF_CHAT_SORT, sort.name).apply()
        _state.update { it.copy(chatSort = sort) }
    }

    fun setChannelSort(sort: ChannelSort) {
        prefs.edit().putString(PREF_CHANNEL_SORT, sort.name).apply()
        publishChannels(sort)
    }

    fun setTextCompression(mode: TextCompressionMode) {
        prefs.edit().putString(PREF_TEXT_COMPRESSION, mode.name).apply()
        _state.update { it.copy(clientSpecific = it.clientSpecific.copy(textCompression = mode)) }
    }

    fun setNodeName(name: String) {
        val normalized = name.trim()
        if (_state.value.connection !is BleState.Connected) {
            BleDebugLog.add("Node name was not changed: node is disconnected")
            return
        }
        if (normalized.isEmpty()) {
            BleDebugLog.add("Node name was not changed: name is empty")
            return
        }
        if (normalized.encodeToByteArray().size > MeshProtocol.maxNodeNameBytes) {
            BleDebugLog.add("Node name was not changed: exceeds ${MeshProtocol.maxNodeNameBytes} UTF-8 bytes")
            return
        }
        client.write(MeshProtocol.setNodeName(normalized))
        _state.update { it.copy(node = it.node.copy(name = normalized)) }
        BleDebugLog.add("Requested node rename to '$normalized'")
    }

    fun sendSelfAdvert(flood: Boolean) {
        if (_state.value.connection !is BleState.Connected) {
            BleDebugLog.add("Self advert was not sent: node is disconnected")
            return
        }
        client.write(MeshProtocol.sendSelfAdvert(flood))
        BleDebugLog.add("Requested ${if (flood) "flood" else "nearby"} self advert")
    }

    fun setRadioSettings(frequencyHz: Int, bandwidthHz: Int, spreadingFactor: Int, codingRate: Int, txPowerDbm: Int) {
        if (_state.value.connection !is BleState.Connected) {
            BleDebugLog.add("Radio settings were not changed: node is disconnected")
            return
        }
        val maxTxPower = _state.value.node.maxTxPowerDbm ?: 22
        if (txPowerDbm !in 0..maxTxPower) {
            BleDebugLog.add("Radio settings were not changed: TX power must be 0-$maxTxPower dBm")
            return
        }
        try {
            // Older firmware encodes 4/5..4/8 as 1..4; preserve its convention.
            val deviceCodingRate = if ((_state.value.node.codingRate ?: codingRate) <= 4) codingRate - 4 else codingRate
            client.write(MeshProtocol.setRadioParams(frequencyHz, bandwidthHz, spreadingFactor, deviceCodingRate))
            client.write(MeshProtocol.setRadioTxPower(txPowerDbm))
            _state.update { it.copy(node = it.node.copy(
                frequencyHz = frequencyHz,
                bandwidthHz = bandwidthHz,
                spreadingFactor = spreadingFactor,
                codingRate = deviceCodingRate,
                txPowerDbm = txPowerDbm,
            )) }
            BleDebugLog.add("Requested radio update: ${frequencyHz / 1_000_000.0} MHz, ${bandwidthHz / 1_000} kHz, SF$spreadingFactor, 4/$codingRate, $txPowerDbm dBm")
            viewModelScope.launch {
                delay(600)
                if (_state.value.connection is BleState.Connected) client.write(MeshProtocol.deviceQuery())
            }
        } catch (e: IllegalArgumentException) {
            BleDebugLog.add("Radio settings were not changed: ${e.message}")
        }
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
        val outboundText = OutgoingText.prepare(text, _state.value.clientSpecific.textCompression)
        if (outboundText.encodeToByteArray().size > MeshProtocol.maxDirectMessageBytes) {
            BleDebugLog.add("Direct message rejected: exceeds ${MeshProtocol.maxDirectMessageBytes} UTF-8 bytes")
            return
        }
        val message = Message(
            messageId = "${System.currentTimeMillis()}_${recipientKey}_${outboundText.hashCode()}",
            text = outboundText,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.Pending,
            senderKey = recipientKey,
            pathLength = contacts[recipientKey]?.hops,
            pathBytes = contacts[recipientKey]?.pathBytes ?: ByteArray(0),
            relayNames = contacts[recipientKey]?.let { resolveRelayNames(it.pathBytes, it.pathHashWidth) } ?: emptyList(),
        )
        messages.getOrPut(recipientKey) { mutableListOf() }.add(message)
        enqueueDirectTransmission(recipientKey, message)
    }

    fun sendChannelMessage(channelIndex: Int, text: String) {
        val outboundText = OutgoingText.prepare(text, _state.value.clientSpecific.textCompression)
        if (outboundText.encodeToByteArray().size > MeshProtocol.maxChannelMessageBytes) {
            BleDebugLog.add("Channel message rejected: exceeds ${MeshProtocol.maxChannelMessageBytes} UTF-8 bytes")
            return
        }
        val message = ChannelMessage(
            messageId = "${System.currentTimeMillis()}_ch${channelIndex}_${outboundText.hashCode()}",
            text = outboundText,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.Pending,
            senderName = _state.value.node.name ?: "You",
            channelIndex = channelIndex,
        )
        channelMessages.getOrPut(channelIndex) { mutableListOf() }.add(message)
        publishChannelMessages()
        pendingChannelTransmissions.addLast(ChannelTransmission(channelIndex, message.messageId))
        client.write(MeshProtocol.sendChannelMessage(channelIndex, outboundText))
    }

    fun setChannel(channelIndex: Int, name: String, pskHex: String) {
        try {
            require(channelIndex in 0..7) { "Channel index must be 0-7" }
            val channel = Channel.fromHex(channelIndex, name, pskHex)
                .copy(pinned = channels[channelIndex]?.pinned == true)
            channels[channelIndex] = channel
            publishChannels()
            client.write(MeshProtocol.setChannel(channelIndex, name, channel.psk))
        } catch (e: Exception) {
            BleDebugLog.add("Failed to set channel: ${e.message}")
        }
    }

    /** Removes a custom channel locally and clears its slot in the companion radio. */
    fun deleteChannel(channelIndex: Int) {
        if (channelIndex !in 0..7 || channelIndex == 0) {
            BleDebugLog.add("Channel $channelIndex cannot be deleted")
            return
        }
        pendingChannelTransmissions.removeAll { it.channelIndex == channelIndex }
        channels[channelIndex] = Channel.empty(channelIndex)
        channelMessages.remove(channelIndex)
        channelUnread.remove(channelIndex)
        if (_state.value.activeChannel?.index == channelIndex) closeChannel()
        publishChannels()
        publishChannelMessages()
        publishUnread()
        deviceScope?.let { stateStore.saveChannelMessages(it, channelIndex, emptyList()) }
        if (_state.value.connection is BleState.Connected) {
            runCatching { client.write(MeshProtocol.setChannel(channelIndex, "", ByteArray(16))) }
                .onFailure { BleDebugLog.add("Failed to clear channel $channelIndex: ${it.message}") }
        }
    }

    fun toggleChannelPinned(channelIndex: Int) {
        val channel = channels[channelIndex] ?: return
        if (channel.isEmpty) return
        channels[channelIndex] = channel.copy(pinned = !channel.pinned)
        publishChannels()
    }

    /** Moves a channel relative to the currently visible order and switches to custom sorting. */
    fun moveChannel(channelIndex: Int, direction: Int) {
        if (direction == 0 || channels[channelIndex]?.isEmpty != false) return
        if (_state.value.channelSort != ChannelSort.Custom) {
            channelOrder.clear()
            channelOrder.addAll(_state.value.channels.map(Channel::index))
        }
        val visible = _state.value.channels.filter { !it.isEmpty }
        val from = visible.indexOfFirst { it.index == channelIndex }
        val to = from + if (direction < 0) -1 else 1
        if (from < 0 || to !in visible.indices) return
        val target = visible[to]
        // Pinned channels deliberately remain in their own section.
        if (target.pinned != channels.getValue(channelIndex).pinned) return
        val first = channelOrder.indexOf(channelIndex)
        val second = channelOrder.indexOf(target.index)
        if (first < 0 || second < 0) return
        channelOrder[first] = target.index
        channelOrder[second] = channelIndex
        persistChannelOrder()
        prefs.edit().putString(PREF_CHANNEL_SORT, ChannelSort.Custom.name).apply()
        publishChannels(ChannelSort.Custom)
    }

    fun retryMessage(contactKey: String, messageId: String) {
        val list = messages[contactKey] ?: return
        val index = list.indexOfFirst { it.messageId == messageId && it.isOutgoing && it.status == MessageStatus.Failed }
        if (index < 0 || _state.value.connection !is BleState.Connected) return
        val retry = list[index].copy(status = MessageStatus.Pending, retryCount = 0, ackHash = null, estimatedTimeoutMs = null, tripTimeMs = null, sentAt = null)
        list[index] = retry
        enqueueDirectTransmission(contactKey, retry)
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

    private fun loadThemeSettings() = AppThemeSettings(
        useMonet = prefs.getBoolean(PREF_USE_MONET, false),
        darkMode = prefs.getBoolean(PREF_DARK_MODE, false),
        languageTag = prefs.getString(PREF_LANGUAGE, DEFAULT_LANGUAGE_TAG)
            ?.takeIf { it in supportedLanguageTags }
            ?: DEFAULT_LANGUAGE_TAG,
    )

    private fun loadClientSpecificSettings() = ClientSpecificSettings(
        textCompression = prefs.getString(PREF_TEXT_COMPRESSION, null)
            ?.let { runCatching { TextCompressionMode.valueOf(it) }.getOrNull() }
            ?: TextCompressionMode.Off,
    )

    private fun loadContactSort(): ContactSort = prefs.getString(PREF_CONTACT_SORT, null)
        ?.let { runCatching { ContactSort.valueOf(it) }.getOrNull() } ?: ContactSort.LastSeen

    private fun loadChatSort(): ChatSort = prefs.getString(PREF_CHAT_SORT, null)
        ?.let { runCatching { ChatSort.valueOf(it) }.getOrNull() } ?: ChatSort.RecentMessages

    private fun loadChannelSort(): ChannelSort = prefs.getString(PREF_CHANNEL_SORT, null)
        ?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: ChannelSort.RecentMessages

    private fun loadChannelOrder(): List<Int> {
        val saved = prefs.getString(PREF_CHANNEL_ORDER, null)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { array.getInt(it) }
            }.getOrNull()
        }.orEmpty().filter { it in 0..7 }.distinct()
        return saved + (0..7).filterNot(saved::contains)
    }

    private fun persistChannelOrder() {
        val array = JSONArray()
        channelOrder.forEach(array::put)
        prefs.edit().putString(PREF_CHANNEL_ORDER, array.toString()).apply()
    }

    private fun publishContacts() {
        val sort = loadContactSort()
        val lastMessageAt = messages.mapValues { (_, list) -> list.maxOfOrNull(Message::timestamp) ?: 0L }
        val comparator = when (sort) {
            ContactSort.RecentMessages -> compareByDescending<Contact> { lastMessageAt[it.publicKey] ?: 0L }
            ContactSort.LastSeen -> compareByDescending<Contact> { it.lastSeenEpoch }
            ContactSort.Name -> compareBy<Contact> { it.name.lowercase() }
        }
        _state.update { it.copy(
            contactSort = sort,
            contacts = contacts.values.sortedWith(
                compareByDescending<Contact> { contact -> contact.favorite }
                    .then(comparator)
                    .thenBy { contact -> contact.name.lowercase() },
            ),
        ) }
    }

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
                publishContacts()
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
                        txPowerDbm = event.node.txPowerDbm ?: it.node.txPowerDbm,
                        maxTxPowerDbm = event.node.maxTxPowerDbm ?: it.node.maxTxPowerDbm,
                        frequencyHz = event.node.frequencyHz ?: it.node.frequencyHz,
                        bandwidthHz = event.node.bandwidthHz ?: it.node.bandwidthHz,
                        spreadingFactor = event.node.spreadingFactor ?: it.node.spreadingFactor,
                        codingRate = event.node.codingRate ?: it.node.codingRate,
                        latitude = event.node.latitude ?: it.node.latitude,
                        longitude = event.node.longitude ?: it.node.longitude,
                    ))
                }
                event.node.publicKey?.let { publicKey ->
                    restoreDeviceState(publicKey)
                    flushDirectMessagesAwaitingIdentity()
                }
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
                handleIncomingChannelMessage(event.message)
                requestNextQueuedMessage()
            }
            is MeshEvent.LogRxData -> MeshProtocol.parseLogRxChannelMessage(event.frame, channels.values)
                ?.let(::handleIncomingChannelMessage)
            is MeshEvent.MessageSent -> handleMessageSent(event)
            is MeshEvent.MessageConfirmed -> handleMessageConfirmed(event)
            is MeshEvent.QueuedMessagesFinished -> finishQueuedMessageSync()
            is MeshEvent.MessagesWaiting -> startQueuedMessageSync()
            is MeshEvent.ChannelUpdated -> {
                channels[event.channel.index] = event.channel.copy(pinned = channels[event.channel.index]?.pinned == true)
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
            if (index >= 0) list[index] = message else list.add(message)
        }
        publishMessages()
        val transmission = DirectTransmission(contactKey, message.messageId, message)
        val expectedAckHash = _state.value.node.publicKey?.let { publicKey ->
            MeshProtocol.expectedDirectAckHash(
                timestampSeconds = message.timestamp / 1_000,
                attempt = message.retryCount,
                text = message.text,
                senderPublicKey = publicKey,
            )
        }
        if (expectedAckHash != null) {
            directMessagesByExpectedAck.getOrPut(expectedAckHash) { ArrayDeque() }.addLast(transmission)
            directMessagesByKnownAck.getOrPut(expectedAckHash) { ArrayDeque() }.addLast(transmission)
            BleDebugLog.add("Direct message queued with ACK ${expectedAckHash.toString(16).padStart(8, '0')}")
        } else {
            pendingDirectAwaitingIdentity.addLast(transmission)
            BleDebugLog.add("Direct message waiting for node identity before transmission")
            return
        }
        client.write(
            MeshProtocol.sendTextMessage(
                recipientKey = contactKey,
                text = message.text,
                attempt = message.retryCount,
                timestampSeconds = message.timestamp / 1_000,
            )
        )
    }

    /** Mirrors MCOA's retry service: one active send per contact, max six globally. */
    private fun enqueueDirectTransmission(contactKey: String, message: Message) {
        directSendQueues.getOrPut(contactKey) { ArrayDeque() }
            .addLast(DirectTransmission(contactKey, message.messageId, message))
        startNextDirectTransmission(contactKey)
    }

    private fun startNextDirectTransmission(contactKey: String) {
        if (activeDirectMessages.size >= MAX_CONCURRENT_DIRECT_TRANSMISSIONS) return
        if (activeDirectMessages.values.any { it == contactKey }) return
        val queue = directSendQueues[contactKey] ?: return
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val message = messages[pending.contactKey]
                ?.firstOrNull { it.messageId == pending.messageId && it.status == MessageStatus.Pending }
                ?: continue
            activeDirectMessages[pending.messageId] = pending.contactKey
            transmitDirect(pending.contactKey, message)
            break
        }
        if (queue.isEmpty()) directSendQueues.remove(contactKey)
    }

    private fun resolveDirectTransmission(transmission: DirectTransmission) {
        if (activeDirectMessages.remove(transmission.messageId) == null) return
        startNextDirectTransmission(transmission.contactKey)
        directSendQueues.keys.toList().forEach(::startNextDirectTransmission)
    }

    private fun handleMessageSent(event: MeshEvent.MessageSent) {
        val direct = takeTransmission(directMessagesByExpectedAck, event.ackHash)
        if (direct != null) {
            val list = messages[direct.contactKey] ?: return
            val index = list.indexOfFirst { it.messageId == direct.messageId }
            if (index < 0 || list[index].status != MessageStatus.Pending) return
            list[index] = list[index].copy(
                status = MessageStatus.Sent,
                ackHash = event.ackHash,
                estimatedTimeoutMs = event.estimatedTimeoutMs,
                sentAt = System.currentTimeMillis(),
            )
            BleDebugLog.add("Direct message accepted: flood=${event.isFlood} ack=${event.ackHash.toString(16)}")
            directMessagesByAck.getOrPut(event.ackHash) { ArrayDeque() }.addLast(direct)
            publishMessages()
            scheduleAckTimeout(direct.contactKey, direct.messageId, event.estimatedTimeoutMs)
            return
        }

        while (pendingChannelTransmissions.isNotEmpty()) {
            val channel = pendingChannelTransmissions.removeFirst()
            val list = channelMessages[channel.channelIndex] ?: continue
            val index = list.indexOfFirst { it.messageId == channel.messageId }
            if (index >= 0 && list[index].status == MessageStatus.Pending) {
                // Channel traffic has no end-to-end ACK. This response only means that the
                // connected radio accepted the packet, while later echoes count relays.
                list[index] = list[index].copy(status = MessageStatus.Sent)
                publishChannelMessages()
                return
            }
        }

        BleDebugLog.add("Unmatched send response: ack=${event.ackHash.toString(16)}")
    }

    private fun flushDirectMessagesAwaitingIdentity() {
        while (pendingDirectAwaitingIdentity.isNotEmpty()) {
            val pending = pendingDirectAwaitingIdentity.removeFirst()
            transmitDirect(pending.contactKey, pending.message)
        }
    }

    private fun handleMessageConfirmed(event: MeshEvent.MessageConfirmed) {
        val acceptedDirect = takeTransmission(directMessagesByAck, event.ackHash)
        val direct = acceptedDirect ?: takeTransmission(directMessagesByKnownAck, event.ackHash)
        if (direct == null) {
            BleDebugLog.add("Unmatched delivery ACK: ack=${event.ackHash.toString(16)}")
            return
        }
        if (acceptedDirect != null) removeTransmission(directMessagesByKnownAck, direct)
        retryJobs.remove(direct.messageId)?.cancel()
        val list = messages[direct.contactKey] ?: return
        val index = list.indexOfFirst { it.messageId == direct.messageId }
        if (index >= 0) {
            list[index] = list[index].copy(status = MessageStatus.Delivered, tripTimeMs = event.tripTimeMs)
            publishMessages()
        }
        resolveDirectTransmission(direct)
    }

    private fun <T> takeTransmission(
        transmissions: MutableMap<Long, ArrayDeque<T>>,
        ackHash: Long,
    ): T? {
        val queue = transmissions[ackHash] ?: return null
        val transmission = queue.removeFirstOrNull()
        if (queue.isEmpty()) transmissions.remove(ackHash)
        return transmission
    }

    private fun <T> removeTransmission(
        transmissions: MutableMap<Long, ArrayDeque<T>>,
        target: T,
    ) {
        val iterator = transmissions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(target)
            if (entry.value.isEmpty()) iterator.remove()
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
                resolveDirectTransmission(DirectTransmission(contactKey, messageId, message))
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
            val isSelfEcho = existing.isOutgoing && incomingIsSelf
            val repeatWindowMs = if (isSelfEcho) SELF_CHANNEL_ECHO_WINDOW_MS else CHANNEL_REPEAT_WINDOW_MS
            existing.text == enriched.text &&
                kotlin.math.abs(existing.timestamp - enriched.timestamp) <= repeatWindowMs &&
                (existing.senderName.equals(enriched.senderName, ignoreCase = true) || isSelfEcho)
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
        val promotedFromPending = existing.status == MessageStatus.Pending
        list[existingIndex] = existing.copy(
            status = if (promotedFromPending) MessageStatus.Sent else existing.status,
            repeatCount = existing.repeatCount + 1,
            pathLength = sequenceOf(existing.pathLength, enriched.pathLength).filterNotNull().maxOrNull(),
            pathHashWidth = existing.pathHashWidth ?: enriched.pathHashWidth,
            pathBytes = if (enriched.pathBytes.size > existing.pathBytes.size) enriched.pathBytes else existing.pathBytes,
            pathVariants = variants,
            snr = enriched.snr ?: existing.snr,
            relayNames = (existing.relayNames + enriched.relayNames).distinct(),
        )
        // MCOA removes an echoed channel message from the send-response
        // queue. Otherwise its later RESP_CODE_SENT would consume the next message's slot.
        if (promotedFromPending) {
            pendingChannelTransmissions.removeAll { it.messageId == existing.messageId }
        }
        return false
    }

    private fun handleIncomingChannelMessage(message: ChannelMessage) {
        if (isDirectSelfChannelEcho(message)) {
            markChannelEchoAccepted(message)
            return
        }
        val isNew = mergeChannelMessage(message)
        if (isNew && _state.value.activeChannel?.index != message.channelIndex && !message.isOutgoing) {
            incrementChannelUnread(message.channelIndex)
        }
        publishChannelMessages()
    }

    /** Mirrors MCOA: a local broadcast is not a relay reception. */
    private fun isDirectSelfChannelEcho(message: ChannelMessage): Boolean {
        val selfName = _state.value.node.name?.trim().orEmpty()
        val hasRoute = message.pathBytes.isNotEmpty() || (message.pathLength != null && message.pathLength != 0)
        return selfName.isNotEmpty() &&
            message.senderName.trim().equals(selfName, ignoreCase = true) &&
            !hasRoute
    }

    /** Accept a direct self-echo without counting it as a relay repeat. */
    private fun markChannelEchoAccepted(incoming: ChannelMessage) {
        val list = channelMessages[incoming.channelIndex] ?: return
        val selfName = _state.value.node.name?.trim().orEmpty()
        val index = list.indexOfLast { existing ->
            existing.isOutgoing &&
                existing.text == incoming.text &&
                existing.senderName.equals(selfName, ignoreCase = true) &&
                kotlin.math.abs(existing.timestamp - incoming.timestamp) <= SELF_CHANNEL_ECHO_WINDOW_MS
        }
        if (index < 0 || list[index].status != MessageStatus.Pending) return
        val existing = list[index]
        list[index] = existing.copy(status = MessageStatus.Sent)
        pendingChannelTransmissions.removeAll { it.messageId == existing.messageId }
        publishChannelMessages()
    }

    private fun resolveRelayNames(pathBytes: ByteArray, hashWidth: Int): List<String> {
        if (pathBytes.isEmpty() || hashWidth !in 1..4) return emptyList()
        return pathBytes.asList().chunked(hashWidth).mapNotNull { hash ->
            val prefix = hash.joinToString("") { "%02X".format(it) }
            contacts.values.filter { it.type == ContactType.Repeater }
                .singleOrNull { it.publicKey.startsWith(prefix, ignoreCase = true) }
                ?.name
        }.distinct()
    }

    private fun publishMessages() {
        _state.update { it.copy(messages = messages.mapValues { (_, list) -> list.toList() }) }
        publishContacts()
        deviceScope?.let { scope -> messages.forEach { (key, list) -> stateStore.saveMessages(scope, key, list) } }
    }

    private fun publishChannelMessages() {
        _state.update { it.copy(channelMessages = channelMessages.mapValues { (_, list) -> list.toList() }) }
        publishChannels()
        deviceScope?.let { scope -> channelMessages.forEach { (index, list) -> stateStore.saveChannelMessages(scope, index, list) } }
    }

    private fun publishChannels(sortOverride: ChannelSort? = null) {
        val sort = sortOverride ?: loadChannelSort()
        val lastMessageAt = channelMessages.mapValues { (_, list) -> list.maxOfOrNull(ChannelMessage::timestamp) ?: 0L }
        val comparator = when (sort) {
            ChannelSort.Custom -> compareBy<Channel> { channelOrder.indexOf(it.index).takeIf { position -> position >= 0 } ?: Int.MAX_VALUE }
            ChannelSort.RecentMessages -> compareByDescending<Channel> { lastMessageAt[it.index] ?: 0L }
            ChannelSort.Unread -> compareByDescending<Channel> { channelUnread[it.index] ?: 0 }
            ChannelSort.Name -> compareBy<Channel> { it.name.lowercase() }
            ChannelSort.Slot -> compareBy<Channel> { it.index }
        }
        _state.update { it.copy(
            channelSort = sort,
            channels = channels.values.sortedWith(
                compareByDescending<Channel> { it.pinned }
                    .then(comparator)
                    .thenBy { it.index },
            ),
        ) }
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
        publishChannels()
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
        directMessagesByExpectedAck.clear()
        directMessagesByKnownAck.clear()
        pendingDirectAwaitingIdentity.clear()
        directSendQueues.clear()
        activeDirectMessages.clear()
        pendingChannelTransmissions.clear()
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
        const val PREF_USE_MONET = "use_monet"
        const val PREF_DARK_MODE = "dark_mode"
        const val PREF_LANGUAGE = "language"
        const val PREF_CONTACT_SORT = "contact_sort"
        const val PREF_CHAT_SORT = "chat_sort"
        const val PREF_CHANNEL_SORT = "channel_sort"
        const val PREF_CHANNEL_ORDER = "channel_order"
        const val PREF_TEXT_COMPRESSION = "text_compression"
        const val DEFAULT_LANGUAGE_TAG = "ru"
        val supportedLanguageTags = setOf("ru", "en")
        const val CONTACT_SYNC_TIMEOUT_MS = 20_000L
        const val QUEUE_SYNC_TIMEOUT_MS = 5_000L
        const val MAX_DIRECT_RETRIES = 3
        const val MAX_CONCURRENT_DIRECT_TRANSMISSIONS = 6
        const val MIN_ACK_TIMEOUT_MS = 2_000L
        const val MAX_ACK_TIMEOUT_MS = 60_000L
        const val CHANNEL_REPEAT_WINDOW_MS = 30_000L
        const val SELF_CHANNEL_ECHO_WINDOW_MS = 10 * 60_000L
        val RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }
}
