package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.text.LocaleContext
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TypingTimingTest {
    @Test
    fun deadlineUsesOriginalEnteredTimeAfterCoalescedProgress() {
        val rig = TestRig()
        rig.scheduler.advance(Duration.ofNanos(-1_000_000_000))
        val handle = rig.start()
        val first = rig.timings.first().second.orElseThrow()
        assertEquals(-1_000_000_000, first.enteredNanos())
        assertEquals(
            first.enteredNanos() + handle.snapshot().totalGraphemes() * 35_000_000L,
            first.endNanos(),
        )
        rig.scheduler.advanceBatched(Duration.ofMillis(123))
        val (frame, timing) = rig.timings.last()
        val later = timing.orElseThrow()
        assertEquals(3, frame.revealedGraphemes())
        assertEquals(first.endNanos(), later.endNanos())
        assertEquals(first.enteredNanos(), later.enteredNanos())
        assertEquals(frame.renderRevision(), later.renderRevision())
        assertEquals(frame.nodeVisitId(), later.nodeVisitId())
        assertEquals(frame.sessionId(), later.sessionId())
        assertEquals(
            first.endNanos() - rig.scheduler.nanoTime(),
            later.remainingNanos(rig.scheduler.nanoTime()),
        )
        rig.scheduler.advance(Duration.ofSeconds(1))
        assertTrue(rig.timings.last().second.isEmpty)
    }

    @Test
    fun acceptedLocaleInvalidatesBeforeReplacement() {
        val rig = TestRig()
        val handle = rig.start()
        rig.failTypingInvalidation = true
        val refreshed = handle.refreshLocale(LocaleContext("de", 1, "en"))
        rig.scheduler.drain()
        assertEquals(RefreshResult.UPDATED, TestRig.done(refreshed))
        assertEquals(1, rig.typingInvalidations)
        handle.refreshLocale(LocaleContext("en", 0, "en"))
        rig.scheduler.drain()
        assertEquals(1, rig.typingInvalidations)
        val closed = handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
        assertEquals(DialogueEndReason.CANCELLED, TestRig.done(closed).reason())
        assertEquals(2, rig.typingInvalidations)
    }

    @Test
    fun remainingDurationAcceptsNegativeAndWrappedNanoTime() {
        val timing =
            TypingTiming(UUID.randomUUID(), 1, 2, 35, Long.MAX_VALUE - 100, Long.MIN_VALUE + 99)
        assertEquals(150, timing.remainingNanos(Long.MAX_VALUE - 50))
        assertEquals(0, timing.remainingNanos(Long.MIN_VALUE + 100))
    }
}
