package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class DialogueLifecycleTest {
    internal open class Surface : DialogueRenderer.PresentationHandle {
        @JvmField var closes = 0
        @JvmField var fail = false
        @JvmField var onReplace: Runnable = Runnable {}

        override fun replace(view: DialogueView): CompletionStage<Void> {
            onReplace.run()
            return CompletableFuture.completedFuture(null)
        }

        override fun reconfigure(preferences: PresentationPreferences): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)

        override fun closeAsync(): CompletionStage<Void> {
            closes++
            return if (fail) CompletableFuture.failedFuture(IllegalStateException("cleanup failed"))
            else CompletableFuture.completedFuture(null)
        }
    }

    companion object {
        @JvmStatic
        fun renderer(rig: TestRig, renderer: DialogueRenderer, actors: ActorPort?) {
            rig.bindings =
                HostBindings(
                    "custom",
                    emptyMap(),
                    emptyMap(),
                    null,
                    rig.bindings.texts(),
                    renderer,
                    actors,
                    emptySet(),
                )
        }
    }

    @Test
    fun cancelDuringLatePrepare() {
        val rig = TestRig()
        val late = CompletableFuture<DialogueRenderer.PresentationHandle>()
        renderer(rig, DialogueRenderer { late }, null)
        val future = rig.runtime.start(rig.request("late"))
        rig.scheduler.drain()
        assertFalse(future.toCompletableFuture().isDone)
        rig.cancellation.cancel(DialogueEndReason.DISCONNECTED)
        rig.scheduler.drain()
        assertFalse(future.toCompletableFuture().isDone, "cleanup waits for registered acquisition")
        val surface = Surface()
        late.complete(surface)
        rig.scheduler.drain()
        assertEquals(1, surface.closes)
        assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(future))
    }

    @Test
    fun synchronousFailureAndReentrantCancellationDoNotHang() {
        val rig = TestRig()
        renderer(rig, DialogueRenderer { throw IllegalStateException("prepare") }, null)
        val future = rig.runtime.start(rig.request("sync"))
        rig.scheduler.drain()
        assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(future))
        val other = TestRig()
        val surface = Surface()
        surface.onReplace = Runnable { other.cancellation.cancel(DialogueEndReason.CANCELLED) }
        renderer(other, DialogueRenderer { CompletableFuture.completedFuture(surface) }, null)
        val start = other.runtime.start(other.request("reentrant"))
        other.scheduler.drain()
        assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(start))
        assertEquals(1, surface.closes)
    }

    @Test
    fun cleanupFailureStillClosesOtherResources() {
        val rig = TestRig()
        val surface = Surface()
        surface.fail = true
        val actorCloses = AtomicInteger()
        val actors = ActorPort {
            CompletableFuture.completedFuture(
                object : ActorPort.ActorHandle {
                    override fun cue(cue: ActorPort.ActorCue): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)

                    override fun closeAsync(): CompletionStage<Void> {
                        actorCloses.incrementAndGet()
                        return CompletableFuture.completedFuture(null)
                    }
                }
            )
        }
        renderer(rig, DialogueRenderer { CompletableFuture.completedFuture(surface) }, actors)
        val handle = rig.start()
        val end = handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
        assertTrue(end.toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, actorCloses.get())
        assertEquals(1, surface.closes)
    }

    @Test
    fun uncooperativeCleanupDeadlineKeepsLateOwnership() {
        val rig = TestRig()
        val late = CompletableFuture<DialogueRenderer.PresentationHandle>()
        renderer(rig, DialogueRenderer { late }, null)
        val future = rig.runtime.start(rig.request("never"))
        rig.scheduler.drain()
        rig.cancellation.cancel(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
        rig.scheduler.advance(Duration.ofSeconds(30))
        assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(future))
        val surface = Surface()
        late.complete(surface)
        rig.scheduler.drain()
        assertEquals(1, surface.closes)
    }

    @Test
    fun optionalActorFailureAndTimeoutDoNotRejectDialogue() {
        val rig = TestRig()
        renderer(
            rig,
            DialogueRenderer { CompletableFuture.completedFuture(Surface()) },
            ActorPort { CompletableFuture.failedFuture(IllegalStateException("optional")) },
        )
        assertNotNull(rig.start())
        val other = TestRig()
        val late = CompletableFuture<ActorPort.ActorHandle>()
        renderer(
            other,
            DialogueRenderer { CompletableFuture.completedFuture(Surface()) },
            ActorPort { late },
        )
        val future = other.runtime.start(other.request("optional"))
        other.scheduler.drain()
        other.scheduler.advance(Duration.ofSeconds(5))
        assertInstanceOf(DialogueStartResult.Started::class.java, TestRig.done(future))
    }

    @Test
    fun throwingCancellationListenerCannotSkipOwnedCleanup() {
        val rig = TestRig()
        val surface = Surface()
        renderer(
            rig,
            DialogueRenderer { request ->
                request.cancellation.onCancel { throw IllegalStateException("listener") }
                CompletableFuture.completedFuture(surface)
            },
            null,
        )
        val handle = rig.start()
        val end =
            assertDoesNotThrow<CompletionStage<DialogueEnd>> {
                handle.stop(DialogueEndReason.CANCELLED)
            }
        rig.scheduler.drain()
        assertEquals(DialogueEndReason.CANCELLED, TestRig.done(end).reason())
        assertEquals(1, surface.closes)
    }
}
