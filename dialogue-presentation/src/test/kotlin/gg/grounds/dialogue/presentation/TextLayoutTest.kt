package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.text.ResolvedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextLayoutTest {
    @Test
    fun fullTextReservedDuringReveal() {
        val result =
            TextLayout()
                .layout(ResolvedText("ab cd", "en"), METRICS, TextLayout.TextLimits(12.0, 3))
        assertTrue(result.isValid())
        assertEquals(12.0, result.width())
        assertEquals(20.0, result.height())
        assertEquals(listOf("", ""), result.visible(0))
        assertEquals(listOf("ab", ""), result.visible(2))
        assertEquals(listOf("ab ", "cd"), result.visible(5))
        assertEquals(20.0, result.height())
    }

    @Test
    fun unicodeAndOverflowAreExplicit() {
        val layout = TextLayout()
        assertTrue(
            layout
                .layout(ResolvedText("你好世界", "zh"), METRICS, TextLayout.TextLimits(8.0, 3))
                .isValid()
        )
        assertEquals(
            "LAYOUT_OVERFLOW",
            layout
                .layout(ResolvedText("abcdefghij", "en"), METRICS, TextLayout.TextLimits(8.0, 2))
                .problem(),
        )
        assertEquals(
            "MISSING_GLYPH",
            layout
                .layout(
                    ResolvedText("你好", "zh"),
                    FontMetrics.defaults(),
                    TextLayout.TextLimits(320.0, 3),
                )
                .problem(),
        )
    }

    @Test
    fun newlinesKeepOriginalGraphemeOffsets() {
        val result =
            TextLayout().layout(ResolvedText("a\nb", "en"), METRICS, TextLayout.TextLimits(12.0, 3))
        assertEquals(listOf("a", ""), result.visible(2))
        assertEquals(listOf("a", "b"), result.visible(3))
    }

    @Test
    fun fontAndMetricsShareOneAssetPin() {
        assertEquals(
            "3d89c53e253dbbb31cb77e2267cbddc54c0e7445d4c2a917207d8a8d9536ac20",
            FontMetrics.defaults().pin(),
        )
    }

    companion object {
        val METRICS =
            object : FontMetrics {
                override fun width(grapheme: String): Double = 4.0

                override fun lineHeight(): Double = 10.0

                override fun pin(): String = "test-4x10"
            }
    }
}
