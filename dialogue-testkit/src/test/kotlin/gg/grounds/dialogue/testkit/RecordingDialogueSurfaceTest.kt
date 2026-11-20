package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RecordingDialogueSurfaceTest {
    @Test
    fun surfaceUsesRealPresenterPortAndImmutableSnapshots() {
        val fixture = RuntimeFixture()
        val surface = RecordingDialogueSurface()
        val presenter =
            DialoguePresenter(
                mapOf("hud" to DialoguePresenter.SurfaceProfile.semantic(surface)),
                fixture.clock,
            )
        val handle = fixture.start(presenter, "a")
        val before = surface.snapshots().first()
        assertFalse(before.frames().isEmpty())
        val size = before.frames().size
        fixture.clock.advanceBy(Duration.ofSeconds(1))
        assertEquals(size, before.frames().size)
        assertTrue(surface.snapshots().first().frames().size > size)
        handle.refreshLocale(LocaleContext("de", 1, "en"))
        fixture.clock.runUntilIdle()
        assertEquals("Hallo", surface.snapshots().first().frames().last().semantic().text())
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.clock.runUntilIdle()
        assertTrue(surface.snapshots().first().closed())
    }

    @Test
    fun closeIsIndependentAndRejectsReplaceAndConfigure() {
        val surface = RecordingDialogueSurface()
        val context =
            DialogueSurface.SurfaceContext(
                UUID.randomUUID(),
                "a",
                1,
                "hud",
                "font",
                CancellationToken(),
                3,
                100,
            )
        val first = RuntimeFixture.done(surface.open(context))
        RuntimeFixture.done(surface.open(context))
        val cleanup = first.closeAsync()
        assertSame(cleanup, first.closeAsync())
        assertThrows<CompletionException> {
            first.configure(PresentationPreferences.defaults()).toCompletableFuture().join()
        }
        val fixture = RuntimeFixture()
        val handle = fixture.start(RecordingDialogueRenderer(), "x")
        val frame = WidgetFrame(handle.snapshot(), emptyList(), "font", "theme")
        assertThrows<CompletionException> { first.replace(frame).toCompletableFuture().join() }
        val snapshot = surface.snapshots().first()
        assertEquals(2, snapshot.closeCalls())
        assertEquals(2, snapshot.rejectedWrites())
        assertTrue(snapshot.frames().isEmpty())
        assertFalse(surface.snapshots()[1].closed())
    }
}
