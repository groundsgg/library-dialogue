package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueFailure
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage

fun interface DialogueRenderer {
    /** A rejected reconfiguration has left the previous presentation intact. */
    open class ReconfigurationRejected : DialogueFailure {
        private val activePreferences: PresentationPreferences?

        constructor(code: String, cause: Throwable?) : this(code, cause, null)

        constructor(
            code: String,
            cause: Throwable?,
            activePreferences: PresentationPreferences?,
        ) : super(code, cause) {
            this.activePreferences = activePreferences
        }

        open fun activePreferences(): PresentationPreferences? = activePreferences
    }

    @JvmRecord
    data class PresentationRequest(
        val sessionId: UUID,
        val audienceId: String,
        val hostGeneration: Long,
        val preferences: PresentationPreferences,
        val cancellation: CancellationToken,
        val deadlineNanos: Long,
        val budgets: RuntimeOptions.Budgets,
    ) {
        constructor(
            sessionId: UUID,
            audienceId: String,
            hostGeneration: Long,
            preferences: PresentationPreferences,
            cancellation: CancellationToken,
            deadlineNanos: Long,
        ) : this(
            sessionId,
            audienceId,
            hostGeneration,
            preferences,
            cancellation,
            deadlineNanos,
            RuntimeOptions.Budgets(
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
            ),
        )
    }

    interface PresentationHandle : OwnedResource {
        fun replace(view: DialogueView): CompletionStage<Void>

        fun reconfigure(preferences: PresentationPreferences): CompletionStage<Void>
    }

    fun prepare(request: PresentationRequest): CompletionStage<PresentationHandle>
}
