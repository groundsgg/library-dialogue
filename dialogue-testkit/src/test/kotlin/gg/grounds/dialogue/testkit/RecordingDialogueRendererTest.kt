package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.LocaleContext
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RecordingDialogueRendererTest {
    private fun request(audience: String) =
        DialogueRenderer.PresentationRequest(
            UUID.randomUUID(),
            audience,
            7,
            PresentationPreferences.defaults(),
            CancellationToken(),
            100,
        )

    @Test
    fun immutableSnapshotsDoNotChangeAfterLaterWrites() {
        val renderer = RecordingDialogueRenderer()
        val handle = RuntimeFixture.done(renderer.prepare(request("a")))
        val fixture = RuntimeFixture()
        val view = fixture.start(renderer, "live").snapshot()
        RuntimeFixture.done(handle.replace(view))
        val before = renderer.snapshots().first()
        RuntimeFixture.done(handle.replace(view))
        assertEquals(1, before.views().size)
        assertEquals(2, renderer.snapshots().first().views().size)
        assertThrows<UnsupportedOperationException> { (before.views() as MutableList).clear() }
        assertThrows<UnsupportedOperationException> {
            (renderer.snapshots() as MutableList).clear()
        }
    }

    @Test
    fun twoSessionsCloseIndependentlyAndRejectLateWrites() {
        val renderer = RecordingDialogueRenderer()
        val handle = RuntimeFixture.done(renderer.prepare(request("a")))
        RuntimeFixture.done(renderer.prepare(request("b")))
        val cleanup = handle.closeAsync()
        assertSame(cleanup, handle.closeAsync())
        // Completing a derived future cannot change the handle's cleanup result.
        cleanup.toCompletableFuture().obtrudeException(RuntimeException())
        RuntimeFixture.done(handle.closeAsync())
        assertThrows<CompletionException> {
            handle.reconfigure(PresentationPreferences.defaults()).toCompletableFuture().join()
        }
        val fixture = RuntimeFixture()
        val live = fixture.start(RecordingDialogueRenderer(), "live")
        assertThrows<CompletionException> {
            handle.replace(live.snapshot()).toCompletableFuture().join()
        }
        val first = renderer.snapshots().first()
        assertTrue(first.closed())
        assertEquals(3, first.closeCalls())
        assertEquals(2, first.rejectedWrites())
        assertTrue(first.views().isEmpty())
        assertFalse(renderer.snapshots()[1].closed())
    }

    @Test
    fun rendererUsesRealCorePort() {
        val fixture = RuntimeFixture()
        val renderer = RecordingDialogueRenderer()
        val handle = fixture.start(renderer, "one")
        fixture.clock.advanceBy(Duration.ofSeconds(1))
        assertEquals("Hello", renderer.snapshots().first().views().last().text())
        val language = handle.refreshLocale(LocaleContext("de", 1, "en"))
        fixture.clock.runUntilIdle()
        RuntimeFixture.done(language)
        assertEquals("Hallo", renderer.snapshots().first().views().last().text())
        handle.stop(DialogueEndReason.CANCELLED)
        fixture.clock.runUntilIdle()
        assertTrue(renderer.snapshots().first().closed())
        val size = renderer.snapshots().first().views().size
        fixture.clock.advanceBy(Duration.ofSeconds(20))
        assertEquals(size, renderer.snapshots().first().views().size)
    }
}
