package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.port.*
import gg.grounds.dialogue.core.port.ActionHandler.*
import gg.grounds.dialogue.core.text.*
import gg.grounds.dialogue.core.validation.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class ReviewRegressionsTest {
    private fun choiceRig(provider: ConditionProvider): TestRig {
        val definition =
            DialogueBuilder.dialogue("review:choices")
                .node(
                    "start",
                    Message(
                        "npc",
                        "hello",
                        null,
                        listOf(
                            Choice(
                                "offer",
                                "c",
                                "end",
                                true,
                                Condition("review:ok", emptyMap(), false),
                            ),
                            Choice("leave", "bye", "end", false, null),
                        ),
                        null,
                    ),
                )
                .node("end", End("completed"))
                .build()
        val rig =
            TestRig(
                definition,
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    mapOf("review:ok" to HostCatalog.ProviderSpec(emptyMap(), false)),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    false,
                ),
            )
        rig.bindings =
            HostBindings(
                "b",
                mapOf("review:ok" to provider),
                emptyMap(),
                null,
                rig.bindings.texts(),
                rig.bindings.renderer(),
                null,
                emptySet(),
            )
        return rig
    }

    @Test
    fun c1CancelledBusyFrameCannotDispatchActionOrOutcome() {
        for (action in listOf(true, false)) {
            val definition =
                DialogueBuilder.dialogue("review:cancel")
                    .node(
                        "start",
                        if (action) Action("review:mutate", emptyMap(), "end", "end", "end", "end")
                        else Outcome("review:milestone", Delivery.LOCAL, "end", "end", "end"),
                    )
                    .node("end", End("completed"))
                    .build()
            val rig =
                TestRig(
                    definition,
                    HostCatalog(
                        "v1",
                        emptySet(),
                        emptyMap(),
                        mapOf("review:mutate" to HostCatalog.ProviderSpec(emptyMap(), true)),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                )
            val calls = AtomicInteger()
            val handler =
                object : ActionHandler {
                    override fun execute(request: ActionRequest): CompletionStage<ActionResult> {
                        calls.incrementAndGet()
                        return CompletableFuture.completedFuture(ActionResult.SUCCESS)
                    }

                    override fun reconcile(reference: ActionRef): CompletionStage<ActionStatus> =
                        CompletableFuture.completedFuture(ActionStatus.SUCCEEDED)
                }
            val surface = DialogueLifecycleTest.Surface()
            surface.onReplace = Runnable { rig.cancellation.cancel(DialogueEndReason.DISCONNECTED) }
            rig.bindings =
                HostBindings(
                    "cancel",
                    emptyMap(),
                    mapOf("review:mutate" to handler),
                    {
                        calls.incrementAndGet()
                        CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED)
                    },
                    rig.bindings.texts(),
                    { CompletableFuture.completedFuture(surface) },
                    null,
                    emptySet(),
                )
            val start = rig.runtime.start(rig.request("cancel"))
            rig.scheduler.drain()
            assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(start))
            assertEquals(0, calls.get(), "No new effect after cancellation won dispatch admission")
        }
    }

    @Test
    fun i2SurfaceSwitchDuringConfirmRestoresActionableChoices() {
        val calls = AtomicInteger()
        val pending = CompletableFuture<ConditionProvider.ConditionResult>()
        val rig = choiceRig {
            if (calls.getAndIncrement() == 0)
                CompletableFuture.completedFuture(ConditionProvider.ConditionResult.TRUE)
            else pending
        }
        val handle = rig.start()
        handle.submit(DialogueInputTest.confirm(handle, 1))
        rig.scheduler.drain()
        val confirm = handle.submit(DialogueInputTest.confirm(handle, 2))
        rig.scheduler.drain()
        val change =
            handle.updatePreferences(
                PresentationPreferences("npc", Duration.ofMillis(35), false, emptyMap())
            )
        rig.scheduler.drain()
        TestRig.done(change)
        pending.complete(ConditionProvider.ConditionResult.FALSE)
        rig.scheduler.drain()
        assertEquals(InputResult.STALE, TestRig.done(confirm))
        assertEquals(DialogueView.State.WAITING_FOR_INPUT, handle.snapshot().state())
        assertEquals("leave", handle.snapshot().selectedChoiceId())
        val next = handle.submit(DialogueInputTest.confirm(handle, 3))
        rig.scheduler.drain()
        assertEquals(InputResult.ACCEPTED, TestRig.done(next))
        assertEquals(DialogueEndReason.COMPLETED, TestRig.done(handle.completion()).reason())
    }

    @Test
    fun i3BatchedConditionDeadlinesKeepUnconditionalChoiceOnEntry() {
        val rig = choiceRig { CompletableFuture() }
        val future = rig.runtime.start(rig.request("batch"))
        rig.scheduler.drain()
        rig.scheduler.advanceBatched(Duration.ofSeconds(2))
        val handle = (TestRig.done(future) as DialogueStartResult.Started).handle
        assertEquals(listOf("offer", "leave"), handle.snapshot().choices().map { it.id })
        assertEquals("leave", handle.snapshot().selectedChoiceId())
        assertEquals(
            DialogueView.Availability.UNAVAILABLE,
            handle.snapshot().choices().first().availability,
        )
    }

    @Test
    fun i3BatchedConditionDeadlinesKeepUnconditionalChoiceOnConfirm() {
        val calls = AtomicInteger()
        val rig = choiceRig {
            if (calls.getAndIncrement() == 0)
                CompletableFuture.completedFuture(ConditionProvider.ConditionResult.TRUE)
            else CompletableFuture()
        }
        val handle = rig.start()
        handle.submit(DialogueInputTest.confirm(handle, 1))
        rig.scheduler.drain()
        val future = handle.submit(DialogueInputTest.confirm(handle, 2))
        rig.scheduler.drain()
        rig.scheduler.advanceBatched(Duration.ofSeconds(2))
        assertEquals(InputResult.STALE, TestRig.done(future))
        assertEquals("leave", handle.snapshot().selectedChoiceId())
        assertEquals(DialogueView.State.WAITING_FOR_INPUT, handle.snapshot().state())
    }

    @Test
    fun i6ImmediateRevealIsCommittedOnlyAfterReconfigure() {
        val rig = TestRig()
        val gate = CompletableFuture<Void>()
        val surface =
            object : DialogueLifecycleTest.Surface() {
                override fun reconfigure(
                    preferences: PresentationPreferences
                ): CompletionStage<Void> = gate
            }
        DialogueLifecycleTest.renderer(rig, { CompletableFuture.completedFuture(surface) }, null)
        val handle = rig.start()
        val future =
            handle.updatePreferences(
                PresentationPreferences("hud", Duration.ZERO, false, emptyMap())
            )
        rig.scheduler.drain()
        rig.scheduler.advance(Duration.ofMillis(35))
        assertNotEquals(DialogueView.State.CLOSED, handle.snapshot().state())
        gate.complete(null)
        rig.scheduler.drain()
        TestRig.done(future)
        assertEquals(DialogueView.State.WAITING_FOR_INPUT, handle.snapshot().state())
        assertEquals(handle.snapshot().totalGraphemes(), handle.snapshot().revealedGraphemes())
    }

    @Test
    fun i8StartAndEndObserversCannotMutateLifecycle() {
        val rig = TestRig()
        val request = rig.request("observers")
        val stage = rig.runtime.start(request)
        stage.toCompletableFuture().cancel(false)
        assertSame(stage, rig.runtime.start(request))
        rig.scheduler.drain()
        val handle = (TestRig.done(stage) as DialogueStartResult.Started).handle
        handle
            .completion()
            .toCompletableFuture()
            .complete(
                DialogueEnd(handle.snapshot().sessionId(), DialogueEndReason.COMPLETED, emptyList())
            )
        assertFalse(handle.completion().toCompletableFuture().isDone)
        handle.completion().toCompletableFuture().cancel(false)
        val stop = handle.stop(DialogueEndReason.DISCONNECTED)
        rig.scheduler.drain()
        assertEquals(DialogueEndReason.DISCONNECTED, TestRig.done(stop).reason())
        assertEquals(DialogueView.State.CLOSED, handle.snapshot().state())
    }

    @Test
    fun i9AuthorGenerationAndPolicyParticipateInDeduplication() {
        val rig = TestRig()
        val request = rig.request("pin")
        rig.runtime.start(request)
        val catalog = rig.content.catalog()
        val v2 =
            HostCatalog(
                "v2",
                catalog.speakers(),
                catalog.conditions(),
                catalog.actions(),
                catalog.gestures(),
                catalog.capabilities(),
                catalog.durableOutcomes(),
            )
        val content =
            DialogueValidator()
                .validate(rig.content.definition(), rig.content.texts(), v2, rig.content.limits())
                .validated()!!
        assertEquals(rig.content.contentPin(), content.contentPin())
        val other =
            DialogueStart(
                request.requestId(),
                request.audienceId(),
                request.hostGeneration(),
                content,
                request.bindings(),
                request.context(),
                request.locale(),
                request.preferences(),
                request.cancellation(),
                0,
            )
        assertEquals(
            "START_REQUEST_CONFLICT",
            (TestRig.done(rig.runtime.start(other)) as DialogueStartResult.Rejected).code(),
        )
        val limits = rig.content.limits()
        val stricter =
            ValidationLimits(
                limits.definitionBytes,
                limits.nodes,
                limits.choices,
                16,
                limits.depth,
                limits.idCodepoints,
                limits.textCodepoints,
                limits.locales,
                limits.textFileBytes,
                limits.textPackageBytes,
                limits.arguments,
            )
        val c2 =
            DialogueValidator()
                .validate(rig.content.definition(), rig.content.texts(), catalog, stricter)
                .validated()!!
        val request2 =
            DialogueStart(
                request.requestId(),
                request.audienceId(),
                request.hostGeneration(),
                c2,
                request.bindings(),
                request.context(),
                request.locale(),
                request.preferences(),
                request.cancellation(),
                0,
            )
        assertEquals(
            "START_REQUEST_CONFLICT",
            (TestRig.done(rig.runtime.start(request2)) as DialogueStartResult.Rejected).code(),
        )
        rig.scheduler.drain()
    }

    @Test
    fun i12OptionalSpeakerLabelUsesOnlyEffectiveFallbackChain() {
        val rig = TestRig()
        val texts =
            TextCatalog(
                mapOf(
                    "en" to mapOf("hello" to "Hello", "bye" to "Bye"),
                    "de" to
                        mapOf(
                            "hello" to "Hallo",
                            "bye" to "Tschüss",
                            "speaker.npc" to "Mechaniker",
                        ),
                )
            )
        val validated =
            DialogueValidator()
                .validate(
                    rig.content.definition(),
                    texts,
                    rig.content.catalog(),
                    rig.content.limits(),
                )
                .validated()!!
        val request = rig.request("labels")
        val bindings =
            HostBindings(
                "labels",
                emptyMap(),
                emptyMap(),
                null,
                TextResolver.catalogue(texts, "en"),
                rig.bindings.renderer(),
                null,
                emptySet(),
            )
        val future =
            rig.runtime.start(
                DialogueStart(
                    request.requestId(),
                    request.audienceId(),
                    request.hostGeneration(),
                    validated,
                    bindings,
                    emptyMap(),
                    request.locale(),
                    request.preferences(),
                    request.cancellation(),
                    0,
                )
            )
        rig.scheduler.drain()
        val handle = (TestRig.done(future) as DialogueStartResult.Started).handle
        assertEquals("npc", handle.snapshot().speaker())
        handle.refreshLocale(LocaleContext("de", 1, "en"))
        rig.scheduler.drain()
        assertEquals("Mechaniker", handle.snapshot().speaker())
        handle.refreshLocale(LocaleContext("en", 2, "en"))
        rig.scheduler.drain()
        assertEquals("npc", handle.snapshot().speaker())
    }
}
