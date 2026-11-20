package gg.grounds.dialogue.core.validation

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.text.TextCatalog
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

internal class ContentFingerprintTest {
    @Test
    fun canonicalHashVectors() {
        val original =
            ContentFingerprint.canonicalBytes(mapOf("b" to "é", "a" to BigDecimal("1.00")))
        assertEquals("{\"a\":1,\"b\":\"é\"}", original.decodeToString())
        assertArrayEquals(
            original,
            ContentFingerprint.canonicalBytes(linkedMapOf("a" to 1, "b" to "é")),
        )
        assertFalse(
            original.contentEquals(
                ContentFingerprint.canonicalBytes(mapOf("a" to 1, "b" to "e\u0301"))
            )
        )
        assertEquals("0", ContentFingerprint.canonicalBytes(BigDecimal("-0.000")).decodeToString())
    }

    @Test
    fun definitionAndTranslationManifestGolden() {
        val definition =
            DialogueBuilder.dialogue("demo:test")
                .node("start", Message("npc", "line", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello")))
        assertEquals(
            "7c23ff827c4f0e0fd214922cb3ddde3b609aedc1055896332646ff0eea54e67e",
            ContentFingerprint.pin(definition, texts).sha256,
        )
    }
}
