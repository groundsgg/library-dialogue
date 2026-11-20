package gg.grounds.dialogue.core.definition

import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DialogueDefinitionTest {
    @Test
    fun builderAndMutationIsolation() {
        val choices =
            arrayListOf(
                Choice("ask", "question", "end", false, null),
                Choice("leave", "bye", "end", true, null),
            )
        val message = Message("mechanic", "hello", null, choices, null)
        val definition =
            DialogueBuilder.dialogue("demo:mechanic")
                .entry("start")
                .node("start", message)
                .node("end", End("completed"))
                .build()
        choices.clear()
        assertEquals(
            listOf("ask", "leave"),
            (definition.nodes()["start"] as Message).choices().map { it.id },
        )
        assertThrows<UnsupportedOperationException> { (message.choices() as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (definition.nodes() as MutableMap).clear() }
    }

    @Test
    fun duplicateNodesNeverOverwriteContent() {
        val builder = DialogueBuilder.dialogue("demo:test").node("end", End("completed"))
        assertThrows<IllegalArgumentException> { builder.node("end", End("cancelled")) }
    }

    @Test
    fun messageHasExactlyOneContinuation() {
        assertThrows<IllegalArgumentException> { Message("npc", "line", null, emptyList(), null) }
        assertThrows<IllegalArgumentException> {
            Message("npc", "line", null, listOf(Choice("c", "c", "end", false, null)), "end")
        }
    }
}
