package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueStart
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextResolver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LatePreparationTest {
    @Test
    fun lateActorPreparationIsClosedWithoutPublishingAfterCancellation() {
        val fixture = RuntimeFixture()
        val renderer = RecordingDialogueRenderer()
        val pending = CompletableFuture<ActorPort.ActorHandle>()
        var actions = 0
        var closes = 0
        val binding =
            HostBindings(
                "v1",
                emptyMap(),
                emptyMap(),
                null,
                TextResolver.catalogue(fixture.content.texts(), "en"),
                renderer,
                { pending },
                emptySet(),
            )
        val token = CancellationToken()
        val started =
            fixture.runtime.start(
                DialogueStart(
                    "late",
                    "a",
                    1,
                    fixture.content,
                    binding,
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences.defaults(),
                    token,
                    0,
                )
            )
        fixture.clock.runUntilIdle()
        token.cancel(DialogueEndReason.CANCELLED)
        fixture.clock.runUntilIdle()
        pending.complete(
            object : ActorPort.ActorHandle {
                override fun activate(): CompletionStage<Void> {
                    actions++
                    return CompletableFuture.completedFuture(null)
                }

                override fun cue(cue: ActorPort.ActorCue): CompletionStage<Void> {
                    actions++
                    return CompletableFuture.completedFuture(null)
                }

                override fun closeAsync(): CompletionStage<Void> {
                    closes++
                    return CompletableFuture.completedFuture(null)
                }
            }
        )
        fixture.clock.runUntilIdle()
        assertEquals(0, actions)
        assertEquals(1, closes)
        for (snapshot in renderer.snapshots()) {
            assertTrue(snapshot.closed())
            assertTrue(snapshot.views().isEmpty())
        }
        assertTrue(started.toCompletableFuture().isDone)
    }
}
