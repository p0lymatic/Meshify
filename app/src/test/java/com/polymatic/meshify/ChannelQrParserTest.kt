package com.polymatic.meshify

import com.polymatic.meshify.mesh.ChannelQrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelQrParserTest {
    private val psk = "8b3387e9c5cdea6ac9e5edbaa115cd72"

    @Test
    fun parsesLegacyChannel() {
        val channel = ChannelQrParser.parse("channel:3:General:$psk", 1).getOrThrow()
        assertEquals(3, channel.index)
        assertEquals("General", channel.name)
        assertEquals(psk, channel.pskHex)
    }

    @Test
    fun parsesJsonAndUsesFreeSlot() {
        val channel = ChannelQrParser.parse("""{"type":"meshcore_channel","name":"Field","psk":"$psk"}""", 5).getOrThrow()
        assertEquals(5, channel.index)
        assertEquals("Field", channel.name)
    }

    @Test
    fun parsesMeshCoreUri() {
        val channel = ChannelQrParser.parse("meshcore://channel?name=Night%20Ops&slot=2&psk=$psk", 0).getOrThrow()
        assertEquals(2, channel.index)
        assertEquals("Night Ops", channel.name)
    }

    @Test
    fun derivesCommunityPublicChannel() {
        val secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val channel = ChannelQrParser.parse("""{"v":1,"type":"meshcore_community","name":"Local","k":"$secret"}""", 4).getOrThrow()
        assertEquals(4, channel.index)
        assertEquals("Local Public", channel.name)
        assertTrue(channel.pskHex.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun rejectsInvalidPsk() {
        assertTrue(ChannelQrParser.parse("channel:0:Bad:1234", 0).isFailure)
    }
}
