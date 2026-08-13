package com.polymatic.meshify.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MeshProtocol {
    const val maxFrameSize = 172
    const val maxDirectMessageBytes = 157
    const val maxChannelMessageBytes = 160
    const val cmdAppStart = 1
    const val cmdSendTextMessage = 2
    const val cmdSendChannelTextMessage = 3
    const val cmdGetContacts = 4
    const val cmdSetDeviceTime = 6
    const val cmdSyncNextMessage = 10
    const val cmdGetBatteryAndStorage = 20
    const val cmdDeviceQuery = 22
    const val cmdGetChannel = 31
    const val cmdSetChannel = 32

    const val responseContactsStart = 2
    const val responseContact = 3
    const val responseContactsEnd = 4
    const val responseSelfInfo = 5
    const val responseSent = 6
    const val responseContactMsgRecv = 7
    const val responseChannelMsgRecv = 8
    const val responseNoMoreMessages = 10
    const val responseBatteryAndStorage = 12
    const val responseDeviceInfo = 13
    const val responseContactMsgRecvV3 = 16
    const val responseChannelMsgRecvV3 = 17
    const val responseChannelInfo = 18
    const val pushSendConfirmed = 0x82
    const val pushMessageWaiting = 0x83

    fun deviceQuery() = byteArrayOf(cmdDeviceQuery.toByte(), 4)
    fun getContacts() = byteArrayOf(cmdGetContacts.toByte())
    fun syncNextMessage() = byteArrayOf(cmdSyncNextMessage.toByte())
    fun getBattery() = byteArrayOf(cmdGetBatteryAndStorage.toByte())
    fun appStart(): ByteArray = byteArrayOf(cmdAppStart.toByte(), 4, 0, 0, 0, 0, 0, 0) +
        "Meshify".encodeToByteArray() + byteArrayOf(0)

    fun setTime(epochSeconds: Long): ByteArray = ByteBuffer.allocate(5)
        .order(ByteOrder.LITTLE_ENDIAN).put(cmdSetDeviceTime.toByte()).putInt(epochSeconds.toInt()).array()

    fun sendChannelMessage(channelIndex: Int, text: String, timestampSeconds: Long = System.currentTimeMillis() / 1_000): ByteArray {
        if (channelIndex !in 0..7) throw IllegalArgumentException("channelIndex must be 0-7")
        val textBytes = text.encodeToByteArray()
        require(textBytes.size <= maxChannelMessageBytes) { "Channel text exceeds $maxChannelMessageBytes bytes" }
        // Companion radio: [cmd][txt_type][channel_index][timestamp u32 LE][text]\0
        return byteArrayOf(cmdSendChannelTextMessage.toByte(), textTypePlain.toByte(), channelIndex.toByte()) +
            uint32Le(timestampSeconds) + textBytes + byteArrayOf(0)
    }

    fun getChannel(channelIndex: Int): ByteArray {
        if (channelIndex !in 0..7) throw IllegalArgumentException("channelIndex must be 0-7")
        return byteArrayOf(cmdGetChannel.toByte(), channelIndex.toByte())
    }

    fun setChannel(channelIndex: Int, name: String, psk: ByteArray): ByteArray {
        if (channelIndex !in 0..7) throw IllegalArgumentException("channelIndex must be 0-7")
        if (psk.size != 16) throw IllegalArgumentException("PSK must be 16 bytes")
        val nameBytes = name.encodeToByteArray().take(31).toByteArray()
        val paddedName = nameBytes + ByteArray(32 - nameBytes.size)
        return byteArrayOf(cmdSetChannel.toByte(), channelIndex.toByte()) + paddedName + psk
    }

    fun sendTextMessage(
        recipientKey: String,
        text: String,
        attempt: Int = 0,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000,
    ): ByteArray {
        val keyBytes = recipientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (keyBytes.size != 32) throw IllegalArgumentException("recipientKey must be 64 hex chars (32 bytes)")
        val textBytes = text.encodeToByteArray()
        require(textBytes.size <= maxDirectMessageBytes) { "Text exceeds $maxDirectMessageBytes bytes" }
        // Companion radio: [cmd][txt_type][attempt][timestamp u32 LE][recipient prefix x6][text]\0
        return byteArrayOf(cmdSendTextMessage.toByte(), textTypePlain.toByte(), attempt.coerceIn(0, 255).toByte()) +
            uint32Le(timestampSeconds) + keyBytes.copyOfRange(0, 6) + textBytes + byteArrayOf(0)
    }

    fun parse(frame: ByteArray): MeshEvent? {
        if (frame.isEmpty() || frame.size > maxFrameSize) return null
        return when (frame[0].toInt() and 0xFF) {
            responseContact -> parseContact(frame)
            responseSelfInfo -> parseSelfInfo(frame)
            responseDeviceInfo -> parseDeviceInfo(frame)
            responseBatteryAndStorage -> parseBattery(frame)
            responseContactsStart -> MeshEvent.ContactsStarted
            responseContactsEnd -> MeshEvent.ContactsFinished
            responseNoMoreMessages -> MeshEvent.QueuedMessagesFinished
            responseContactMsgRecv, responseContactMsgRecvV3 -> parseIncomingMessage(frame)
            responseChannelMsgRecv, responseChannelMsgRecvV3 -> parseIncomingChannelMessage(frame)
            responseChannelInfo -> parseChannelInfo(frame)
            responseSent -> parseSent(frame)
            pushSendConfirmed -> parseSendConfirmed(frame)
            pushMessageWaiting -> MeshEvent.MessagesWaiting
            else -> null
        }
    }

    private fun parseSent(frame: ByteArray): MeshEvent.MessageSent? {
        // [code][is_flood][ack_hash u32][estimated_timeout_ms u32]
        if (frame.size < 10) return null
        return MeshEvent.MessageSent(
            isFlood = frame[1].toInt() != 0,
            ackHash = uint32(frame, 2),
            estimatedTimeoutMs = uint32(frame, 6),
        )
    }

    private fun parseSendConfirmed(frame: ByteArray): MeshEvent.MessageConfirmed? {
        // [code][ack_hash u32][trip_time_ms u32]
        if (frame.size < 9) return null
        return MeshEvent.MessageConfirmed(
            ackHash = uint32(frame, 1),
            tripTimeMs = uint32(frame, 5),
        )
    }

    private fun parseChannelInfo(frame: ByteArray): MeshEvent.ChannelUpdated? {
        // [0]=code, [1]=index, [2-33]=name(32), [34-49]=psk(16)
        if (frame.size < 50) return null
        val index = frame[1].toInt() and 0xFF
        val name = cString(frame, 2, 32)
        val psk = frame.copyOfRange(34, 50)
        return MeshEvent.ChannelUpdated(Channel(index, name, psk))
    }

    private fun parseIncomingChannelMessage(frame: ByteArray): MeshEvent.ChannelMessageReceived? {
        // V2: [code][channel][path_len][txt_type][timestamp u32]["sender: text"]
        // V3: [code][snr][flags][reserved][channel][packed_path_len][path?][txt_type][timestamp u32][text]
        if (frame.size < 8) return null
        val code = frame[0].toInt() and 0xFF
        var cursor: Int
        val channelIndex: Int
        val pathLength: Int
        var pathHashWidth: Int? = null
        var pathBytes = ByteArray(0)
        val snr: Float?
        if (code == responseChannelMsgRecvV3) {
            if (frame.size < 11) return null
            snr = frame[1].toInt().toByte().toInt() / 4f
            val hasPath = (frame[2].toInt() and 0x01) != 0
            channelIndex = frame[4].toInt() and 0xFF
            val packedPath = frame[5].toInt() and 0xFF
            pathHashWidth = ((packedPath and 0xC0) shr 6) + 1
            pathLength = packedPath and 0x3F
            cursor = 6
            if (hasPath && pathLength > 0) {
                val byteCount = pathLength * pathHashWidth
                if (cursor + byteCount + 5 > frame.size) return null
                pathBytes = frame.copyOfRange(cursor, cursor + byteCount)
                cursor += byteCount
            }
        } else {
            snr = null
            channelIndex = frame[1].toInt() and 0xFF
            pathLength = frame[2].toInt().toByte().toInt()
            cursor = 3
        }
        if (channelIndex !in 0..7 || cursor + 5 > frame.size) return null
        val textType = frame[cursor++].toInt() and 0xFF
        if (textType != 0 && (textType shr 2) != 0) return null
        val timestamp = uint32(frame, cursor)
        cursor += 4
        val rawText = cString(frame, cursor, frame.size - cursor)
        if (rawText.isBlank()) return null
        val splitAt = rawText.indexOf(':').takeIf { it in 1..49 }
        val senderName = splitAt?.let { rawText.substring(0, it).trim() }?.takeIf { it.isNotBlank() } ?: "Unknown"
        val text = splitAt?.let { rawText.substring(it + 1).trimStart() } ?: rawText
        val message = ChannelMessage(
            messageId = "${timestamp}_ch${channelIndex}_${text.hashCode()}",
            text = text,
            timestamp = timestamp * 1000L,
            isOutgoing = false,
            status = MessageStatus.Delivered,
            senderName = senderName,
            channelIndex = channelIndex,
            pathLength = pathLength,
            pathHashWidth = pathHashWidth,
            pathBytes = pathBytes,
            snr = snr,
        )
        return MeshEvent.ChannelMessageReceived(message)
    }

    private fun parseIncomingMessage(frame: ByteArray): MeshEvent.MessageReceived? {
        // Companion responses identify the sender by a six-byte public-key prefix.
        // V3 prepends SNR and two reserved bytes to the V2 payload.
        val isV3 = (frame[0].toInt() and 0xFF) == responseContactMsgRecvV3
        val prefixAt = if (isV3) 4 else 1
        val minimumSize = if (isV3) 17 else 14
        if (frame.size < minimumSize) return null
        val senderKey = frame.copyOfRange(prefixAt, prefixAt + 6).toHex()
        val pathLengthRaw = frame[prefixAt + 6].toInt() and 0xFF
        // 0xFF is the firmware sentinel for an unknown/flood route. Keep it
        // distinct from a packed route so the UI never presents it as 63 hops.
        val pathLength = if (pathLengthRaw == 0xFF) -1 else pathLengthRaw and 0x3F
        val textType = frame[prefixAt + 7].toInt() and 0xFF
        val shiftedType = textType shr 2
        val signed = shiftedType == 2 || textType == 2
        if (shiftedType != 0 && textType != 0 && !signed) return null
        val timestampAt = prefixAt + 8
        val timestamp = uint32(frame, timestampAt)
        val textAt = timestampAt + 4 + if (signed) 4 else 0
        if (textAt > frame.size) return null
        val text = cString(frame, textAt, frame.size - textAt)
        if (text.isBlank()) return null
        val message = Message(
            messageId = "${timestamp}_${senderKey}_${text.hashCode()}",
            text = text,
            timestamp = timestamp * 1000L,
            isOutgoing = false,
            status = MessageStatus.Delivered,
            senderKey = senderKey,
            pathLength = pathLength,
            snr = if (isV3) frame[1].toInt().toByte().toInt() / 4f else null,
        )
        return MeshEvent.MessageReceived(message)
    }

    private fun parseContact(frame: ByteArray): MeshEvent.ContactReceived? {
        // Firmware contact record: code + pubKey(32) + type + flags + pathLen + path(64) + name(32) + time + lat + lon + modified.
        if (frame.size < 148) return null
        val type = frame[33].toInt() and 0xFF
        val flags = frame[34].toInt() and 0xFF
        val packedPath = frame[35].toInt() and 0xFF
        val hops = if (packedPath == 0xFF) -1 else packedPath and 0x3F
        val pathHashWidth = if (hops > 0) ((packedPath and 0xC0) shr 6) + 1 else 1
        val name = cString(frame, 100, 32).ifBlank { "Unknown node" }
        val lastSeen = uint32(frame, 132)
        val latitude = int32(frame, 136) / 1_000_000.0
        val longitude = int32(frame, 140) / 1_000_000.0
        val key = frame.copyOfRange(1, 33).joinToString("") { "%02X".format(it) }
        val pathByteCount = if (hops > 0) (hops * pathHashWidth).coerceAtMost(64) else 0
        val path = frame.copyOfRange(36, 36 + pathByteCount)
        return MeshEvent.ContactReceived(Contact(key, name, ContactType.fromWire(type), flags and 1 != 0, hops, lastSeen, latitude, longitude, path, pathHashWidth))
    }

    private fun parseSelfInfo(frame: ByteArray): MeshEvent.NodeUpdated? {
        if (frame.size < 58) return null
        return MeshEvent.NodeUpdated(NodeInfo(name = cString(frame, 58, frame.size - 58), publicKey = frame.copyOfRange(4, 36).joinToString("") { "%02X".format(it) }))
    }

    private fun parseDeviceInfo(frame: ByteArray): MeshEvent.NodeUpdated? {
        if (frame.size < 2) return null
        val model = if (frame.size >= 60) cString(frame, 20, 40) else "MeshCore"
        val firmware = if (frame.size >= 80) cString(frame, 60, 20) else null
        return MeshEvent.NodeUpdated(NodeInfo(model = model.ifBlank { "MeshCore" }, firmware = firmware?.ifBlank { null }))
    }

    private fun parseBattery(frame: ByteArray): MeshEvent.BatteryUpdated? =
        if (frame.size >= 3) MeshEvent.BatteryUpdated(uint16(frame, 1)) else null

    private fun cString(bytes: ByteArray, start: Int, length: Int): String {
        if (start >= bytes.size) return ""
        val end = (start + length).coerceAtMost(bytes.size)
        val zero = (start until end).firstOrNull { bytes[it] == 0.toByte() } ?: end
        return bytes.copyOfRange(start, zero).decodeToString().trim()
    }
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun uint16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun uint32Le(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    private fun uint32(b: ByteArray, at: Int): Long = int32(b, at).toLong() and 0xFFFF_FFFFL
    private fun int32(b: ByteArray, at: Int): Int = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

private const val textTypePlain = 0
data class Contact(
    val publicKey: String,
    val name: String,
    val type: ContactType,
    val favorite: Boolean,
    val hops: Int,
    val lastSeenEpoch: Long,
    val latitude: Double,
    val longitude: Double,
    val pathBytes: ByteArray = ByteArray(0),
    val pathHashWidth: Int = 1,
)
enum class ContactType(val label: String) { Chat("Chat"), Repeater("Repeater"), Room("Room"), Sensor("Sensor"); companion object { fun fromWire(value: Int) = entries.getOrElse(value - 1) { Chat } } }
data class NodeInfo(val name: String? = null, val model: String? = null, val firmware: String? = null, val publicKey: String? = null, val batteryMv: Int? = null)

data class Message(
    val messageId: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageStatus,
    val senderKey: String,
    val pathLength: Int? = null,
    val pathBytes: ByteArray = ByteArray(0),
    val snr: Float? = null,
    val ackHash: Long? = null,
    val estimatedTimeoutMs: Long? = null,
    val tripTimeMs: Long? = null,
    val relayNames: List<String> = emptyList(),
    val retryCount: Int = 0,
    val sentAt: Long? = null,
)

data class ChannelMessage(
    val messageId: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageStatus,
    val senderName: String,
    val channelIndex: Int,
    val repeatCount: Int = 0,
    val pathLength: Int? = null,
    val pathHashWidth: Int? = null,
    val pathBytes: ByteArray = ByteArray(0),
    val pathVariants: List<ByteArray> = emptyList(),
    val snr: Float? = null,
    val relayNames: List<String> = emptyList(),
)

data class Channel(
    val index: Int,
    val name: String,
    val psk: ByteArray,
) {
    val pskHex: String get() = psk.joinToString("") { "%02x".format(it) }
    val isEmpty: Boolean get() = name.isEmpty() && psk.all { it == 0.toByte() }

    companion object {
        fun empty(index: Int) = Channel(index, "", ByteArray(16))
        fun fromHex(index: Int, name: String, pskHex: String): Channel {
            val psk = pskHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (psk.size != 16) throw IllegalArgumentException("PSK must be 32 hex chars (16 bytes)")
            return Channel(index, name, psk)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Channel) return false
        return index == other.index && name == other.name && psk.contentEquals(other.psk)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + name.hashCode()
        result = 31 * result + psk.contentHashCode()
        return result
    }
}

enum class MessageStatus { Pending, Sent, Delivered, Failed }

sealed interface MeshEvent {
    data object ContactsStarted : MeshEvent
    data object ContactsFinished : MeshEvent
    data object QueuedMessagesFinished : MeshEvent
    data object MessagesWaiting : MeshEvent
    data class ContactReceived(val contact: Contact) : MeshEvent
    data class NodeUpdated(val node: NodeInfo) : MeshEvent
    data class BatteryUpdated(val millivolts: Int) : MeshEvent
    data class MessageReceived(val message: Message) : MeshEvent
    data class MessageStatusUpdated(val messageId: String, val status: MessageStatus) : MeshEvent
    data class MessageSent(val isFlood: Boolean, val ackHash: Long, val estimatedTimeoutMs: Long) : MeshEvent
    data class MessageConfirmed(val ackHash: Long, val tripTimeMs: Long) : MeshEvent
    data class ChannelMessageReceived(val message: ChannelMessage) : MeshEvent
    data class ChannelUpdated(val channel: Channel) : MeshEvent
}
