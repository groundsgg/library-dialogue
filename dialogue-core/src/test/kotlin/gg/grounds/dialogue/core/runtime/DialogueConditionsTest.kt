package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.port.ConditionProvider
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.text.ResolvedText
import gg.grounds.dialogue.core.validation.HostCatalog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class DialogueConditionsTest {
    @Test
    fun conditionChangedBeforeConfirm() {
        val condition = Condition("demo:ok", emptyMap(), false)
        val definition =
            DialogueBuilder.dialogue("demo:choices")
                .node(
                    "start",
                    Message(
                        "npc",
                        "hello",
                        null,
                        listOf(
                            Choice("offer", "c", "end", true, condition),
                            Choice("leave", "bye", "end", false, null),
                        ),
                        null,
                    ),
                )
                .node("end", End("completed"))
                .build()
        val rig =
            TestRig(
                definition,
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    mapOf("demo:ok" to HostCatalog.ProviderSpec(emptyMap(), false)),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    false,
                ),
            )
        val available = AtomicReference(ConditionProvider.ConditionResult.TRUE)
        rig.bindings =
            HostBindings(
                "conditions",
                mapOf(
                    "demo:ok" to
                        ConditionProvider {
                            CompletableFuture.completedFuture(available.get())
                        }
                ),
                emptyMap(),
                null,
                rig.bindings.texts(),
                rig.bindings.renderer(),
                null,
                emptySet(),
            )
        val handle = rig.start()
        assertEquals("offer", handle.snapshot().selectedChoiceId())
        handle.submit(DialogueInputTest.confirm(handle, 1))
        rig.scheduler.drain()
        available.set(ConditionProvider.ConditionResult.FALSE)
        val result = handle.submit(DialogueInputTest.confirm(handle, 2))
        rig.scheduler.drain()
        assertEquals(InputResult.STALE, TestRig.done(result))
        assertEquals("start", handle.snapshot().nodeId())
        assertEquals("leave", handle.snapshot().selectedChoiceId())
        assertEquals(listOf("leave"), handle.snapshot().choices().map { it.id })
    }

    @Test
    fun unavailableIncludingNegationIsNotFalse() {
        val condition = Condition("demo:unknown", emptyMap(), true)
        val definition =
            DialogueBuilder.dialogue("demo:branch")
                .node("start", Branch(listOf(Case(condition, "wrong")), "wrong", "safe"))
                .node("wrong", Message("npc", "hello", null, emptyList(), "end"))
                .node("safe", Message("npc", "bye", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val rig =
            TestRig(
                definition,
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    mapOf("demo:unknown" to HostCatalog.ProviderSpec(emptyMap(), false)),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    false,
                ),
            )
        assertEquals("safe", rig.start().snapshot().nodeId())
    }

    @Test
    fun unchangedChoiceConfirmUsesResolvedLabels() {
        val definition =
            DialogueBuilder.dialogue("demo:resolved")
                .node(
                    "start",
                    Message(
                        "npc",
                        "hello",
                        null,
                        listOf(Choice("leave", "c", "end", true, null)),
                        null,
                    ),
                )
                .node("end", End("completed"))
                .build()
        val rig =
            TestRig(
                definition,
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    false,
                ),
            )
        val resolver = rig.bindings.texts()
        rig.bindings =
            HostBindings(
                "translated",
                emptyMap(),
                emptyMap(),
                null,
                { request ->
                    resolver.resolve(request).thenApply { text ->
                        if (request.key() == "c") ResolvedText("Resolved answer", text.localeTag)
                        else text
                    }
                },
                rig.bindings.renderer(),
                null,
                emptySet(),
            )
        val handle = rig.start()
        handle.submit(DialogueInputTest.confirm(handle, 1))
        rig.scheduler.drain()
        val result = handle.submit(DialogueInputTest.confirm(handle, 2))
        rig.scheduler.drain()
        assertEquals(InputResult.ACCEPTED, TestRig.done(result))
        assertEquals(DialogueEndReason.COMPLETED, TestRig.done(handle.completion()).reason())
    }

    @Test
    fun automaticStepGuardStopsLongAcyclicChain() {
        val builder =
            DialogueBuilder.dialogue("demo:chain").entry("b0").node("end", End("completed"))
        for (index in 0 until 33) builder.node(
            "b$index",
            Branch(emptyList(), if (index == 32) "end" else "b${index + 1}", "end"),
        )
        val rig =
            TestRig(
                builder.build(),
                HostCatalog(
                    "v1",
                    emptySet(),
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    false,
                ),
            )
        val result = rig.runtime.start(rig.request("chain"))
        rig.scheduler.drain()
        assertEquals(
            "AUTOMATIC_STEP_LIMIT",
            (TestRig.done(result) as DialogueStartResult.Rejected).code(),
        )
    }
}
