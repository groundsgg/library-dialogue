package gg.grounds.dialogue.json

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.Capabilities
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.core.validation.ValidationReport
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewFormatRegressionsTest {
    @Test
    fun i11NumericNormalizationKeepsStrictLexicalGrammar() {
        listOf(
                "01",
                "+1",
                "1.",
                ".1",
                "1e",
                "1e+",
                "NaN",
                "Infinity",
                "1true",
                "--1",
                "1e309",
                "1".repeat(441),
            )
            .forEach { token ->
                assertFalse(
                    DialogueJsonCodecTest.decode(
                            DialogueJsonCodecTest.SOURCE.replace(
                                "\"schemaVersion\":1",
                                "\"schemaVersion\":$token",
                            )
                        )
                        .isValid(),
                    token,
                )
            }
    }

    @Test
    fun i10ExplicitNextAndEmptyChoicesIsNotNormalizedIntoAValidMessage() {
        assertFalse(
            DialogueJsonCodecTest.decode(
                    DialogueJsonCodecTest.SOURCE.replace(
                        "\"next\":\"end\"",
                        "\"next\":\"end\",\"choices\":[]",
                    )
                )
                .isValid()
        )
    }

    @Test
    fun i10UnreferencedHostDeclarationsAndOptionalIdsAreValidated() {
        val codec = DialogueJsonCodec()
        listOf(
                DialogueJsonCodecTest.HOST.replace("\"v1\"", "\"\""),
                DialogueJsonCodecTest.HOST.replace(
                    "\"speakers\":[\"npc\"]",
                    "\"speakers\":[\"npc\",\"bad speaker\"]",
                ),
                DialogueJsonCodecTest.HOST.replace(
                    "\"conditions\":{}",
                    "\"conditions\":{\"unused\":{\"arguments\":{\"x\":{\"type\":\"TEXT\"}}}}",
                ),
            )
            .forEach { host ->
                assertFalse(
                    codec
                        .decode(
                            DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.SOURCE),
                            mapOf("en" to DialogueJsonCodecTest.bytes("{\"line\":\"Hi\"}")),
                            DialogueJsonCodecTest.bytes(host),
                        )
                        .isValid(),
                    host,
                )
            }
        listOf("bad capability", "\ud800").forEach { optional ->
            val definition =
                DialogueBuilder.dialogue("review:ids")
                    .capabilities(Capabilities(emptySet(), setOf(optional)))
                    .node("start", Message("npc", "line", null, emptyList(), "end"))
                    .node("end", End("completed"))
                    .build()
            val report =
                assertDoesNotThrow<ValidationReport> {
                    DialogueValidator()
                        .validate(
                            definition,
                            TextCatalog(mapOf("en" to mapOf("line" to "Hi"))),
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
                }
            assertFalse(report.isValid())
        }
    }

    @Test
    fun i10VersionLimitsCountCodepoints() {
        val valid = "😀".repeat(80)
        assertTrue(
            DialogueJsonCodecTest.decode(
                    DialogueJsonCodecTest.SOURCE.replace(
                        "\"contentVersion\":\"1.0.0\"",
                        "\"contentVersion\":\"$valid\"",
                    )
                )
                .isValid()
        )
        assertFalse(
            DialogueJsonCodecTest.decode(
                    DialogueJsonCodecTest.SOURCE.replace(
                        "\"contentVersion\":\"1.0.0\"",
                        "\"contentVersion\":\"${"😀".repeat(129)}\"",
                    )
                )
                .isValid()
        )
    }

    @Test
    fun i11CanonicalNumericSpellingsRoundTripAcrossExponentBounds() {
        val codec = DialogueJsonCodec()
        val host =
            """{"schemaVersion":1,"generation":"v1","speakers":["npc"],"conditions":{},"actions":{"review:number":{"arguments":{"x":{"type":"number","required":true}},"durable":false}},"gestures":[],"capabilities":[],"durableOutcomes":false}
"""
        val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hi")))
        val catalog =
            HostCatalog(
                "v1",
                setOf("npc"),
                emptyMap(),
                mapOf(
                    "review:number" to
                        HostCatalog.ProviderSpec(
                            mapOf(
                                "x" to
                                    HostCatalog.ArgumentSpec(
                                        HostCatalog.Kind.NUMBER,
                                        true,
                                        false,
                                    )
                            ),
                            false,
                        )
                ),
                emptySet(),
                emptySet(),
                false,
            )
        listOf("1e128", "1e308", "1e-308", "1.2300e308", "1.2300").forEach { number ->
            val definition =
                DialogueBuilder.dialogue("review:numbers")
                    .node(
                        "start",
                        Action(
                            "review:number",
                            mapOf("x" to ArgumentValue.Number(BigDecimal(number))),
                            "end",
                            "end",
                            "end",
                            "end",
                        ),
                    )
                    .node("end", End("completed"))
                    .build()
            val validated =
                DialogueValidator()
                    .validate(definition, texts, catalog, ValidationLimits.defaults())
            assertTrue(validated.isValid(), number)
            val decoded =
                codec.decode(
                    codec.encode(definition),
                    mapOf("en" to DialogueJsonCodecTest.bytes("{\"line\":\"Hi\"}")),
                    DialogueJsonCodecTest.bytes(host),
                )
            assertTrue(decoded.isValid(), "$number ${decoded.problems()}")
            assertEquals(
                checkNotNull(validated.validated()).contentPin(),
                decoded.validated()!!.contentPin(),
            )
        }
    }
}
