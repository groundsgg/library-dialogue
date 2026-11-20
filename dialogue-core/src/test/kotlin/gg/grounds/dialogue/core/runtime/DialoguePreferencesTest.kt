package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DialoguePreferencesTest {
    @Test
    fun completedPreferenceUpdateAlreadyPublishesNewSnapshot() {
        val rig = TestRig()
        val handle = rig.start()
        val before = handle.snapshot().renderRevision()
        val observed =
            handle
                .updatePreferences(
                    PresentationPreferences("npc", Duration.ofMillis(35), false, emptyMap())
                )
                .thenApply { handle.snapshot().renderRevision() }
        rig.scheduler.drain()
        assertTrue(TestRig.done(observed) > before)
    }

    @Test
    fun unknownTimedOutHandoffStopsInsteadOfRestoringAnUncertainSurface() {
        val rig = TestRig()
        val renderer = DialogueRenderer {
            CompletableFuture.completedFuture(
                object : DialogueRenderer.PresentationHandle {
                    override fun replace(view: DialogueView): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)

                    override fun reconfigure(
                        preferences: PresentationPreferences
                    ): CompletionStage<Void> = CompletableFuture()

                    override fun closeAsync(): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)
                }
            )
        }
        rig.bindings =
            HostBindings(
                "slow",
                emptyMap(),
                emptyMap(),
                null,
                rig.bindings.texts(),
                renderer,
                null,
                emptySet(),
            )
        val handle = rig.start()
        val future =
            handle.updatePreferences(
                PresentationPreferences("npc", Duration.ofMillis(35), false, emptyMap())
            )
        rig.scheduler.drain()
        rig.scheduler.advance(Duration.ofSeconds(5))
        assertTrue(future.toCompletableFuture().isCompletedExceptionally)
        assertEquals(DialogueView.State.CLOSED, handle.snapshot().state())
        assertEquals(DialogueEndReason.FAILED, TestRig.done(handle.completion()).reason())
    }
}
