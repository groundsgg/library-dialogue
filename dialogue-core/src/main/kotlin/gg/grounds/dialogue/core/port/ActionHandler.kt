package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.definition.ArgumentValue
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Durable handlers register and deduplicate before dispatch. */
interface ActionHandler {
    @JvmRecord
    data class ActionRef(
        val requestId: UUID,
        val payloadHash: String?,
        val audienceId: String?,
        val hostGeneration: Long,
        val sessionId: UUID?,
        val nodeVisitId: Long,
    )

    @JvmSuppressWildcards
    class ActionRequest(
        private val reference: ActionRef?,
        private val key: String?,
        arguments: Map<String, ArgumentValue>,
        private val durable: Boolean,
    ) {
        private val arguments = java.util.Map.copyOf(arguments)

        fun reference(): ActionRef? = reference

        fun key(): String? = key

        fun arguments(): Map<String, ArgumentValue> = arguments

        fun durable(): Boolean = durable

        override fun equals(other: Any?): Boolean =
            other is ActionRequest &&
                reference == other.reference &&
                key == other.key &&
                arguments == other.arguments &&
                durable == other.durable

        override fun hashCode(): Int {
            var hash = (reference?.hashCode() ?: 0)
            hash = 31 * hash + (key?.hashCode() ?: 0)
            hash = 31 * hash + arguments.hashCode()
            hash = 31 * hash + durable.hashCode()
            return hash
        }

        override fun toString(): String =
            "ActionRequest[reference=$reference, key=$key, arguments=$arguments, durable=$durable]"
    }

    enum class ActionResult {
        SUCCESS,
        REJECTED,
        UNAVAILABLE,
        UNCERTAIN,
    }

    enum class ActionStatus {
        PENDING,
        SUCCEEDED,
        REJECTED,
        UNAVAILABLE,
    }

    fun execute(request: ActionRequest): CompletionStage<ActionResult>

    fun reconcile(reference: ActionRef): CompletionStage<ActionStatus>
}
