package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.presentation.*
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class PresenterReviewRegressionsTest {
    private class Surface : DialogueSurface {
        var opens = 0
        var closes = 0
        var writes = 0
        val contexts = mutableListOf<DialogueSurface.SurfaceContext>()
        val frames = mutableListOf<WidgetFrame>()
        var openGate: CompletableFuture<DialogueSurface.SurfaceHandle>? = null
        var firstWrite: CompletableFuture<Void>? = null
        var closeGate: CompletableFuture<Void>? = null
        var failClose = false
        lateinit var lastHandle: WeakReference<DialogueSurface.SurfaceHandle>

        override fun open(
            context: DialogueSurface.SurfaceContext
        ): CompletionStage<DialogueSurface.SurfaceHandle> {
            opens++
            contexts.add(context)
            return openGate ?: CompletableFuture.completedFuture(handle())
        }

        fun handle(): DialogueSurface.SurfaceHandle {
            val handle =
                object : DialogueSurface.SurfaceHandle {
                    override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                        writes++
                        frames.add(frame)
                        return if (firstWrite != null && writes == 1) firstWrite!!
                        else CompletableFuture.completedFuture(null)
                    }

                    override fun closeAsync(): CompletionStage<Void> {
                        closes++
                        closeGate?.let {
                            return it
                        }
                        return if (failClose)
                            CompletableFuture.failedFuture(
                                IllegalStateException("surface cleanup failed")
                            )
                        else CompletableFuture.completedFuture(null)
                    }
                }
            lastHandle = WeakReference(handle)
            return handle
        }
    }

    private class Fixture {
        val rig = TestRig()
        val hud = Surface()
        val npc = Surface()
        val presentation = AtomicReference<DialogueRenderer.PresentationHandle>()
        val presenter =
            DialoguePresenter(
                mapOf(
                    "hud" to
                        DialoguePresenter.SurfaceProfile(
                            hud,
                            LayoutProfile.hud(),
                            DialogueTheme.neutral(),
                            FontMetrics.defaults(),
                            false,
                        ),
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            npc,
                            LayoutProfile.npc(),
                            DialogueTheme.neutral(),
                            FontMetrics.defaults(),
                            false,
                        ),
                ),
                rig.scheduler,
            )

        init {
            rig.bindings =
                HostBindings(
                    "presenter",
                    emptyMap(),
                    emptyMap(),
                    null,
                    rig.bindings.texts(),
                    { request ->
                        presenter.prepare(request).thenApply { handle ->
                            presentation.set(handle)
                            handle
                        }
                    },
                    null,
                    emptySet(),
                )
        }

        fun change(handle: DialogueHandle, key: String): CompletionStage<Void> {
            val result =
                handle.updatePreferences(
                    PresentationPreferences(key, Duration.ofMillis(35), false, emptyMap())
                )
            rig.scheduler.drain()
            return result
        }
    }

    @Test
    fun i1LateFailedSurfaceCleanupFailsStartAndRuntimeClose() {
        val fixture = Fixture()
        fixture.hud.openGate = CompletableFuture()
        fixture.hud.failClose = true
        val start = fixture.rig.runtime.start(fixture.rig.request("late-failure"))
        fixture.rig.scheduler.drain()
        fixture.rig.cancellation.cancel(DialogueEndReason.DISCONNECTED)
        fixture.rig.scheduler.drain()
        fixture.hud.openGate!!.complete(fixture.hud.handle())
        fixture.rig.scheduler.drain()
        assertEquals("CLEANUP_FAILED", (TestRig.done(start) as DialogueStartResult.Rejected).code())
        val closed = fixture.rig.runtime.closeAsync()
        fixture.rig.scheduler.drain()
        assertTrue(closed.toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, fixture.hud.closes)
    }

    @Test
    fun i4ReturningToCommittedSurfaceSupersedesPendingOpen() {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        fixture.npc.openGate = CompletableFuture()
        val first = fixture.change(handle, "npc")
        val latest = fixture.change(handle, "hud")
        TestRig.done(latest)
        fixture.npc.openGate!!.complete(fixture.npc.handle())
        fixture.rig.scheduler.drain()
        assertTrue(first.toCompletableFuture().isDone)
        assertEquals(0, fixture.hud.closes)
        assertEquals(1, fixture.npc.closes)
        assertTrue(fixture.npc.frames.isEmpty())
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.rig.scheduler.drain()
    }

    @Test
    fun i4RejectedNewRequestAlsoSupersedesOldCandidate() {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        fixture.npc.openGate = CompletableFuture()
        fixture.change(handle, "npc")
        val rejected = fixture.change(handle, "missing")
        assertTrue(rejected.toCompletableFuture().isCompletedExceptionally)
        fixture.npc.openGate!!.complete(fixture.npc.handle())
        fixture.rig.scheduler.drain()
        assertNotEquals(DialogueView.State.CLOSED, handle.snapshot().state())
        assertEquals(0, fixture.hud.closes)
        assertEquals(1, fixture.npc.closes)
        assertTrue(fixture.npc.frames.isEmpty())
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.rig.scheduler.drain()
    }

    @Test
    fun i5FreshDeadlineAndNonDefaultPreparationBudgetAreForwarded() {
        val fixture = Fixture()
        val runtime =
            DialogueRuntime.create(
                RuntimeOptions(
                    fixture.rig.scheduler,
                    RuntimeOptions.Budgets(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(9),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(7),
                    ),
                    100,
                    256,
                )
            )
        val result = runtime.start(fixture.rig.request("budget"))
        fixture.rig.scheduler.drain()
        val handle = (TestRig.done(result) as DialogueStartResult.Started).handle
        fixture.rig.scheduler.advance(Duration.ofSeconds(60))
        fixture.npc.openGate = CompletableFuture()
        val change = fixture.change(handle, "npc")
        assertEquals(69_000_000_000L, fixture.npc.contexts.last().deadlineNanos)
        fixture.rig.scheduler.advance(Duration.ofSeconds(8))
        assertFalse(change.toCompletableFuture().isDone)
        fixture.npc.openGate!!.complete(fixture.npc.handle())
        fixture.rig.scheduler.drain()
        TestRig.done(change)
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.rig.scheduler.drain()
    }

    @Test
    fun i5CleanupBudgetAlsoReachesTheNestedPresenter() {
        val fixture = Fixture()
        val runtime =
            DialogueRuntime.create(
                RuntimeOptions(
                    fixture.rig.scheduler,
                    RuntimeOptions.Budgets(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(9),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(7),
                    ),
                    100,
                    256,
                )
            )
        val result = runtime.start(fixture.rig.request("cleanup-budget"))
        fixture.rig.scheduler.drain()
        val handle = (TestRig.done(result) as DialogueStartResult.Started).handle
        fixture.npc.openGate = CompletableFuture()
        fixture.change(handle, "npc")
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.rig.scheduler.drain()
        val ownClose = fixture.presentation.get().closeAsync()
        fixture.rig.scheduler.advance(Duration.ofSeconds(7))
        assertTrue(ownClose.toCompletableFuture().isCompletedExceptionally)
        fixture.npc.openGate!!.complete(fixture.npc.handle())
        fixture.rig.scheduler.drain()
        assertEquals(1, fixture.npc.closes)
    }

    @Test
    fun i7CoalescingNeverAcknowledgesAnUndrawnFirstFrame() {
        for (fail in listOf(false, true)) {
            val fixture = Fixture()
            fixture.hud.firstWrite = CompletableFuture()
            val start = fixture.rig.runtime.start(fixture.rig.request("first-frame"))
            fixture.rig.scheduler.drain()
            fixture.rig.scheduler.advance(Duration.ofMillis(70))
            assertFalse(start.toCompletableFuture().isDone, "No frame has succeeded yet")
            assertEquals(1, fixture.hud.writes)
            if (fail)
                fixture.hud.firstWrite!!.completeExceptionally(IllegalStateException("first draw"))
            else fixture.hud.firstWrite!!.complete(null)
            fixture.rig.scheduler.drain()
            if (fail)
                assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(start))
            else {
                assertInstanceOf(DialogueStartResult.Started::class.java, TestRig.done(start))
                val handle = (TestRig.done(start) as DialogueStartResult.Started).handle
                handle.stop(DialogueEndReason.CANCELLED)
                fixture.rig.scheduler.drain()
            }
        }
    }

    @Test
    fun i8PresenterObserversCannotCompleteOrCancelOwnedPromises() {
        val fixture = Fixture()
        fixture.hud.openGate = CompletableFuture()
        val request =
            DialogueRenderer.PresentationRequest(
                UUID.randomUUID(),
                "viewer",
                1,
                PresentationPreferences.defaults(),
                CancellationToken(),
                5_000_000_000L,
            )
        val stage = fixture.presenter.prepare(request)
        stage.toCompletableFuture().cancel(false)
        fixture.rig.scheduler.drain()
        fixture.hud.openGate!!.complete(fixture.hud.handle())
        fixture.rig.scheduler.drain()
        val handle = TestRig.done(stage)
        val close = handle.closeAsync()
        close.toCompletableFuture().cancel(false)
        fixture.rig.scheduler.drain()
        TestRig.done(handle.closeAsync())
        assertEquals(1, fixture.hud.closes)
    }

    private data class LiveHandoff(
        val fixture: Fixture,
        val handle: DialogueHandle,
        val refs: List<WeakReference<DialogueSurface.SurfaceHandle>>,
    )

    private fun handoffHandles(): LiveHandoff {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        val references = mutableListOf<WeakReference<DialogueSurface.SurfaceHandle>>()
        var onHud = true
        repeat(20) {
            references.add((if (onHud) fixture.hud else fixture.npc).lastHandle)
            TestRig.done(fixture.change(handle, if (onHud) "npc" else "hud"))
            onHud = !onHud
        }
        return LiveHandoff(fixture, handle, references)
    }

    @Test
    fun i14ConfirmedOldHandlesAreCollectableBeforeSessionEnd() {
        val live = handoffHandles()
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3)) {
                while (live.refs.any { it.get() != null }) {
                    System.gc()
                    Thread.sleep(10)
                }
            }
        } finally {
            Reference.reachabilityFence(live)
            live.handle.stop(DialogueEndReason.CANCELLED)
            live.fixture.rig.scheduler.drain()
        }
    }

    @Test
    fun i16SurfaceFrameReceivesActualThemePalette() {
        val fixture = Fixture()
        val firstTheme =
            DialogueTheme(
                "same",
                mapOf("text" to 0x123456, "panel" to 0xabcdef),
                1.08,
                Duration.ofMillis(150),
            )
        val secondTheme =
            DialogueTheme(
                "same",
                mapOf("text" to 0x654321, "panel" to 0xfedcba),
                1.08,
                Duration.ofMillis(150),
            )
        val presenter =
            DialoguePresenter(
                mapOf(
                    "a" to
                        DialoguePresenter.SurfaceProfile(
                            fixture.hud,
                            LayoutProfile.hud(),
                            firstTheme,
                            FontMetrics.defaults(),
                            false,
                        ),
                    "b" to
                        DialoguePresenter.SurfaceProfile(
                            fixture.npc,
                            LayoutProfile.hud(),
                            secondTheme,
                            FontMetrics.defaults(),
                            false,
                        ),
                ),
                fixture.rig.scheduler,
            )
        val handle = fixture.rig.start()
        val first = presenter.compose(handle.snapshot(), "a", PresentationPreferences.defaults())
        val second = presenter.compose(handle.snapshot(), "b", PresentationPreferences.defaults())
        assertNotEquals(
            first,
            second,
            "A different configured palette must be observable by a standalone surface",
        )
    }

    @Test
    fun i5PreparationBudgetBoundsOldSurfaceReleaseToo() {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        fixture.hud.closeGate = CompletableFuture()
        val change = fixture.change(handle, "npc")
        assertFalse(change.toCompletableFuture().isDone)
        fixture.rig.scheduler.advance(Duration.ofSeconds(5))
        assertTrue(change.toCompletableFuture().isCompletedExceptionally)
        assertNotEquals(DialogueView.State.WAITING_FOR_INPUT, handle.snapshot().state())
        fixture.hud.closeGate!!.complete(null)
        fixture.rig.scheduler.drain()
        assertEquals(DialogueView.State.CLOSED, handle.snapshot().state())
    }

    @Test
    fun i7PreferenceAcknowledgementWaitsForActualReplacement() {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        fixture.hud.writes = 0
        fixture.hud.firstWrite = CompletableFuture()
        val change = fixture.change(handle, "hud")
        assertFalse(change.toCompletableFuture().isDone)
        fixture.rig.scheduler.advance(Duration.ofMillis(70))
        assertFalse(change.toCompletableFuture().isDone)
        fixture.hud.firstWrite!!.complete(null)
        fixture.rig.scheduler.drain()
        TestRig.done(change)
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.rig.scheduler.drain()
    }

    @Test
    fun i8RuntimeShutdownObservationCannotCancelCleanup() {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        fixture.hud.closeGate = CompletableFuture()
        val shutdown = fixture.rig.runtime.closeAsync()
        shutdown.toCompletableFuture().cancel(false)
        fixture.rig.scheduler.drain()
        assertFalse(fixture.rig.runtime.closeAsync().toCompletableFuture().isDone)
        fixture.hud.closeGate!!.complete(null)
        fixture.rig.scheduler.drain()
        TestRig.done(fixture.rig.runtime.closeAsync())
        assertEquals(DialogueView.State.CLOSED, handle.snapshot().state())
    }

    @Test
    fun i13ProfilePinUsesCanonicalDataNotMapToString() {
        val fixture = Fixture()
        val handle = fixture.rig.start()
        assertEquals(
            "0068b520af32b07ce3973f1c4eda119740d5dcbf5259d3d972fb6eb822cbc361",
            fixture.hud.contexts.first().profilePin,
        )
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.rig.scheduler.drain()
    }
}
