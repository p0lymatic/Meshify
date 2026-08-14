package com.polymatic.meshify

import com.polymatic.meshify.mesh.MediaTokenParser
import com.polymatic.meshify.mesh.OutgoingText
import com.polymatic.meshify.mesh.TextCompressionMode
import org.junit.Assert.*
import org.junit.Test

class MediaTokenParserTest {
    @Test fun parsesCompleteMarkersAndCanonicalizesHex() {
        assertEquals(MediaTokenParser.Type.Emoji, MediaTokenParser.parse(":cats/12:")?.type)
        assertEquals("0xdead", MediaTokenParser.parse(";my_pack/0xDEAD;")?.itemId)
        assertNull(MediaTokenParser.parse(":bad pack/1:"))
        assertNull(MediaTokenParser.parse(":pack/one:"))
        assertNull(MediaTokenParser.parse(":pack/1;"))
    }

    @Test fun preservesTokensWhenCompressingAndCountsWireBytes() {
        val text = "Привет :pack/0xDEAD: мир"
        val prepared = OutgoingText.prepare(text, TextCompressionMode.MeshCoreOpen)
        assertTrue(prepared.contains(":pack/0xDEAD:"))
        assertEquals(prepared.encodeToByteArray().size, OutgoingText.utf8Size(text, TextCompressionMode.MeshCoreOpen))
    }
}
