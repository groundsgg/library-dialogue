package gg.grounds.dialogue.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class GraphemesTest {
    @Test
    fun clustersAreNeverSplit() {
        assertEquals(5, Graphemes.split("e\u0301 👨‍👩‍👧‍👦 🇩🇪").size)
        assertEquals("e\u0301", Graphemes.reveal("e\u0301x", 1))
    }
}
