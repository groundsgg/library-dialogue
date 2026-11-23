package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.validation.ContentPin
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TypingSoundTest {
    private val session = UUID.randomUUID()

    private fun view(count: Int, text: String = "a b.c", visit: Long = 1): DialogueView =
        DialogueView(
            session,
            ContentPin("0".repeat(64)),
            "message",
            visit,
            if (count < gg.grounds.dialogue.core.text.Graphemes.split(text).size)
                DialogueView.State.REVEALING
            else DialogueView.State.WAITING_FOR_INPUT,
            count.toLong(),
            count.toLong(),
            "en",
            "NPC",
            text,
            emptyList(),
            null,
            gg.grounds.dialogue.core.text.Graphemes.split(text).size,
            count,
            DialogueView.HintPhase.REVEAL,
            "",
            null,
        )

    @Test
    fun naturalLettersAreRateLimitedAndWhitespaceAndPunctuationStaySilent() {
        val sound = TypingSound()
        assertFalse(sound.observe(view(0), 0))
        assertTrue(sound.observe(view(1), 1))
        assertFalse(sound.observe(view(2), 100_000_001))
        assertTrue(sound.observe(view(3), 100_000_002))
        assertFalse(sound.observe(view(4), 200_000_002))
        assertTrue(sound.observe(view(5), 200_000_003))
        assertFalse(sound.observe(view(5), 400_000_000))
    }

    @Test
    fun catchupMakesOneTickAndDoesNotReplayThrottledCharacters() {
        val sound = TypingSound()
        sound.observe(view(0, "abcdef"), 0)
        assertTrue(sound.observe(view(3, "abcdef"), 1))
        assertFalse(sound.observe(view(4, "abcdef"), 30_000_000))
        assertFalse(sound.observe(view(4, "abcdef"), 200_000_000))
        assertTrue(sound.observe(view(6, "abcdef"), 200_000_001))
    }

    @Test
    fun skippedLastCharacterAndNewVisitOrTextAreSilent() {
        val sound = TypingSound()
        sound.observe(view(4), 0)
        assertFalse(sound.observe(view(5), 100_000_000, suppressed = true))
        assertFalse(sound.observe(view(5, visit = 2), 200_000_000))
        assertFalse(sound.observe(view(5, "other", 2), 300_000_000))
        sound.reset()
        assertFalse(sound.observe(view(5), 400_000_000))
    }

    @Test
    fun unicodeGraphemesUseLetterCodePoints() {
        val sound = TypingSound()
        sound.observe(view(0, "👨‍👩‍👧é!"), 0)
        assertFalse(sound.observe(view(1, "👨‍👩‍👧é!"), 1))
        assertTrue(sound.observe(view(2, "👨‍👩‍👧é!"), 2))
    }
}
