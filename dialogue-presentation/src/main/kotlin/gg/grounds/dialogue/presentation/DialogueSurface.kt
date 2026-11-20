package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** An open surface is hidden until its first frame. Close prevents all later mutations. */
fun interface DialogueSurface {
    @JvmRecord
    data class SurfaceContext(
        val sessionId: UUID,
        val audienceId: String,
        val hostGeneration: Long,
        val surfaceKey: String,
        val profilePin: String,
        val cancellation: CancellationToken,
        val generation: Long,
        val deadlineNanos: Long,
        val preferences: PresentationPreferences,
        val cleanupBudget: Duration,
    ) {
        init {
            require(!cleanupBudget.isNegative && !cleanupBudget.isZero) { "cleanupBudget" }
        }

        constructor(
            sessionId: UUID,
            audienceId: String,
            hostGeneration: Long,
            surfaceKey: String,
            profilePin: String,
            cancellation: CancellationToken,
            generation: Long,
            deadlineNanos: Long,
            preferences: PresentationPreferences,
        ) : this(
            sessionId,
            audienceId,
            hostGeneration,
            surfaceKey,
            profilePin,
            cancellation,
            generation,
            deadlineNanos,
            preferences,
            Duration.ofSeconds(30),
        )

        constructor(
            sessionId: UUID,
            audienceId: String,
            hostGeneration: Long,
            surfaceKey: String,
            profilePin: String,
            cancellation: CancellationToken,
            generation: Long,
            deadlineNanos: Long,
        ) : this(
            sessionId,
            audienceId,
            hostGeneration,
            surfaceKey,
            profilePin,
            cancellation,
            generation,
            deadlineNanos,
            PresentationPreferences.defaults(),
        )
    }

    interface SurfaceHandle : OwnedResource {
        fun replace(frame: WidgetFrame): CompletionStage<Void>

        fun configure(preferences: PresentationPreferences): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
    }

    fun open(context: SurfaceContext): CompletionStage<SurfaceHandle>
}
