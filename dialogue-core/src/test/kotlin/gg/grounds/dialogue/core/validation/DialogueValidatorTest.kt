package gg.grounds.dialogue.core.validation

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.definition.DialogueDefinition.Branch
import gg.grounds.dialogue.core.definition.DialogueDefinition.Case
import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.Condition
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.text.TextCatalog
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

internal class DialogueValidatorTest {
    private val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello", "c" to "Continue")))
    private val catalogue =
        HostCatalog(
            "v1",
            setOf("npc"),
            mapOf("demo:ok" to HostCatalog.ProviderSpec(emptyMap(), false)),
            emptyMap(),
            emptySet(),
            emptySet(),
            true,
        )

    private fun validate(definition: DialogueDefinition): ValidationReport =
        DialogueValidator().validate(definition, texts, catalogue, ValidationLimits.defaults())

    private fun base() = DialogueBuilder.dialogue("demo:test").node("end", End("completed"))

    private fun condition() = Condition("demo:ok", emptyMap(), false)

    @Test
    fun conditionalOnlyChoicesRejected() {
        val conditional = Choice("c", "c", "end", true, condition())
        val bad =
            validate(
                base()
                    .node("start", Message("npc", "line", null, listOf(conditional), null))
                    .build()
            )
        assertTrue(bad.problems().any { it.code == "CHOICE_ALWAYS_AVAILABLE_REQUIRED" })
        assertFalse(bad.isValid())
        val choices = listOf(conditional, Choice("leave", "c", "end", false, null))
        assertTrue(
            validate(base().node("start", Message("npc", "line", null, choices, null)).build())
                .isValid()
        )
    }

    @Test
    fun automaticCyclesRejectedButQuestionLoopAllowed() {
        val cycle =
            base()
                .node("start", Branch(listOf(Case(condition(), "end")), "loop", "end"))
                .node("loop", Branch(emptyList(), "start", "end"))
                .build()
        assertTrue(validate(cycle).problems().any { it.code == "AUTOMATIC_CYCLE" })
        val choices =
            listOf(
                Choice("again", "c", "start", false, null),
                Choice("leave", "c", "end", false, null),
            )
        assertTrue(
            validate(base().node("start", Message("npc", "line", null, choices, null)).build())
                .isValid()
        )
    }

    @Test
    fun graphAndCatalogFailuresAreLocated() {
        val choices =
            listOf(
                Choice("same", "c", "gone", false, null),
                Choice("same", "c", "end", false, null),
            )
        val definition =
            base().node("start", Message("unknown", "missing", null, choices, null)).build()
        val problems = validate(definition).problems()
        assertTrue(
            problems
                .map { it.code }
                .containsAll(
                    listOf(
                        "SPEAKER_UNKNOWN",
                        "TEXT_MISSING",
                        "REFERENCE_UNKNOWN",
                        "CHOICE_DUPLICATE",
                    )
                )
        )
        assertTrue(problems.all { it.path != null && it.message.isNotBlank() })
    }

    @Test
    fun boundariesAreAppliedToDefinitions() {
        val builder = base().node("start", Message("npc", "line", null, emptyList(), "end"))
        repeat(1022) { builder.node("n" + it, End("completed")) }
        assertTrue(validate(builder.build()).isValid())
        assertFalse(validate(builder.node("extra", End("completed")).build()).isValid())
        val choices = MutableList(64) { Choice("c" + it, "c", "end", false, null) }
        assertTrue(
            validate(base().node("start", Message("npc", "line", null, choices, null)).build())
                .isValid()
        )
        choices.add(Choice("extra", "c", "end", false, null))
        assertFalse(
            validate(base().node("start", Message("npc", "line", null, choices, null)).build())
                .isValid()
        )
    }

    @Test
    fun missingEndAndInvalidArgumentsRejected() {
        val loop =
            DialogueBuilder.dialogue("demo:loop")
                .node("start", Message("npc", "line", null, emptyList(), "start"))
                .build()
        assertFalse(validate(loop).isValid())
        val condition = Condition("demo:ok", mapOf("injected" to ArgumentValue.of("/op @s")), false)
        val definition =
            base().node("start", Branch(listOf(Case(condition, "end")), "end", "end")).build()
        assertTrue(validate(definition).problems().any { it.code == "ARGUMENT_UNKNOWN" })
    }

    @Test
    fun malformedUnusedTextKeyIsAReportNotAnException() {
        val definition =
            base().node("start", Message("npc", "line", null, emptyList(), "end")).build()
        val bad = TextCatalog(mapOf("en" to mapOf("line" to "Hello", "\ud800" to "bad")))
        val report = assertDoesNotThrow {
            DialogueValidator().validate(definition, bad, catalogue, ValidationLimits.defaults())
        }
        assertFalse(report.isValid())
    }
}
