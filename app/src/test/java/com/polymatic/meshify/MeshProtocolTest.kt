package com.polymatic.meshify

import com.polymatic.meshify.mesh.MeshEvent
import com.polymatic.meshify.mesh.MeshProtocol
import com.polymatic.meshify.ui.screens.routeCountLabel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshProtocolTest {
    @Test
    fun buildsDirectMessageUsingCompanionRadioFrame() {
        val frame = MeshProtocol.sendTextMessage(
            recipientKey = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
            text = "Hi",
            attempt = 2,
            timestampSeconds = 0x12345678,
        )

        assertArrayEquals(
            byteArrayOf(2, 0, 2, 0x78, 0x56, 0x34, 0x12, 1, 2, 3, 4, 5, 6, 'H'.code.toByte(), 'i'.code.toByte(), 0),
            frame,
        )
    }

    @Test
    fun buildsChannelMessageUsingCompanionRadioFrame() {
        val frame = MeshProtocol.sendChannelMessage(channelIndex = 3, text = "Hi", timestampSeconds = 0x12345678)

        assertArrayEquals(
            byteArrayOf(3, 0, 3, 0x78, 0x56, 0x34, 0x12, 'H'.code.toByte(), 'i'.code.toByte(), 0),
            frame,
        )
    }

    @Test
    fun buildsNodeControlFramesUsingMeshCoreFormat() {
        assertArrayEquals(
            byteArrayOf(8, 'N'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte()),
            MeshProtocol.setNodeName("Node"),
        )
        assertArrayEquals(byteArrayOf(7, 0), MeshProtocol.sendSelfAdvert(flood = false))
        assertArrayEquals(byteArrayOf(7, 1), MeshProtocol.sendSelfAdvert(flood = true))
    }

    @Test
    fun buildsRadioSettingsFramesUsingMeshCoreFormat() {
        assertArrayEquals(
            byteArrayOf(11, 0x70, 0xF6.toByte(), 0x36, 0x36, 0x48, 0xE8.toByte(), 0x01, 0, 9, 5),
            MeshProtocol.setRadioParams(909_571_696, 125_000, 9, 5),
        )
        assertArrayEquals(byteArrayOf(12, 22), MeshProtocol.setRadioTxPower(22))
    }

    @Test
    fun truncatesNodeNameAtMeshCoreUtf8Limit() {
        val frame = MeshProtocol.setNodeName("x".repeat(MeshProtocol.maxNodeNameBytes + 4))
        assertEquals(MeshProtocol.maxNodeNameBytes + 1, frame.size)
        assertTrue(frame.drop(1).all { it == 'x'.code.toByte() })
    }

    @Test
    fun rejectsDirectMessageThatWouldExceedFrameSize() {
        try {
            MeshProtocol.sendTextMessage(
                recipientKey = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
                text = "x".repeat(MeshProtocol.maxDirectMessageBytes + 1),
            )
            fail("Expected oversized direct message to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: the direct frame has a larger header than the channel frame.
        }
    }

    @Test
    fun parsesV2DirectMessagePrefixAndHops() {
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        val frame = byteArrayOf(MeshProtocol.responseContactMsgRecv.toByte()) + prefix +
            byteArrayOf(2, 0) + uint32(1_700_000_000L) + "hello".encodeToByteArray() + byteArrayOf(0)

        val message = (MeshProtocol.parse(frame) as MeshEvent.MessageReceived).message
        assertEquals("010203040506", message.senderKey)
        assertEquals(2, message.pathLength)
        assertEquals("hello", message.text)
    }

    @Test
    fun parsesFloodRouteAsUnknownRoute() {
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        val frame = byteArrayOf(MeshProtocol.responseContactMsgRecv.toByte()) + prefix +
            byteArrayOf(0xFF.toByte(), 0) + uint32(1_700_000_000L) + "hello".encodeToByteArray() + byteArrayOf(0)

        val message = (MeshProtocol.parse(frame) as MeshEvent.MessageReceived).message
        assertEquals(-1, message.pathLength)
    }

    @Test
    fun parsesQueuedMessageControlFrames() {
        assertTrue(MeshProtocol.parse(byteArrayOf(MeshProtocol.pushMessageWaiting.toByte())) is MeshEvent.MessagesWaiting)
        assertTrue(MeshProtocol.parse(byteArrayOf(MeshProtocol.responseNoMoreMessages.toByte())) is MeshEvent.QueuedMessagesFinished)
        assertArrayEquals(byteArrayOf(MeshProtocol.cmdSyncNextMessage.toByte()), MeshProtocol.syncNextMessage())
    }

    @Test
    fun displaysRouteCountAsHopsInsteadOfPathHash() {
        assertEquals("1 реле", routeCountLabel(1))
        assertEquals("3 реле", routeCountLabel(3))
        assertEquals("flood", routeCountLabel(-1))
    }

    @Test
    fun parsesV3ChannelPathAndSender() {
        val frame = byteArrayOf(
            MeshProtocol.responseChannelMsgRecvV3.toByte(),
            24, 1, 0, 3, 0x42,
            0x12, 0x34, 0x56, 0x78,
            0,
        ) + uint32(1_700_000_000L) + "Node A: test".encodeToByteArray() + byteArrayOf(0)

        val message = (MeshProtocol.parse(frame) as MeshEvent.ChannelMessageReceived).message
        assertEquals(3, message.channelIndex)
        assertEquals(2, message.pathLength)
        assertEquals(2, message.pathHashWidth)
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), message.pathBytes)
        assertEquals("Node A", message.senderName)
        assertEquals("test", message.text)
        assertEquals(6f, message.snr)
    }

    @Test
    fun parsesDeliveryAcknowledgement() {
        val frame = byteArrayOf(MeshProtocol.pushSendConfirmed.toByte()) + uint32(0x12345678) + uint32(845)
        val event = MeshProtocol.parse(frame) as MeshEvent.MessageConfirmed
        assertEquals(0x12345678, event.ackHash)
        assertEquals(845, event.tripTimeMs)
        assertTrue(event.tripTimeMs > 0)
    }

    private fun uint32(value: Long): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value.toInt())
        .array()
}
