package com.polymatic.meshify.mesh

/**
 * MCOA AEIC image chunk header used in GRP_DATA payloads (data type 0xAE1C).
 * This parser deliberately validates and exposes the opaque bitstream only:
 * decoding it requires the matching AEIC ONNX model, not Android's image decoder.
 */
object McoImageFormat {
    const val dataType = 0xAE1C
    const val maxChunkBytes = 163
    const val headerBytes = 4

    data class Chunk(val senderPrefix: Int, val imageId: Int, val index: Int, val total: Int, val body: ByteArray) {
        val isParity get() = index == total
    }

    fun parse(dataType: Int, blob: ByteArray): Chunk? {
        if (dataType != this.dataType || blob.size !in (headerBytes + 1)..maxChunkBytes) return null
        val senderPrefix = ((blob[0].toInt() and 0xff) shl 8) or (blob[1].toInt() and 0xff)
        val imageId = blob[2].toInt() and 0xff
        val packed = blob[3].toInt() and 0xff
        val index = packed ushr 4
        val total = packed and 0x0f
        if (total !in 1..15 || index > total) return null
        return Chunk(senderPrefix, imageId, index, total, blob.copyOfRange(headerBytes, blob.size))
    }
}
