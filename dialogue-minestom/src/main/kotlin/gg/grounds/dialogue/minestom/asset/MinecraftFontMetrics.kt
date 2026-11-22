package gg.grounds.dialogue.minestom.asset

import gg.grounds.dialogue.presentation.FontMetrics

/** Native bitmap advances from the frozen atlas, never host-installed font metrics. */
class MinecraftFontMetrics
internal constructor(
    private val pin: String,
    advances: Map<Int, Int>,
    private val height: Double,
    ink: Map<Int, FontMetrics.InkBounds>,
    private val controlIcons: Boolean,
) : FontMetrics {
    internal constructor(
        pin: String,
        advances: Map<Int, Int>,
        height: Double,
        ink: Map<Int, FontMetrics.InkBounds>,
    ) : this(pin, advances, height, ink, false)

    override fun supportsControlGlyphs(): Boolean = controlIcons

    private val advances = advances.toMap()
    private val ink = ink.toMap()

    internal constructor(pin: String, advances: Map<Int, Int>) : this(pin, advances, 16.0)

    internal constructor(
        pin: String,
        advances: Map<Int, Int>,
        height: Double,
    ) : this(pin, advances, height, emptyMap())

    override fun width(grapheme: String): Double {
        if (grapheme.codePointCount(0, grapheme.length) != 1) {
            throw IllegalArgumentException("MISSING_GLYPH")
        }
        return advances[grapheme.codePointAt(0)]?.toDouble()
            ?: throw IllegalArgumentException("MISSING_GLYPH")
    }

    override fun inkBounds(grapheme: String): FontMetrics.InkBounds {
        if (grapheme.codePointCount(0, grapheme.length) == 1) {
            ink[grapheme.codePointAt(0)]?.let {
                return it
            }
        }
        return super.inkBounds(grapheme)
    }

    override fun lineHeight(): Double = height

    override fun pin(): String = pin
}
