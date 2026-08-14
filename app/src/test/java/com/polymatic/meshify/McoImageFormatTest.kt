package com.polymatic.meshify

import com.polymatic.meshify.mesh.McoImageFormat
import org.junit.Assert.*
import org.junit.Test

class McoImageFormatTest {
    @Test fun readsValidAeicChunkHeader() {
        val chunk = McoImageFormat.parse(0xAE1C, byteArrayOf(0x12, 0x34, 0x56, 0x13, 9, 8))!!
        assertEquals(0x1234, chunk.senderPrefix)
        assertEquals(0x56, chunk.imageId)
        assertEquals(1, chunk.index)
        assertEquals(3, chunk.total)
        assertArrayEquals(byteArrayOf(9, 8), chunk.body)
    }

    @Test fun rejectsWrongDataTypeAndBrokenHeader() {
        assertNull(McoImageFormat.parse(0, ByteArray(5)))
        assertNull(McoImageFormat.parse(0xAE1C, byteArrayOf(0, 0, 0, 0, 0)))
    }
}
