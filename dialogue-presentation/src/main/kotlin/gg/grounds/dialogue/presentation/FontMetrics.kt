package gg.grounds.dialogue.presentation

/** Metrics and glyph coverage are pinned to the same font asset as the eventual surface. */
interface FontMetrics {
    /** Foreground ink relative to the glyph origin; line advance remains independent. */
    @JvmRecord
    data class InkBounds(val top: Double, val bottom: Double) {
        init {
            require(top.isFinite() && bottom.isFinite() && bottom >= top) {
                "Invalid glyph ink bounds"
            }
        }
    }

    /** Legacy/custom metrics retain line-box alignment unless they provide ink data. */
    fun inkBounds(grapheme: String): InkBounds =
        if (
            grapheme.codePoints().allMatch {
                Character.isWhitespace(it) || Character.isSpaceChar(it)
            }
        ) {
            InkBounds(0.0, 0.0)
        } else {
            InkBounds(0.0, lineHeight())
        }

    fun width(grapheme: String): Double

    fun lineHeight(): Double

    fun pin(): String

    companion object {
        @JvmStatic fun defaults(): FontMetrics = BundledFontMetrics.INSTANCE
    }
}
