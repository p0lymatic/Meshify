package com.polymatic.meshify.mesh

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ScannedChannel(val index: Int, val name: String, val pskHex: String)

object ChannelQrParser {
    private val pskPattern = Regex("^[0-9a-fA-F]{32}$")

    fun parse(raw: String, defaultIndex: Int): Result<ScannedChannel> = runCatching {
        val data = raw.trim()
        require(data.isNotEmpty()) { "The QR code is empty" }
        parseCommunityJson(data, defaultIndex)
            ?: parseJson(data, defaultIndex)
            ?: parseUri(data, defaultIndex)
            ?: parseLegacy(data, defaultIndex)
            ?: parseBarePsk(data, defaultIndex)
            ?: throw IllegalArgumentException("This QR code does not contain a MeshCore channel")
    }

    private fun parseCommunityJson(data: String, defaultIndex: Int): ScannedChannel? {
        if (!data.startsWith('{')) return null
        val json = JsonParser.parseString(data).asJsonObject
        if (json.string("type") != "meshcore_community") return null
        require(json.int("v") == 1) { "Unsupported community QR version" }
        val name = json.string("name").trim()
        require(name.isNotEmpty()) { "Community name is missing" }
        val encodedSecret = json.string("k").trim()
        val secret = Base64.getUrlDecoder().decode(encodedSecret)
        require(secret.size == 32) { "Community secret must be 32 bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val psk = mac.doFinal("channel:v1:__public__".toByteArray(StandardCharsets.UTF_8)).copyOf(16)
        return validated(defaultIndex, "$name Public", psk.toHex())
    }

    private fun parseJson(data: String, defaultIndex: Int): ScannedChannel? {
        if (!data.startsWith('{')) return null
        val json = JsonParser.parseString(data).asJsonObject
        val type = json.string("type").lowercase()
        if (type.isNotEmpty() && type !in setOf("channel", "meshcore_channel")) return null
        val psk = sequenceOf("psk", "pskHex", "key", "k")
            .map { json.string(it).trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        val name = sequenceOf("name", "channel", "label")
            .map { json.string(it).trim() }
            .firstOrNull { it.isNotEmpty() } ?: "Channel"
        val index = sequenceOf("index", "channelIndex", "slot")
            .map { json.int(it) ?: -1 }
            .firstOrNull { it in 0..7 } ?: defaultIndex
        return validated(index, name, psk)
    }

    private fun parseUri(data: String, defaultIndex: Int): ScannedChannel? {
        if (!data.contains("://") && !data.startsWith("channel?", ignoreCase = true)) return null
        val uri = URI(if (data.startsWith("channel?", true)) "meshcore://$data" else data)
        val pathFirst = uri.path?.trim('/')?.substringBefore('/')
        val isChannel = uri.scheme.equals("meshcore", true) &&
            (uri.host.equals("channel", true) || pathFirst.equals("channel", true)) ||
            uri.scheme.equals("meshcore-channel", true) || uri.scheme.equals("channel", true)
        if (!isChannel) return null
        val query = parseQuery(uri.rawQuery)
        val psk = listOf("psk", "key", "k").firstNotNullOfOrNull(query::get) ?: return null
        val name = listOf("name", "channel", "label").firstNotNullOfOrNull(query::get) ?: "Channel"
        val index = listOf("index", "slot", "channelIndex")
            .firstNotNullOfOrNull { query[it]?.toIntOrNull() }
            ?.takeIf { it in 0..7 } ?: defaultIndex
        return validated(index, name, psk)
    }

    private fun parseLegacy(data: String, defaultIndex: Int): ScannedChannel? {
        if (!data.startsWith("channel:", ignoreCase = true)) return null
        val parts = data.split(':', limit = 4)
        require(parts.size == 4) { "Expected channel:index:name:psk" }
        val index = parts[1].toIntOrNull()?.takeIf { it in 0..7 } ?: defaultIndex
        return validated(index, parts[2], parts[3])
    }

    private fun parseBarePsk(data: String, defaultIndex: Int): ScannedChannel? =
        data.filterNot { it == ' ' || it == '-' }.takeIf(pskPattern::matches)
            ?.let { validated(defaultIndex, "Channel", it) }

    private fun validated(index: Int, rawName: String, rawPsk: String): ScannedChannel {
        val name = rawName.trim().removePrefix("#").take(31)
        val psk = rawPsk.trim().removePrefix("0x").filterNot { it == ' ' || it == '-' }.lowercase()
        require(index in 0..7) { "Channel index must be between 0 and 7" }
        require(name.isNotEmpty()) { "Channel name is missing" }
        require(pskPattern.matches(psk)) { "PSK must contain exactly 32 hexadecimal characters" }
        return ScannedChannel(index, name, psk)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery
        ?.split('&')
        ?.mapNotNull { part ->
            val split = part.indexOf('=')
            if (split <= 0) null else URLDecoder.decode(part.substring(0, split), StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8.name())
        }
        ?.toMap()
        .orEmpty()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.int(key: String): Int? = get(key)?.takeUnless { it.isJsonNull }?.asInt
}
