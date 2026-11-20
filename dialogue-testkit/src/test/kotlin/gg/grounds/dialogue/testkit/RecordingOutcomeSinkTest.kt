package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery
import gg.grounds.dialogue.core.port.OutcomeSink.OutcomeEvent
import gg.grounds.dialogue.core.port.OutcomeSink.OutcomeResult
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RecordingOutcomeSinkTest {
    @Test
    fun recordsExactEventAndAcknowledgesWithoutMutatingEarlierSnapshot() {
        val sink = RecordingOutcomeSink()
        val event =
            OutcomeEvent(
                UUID.randomUUID(),
                "test",
                Delivery.LOCAL,
                "a",
                1,
                UUID.randomUUID(),
                2,
                "hash",
            )
        assertEquals(OutcomeResult.ACKNOWLEDGED, RuntimeFixture.done(sink.deliver(event)))
        val before = sink.events()
        RuntimeFixture.done(sink.deliver(event))
        assertEquals(listOf(event), before)
        assertEquals(2, sink.events().size)
        assertThrows<UnsupportedOperationException> { (before as MutableList).clear() }
    }
}
