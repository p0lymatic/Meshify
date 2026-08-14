package com.polymatic.meshify.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.polymatic.meshify.debug.BleDebugLog
import com.polymatic.meshify.mesh.Channel
import com.polymatic.meshify.mesh.ChannelMessage
import com.polymatic.meshify.mesh.Message
import com.polymatic.meshify.mesh.MessageStatus
import java.util.Base64

/** Persistent state is deliberately scoped to the first ten hex chars of a MeshCore identity. */
class MeshStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("meshify_state", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadMessages(scope: String): Map<String, List<Message>> = prefs.all
        .filterKeys { it.startsWith("messages_${scope}_") }
        .mapNotNull { (key, value) ->
            val contactKey = key.removePrefix("messages_${scope}_")
            (value as? String)?.let { json -> contactKey to parseArray(json, MessageDto::class.java).mapNotNull(MessageDto::toModel) }
        }
        .toMap()

    fun saveMessages(scope: String, contactKey: String, messages: List<Message>) =
        put("messages_${scope}_$contactKey", messages.map(MessageDto::from).toTypedArray())

    fun loadChannelMessages(scope: String): Map<Int, List<ChannelMessage>> = (0..7).mapNotNull { index ->
        prefs.getString("channel_messages_${scope}_$index", null)?.let { json ->
            index to parseArray(json, ChannelMessageDto::class.java).mapNotNull(ChannelMessageDto::toModel)
        }
    }.toMap()

    fun saveChannelMessages(scope: String, index: Int, messages: List<ChannelMessage>) =
        put("channel_messages_${scope}_$index", messages.map(ChannelMessageDto::from).toTypedArray())

    fun loadChannels(scope: String): List<Channel> = prefs.getString("channels_$scope", null)
        ?.let { parseArray(it, ChannelDto::class.java).mapNotNull(ChannelDto::toModel) }
        .orEmpty()

    fun saveChannels(scope: String, channels: Collection<Channel>) =
        put("channels_$scope", channels.map(ChannelDto::from).toTypedArray())

    fun loadUnread(scope: String): UnreadState = prefs.getString("unread_$scope", null)?.let { json ->
        runCatching { gson.fromJson(json, UnreadDto::class.java)?.toModel() ?: UnreadState() }
            .getOrElse { BleDebugLog.add("Unread state ignored: ${it.message}"); UnreadState() }
    } ?: UnreadState()

    fun saveUnread(scope: String, state: UnreadState) = put("unread_$scope", UnreadDto.from(state))

    private fun put(key: String, value: Any) {
        prefs.edit().putString(key, gson.toJson(value)).apply()
    }

    private fun <T> parseArray(json: String, type: Class<T>): List<T> = runCatching {
        JsonParser.parseString(json).asJsonArray.mapNotNull { item ->
            runCatching { gson.fromJson(item, type) }.getOrElse {
                BleDebugLog.add("Stored ${type.simpleName} entry ignored: ${it.message}")
                null
            }
        }
    }.getOrElse {
        BleDebugLog.add("Stored ${type.simpleName} list ignored: ${it.message}")
        emptyList()
    }

    data class UnreadState(
        val contacts: Map<String, Int> = emptyMap(),
        val channels: Map<Int, Int> = emptyMap(),
    )

    private data class MessageDto(
        val id: String = "", val text: String = "", val timestamp: Long = 0,
        val outgoing: Boolean = false, val status: String = MessageStatus.Delivered.name,
        val senderKey: String = "", val pathLength: Int? = null, val path: String = "",
        val snr: Float? = null, val ackHash: Long? = null, val timeoutMs: Long? = null,
        val tripMs: Long? = null, val relays: List<String> = emptyList(), val retryCount: Int = 0,
        val sentAt: Long? = null,
    ) {
        fun toModel(): Message? = runCatching {
            require(id.isNotBlank() && senderKey.isNotBlank())
            Message(id, text, timestamp, outgoing, MessageStatus.valueOf(status), senderKey, pathLength, decode(path), snr, ackHash, timeoutMs, tripMs, relays, retryCount, sentAt)
        }.getOrNull()
        companion object { fun from(value: Message) = MessageDto(value.messageId, value.text, value.timestamp, value.isOutgoing, value.status.name, value.senderKey, value.pathLength, encode(value.pathBytes), value.snr, value.ackHash, value.estimatedTimeoutMs, value.tripTimeMs, value.relayNames, value.retryCount, value.sentAt) }
    }

    private data class ChannelMessageDto(
        val id: String = "", val text: String = "", val timestamp: Long = 0,
        val outgoing: Boolean = false, val status: String = MessageStatus.Delivered.name,
        val sender: String = "", val index: Int = -1, val repeats: Int = 0,
        val pathLength: Int? = null, val pathHashWidth: Int? = null, val path: String = "",
        val variants: List<String> = emptyList(), val snr: Float? = null, val relays: List<String> = emptyList(),
    ) {
        fun toModel(): ChannelMessage? = runCatching {
            require(id.isNotBlank() && index in 0..7)
            ChannelMessage(id, text, timestamp, outgoing, MessageStatus.valueOf(status), sender, index, repeats, pathLength, pathHashWidth, decode(path), variants.map(::decode), snr, relays)
        }.getOrNull()
        companion object { fun from(value: ChannelMessage) = ChannelMessageDto(value.messageId, value.text, value.timestamp, value.isOutgoing, value.status.name, value.senderName, value.channelIndex, value.repeatCount, value.pathLength, value.pathHashWidth, encode(value.pathBytes), value.pathVariants.map(::encode), value.snr, value.relayNames) }
    }

    private data class ChannelDto(val index: Int = -1, val name: String = "", val psk: String = "", val pinned: Boolean = false) {
        fun toModel(): Channel? = runCatching { Channel.fromHex(index, name, psk).copy(pinned = pinned) }.getOrNull()
        companion object { fun from(value: Channel) = ChannelDto(value.index, value.name, value.pskHex, value.pinned) }
    }

    private data class UnreadDto(val contacts: Map<String, Int> = emptyMap(), val channels: Map<String, Int> = emptyMap()) {
        fun toModel() = UnreadState(contacts.filterValues { it > 0 }, channels.mapNotNull { (key, value) -> key.toIntOrNull()?.takeIf { it in 0..7 && value > 0 }?.let { it to value } }.toMap())
        companion object { fun from(value: UnreadState) = UnreadDto(value.contacts, value.channels.mapKeys { it.key.toString() }) }
    }

    private companion object {
        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun decode(value: String): ByteArray = if (value.isBlank()) ByteArray(0) else Base64.getDecoder().decode(value)
    }
}
