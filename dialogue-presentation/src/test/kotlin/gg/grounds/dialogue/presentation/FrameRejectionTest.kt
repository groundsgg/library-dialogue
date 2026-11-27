package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameRejectionTest {
    private class Fixture {
        val calls = ArrayList<WidgetFrame>()
        var write: (WidgetFrame) -> CompletionStage<Void> = {
            CompletableFuture.completedFuture(null)
        }
        val surface = DialogueSurface {
            CompletableFuture.completedFuture(
                object : DialogueSurface.SurfaceHandle {
                    override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                        calls += frame
                        return write(frame)
                    }

                    override fun closeAsync(): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)
                }
            )
        }
        val request = PresenterFixtures.request()
        val handle =
            DialoguePresenter(
                    mapOf(
                        "npc" to
                            DialoguePresenter.SurfaceProfile(
                                surface,
                                LayoutProfile.npc(),
                                DialogueTheme.neutral(),
                                FontMetrics.defaults(),
                                false,
                            )
                    ),
                    PresenterFixtures.Clock(),
                )
                .prepare(
                    DialogueRenderer.PresentationRequest(
                        request.sessionId,
                        request.audienceId,
                        request.hostGeneration,
                        PresentationPreferences("npc", Duration.ZERO, false, emptyMap()),
                        request.cancellation,
                        request.deadlineNanos,
                    )
                )
                .toCompletableFuture()
                .join()
        val cursor = handle as ChoiceViewport.Controller

        fun view(text: String, selected: String = "c1", revision: Long = 1): DialogueView =
            PresenterFixtures.view(text, text.length, selected, revision)

        fun draw(view: DialogueView) = handle.replace(view).toCompletableFuture()

        fun choices() =
            calls.last().widgets().filter { it.kind == WidgetFrame.Kind.CHOICE }.map { it.choiceId }
    }

    @Test
    fun immediateDuplicateKeepsIdentityWhileItsRedrawIsPendingAfterPhysicalAck() {
        val f = Fixture()
        val view = f.view("choices")
        f.draw(view).join()
        val firstGate = CompletableFuture<Void>()
        val redrawGate = CompletableFuture<Void>()
        var writes = 0
        f.write = { if (writes++ == 0) firstGate else redrawGate }
        f.cursor.setChoiceStart(view, 3).toCompletableFuture().join()
        val first = f.draw(view)
        f.cursor.setChoiceStart(view, 3).toCompletableFuture().join()
        val duplicate = f.draw(view)
        firstGate.complete(null)
        first.join()
        f.cursor.setChoiceStart(view, 3).toCompletableFuture().join()
        val laterDuplicate = f.draw(view)
        redrawGate.completeExceptionally(DialogueSurface.FrameRejected("duplicate redraw rejected"))
        assertTrue(duplicate.isCompletedExceptionally)
        assertTrue(laterDuplicate.isCompletedExceptionally)
        assertEquals(
            3,
            f.calls.size,
            "An immediate duplicate must not become a separate failed attempt",
        )
        f.handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun rejectedPageLeavesDifferentQueuedPagePendingUntilItsAck() {
        val f = Fixture()
        val view = f.view("choices")
        f.draw(view).join()
        val firstGate = CompletableFuture<Void>()
        val lastGate = CompletableFuture<Void>()
        f.write = { if (it.widgets().any { w -> w.choiceId == "c4" }) firstGate else lastGate }
        assertTrue(f.cursor.setChoiceStart(view, 3).toCompletableFuture().join())
        val first = f.draw(view)
        assertTrue(f.cursor.setChoiceStart(view, 6).toCompletableFuture().join())
        val last = f.draw(view)
        firstGate.completeExceptionally(DialogueSurface.FrameRejected("page 3 rejected"))
        assertTrue(first.isCompletedExceptionally)
        assertFalse(last.isDone)
        assertEquals(listOf("leave"), f.choices())
        lastGate.complete(null)
        last.join()
        f.write = { CompletableFuture.completedFuture(null) }
        f.draw(view).join()
        assertEquals(listOf("leave"), f.choices())
        f.handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun returnToEarlierOffsetIsNewIntentAfterInterveningPage() {
        val f = Fixture()
        val view = f.view("choices")
        f.draw(view).join()
        val firstGate = CompletableFuture<Void>()
        val laterGate = CompletableFuture<Void>()
        var writes = 0
        f.write = { if (writes++ == 0) firstGate else laterGate }
        f.cursor.setChoiceStart(view, 3).toCompletableFuture().join()
        val first = f.draw(view)
        f.cursor.setChoiceStart(view, 3).toCompletableFuture().join()
        val duplicate = f.draw(view)
        f.cursor.setChoiceStart(view, 6).toCompletableFuture().join()
        val superseded = f.draw(view)
        f.cursor.setChoiceStart(view, 3).toCompletableFuture().join()
        val later = f.draw(view)
        firstGate.completeExceptionally(DialogueSurface.FrameRejected("first page 3 rejected"))
        assertTrue(first.isCompletedExceptionally)
        assertTrue(duplicate.isCompletedExceptionally)
        assertFalse(superseded.isDone)
        assertFalse(later.isDone)
        assertEquals(listOf("c4", "c5", "c6"), f.choices())
        laterGate.complete(null)
        superseded.join()
        later.join()
        f.write = { CompletableFuture.completedFuture(null) }
        f.draw(view).join()
        assertEquals(listOf("c4", "c5", "c6"), f.choices())
        f.handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun rejectedViewRestoresAcknowledgedViewAndExplicitPage() {
        val f = Fixture()
        val accepted = f.view("accepted")
        f.draw(accepted).join()
        assertTrue(f.cursor.setChoiceStart(accepted, 3).toCompletableFuture().join())
        f.draw(accepted).join()
        val page = f.choices()
        assertTrue(page.contains("c4"))
        f.write = { CompletableFuture.failedFuture(DialogueSurface.FrameRejected("foreign owner")) }
        assertTrue(f.draw(f.view("rejected", "c2", 2)).isCompletedExceptionally)
        assertTrue(f.cursor.setChoiceStart(accepted, 3).toCompletableFuture().join())
        f.write = { CompletableFuture.completedFuture(null) }
        f.draw(accepted).join()
        assertEquals(page, f.choices())
        f.handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun queuedValidViewSurvivesRejectedAttemptAndDuplicateObservers() {
        val f = Fixture()
        val rejected = f.view("bad", revision = 2)
        val gate = CompletableFuture<Void>()
        f.write = {
            if (it.semantic() == rejected) gate else CompletableFuture.completedFuture(null)
        }
        val first = f.draw(rejected)
        val duplicate = f.draw(rejected)
        val newer = f.view("good", revision = 3)
        val good = f.draw(newer)
        gate.completeExceptionally(DialogueSurface.FrameRejected("foreign owner"))
        assertTrue(first.isCompletedExceptionally)
        assertTrue(duplicate.isCompletedExceptionally)
        good.join()
        assertTrue(f.cursor.setChoiceStart(newer, 3).toCompletableFuture().join())
        assertEquals("good", f.calls.last().semantic().text())
        f.handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun acknowledgedIntermediateFrameBecomesRollbackPoint() {
        val f = Fixture()
        val firstGate = CompletableFuture<Void>()
        val badGate = CompletableFuture<Void>()
        f.write = { if (it.semantic().text() == "first") firstGate else badGate }
        val first = f.view("first")
        val written = f.draw(first)
        val rejected = f.draw(f.view("bad", revision = 2))
        firstGate.complete(null)
        written.join()
        badGate.completeExceptionally(DialogueSurface.FrameRejected("foreign owner"))
        assertTrue(rejected.isCompletedExceptionally)
        assertTrue(f.cursor.setChoiceStart(first, 3).toCompletableFuture().join())
        f.handle.closeAsync().toCompletableFuture().join()
    }
}
