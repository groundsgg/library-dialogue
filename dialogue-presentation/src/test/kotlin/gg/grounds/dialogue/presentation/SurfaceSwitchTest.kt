package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurfaceSwitchTest {
    @Test
    fun switchKeepsSemanticIdentityAndClosesOldSurface() {
        val hud = PresenterFixtures.Surface()
        val npc = PresenterFixtures.Surface()
        val handle =
            PresenterFixtures.presenter(hud, npc)
                .prepare(PresenterFixtures.request())
                .toCompletableFuture()
                .join()
        val view = PresenterFixtures.view("Hello", 5, "leave", 1)
        handle.replace(view).toCompletableFuture().join()
        handle
            .reconfigure(PresentationPreferences("npc", Duration.ofMillis(35), false, emptyMap()))
            .toCompletableFuture()
            .join()
        assertEquals(1, hud.closes)
        assertEquals(1, npc.opens)
        assertEquals(view.nodeVisitId(), npc.frames.last().semantic().nodeVisitId())
        assertEquals("leave", npc.frames.last().semantic().selectedChoiceId())
        handle.closeAsync().toCompletableFuture().join()
        assertEquals(1, npc.closes)
        handle.closeAsync().toCompletableFuture().join()
        assertEquals(1, npc.closes)
    }

    @Test
    fun closeWhileNewSurfacePreparesDisposesLateHandle() {
        val hud = PresenterFixtures.Surface()
        val npc = PresenterFixtures.Surface()
        npc.delayed = CompletableFuture()
        val handle =
            PresenterFixtures.presenter(hud, npc)
                .prepare(PresenterFixtures.request())
                .toCompletableFuture()
                .join()
        handle.replace(PresenterFixtures.view("Hello", 5, "c1", 1))
        val change =
            handle.reconfigure(
                PresentationPreferences("npc", Duration.ofMillis(35), false, emptyMap())
            )
        assertFalse(change.toCompletableFuture().isDone)
        val close = handle.closeAsync()
        assertFalse(close.toCompletableFuture().isDone)
        npc.delayed!!.complete(npc.handle())
        assertTrue(close.toCompletableFuture().isDone)
        assertTrue(change.toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, npc.closes)
        assertTrue(npc.frames.isEmpty())
    }

    @Test
    fun lateFirstFrameCannotActivateAfterItsDeadline() {
        val hud = PresenterFixtures.Surface()
        val gate = CompletableFuture<Void>()
        val npc =
            object : PresenterFixtures.Surface() {
                override fun handle(): DialogueSurface.SurfaceHandle =
                    object : DialogueSurface.SurfaceHandle {
                        override fun replace(frame: WidgetFrame): CompletionStage<Void> = gate

                        override fun closeAsync(): CompletionStage<Void> {
                            closes++
                            return CompletableFuture.completedFuture(null)
                        }
                    }
            }
        val clock = PresenterFixtures.Clock()
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
                clock,
            )
        val handle = presenter.prepare(PresenterFixtures.request()).toCompletableFuture().join()
        handle.replace(PresenterFixtures.view("Hello", 5, "c1", 1)).toCompletableFuture().join()
        val switchResult =
            handle.reconfigure(PresentationPreferences("npc", Duration.ZERO, true, emptyMap()))
        clock.now = 6_000_000_000L
        gate.complete(null)
        assertTrue(switchResult.toCompletableFuture().isCompletedExceptionally)
        assertEquals(0, hud.closes)
        assertEquals(1, npc.closes)
        handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun delayedFrameWritesAreSerialized() {
        val first = CompletableFuture<Void>()
        val calls = arrayListOf<WidgetFrame>()
        val hud =
            object : PresenterFixtures.Surface() {
                override fun handle(): DialogueSurface.SurfaceHandle =
                    object : DialogueSurface.SurfaceHandle {
                        override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                            calls += frame
                            return if (calls.size == 1) first
                            else CompletableFuture.completedFuture(null)
                        }

                        override fun closeAsync(): CompletionStage<Void> {
                            closes++
                            return CompletableFuture.completedFuture(null)
                        }
                    }
            }
        val handle =
            PresenterFixtures.presenter(hud, PresenterFixtures.Surface())
                .prepare(PresenterFixtures.request())
                .toCompletableFuture()
                .join()
        handle.replace(PresenterFixtures.view("Hello", 1, "c1", 1))
        val next = handle.replace(PresenterFixtures.view("Hello", 5, "c2", 2))
        assertEquals(1, calls.size)
        assertFalse(next.toCompletableFuture().isDone)
        first.complete(null)
        assertEquals(2, calls.size)
        assertEquals("c2", calls.last().semantic().selectedChoiceId())
        handle.closeAsync().toCompletableFuture().join()
    }
}
