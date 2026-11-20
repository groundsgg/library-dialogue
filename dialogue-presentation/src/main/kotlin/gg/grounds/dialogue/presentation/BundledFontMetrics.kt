package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.validation.ContentFingerprint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

internal class BundledFontMetrics private constructor() : FontMetrics {
    private val advances: Map<Int, Double>
    private val pin: String
    private val height: Double

    init {
        val metrics =
            javaClass.getResourceAsStream("/default-theme/metrics.properties")?.use {
                it.readAllBytes()
            } ?: throw IllegalStateException("Missing bundled metrics")
        val properties = Properties().apply { load(ByteArrayInputStream(metrics)) }
        val bytes =
            javaClass.getResourceAsStream("/default-theme/DejaVuSans.ttf")?.use {
                it.readAllBytes()
            } ?: throw IllegalStateException("Missing bundled font")
        check(ContentFingerprint.hash(bytes).sha256 == properties.getProperty("fontSha256")) {
            "Font/source pin mismatch"
        }
        pin =
            ByteArrayOutputStream().use {
                it.writeBytes(bytes)
                it.writeBytes(metrics)
                ContentFingerprint.hash(it.toByteArray()).sha256
            }
        height = properties.getProperty("lineHeight").toDouble()
        advances =
            properties
                .stringPropertyNames()
                .asSequence()
                .filter { it.startsWith("glyph.") }
                .associate { it.substring(6).toInt() to properties.getProperty(it).toDouble() }
                .toMap()
    }

    override fun width(grapheme: String): Double {
        require(grapheme.codePointCount(0, grapheme.length) == 1) { "MISSING_GLYPH" }
        return advances[grapheme.codePointAt(0)] ?: throw IllegalArgumentException("MISSING_GLYPH")
    }

    override fun lineHeight(): Double = height

    override fun pin(): String = pin

    companion object {
        @JvmField val INSTANCE = BundledFontMetrics()
    }
}
