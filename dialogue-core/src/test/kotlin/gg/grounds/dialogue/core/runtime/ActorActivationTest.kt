package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.port.*
import gg.grounds.dialogue.core.port.ActionHandler.*
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.HostCatalog
import java.time.Duration
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class ActorActivationTest {
    private class Actor(val calls: MutableList<String>) : ActorPort.ActorHandle {
        val activation = CompletableFuture<Void>()
        val cleanup = CompletableFuture<Void>()
        var throwsOnActivation = false

        override fun activate(): CompletionStage<Void> {
            calls.add("activate")
            if (throwsOnActivation) throw IllegalStateException("activation")
            return activation
        }

        override fun cue(cue: ActorPort.ActorCue): CompletionStage<Void> {
            calls.add("cue")
            return CompletableFuture.completedFuture(null)
        }

        override fun closeAsync(): CompletionStage<Void> {
            calls.add("close")
            return cleanup
        }
    }

    private fun rig(actor: Actor, required: Boolean, action: Boolean): TestRig {
        val definition =
            DialogueBuilder.dialogue("test:activation")
                .capabilities(
                    Capabilities(if (required) setOf("actor.gesture") else emptySet(), emptySet())
                )
                .node(
                    "start",
                    if (action)
                        Action(
                            "test:action",
                            emptyMap(),
                            "message",
                            "message",
                            "message",
                            "message",
                        )
                    else Message("npc", "hello", "demo:wave", emptyList(), "end"),
                )
                .node("message", Message("npc", "hello", "demo:wave", emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val catalog =
            HostCatalog(
                "v1",
                setOf("npc"),
                emptyMap(),
                mapOf("test:action" to HostCatalog.ProviderSpec(emptyMap(), false)),
                setOf("demo:wave"),
                setOf("actor.gesture"),
                false,
            )
        val rig = TestRig(definition, catalog)
        val old = rig.bindings
        val handler =
            object : ActionHandler {
                override fun execute(request: ActionRequest): CompletionStage<ActionResult> {
                    actor.calls.add("execute")
                    return CompletableFuture.completedFuture(ActionResult.SUCCESS)
                }

                override fun reconcile(reference: ActionRef): CompletionStage<ActionStatus> =
                    CompletableFuture.completedFuture(ActionStatus.SUCCEEDED)
            }
        rig.bindings =
            HostBindings(
                "activation",
                emptyMap(),
                mapOf("test:action" to handler),
                null,
                old.texts(),
                {
                    CompletableFuture.completedFuture(
                        object : DialogueRenderer.PresentationHandle {
                            override fun replace(view: DialogueView): CompletionStage<Void> {
                                actor.calls.add("draw")
                                rig.frames.add(view)
                                return CompletableFuture.completedFuture(null)
                            }

                            override fun reconfigure(
                                preferences: PresentationPreferences
                            ): CompletionStage<Void> = CompletableFuture.completedFuture(null)

                            override fun closeAsync(): CompletionStage<Void> =
                                CompletableFuture.completedFuture(null)
                        }
                    )
                },
                {
                    actor.calls.add("prepare")
                    CompletableFuture.completedFuture(actor)
                },
                setOf("actor.gesture"),
            )
        return rig
    }

    private fun frequency(actor: Actor, call: String) = actor.calls.count { it == call }

    @Test
    fun activationPrecedesCueAndDispatch() {
        val calls = mutableListOf<String>()
        val actor = Actor(calls)
        val rig = rig(actor, false, false)
        val start = rig.runtime.start(rig.request("activation"))
        rig.scheduler.drain()
        assertFalse(start.toCompletableFuture().isDone)
        assertEquals(listOf("prepare", "activate"), calls)
        actor.activation.complete(null)
        rig.scheduler.drain()
        val handle = (TestRig.done(start) as DialogueStartResult.Started).handle
        assertEquals(listOf("prepare", "activate", "cue", "draw"), calls)
        actor.cleanup.complete(null)
        handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
    }

    @Test
    fun noActionDispatchBeforeActivation() {
        val actor = Actor(mutableListOf())
        val rig = rig(actor, false, true)
        val start = rig.runtime.start(rig.request("action"))
        rig.scheduler.drain()
        assertFalse(actor.calls.contains("execute"))
        actor.activation.complete(null)
        rig.scheduler.drain()
        assertTrue(actor.calls.indexOf("activate") < actor.calls.indexOf("execute"))
        val handle = (TestRig.done(start) as DialogueStartResult.Started).handle
        actor.cleanup.complete(null)
        handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
    }

    @Test
    fun cancellationDuringActivationOwnsLateCleanup() {
        val actor = Actor(mutableListOf())
        val rig = rig(actor, false, false)
        val start = rig.runtime.start(rig.request("cancel"))
        rig.scheduler.drain()
        rig.cancellation.cancel(DialogueEndReason.DISCONNECTED)
        rig.scheduler.drain()
        assertFalse(start.toCompletableFuture().isDone)
        actor.activation.complete(null)
        rig.scheduler.drain()
        assertTrue(rig.frames.isEmpty())
        actor.cleanup.complete(null)
        rig.scheduler.drain()
        assertEquals("DISCONNECTED", (TestRig.done(start) as DialogueStartResult.Rejected).code())
        assertEquals(1, frequency(actor, "close"))
    }

    @Test
    fun optionalFailureContinuesOnlyAfterCleanup() {
        val actor = Actor(mutableListOf())
        val rig = rig(actor, false, false)
        val start = rig.runtime.start(rig.request("optional"))
        rig.scheduler.drain()
        actor.activation.completeExceptionally(IllegalStateException("activation"))
        rig.scheduler.drain()
        assertFalse(start.toCompletableFuture().isDone)
        assertTrue(rig.frames.isEmpty())
        actor.cleanup.complete(null)
        rig.scheduler.drain()
        val handle = (TestRig.done(start) as DialogueStartResult.Started).handle
        assertFalse(actor.calls.contains("cue"))
        handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
        assertEquals(1, frequency(actor, "close"))
    }

    @Test
    fun optionalCleanupFailureRejectsRatherThanHidingFailure() {
        val actor = Actor(mutableListOf())
        val rig = rig(actor, false, false)
        val start = rig.runtime.start(rig.request("cleanup"))
        rig.scheduler.drain()
        actor.activation.completeExceptionally(IllegalStateException("activation"))
        actor.cleanup.completeExceptionally(IllegalStateException("cleanup"))
        rig.scheduler.drain()
        assertEquals("CLEANUP_FAILED", (TestRig.done(start) as DialogueStartResult.Rejected).code())
        assertTrue(rig.frames.isEmpty())
    }

    @Test
    fun requiredFailureRejects() {
        val actor = Actor(mutableListOf())
        val rig = rig(actor, true, false)
        val start = rig.runtime.start(rig.request("required"))
        rig.scheduler.drain()
        actor.activation.completeExceptionally(IllegalStateException("activation"))
        actor.cleanup.complete(null)
        rig.scheduler.drain()
        assertEquals(
            "ACTOR_ACTIVATION_FAILED",
            (TestRig.done(start) as DialogueStartResult.Rejected).code(),
        )
        assertTrue(rig.frames.isEmpty())
    }

    @Test
    fun optionalActivationTimeoutWaitsForCleanup() {
        val actor = Actor(mutableListOf())
        val rig = rig(actor, false, false)
        val start = rig.runtime.start(rig.request("timeout"))
        rig.scheduler.drain()
        rig.scheduler.advance(Duration.ofSeconds(5))
        assertFalse(start.toCompletableFuture().isDone)
        assertTrue(rig.frames.isEmpty())
        actor.cleanup.complete(null)
        rig.scheduler.drain()
        assertInstanceOf(DialogueStartResult.Started::class.java, TestRig.done(start))
    }

    @Test
    fun synchronousActivationFailureIsBounded() {
        val actor = Actor(mutableListOf())
        actor.throwsOnActivation = true
        actor.cleanup.complete(null)
        val rig = rig(actor, true, false)
        val start = rig.runtime.start(rig.request("throw"))
        rig.scheduler.drain()
        assertEquals(
            "ACTOR_ACTIVATION_FAILED",
            (TestRig.done(start) as DialogueStartResult.Rejected).code(),
        )
    }

    @Test
    fun localeAndSurfaceChangesNeverReactivate() {
        val actor = Actor(mutableListOf())
        actor.activation.complete(null)
        actor.cleanup.complete(null)
        val rig = rig(actor, false, false)
        val handle = rig.start()
        handle.refreshLocale(LocaleContext("de", 1, "en"))
        handle.updatePreferences(PresentationPreferences("npc", Duration.ZERO, false, emptyMap()))
        rig.scheduler.drain()
        assertEquals(1, frequency(actor, "activate"))
        assertEquals(1, frequency(actor, "cue"))
        handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
    }
}
