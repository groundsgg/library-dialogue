package gg.grounds.dialogue.minestom.asset

import gg.grounds.dialogue.presentation.FontMetrics
import java.io.ByteArrayInputStream
import java.util.Properties

/** Optional, bounded metadata; a present document must describe all four inputs. */
internal object ControlGlyphMetadata {
    data class Metrics(val advances: Map<Int, Int>, val ink: Map<Int, FontMetrics.InkBounds>)

    fun parse(bytes: ByteArray?): Map<String, Metrics> {
        if (bytes == null) return emptyMap()
        require(bytes.size <= 4096) { "CONTROL_GLYPH_METRICS_LIMIT" }
        try {
            val properties = Properties().apply { load(ByteArrayInputStream(bytes)) }
            require(properties.getProperty("schemaVersion") == "1")
            val expected = mutableSetOf("schemaVersion")
            val result = linkedMapOf<String, Metrics>()
            for (profile in listOf("native", "hud")) {
                val advances = linkedMapOf<Int, Int>()
                val ink = linkedMapOf<Int, FontMetrics.InkBounds>()
                val scale = if (profile == "native") 1.0 else 0.75
                for (codePoint in 0xe790..0xe793) {
                    val advanceKey = "$profile.advance.$codePoint"
                    val inkKey = "$profile.ink.$codePoint"
                    expected += advanceKey
                    expected += inkKey
                    val advance = properties.getProperty(advanceKey).toInt()
                    require(advance in 2..(16 * scale + 1).toInt())
                    val values = properties.getProperty(inkKey).split(',')
                    require(values.size == 2)
                    val bounds = FontMetrics.InkBounds(values[0].toDouble(), values[1].toDouble())
                    require(
                        bounds.top >= 0 && bounds.bottom > bounds.top && bounds.bottom <= 12 * scale
                    )
                    advances[codePoint] = advance
                    ink[codePoint] = bounds
                }
                result[profile] = Metrics(advances, ink)
            }
            require(properties.stringPropertyNames() == expected)
            return result
        } catch (error: Exception) {
            throw IllegalArgumentException("CONTROL_GLYPH_METRICS", error)
        }
    }
}
