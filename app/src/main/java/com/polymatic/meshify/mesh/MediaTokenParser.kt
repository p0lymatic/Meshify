package com.polymatic.meshify.mesh

/** MeshCore-compatible client media references. The wire format remains plain text. */
object MediaTokenParser {
    private const val PACK = "[a-z0-9_-]{1,32}"
    private const val ITEM = "(?:[0-9]+|0[xX][0-9a-fA-F]+)"
    private val token = Regex("([:;])($PACK)/($ITEM)([:;])")

    enum class Type { Emoji, Sticker }
    data class Token(val type: Type, val packId: String, val itemId: String, val raw: String)
    sealed interface Fragment { data class Text(val value: String) : Fragment; data class Media(val token: Token) : Fragment }

    fun parse(value: String): Token? {
        val match = token.matchEntire(value) ?: return null
        val open = match.groupValues[1]
        val close = match.groupValues[4]
        if (open != close) return null
        return Token(if (open == ":") Type.Emoji else Type.Sticker, match.groupValues[2], canonicalItemId(match.groupValues[3]), value)
    }

    fun fragments(value: String): List<Fragment> {
        val result = mutableListOf<Fragment>()
        var cursor = 0
        token.findAll(value).forEach { match ->
            if (match.range.first > cursor) result += Fragment.Text(value.substring(cursor, match.range.first))
            parse(match.value)?.let { result += Fragment.Media(it) } ?: run { result += Fragment.Text(match.value) }
            cursor = match.range.last + 1
        }
        if (cursor < value.length) result += Fragment.Text(value.substring(cursor))
        return result
    }

    fun canonicalItemId(value: String): String = when {
        value.startsWith("0x", true) -> "0x" + value.drop(2).lowercase()
        else -> value
    }
}

/** Keeps media references byte-for-byte intact while applying user-selected text compression elsewhere. */
object OutgoingText {
    fun prepare(text: String, mode: TextCompressionMode): String = MediaTokenParser.fragments(text).joinToString("") { fragment ->
        when (fragment) {
            is MediaTokenParser.Fragment.Text -> TextCompression.encode(fragment.value, mode)
            is MediaTokenParser.Fragment.Media -> fragment.token.raw
        }
    }

    fun utf8Size(text: String, mode: TextCompressionMode): Int = prepare(text, mode).encodeToByteArray().size
}
