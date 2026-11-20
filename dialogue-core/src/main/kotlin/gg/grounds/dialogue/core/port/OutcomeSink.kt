package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery
import java.util.UUID
import java.util.concurrent.CompletionStage

fun interface OutcomeSink {
    @JvmRecord
    data class OutcomeEvent(
        val eventId: UUID?,
        val milestone: String?,
        val delivery: Delivery?,
        val audienceId: String?,
        val hostGeneration: Long,
        val sessionId: UUID?,
        val nodeVisitId: Long,
        val payloadHash: String?,
    )

    enum class OutcomeResult {
        ACKNOWLEDGED,
        UNAVAILABLE,
        UNCERTAIN,
    }

    fun deliver(event: OutcomeEvent): CompletionStage<OutcomeResult>
}
