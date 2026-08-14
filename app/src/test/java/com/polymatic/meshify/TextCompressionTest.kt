package com.polymatic.meshify

import com.polymatic.meshify.mesh.TextCompression
import com.polymatic.meshify.mesh.TextCompressionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCompressionTest {
    @Test
    fun meshifyModeReplacesOnlyTheCompactLookAlikeSet() {
        assertEquals("ABCE HKMOPTX ac eopxy н", TextCompression.encode("АВСЕ НКМОРТХ аc еoрxу н", TextCompressionMode.SimilarLatin))
    }

    @Test
    fun referenceModeMatchesMeshCoreOpenExtraMappings() {
        assertEquals("A B E E 3 K M H O P C T X b a e e o p c y x", TextCompression.encode("А В Е Ё З К М Н О Р С Т Х Ь а е ё о р с у х", TextCompressionMode.MeshCoreOpen))
    }

    @Test
    fun structuredPayloadsAreNotTransformed() {
        assertEquals("m:АВС", TextCompression.encode("m:АВС", TextCompressionMode.MeshCoreOpen))
    }

    @Test
    fun substitutionsActuallyReduceUtf8PayloadSize() {
        val original = "АВСЕ НКМОРТХ асеорху"
        val encoded = TextCompression.encode(original, TextCompressionMode.SimilarLatin)
        assertTrue(encoded.encodeToByteArray().size < original.encodeToByteArray().size)
    }
}
