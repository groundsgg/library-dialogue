package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.runtime.CancellationToken
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

fun interface ActorPort {
    class ActorRequest(
        private val sessionId: UUID?,
        private val audienceId: String?,
        private val hostGeneration: Long,
        speakerIds: Set<String>,
        private val cancellation: CancellationToken?,
        private val deadlineNanos: Long,
    ) {
        private val speakerIds = java.util.Set.copyOf(speakerIds)

        fun sessionId(): UUID? = sessionId

        fun audienceId(): String? = audienceId

        fun hostGeneration(): Long = hostGeneration

        fun speakerIds(): Set<String> = speakerIds

        fun cancellation(): CancellationToken? = cancellation

        fun deadlineNanos(): Long = deadlineNanos

        override fun equals(other: Any?): Boolean =
            other is ActorRequest &&
                sessionId == other.sessionId &&
                audienceId == other.audienceId &&
                hostGeneration == other.hostGeneration &&
                speakerIds == other.speakerIds &&
                cancellation == other.cancellation &&
                deadlineNanos == other.deadlineNanos

        override fun hashCode(): Int {
            var hash = (sessionId?.hashCode() ?: 0)
            hash = 31 * hash + (audienceId?.hashCode() ?: 0)
            hash = 31 * hash + hostGeneration.hashCode()
            hash = 31 * hash + speakerIds.hashCode()
            hash = 31 * hash + (cancellation?.hashCode() ?: 0)
            hash = 31 * hash + deadlineNanos.hashCode()
            return hash
        }

        override fun toString(): String =
            "ActorRequest[sessionId=$sessionId, audienceId=$audienceId, hostGeneration=$hostGeneration, speakerIds=$speakerIds, cancellation=$cancellation, deadlineNanos=$deadlineNanos]"
    }

    @JvmRecord
    data class ActorCue(
        val speaker: String?,
        val gesture: String?,
        val nodeVisitId: Long,
        val cueId: String?,
    )

    interface ActorHandle : OwnedResource {
        /** Publish the prepared personal actor once before any node effects or cues. */
        fun activate(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

        fun cue(cue: ActorCue): CompletionStage<Void>
    }

    fun prepare(request: ActorRequest): CompletionStage<ActorHandle>
}
