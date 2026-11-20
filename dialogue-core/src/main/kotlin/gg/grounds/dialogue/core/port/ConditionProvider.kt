package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.definition.ArgumentValue
import java.util.UUID
import java.util.concurrent.CompletionStage

fun interface ConditionProvider {
    @JvmSuppressWildcards
    class ConditionRequest(
        private val key: String?,
        arguments: Map<String, ArgumentValue>,
        private val audienceId: String?,
        private val hostGeneration: Long,
        private val sessionId: UUID?,
        private val nodeVisitId: Long,
    ) {
        private val arguments = java.util.Map.copyOf(arguments)

        fun key(): String? = key

        fun arguments(): Map<String, ArgumentValue> = arguments

        fun audienceId(): String? = audienceId

        fun hostGeneration(): Long = hostGeneration

        fun sessionId(): UUID? = sessionId

        fun nodeVisitId(): Long = nodeVisitId

        override fun equals(other: Any?): Boolean =
            other is ConditionRequest &&
                key == other.key &&
                arguments == other.arguments &&
                audienceId == other.audienceId &&
                hostGeneration == other.hostGeneration &&
                sessionId == other.sessionId &&
                nodeVisitId == other.nodeVisitId

        override fun hashCode(): Int {
            var hash = (key?.hashCode() ?: 0)
            hash = 31 * hash + arguments.hashCode()
            hash = 31 * hash + (audienceId?.hashCode() ?: 0)
            hash = 31 * hash + hostGeneration.hashCode()
            hash = 31 * hash + (sessionId?.hashCode() ?: 0)
            hash = 31 * hash + nodeVisitId.hashCode()
            return hash
        }

        override fun toString(): String =
            "ConditionRequest[key=$key, arguments=$arguments, audienceId=$audienceId, hostGeneration=$hostGeneration, sessionId=$sessionId, nodeVisitId=$nodeVisitId]"
    }

    enum class ConditionResult {
        TRUE,
        FALSE,
        UNAVAILABLE,
    }

    fun evaluate(request: ConditionRequest): CompletionStage<ConditionResult>
}
