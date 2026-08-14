package com.polymatic.meshify.mesh

/** Outgoing text substitutions supported by Meshify's client-specific settings. */
enum class TextCompressionMode {
    Off,
    SimilarLatin,
    MeshCoreOpen,
}

object TextCompression {
    private val meshifyMap = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H',
        'К' to 'K', 'М' to 'M', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X',
        'а' to 'a', 'с' to 'c', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'х' to 'x', 'у' to 'y',
    )

    // The map used by meshcore-open's Cyr2Lat helper.
    private val meshCoreOpenMap = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'Ё' to 'E', 'З' to '3', 'К' to 'K',
        'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T',
        'Х' to 'X', 'Ь' to 'b', 'а' to 'a', 'е' to 'e', 'ё' to 'e', 'о' to 'o',
        'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x',
    )

    /**
     * Applies a compatibility substitution. Structured MeshCore payloads are intentionally
     * left untouched, matching meshcore-open's compression exclusions.
     */
    fun encode(text: String, mode: TextCompressionMode): String {
        if (mode == TextCompressionMode.Off || text.isEmpty()) return text
        val trimmed = text.trimStart()
        if (trimmed.startsWith("g:") || trimmed.startsWith("m:") || trimmed.startsWith("V1|")) return text
        val map = if (mode == TextCompressionMode.MeshCoreOpen) meshCoreOpenMap else meshifyMap
        return buildString(text.length) { text.forEach { append(map[it] ?: it) } }
    }
}
