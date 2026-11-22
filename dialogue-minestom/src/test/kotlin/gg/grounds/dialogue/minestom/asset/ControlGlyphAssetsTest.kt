package gg.grounds.dialogue.minestom.asset

import java.io.ByteArrayInputStream
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ControlGlyphAssetsTest {
    @Test
    fun controlGlyphInkAndMetricsMatchBothDefaultProfiles() {
        for (profile in
            listOf(MinecraftPackProfile.public262(), MinecraftPackProfile.grounds263())) {
            val entries = AssetBundle.loadDefault(profile).entries()
            val image =
                ImageIO.read(
                    ByteArrayInputStream(
                        entries.getValue("assets/dialogue/textures/font/controls.png")
                    )
                )
            assertEquals(64, image.width, profile.id)
            assertEquals(256, image.height, profile.id)

            val metadata =
                Properties().apply {
                    entries
                        .getValue("assets/dialogue/control_glyphs.properties")
                        .inputStream()
                        .use {
                            load(it)
                        }
                }
            val codepoints = listOf(0xe790, 0xe791, 0xe792, 0xe793)
            for ((cell, codepoint) in codepoints.withIndex()) {
                var left = 16
                var right = -1
                var top = 256
                var bottom = 0
                for (y in 0 until image.height) {
                    for (x in 0 until 16) {
                        if (image.getRGB(cell * 16 + x, y) ushr 24 != 0) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                            top = minOf(top, y)
                            bottom = maxOf(bottom, y + 1)
                            assertTrue(
                                y < 12,
                                "${profile.id} U+${codepoint.toString(16)} ink row $y",
                            )
                        }
                    }
                }
                assertTrue(left <= right, "${profile.id} U+${codepoint.toString(16)} has no ink")
                for ((name, scale) in listOf("native" to 1.0, "hud" to .75)) {
                    val expectedAdvance = ((right + 1) * scale).roundToInt() + 1
                    val expectedInkTop = top * scale
                    val expectedInkBottom = bottom * scale
                    assertEquals(
                        expectedAdvance.toString(),
                        metadata.getProperty("$name.advance.$codepoint"),
                        "${profile.id} U+${codepoint.toString(16)} $name advance",
                    )
                    assertEquals(
                        "$expectedInkTop,$expectedInkBottom",
                        metadata.getProperty("$name.ink.$codepoint"),
                        "${profile.id} U+${codepoint.toString(16)} $name ink",
                    )
                }
            }

            assertTrue(image.getRGB(3 * 16 + 10, 2) ushr 24 != 0, "Upper swap arrow tip")
            assertTrue(image.getRGB(3 * 16 + 4, 5) ushr 24 != 0, "Lower swap arrow tip")
        }
    }
}
