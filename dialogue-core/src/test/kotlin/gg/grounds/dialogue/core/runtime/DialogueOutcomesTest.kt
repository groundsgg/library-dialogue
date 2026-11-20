package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.definition.DialogueDefinition.Outcome
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.port.OutcomeSink
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.HostCatalog
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DialogueOutcomesTest {
    @Test
    fun outcomeIdentityAndExplicitConfirmation() {
        val definition =
            DialogueBuilder.dialogue("demo:outcomes")
                .locales("en", "en", "de")
                .node(
                    "start",
                    Message(
                        "npc",
                        "hello",
                        null,
                        listOf(
                            Choice("again", "c", "milestone", true, null),
                            Choice("leave", "bye", "end", false, null),
                        ),
                        null,
                    ),
                )
                .node("milestone", Outcome("demo:explained", Delivery.LOCAL, "start", "end", "end"))
                .node("end", End("completed"))
                .build()
        val rig = TestRig(definition, catalogue(false))
        val events = ArrayList<OutcomeSink.OutcomeEvent>()
        rig.bindings =
            HostBindings(
                "events",
                emptyMap(),
                emptyMap(),
                {
                    events.add(it)
                    CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED)
                },
                rig.bindings.texts(),
                rig.bindings.renderer(),
                null,
                emptySet(),
            )
        val handle = rig.start()
        assertTrue(events.isEmpty())
        handle.submit(DialogueInputTest.confirm(handle, 1))
        rig.scheduler.drain()
        assertTrue(events.isEmpty())
        handle.submit(DialogueInputTest.confirm(handle, 2))
        rig.scheduler.drain()
        assertEquals(1, events.size)
        handle.refreshLocale(LocaleContext("de", 1, "en"))
        rig.scheduler.drain()
        assertEquals(1, events.size)
        handle.submit(DialogueInputTest.confirm(handle, 3))
        rig.scheduler.drain()
        assertEquals(2, events.size)
        assertNotEquals(events[0].eventId, events[1].eventId)
    }

    @Test
    fun durableOutcomeRequiresDeclaredHostCapabilityAndAcknowledgement() {
        val definition =
            DialogueBuilder.dialogue("demo:durable")
                .node("start", Message("npc", "hello", null, emptyList(), "milestone"))
                .node(
                    "milestone",
                    Outcome("demo:stored", Delivery.DURABLE, "ack", "unavailable", "uncertain"),
                )
                .node("ack", Message("npc", "bye", null, emptyList(), "end"))
                .node("unavailable", Message("npc", "hello", null, emptyList(), "end"))
                .node("uncertain", Message("npc", "hello", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        for (declared in listOf(false, true)) {
            val rig = TestRig(definition, catalogue(declared))
            val log = HashMap<UUID?, OutcomeSink.OutcomeEvent>()
            val ack = CompletableFuture<OutcomeSink.OutcomeResult>()
            rig.bindings =
                HostBindings(
                    "durable",
                    emptyMap(),
                    emptyMap(),
                    { event ->
                        log.putIfAbsent(event.eventId, event)
                        ack
                    },
                    rig.bindings.texts(),
                    rig.bindings.renderer(),
                    null,
                    if (declared) setOf("outcome.durable") else emptySet(),
                )
            val handle = rig.start()
            handle.submit(DialogueInputTest.confirm(handle, 1))
            rig.scheduler.drain()
            handle.submit(DialogueInputTest.confirm(handle, 2))
            rig.scheduler.drain()
            if (!declared) {
                assertEquals("unavailable", handle.snapshot().nodeId())
                assertTrue(log.isEmpty())
            } else {
                assertEquals(1, log.size)
                assertEquals("milestone", handle.snapshot().nodeId())
                ack.complete(OutcomeSink.OutcomeResult.ACKNOWLEDGED)
                rig.scheduler.drain()
                assertEquals("ack", handle.snapshot().nodeId())
            }
        }
    }

    private fun catalogue(durable: Boolean) =
        HostCatalog("v1", setOf("npc"), emptyMap(), emptyMap(), emptySet(), emptySet(), durable)
}
