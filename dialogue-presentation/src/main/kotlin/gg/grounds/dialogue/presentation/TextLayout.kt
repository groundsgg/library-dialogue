package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.core.text.ResolvedText

/** Full-text layout remains fixed as the core reveals its grapheme prefix. */
class TextLayout {
    @JvmRecord
    data class TextLimits(val maxWidth: Double, val maxLines: Int) {
        init {
            require(maxWidth.isFinite() && maxWidth > 0 && maxLines in 1..1024) {
                "Invalid text layout bounds"
            }
        }
    }

    class Line(startGrapheme: Int, graphemes: List<String>, advances: List<Double>, width: Double) {
        private val startGrapheme = startGrapheme
        private val graphemes = java.util.List.copyOf(graphemes)
        private val advances = java.util.List.copyOf(advances)
        private val width = width

        fun startGrapheme(): Int = startGrapheme

        fun graphemes(): List<String> = graphemes

        fun advances(): List<Double> = advances

        fun width(): Double = width

        override fun equals(other: Any?): Boolean =
            other is Line &&
                startGrapheme == other.startGrapheme &&
                graphemes == other.graphemes &&
                advances == other.advances &&
                width == other.width

        override fun hashCode(): Int =
            ((startGrapheme * 31 + graphemes.hashCode()) * 31 + advances.hashCode()) * 31 +
                width.hashCode()

        override fun toString(): String =
            "Line[startGrapheme=$startGrapheme, graphemes=$graphemes, advances=$advances, width=$width]"
    }

    class TextLayoutResult(
        lines: List<Line>,
        width: Double,
        height: Double,
        private val problem: String?,
    ) {
        private val lines = java.util.List.copyOf(lines)
        private val width = width
        private val height = height

        fun lines(): List<Line> = lines

        fun width(): Double = width

        fun height(): Double = height

        fun problem(): String? = problem

        fun isValid(): Boolean = problem == null

        fun visible(revealed: Int): List<String> {
            require(revealed >= 0) { "Negative reveal" }
            return lines.map { line ->
                line
                    .graphemes()
                    .subList(
                        0,
                        minOf(line.graphemes().size, maxOf(0, revealed - line.startGrapheme())),
                    )
                    .joinToString("")
            }
        }

        override fun equals(other: Any?): Boolean =
            other is TextLayoutResult &&
                lines == other.lines &&
                width == other.width &&
                height == other.height &&
                problem == other.problem

        override fun hashCode(): Int =
            ((lines.hashCode() * 31 + width.hashCode()) * 31 + height.hashCode()) * 31 +
                (problem?.hashCode() ?: 0)

        override fun toString(): String =
            "TextLayoutResult[lines=$lines, width=$width, height=$height, problem=$problem]"
    }

    fun layout(text: ResolvedText, metrics: FontMetrics, limits: TextLimits): TextLayoutResult {
        val clusters = Graphemes.split(text.plain)
        val widths = ArrayList<Double>()
        val lineHeight = metrics.lineHeight()
        if (!lineHeight.isFinite() || lineHeight <= 0) return invalid("FONT_METRICS_INVALID")
        for (grapheme in clusters) {
            if (newline(grapheme)) {
                widths.add(0.0)
                continue
            }
            val width =
                try {
                    metrics.width(grapheme)
                } catch (_: IllegalArgumentException) {
                    return invalid("MISSING_GLYPH")
                }
            if (!width.isFinite() || width < 0) return invalid("FONT_METRICS_INVALID")
            if (width > limits.maxWidth) return invalid("LAYOUT_OVERFLOW")
            widths.add(width)
        }
        val lines = ArrayList<Line>()
        var start = 0
        while (start < clusters.size) {
            if (lines.size >= limits.maxLines) return invalid("LAYOUT_OVERFLOW")
            var end = start
            var lastSpace = -1
            var width = 0.0
            while (
                end < clusters.size &&
                    !newline(clusters[end]) &&
                    width + widths[end] <= limits.maxWidth
            ) {
                width += widths[end]
                if (clusters[end].isBlank()) lastSpace = end
                end++
            }
            if (end < clusters.size && !newline(clusters[end]) && lastSpace >= start) {
                end = lastSpace + 1
                width = (start until end).sumOf { widths[it] }
            }
            lines.add(Line(start, clusters.subList(start, end), widths.subList(start, end), width))
            start = end
            if (start < clusters.size && newline(clusters[start])) start++
        }
        if (clusters.isEmpty() || newline(clusters.last())) {
            if (lines.size >= limits.maxLines) return invalid("LAYOUT_OVERFLOW")
            lines.add(Line(clusters.size, emptyList(), emptyList(), 0.0))
        }
        return TextLayoutResult(
            lines,
            lines.maxOfOrNull { it.width() } ?: 0.0,
            lines.size * lineHeight,
            null,
        )
    }

    companion object {
        private fun newline(grapheme: String): Boolean =
            grapheme == "\n" || grapheme == "\r" || grapheme == "\r\n"

        private fun invalid(problem: String): TextLayoutResult =
            TextLayoutResult(emptyList(), 0.0, 0.0, problem)
    }
}
