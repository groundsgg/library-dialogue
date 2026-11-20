package gg.grounds.dialogue.json

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidationLimits
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogueJsonCodecTest {
    @Test
    fun javaAndJsonShareDefinitionAndPin() {
        val result = decode(SOURCE)
        assertTrue(result.isValid(), result.problems().toString())
        val definition =
            DialogueBuilder.dialogue("demo:test")
                .node("start", Message("npc", "line", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val validated =
            DialogueValidator()
                .validate(
                    definition,
                    TextCatalog(mapOf("en" to mapOf("line" to "Hello"))),
                    HostCatalog(
                        "v1",
                        setOf("npc"),
                        emptyMap(),
                        emptyMap(),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                    ValidationLimits.defaults(),
                )
        assertEquals(definition, result.validated()!!.definition())
        assertEquals(
            checkNotNull(validated.validated()).contentPin(),
            result.validated()!!.contentPin(),
        )
        assertEquals(
            result.validated()!!.contentPin(),
            decode(String(DialogueJsonCodec().encode(definition), StandardCharsets.UTF_8))
                .validated()!!
                .contentPin(),
        )
    }

    @Test
    fun strictBoundaryFailures() {
        listOf(
                SOURCE.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                SOURCE.replace("\"entry\":", "\"entry\":\"x\",\"entry\":"),
                "$SOURCE{}",
                SOURCE.replace("\"id\":", "\"unknown\":1,\"id\":"),
                "\ufeff$SOURCE",
                SOURCE.replace("Hello", "bad"),
            )
            .forEach { source ->
                if (source != SOURCE) assertFalse(decode(source).isValid(), source)
            }
        val codec = DialogueJsonCodec()
        assertFalse(
            codec.decode(byteArrayOf(0xc3.toByte(), 0x28), emptyMap(), bytes(HOST)).isValid()
        )
        assertFalse(decode(SOURCE.replace("demo:test", "\\ud800")).isValid())
        assertFalse(
            decode(
                    SOURCE.replace(
                        "\"id\":",
                        "\"nested\":" + "[".repeat(33) + "0" + "]".repeat(33) + ",\"id\":",
                    )
                )
                .isValid()
        )
        assertFalse(codec.decode(ByteArray(1_048_577), emptyMap(), bytes(HOST)).isValid())
    }

    @Test
    fun malformedJsonDiagnosticsKeepFileAndPath() {
        val result = decode(SOURCE.replace("\"end\":{", "\"end\":{\"reason\":\"completed\","))
        assertFalse(result.isValid())
        assertNotNull(result.problems().first().path)
        assertEquals("dialogue.json", result.problems().first().file)
    }

    companion object {
        const val SOURCE =
            """{"schemaVersion":1,"id":"demo:test","contentVersion":"1.0.0","entry":"start","locales":{"default":"en","supported":["en"]},"nodes":{"start":{"type":"message","speaker":"npc","text":"line","next":"end"},"end":{"type":"end","reason":"completed"}}}
"""
        const val HOST =
            """{"schemaVersion":1,"generation":"v1","speakers":["npc"],"conditions":{},"actions":{},"gestures":[],"capabilities":[],"durableOutcomes":false}
"""

        fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

        fun decode(source: String): DialogueJsonCodec.DecodeResult =
            DialogueJsonCodec()
                .decode(
                    bytes(source),
                    mapOf("en" to bytes("{\"line\":\"Hello\"}")),
                    bytes(HOST),
                )
    }
}
