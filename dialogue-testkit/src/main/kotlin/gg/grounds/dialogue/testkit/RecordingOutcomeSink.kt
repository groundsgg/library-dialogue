package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.port.OutcomeSink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Acknowledges and records events without applying gameplay side effects. */
class RecordingOutcomeSink : OutcomeSink {
    private val events = ArrayList<OutcomeSink.OutcomeEvent>()

    @Synchronized
    override fun deliver(
        event: OutcomeSink.OutcomeEvent
    ): CompletionStage<OutcomeSink.OutcomeResult> {
        events.add(event)
        return CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED)
    }

    @Synchronized fun events(): List<OutcomeSink.OutcomeEvent> = java.util.List.copyOf(events)
}
