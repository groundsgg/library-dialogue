package gg.grounds.dialogue.core.runtime

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class DialogueInputTest {
    companion object {
        @JvmStatic
        fun confirm(handle: DialogueHandle, impulse: Long): DialogueInput.Confirm {
            val view = handle.snapshot()
            return DialogueInput.Confirm(
                DialogueInput.InputStamp(view.sessionId(), view.renderRevision(), impulse),
                view.selectedChoiceId(),
            )
        }
    }

    @Test
    fun openingDoubleAndHeldImpulse() {
        val rig = TestRig()
        val handle = rig.start()
        assertEquals(DialogueView.State.REVEALING, handle.snapshot().state())
        val opening = handle.submit(confirm(handle, 0))
        rig.scheduler.drain()
        assertEquals(InputResult.STALE, TestRig.done(opening))
        val first = handle.submit(confirm(handle, 1))
        rig.scheduler.drain()
        assertEquals(InputResult.REVEAL_ONLY, TestRig.done(first))
        assertEquals("start", handle.snapshot().nodeId())
        val duplicate = handle.submit(confirm(handle, 1))
        rig.scheduler.drain()
        assertEquals(InputResult.STALE, TestRig.done(duplicate))
        val next = handle.submit(confirm(handle, 2))
        rig.scheduler.drain()
        assertEquals(InputResult.ACCEPTED, TestRig.done(next))
        assertEquals("bye", handle.snapshot().nodeId())
        val reveal = handle.submit(confirm(handle, 3))
        rig.scheduler.drain()
        assertEquals(InputResult.REVEAL_ONLY, TestRig.done(reveal))
        assertEquals(DialogueView.HintPhase.FINISH, handle.snapshot().hint())
        handle.submit(confirm(handle, 4))
        rig.scheduler.drain()
        assertEquals(DialogueEndReason.COMPLETED, TestRig.done(handle.completion()).reason())
    }

    @Test
    fun clockDrivesRevealWithoutInvalidatingTargets() {
        val rig = TestRig()
        val handle = rig.start()
        val revision = handle.snapshot().renderRevision()
        assertEquals(0, handle.snapshot().revealedGraphemes())
        rig.scheduler.advance(Duration.ofMillis(35))
        assertEquals(1, handle.snapshot().revealedGraphemes())
        assertEquals(revision, handle.snapshot().renderRevision())
        handle.submit(DialogueInput.RevealFinished(handle.snapshot().sessionId(), revision))
        rig.scheduler.drain()
        assertEquals(1, handle.snapshot().revealedGraphemes())
        assertEquals("start", handle.snapshot().nodeId())
        rig.scheduler.advance(Duration.ofSeconds(1))
        assertEquals(DialogueView.State.WAITING_FOR_INPUT, handle.snapshot().state())
    }
}
