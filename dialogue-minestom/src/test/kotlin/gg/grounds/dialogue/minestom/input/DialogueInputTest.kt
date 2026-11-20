package gg.grounds.dialogue.minestom.input

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogueInputTest {
    @Test
    fun openingBurstCannotAdvance() {
        val gate = ClickBurstGate(Duration.ofMillis(350))
        gate.opened(0)
        assertFalse(gate.accept(1))
        assertFalse(gate.accept(300_000_000))
        assertFalse(gate.accept(600_000_000))
        assertTrue(gate.accept(950_000_000))
        assertFalse(gate.accept(950_000_001))
    }

    @Test
    fun scrollWrapKeepsVirtualHistory() {
        val cursor = SlotCursor(0, 7)
        assertEquals(-1, cursor.scroll(8))
        assertEquals(-1, cursor.scroll(7))
        assertEquals(1, cursor.scroll(8))
        assertEquals(1, cursor.scroll(0))
        assertEquals(0, cursor.scroll(0))
    }

    @Test
    fun oldPongCannotResetNewCursor() {
        val old = SlotCursor(0, 1)
        old.scroll(8)
        old.fence(10)
        val next = SlotCursor(0, 2)
        next.scroll(1)
        next.fence(11)
        assertFalse(next.pong(10, 1, 0))
        assertFalse(next.pong(11, 1, 0))
        assertEquals(1, next.slot())
        assertTrue(next.pong(11, 2, 0))
        assertEquals(0, next.slot())
    }
}
