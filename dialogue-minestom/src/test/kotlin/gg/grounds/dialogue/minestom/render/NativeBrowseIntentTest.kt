package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.*
import java.time.Duration
import java.util.concurrent.*
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class NativeBrowseIntentTest {
    private inner class Fixture(val env: Env, manual: RendererFallbackTest.Clock? = null) {
        val world = env.createFlatInstance()
        val player = env.createConnection().connect(world, Pos(0.0, 42.0, 0.0))
        val targets = SurfaceInputTargets()
        val frames = ArrayList<List<String>>()
        val token = CancellationToken()
        val clock = manual ?: MinestomDialogueScheduler(world)
        var write: (WidgetFrame) -> CompletionStage<Void> = {
            CompletableFuture.completedFuture(null)
        }
        var layout: () -> LayoutProfile = { LayoutProfile.npc() }
        val surface = DialogueSurface { context ->
            CompletableFuture.completedFuture(
                object : DialogueSurface.SurfaceHandle {
                    override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                        frames.add(
                            frame
                                .widgets()
                                .filter { it.kind == WidgetFrame.Kind.CHOICE }
                                .map { it.choiceId!! }
                        )
                        return write(frame).thenRun {
                            targets.publish(
                                frame,
                                context.generation,
                                frame
                                    .widgets()
                                    .filter { it.kind == WidgetFrame.Kind.CHOICE }
                                    .associate { it.choiceId!! to it.hitRect() },
                            )
                        }
                    }

                    override fun closeAsync(): CompletionStage<Void> {
                        targets.clear(context.generation)
                        return CompletableFuture.completedFuture(null)
                    }
                }
            )
        }
        val renderer =
            MinestomDialogueRenderer(
                MinestomConnectionScope(player, 1, world),
                mapOf(
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            surface,
                            LayoutProfile.npc(),
                            DialogueTheme.neutral(),
                            FontMetrics.defaults(),
                            false,
                            { layout() },
                        ),
                    "chat" to DialoguePresenter.SurfaceProfile.semantic(surface),
                ),
                clock,
                DialoguePresenter.Controls.defaults(),
                UiLabelSource.defaults(),
                targets,
                {},
            )
        val view: DialogueView
        val handle: DialogueRenderer.PresentationHandle

        init {
            val v = RenderFixtures.view("choices", 7)
            view =
                DialogueView(
                    v.sessionId(),
                    v.contentPin(),
                    v.nodeId(),
                    v.nodeVisitId(),
                    v.state(),
                    1,
                    1,
                    v.localeTag(),
                    v.speaker(),
                    v.text(),
                    (1..7).map {
                        DialogueView.ChoiceView(
                            "c$it",
                            "Choice $it",
                            DialogueView.Availability.AVAILABLE,
                            "",
                        )
                    },
                    "c1",
                    v.totalGraphemes(),
                    v.revealedGraphemes(),
                    v.hint(),
                    v.notice(),
                    v.pendingAction(),
                )
            handle =
                done(
                    renderer.prepare(
                        DialogueRenderer.PresentationRequest(
                            view.sessionId(),
                            player.uuid.toString(),
                            1,
                            PresentationPreferences("npc", Duration.ZERO, false, emptyMap()),
                            token,
                            Long.MAX_VALUE,
                        )
                    )
                )
            done(handle.replace(view))
        }

        fun <T> done(stage: CompletionStage<T>): T {
            if (clock is RendererFallbackTest.Clock) {
                clock.drain()
                return stage.toCompletableFuture().join()
            }
            return HudDialogueSurfaceTest.done(env, stage)
        }

        fun drain() {
            if (clock is RendererFallbackTest.Clock) clock.drain() else repeat(15) { env.tick() }
        }
    }

    @Test
    fun transientLayoutFailureKeepsAcknowledgedOutputAndAllowsRetry(env: Env) {
        val clock = RendererFallbackTest.Clock()
        val f = Fixture(env, clock)
        val page = f.targets.current()!!
        f.layout = {
            f.layout = { LayoutProfile.npc() }
            throw IllegalStateException("resolver unavailable on browse")
        }
        val failed = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        assertEquals(
            "resolver unavailable on browse",
            assertThrows(CompletionException::class.java) { failed.join() }.cause!!.message,
        )
        assertEquals(1, f.frames.size)
        assertSame(page, f.targets.current())
        assertNull(f.token.reason())
        assertEquals("npc", f.renderer.effectiveSurface())
        // A retired failed batch must not cancel the following independent batch.
        clock.advance(Duration.ofSeconds(4))
        val gate = CompletableFuture<Void>()
        f.write = { gate }
        val retry = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        clock.advance(Duration.ofSeconds(1))
        assertFalse(retry.isDone)
        assertNull(f.token.reason())
        gate.complete(null)
        assertEquals(InputResult.ACCEPTED, f.done(retry))
        f.write = { CompletableFuture.completedFuture(null) }
        f.renderer.refreshOutput()
        f.drain()
        assertEquals(listOf("c4", "c5", "c6"), f.frames.last())
        f.done(f.handle.closeAsync())
    }

    @Test
    fun stagingFailureRejectsDuplicatesButWritesNewerQueuedIntent(env: Env) {
        val f = Fixture(env, RendererFallbackTest.Clock())
        val page = f.targets.current()!!
        val gate = CompletableFuture<Void>()
        f.write = { gate }
        lateinit var duplicate: CompletableFuture<InputResult>
        lateinit var newer: CompletableFuture<InputResult>
        f.layout = {
            f.layout = { LayoutProfile.npc() }
            duplicate = f.renderer.browse(page, 3).toCompletableFuture()
            newer = f.renderer.browse(page, 6).toCompletableFuture()
            throw IllegalStateException("staging failed")
        }
        val failed = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        assertTrue(failed.isCompletedExceptionally)
        assertTrue(duplicate.isCompletedExceptionally)
        assertFalse(newer.isDone)
        assertEquals(listOf(listOf("c1", "c2", "c3"), listOf("c7")), f.frames)
        assertSame(page, f.targets.current())
        assertNull(f.token.reason())
        gate.complete(null)
        assertEquals(InputResult.ACCEPTED, f.done(newer))
        f.write = { CompletableFuture.completedFuture(null) }
        f.renderer.refreshOutput()
        f.drain()
        assertEquals(listOf("c7"), f.frames.last())
        f.done(f.handle.closeAsync())
    }

    @Test
    fun stagingFailureCannotExtendSurvivingBatchDeadline(env: Env) {
        val clock = RendererFallbackTest.Clock()
        val f = Fixture(env, clock)
        val page = f.targets.current()!!
        val gate = CompletableFuture<Void>()
        f.write = { gate }
        lateinit var newer: CompletableFuture<InputResult>
        f.layout = {
            f.layout = { LayoutProfile.npc() }
            newer = f.renderer.browse(page, 6).toCompletableFuture()
            clock.now += Duration.ofSeconds(4).toNanos()
            throw IllegalStateException("staging failed")
        }
        val failed = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        assertTrue(failed.isCompletedExceptionally)
        assertFalse(newer.isDone)
        assertNull(f.token.reason())
        clock.advance(Duration.ofSeconds(1))
        assertTrue(newer.isCompletedExceptionally)
        assertEquals(DialogueEndReason.FAILED, f.token.reason())
        gate.complete(null)
        f.drain()
        assertNull(f.targets.current())
        f.done(f.handle.closeAsync())
    }

    @Test
    fun closeDuringStagingPreventsLateReplacement(env: Env) {
        val f = Fixture(env, RendererFallbackTest.Clock())
        val page = f.targets.current()!!
        lateinit var closing: CompletionStage<Void>
        f.layout = {
            closing = f.handle.closeAsync()
            throw IllegalStateException("staging failed after close")
        }
        val failed = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        assertTrue(failed.isCompletedExceptionally)
        f.done(closing)
        assertEquals(1, f.frames.size)
        assertNull(f.targets.current())
        assertEquals(InputResult.STALE, f.done(f.renderer.browse(page, 6)))
    }

    @Test
    fun stagingFailureDoesNotRejectAnAlreadyAcknowledgedObserver(env: Env) {
        val f = Fixture(env, RendererFallbackTest.Clock())
        val gate = CompletableFuture<Void>()
        f.write = { gate }
        val first = f.renderer.browse(f.targets.current()!!, 3).toCompletableFuture()
        f.drain()
        val duplicate = f.renderer.browse(f.targets.current()!!, 3).toCompletableFuture()
        f.drain()
        f.layout = {
            f.layout = { LayoutProfile.npc() }
            throw IllegalStateException("redraw staging failed")
        }
        gate.complete(null)
        f.drain()
        assertEquals(InputResult.ACCEPTED, f.done(first))
        assertTrue(duplicate.isCompletedExceptionally)
        assertEquals(2, f.frames.size)
        assertEquals(setOf("c4", "c5", "c6"), f.targets.current()!!.choices().keys)
        assertNull(f.token.reason())
        f.write = { CompletableFuture.completedFuture(null) }
        assertEquals(InputResult.ACCEPTED, f.done(f.renderer.browse(f.targets.current()!!, 6)))
        f.done(f.handle.closeAsync())
    }

    @Test
    fun pendingDifferentSemanticViewWithEqualRevisionsCannotAuthorizeOldPage(env: Env) {
        val f = Fixture(env)
        val page = f.targets.current()!!
        val gate = CompletableFuture<Void>()
        f.write = { gate }
        val v = f.view
        val desired =
            DialogueView(
                v.sessionId(),
                v.contentPin(),
                v.nodeId(),
                v.nodeVisitId(),
                v.state(),
                1,
                1,
                v.localeTag(),
                v.speaker(),
                "different semantic view",
                v.choices(),
                v.selectedChoiceId(),
                23,
                23,
                v.hint(),
                v.notice(),
                v.pendingAction(),
            )
        val pending = f.handle.replace(desired).toCompletableFuture()
        f.drain()
        val stale = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        gate.complete(null)
        f.done(pending)
        assertEquals(InputResult.STALE, f.done(stale))
        assertEquals(2, f.frames.size)
        f.done(f.handle.closeAsync())
    }

    @Test
    fun immediateDuplicateRetainsPendingIdentityAfterIntermediateAck(env: Env) {
        val f = Fixture(env)
        val page = f.targets.current()!!
        val firstGate = CompletableFuture<Void>()
        val redrawGate = CompletableFuture<Void>()
        var writes = 0
        f.write = { if (writes++ == 0) firstGate else redrawGate }
        val first = f.renderer.browse(page, 3).toCompletableFuture()
        assertTrue(env.tickWhile({ f.frames.size < 2 }, Duration.ofSeconds(2)))
        val duplicate = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        firstGate.complete(null)
        f.drain()
        assertEquals(3, f.frames.size)
        val laterDuplicate = f.renderer.browse(f.targets.current()!!, 3).toCompletableFuture()
        f.drain()
        redrawGate.completeExceptionally(DialogueSurface.FrameRejected("duplicate redraw rejected"))
        f.drain()
        assertEquals(InputResult.ACCEPTED, f.done(first))
        assertTrue(duplicate.isCompletedExceptionally)
        assertTrue(laterDuplicate.isCompletedExceptionally)
        assertEquals(3, f.frames.size)
        f.done(f.handle.closeAsync())
    }

    @Test
    fun selectedInvalidPageIsStaleOnlyAfterItsActualFrameAck(env: Env) {
        val f = Fixture(env)
        val page = f.targets.current()!!
        val gate = CompletableFuture<Void>()
        f.write = { gate }
        val stale = f.renderer.browse(page, 99).toCompletableFuture()
        f.drain()
        assertFalse(stale.isDone)
        assertEquals(listOf("c1", "c2", "c3"), f.frames.last())
        gate.complete(null)
        assertEquals(InputResult.STALE, f.done(stale))
        val calls = f.frames.size
        assertEquals(InputResult.STALE, f.done(f.renderer.browse(page, 3)))
        assertEquals(calls, f.frames.size, "An already-retired displayed target cannot be admitted")
        f.done(f.handle.closeAsync())
    }

    @Test
    fun queuedPageForOlderSemanticViewRetainsStaleOutcome(env: Env) {
        val f = Fixture(env)
        val page = f.targets.current()!!
        val firstGate = CompletableFuture<Void>()
        val newGate = CompletableFuture<Void>()
        f.write = { if (it.semantic().stateRevision() == 1L) firstGate else newGate }
        val first = f.renderer.browse(page, 3).toCompletableFuture()
        assertTrue(env.tickWhile({ f.frames.size < 2 }, Duration.ofSeconds(2)))
        val olderPage = f.renderer.browse(page, 6).toCompletableFuture()
        val v = f.view
        val newer =
            DialogueView(
                v.sessionId(),
                v.contentPin(),
                v.nodeId(),
                v.nodeVisitId(),
                v.state(),
                2,
                2,
                v.localeTag(),
                v.speaker(),
                "updated",
                v.choices(),
                v.selectedChoiceId(),
                7,
                7,
                v.hint(),
                v.notice(),
                v.pendingAction(),
            )
        val current = f.handle.replace(newer).toCompletableFuture()
        f.drain()
        firstGate.completeExceptionally(DialogueSurface.FrameRejected("first page rejected"))
        f.drain()
        assertTrue(first.isCompletedExceptionally)
        assertFalse(olderPage.isDone)
        newGate.complete(null)
        assertEquals(InputResult.STALE, f.done(olderPage))
        f.done(current)
        assertEquals(2L, f.targets.current()!!.stateRevision())
        f.done(f.handle.closeAsync())
    }

    @Test
    fun rejectedPageCannotExtendDeadlineOfNewerQueuedPage(env: Env) {
        val clock = RendererFallbackTest.Clock()
        val f = Fixture(env, clock)
        val page = f.targets.current()!!
        val firstGate = CompletableFuture<Void>()
        val lastGate = CompletableFuture<Void>()
        f.write = { if (it.widgets().any { w -> w.choiceId == "c4" }) firstGate else lastGate }
        val first = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        val last = f.renderer.browse(page, 6).toCompletableFuture()
        f.drain()
        clock.advance(Duration.ofSeconds(4))
        firstGate.completeExceptionally(DialogueSurface.FrameRejected("page 3 rejected"))
        f.drain()
        assertTrue(first.isCompletedExceptionally)
        assertFalse(last.isDone)
        clock.advance(Duration.ofSeconds(1))
        assertTrue(last.isCompletedExceptionally)
        assertEquals(DialogueEndReason.FAILED, f.token.reason())
        lastGate.complete(null)
        f.drain()
        f.done(f.handle.closeAsync())
    }

    @Test
    fun laterPageSurvivesEarlierRejectionAndSettlesOnlyOnItsAck(env: Env) {
        val f = Fixture(env)
        val page = f.targets.current()!!
        val firstGate = CompletableFuture<Void>()
        val lastGate = CompletableFuture<Void>()
        f.write = { if (it.widgets().any { w -> w.choiceId == "c4" }) firstGate else lastGate }
        val first = f.renderer.browse(page, 3).toCompletableFuture()
        assertTrue(env.tickWhile({ f.frames.size < 2 }, Duration.ofSeconds(2)))
        val last = f.renderer.browse(page, 6).toCompletableFuture()
        f.drain()
        firstGate.completeExceptionally(DialogueSurface.FrameRejected("page 3 rejected"))
        f.drain()
        assertTrue(first.isCompletedExceptionally)
        assertFalse(last.isDone)
        assertEquals(listOf("c7"), f.frames.last())
        assertSame(page, f.targets.current())
        lastGate.complete(null)
        assertEquals(InputResult.ACCEPTED, f.done(last))
        assertEquals(setOf("c7"), f.targets.current()!!.choices().keys)
        f.write = { CompletableFuture.completedFuture(null) }
        f.renderer.refreshOutput()
        f.drain()
        assertEquals(listOf("c7"), f.frames.last())
        assertFalse(f.token.isCancelled())
        f.done(f.handle.closeAsync())
    }

    @Test
    fun returnAfterInterveningPageIsNotAnImmediateDuplicate(env: Env) {
        val f = Fixture(env)
        val page = f.targets.current()!!
        val firstGate = CompletableFuture<Void>()
        val lastGate = CompletableFuture<Void>()
        var writes = 0
        f.write = { if (writes++ == 0) firstGate else lastGate }
        val first = f.renderer.browse(page, 3).toCompletableFuture()
        assertTrue(env.tickWhile({ f.frames.size < 2 }, Duration.ofSeconds(2)))
        val duplicate = f.renderer.browse(page, 3).toCompletableFuture()
        val superseded = f.renderer.browse(page, 6).toCompletableFuture()
        val later = f.renderer.browse(page, 3).toCompletableFuture()
        f.drain()
        firstGate.completeExceptionally(DialogueSurface.FrameRejected("first page 3 rejected"))
        f.drain()
        assertTrue(first.isCompletedExceptionally)
        assertTrue(duplicate.isCompletedExceptionally)
        assertFalse(superseded.isDone)
        assertFalse(later.isDone)
        assertEquals(listOf("c4", "c5", "c6"), f.frames.last())
        lastGate.complete(null)
        assertEquals(InputResult.ACCEPTED, f.done(superseded))
        assertEquals(InputResult.ACCEPTED, f.done(later))
        f.write = { CompletableFuture.completedFuture(null) }
        f.renderer.refreshOutput()
        f.drain()
        assertEquals(listOf("c4", "c5", "c6"), f.frames.last())
        f.done(f.handle.closeAsync())
    }
}
