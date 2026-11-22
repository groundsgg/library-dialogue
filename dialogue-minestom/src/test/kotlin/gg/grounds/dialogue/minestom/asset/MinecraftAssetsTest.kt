package gg.grounds.dialogue.minestom.asset

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MinecraftAssetsTest {
    @Test
    fun navigationChevronsAreCompactTransparentMirrorsInBothProfiles() {
        for (profile in
            listOf(MinecraftPackProfile.public262(), MinecraftPackProfile.grounds263())) {
            val bundle = AssetBundle.loadDefault(profile)
            val bytes = bundle.entries()["assets/dialogue/textures/font/navigation.png"]
            assertNotNull(bytes, "Page controls use hand-drawn chevrons rather than text arrows")
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(32, image.width)
            assertEquals(256, image.height)
            var ink = 0
            for (y in 0 until 256) {
                for (x in 0 until 16) {
                    val pixel = image.getRGB(x, y)
                    if (pixel ushr 24 != 0) {
                        ink++
                        assertTrue(y < 8)
                    }
                    if (y < 8) assertEquals(pixel, image.getRGB(16 + x, 7 - y))
                    else assertEquals(0, image.getRGB(16 + x, y))
                }
            }
            assertTrue(ink in 17..99)
            assertEquals(0, image.getRGB(0, 0))
            assertEquals(0xffffffff.toInt(), image.getRGB(7, 0))
            val providers =
                JsonParser.parseString(
                        String(
                            bundle.entries()["assets/dialogue/font/a_212.json"]!!,
                            StandardCharsets.UTF_8,
                        )
                    )
                    .asJsonObject
                    .getAsJsonArray("providers")
            assertTrue(
                providers.any {
                    val value = it.asJsonObject
                    value.has("file") &&
                        value.get("file").asString == "dialogue:font/navigation.png" &&
                        value.getAsJsonArray("chars")[0].asString == "\ue780\ue781"
                }
            )
        }
    }

    @Test
    fun belowHudKeepsTheExactOriginalPreviewPixels() {
        val bundle = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        val original = bundle.entries()["assets/dialogue/font-source/prototype_hud_panel.png"]
        assertNotNull(
            original,
            "Retain the actual preview source rather than guessing its proportions",
        )
        val source = ImageIO.read(ByteArrayInputStream(original))
        val generated =
            ImageIO.read(
                ByteArrayInputStream(
                    bundle.entries()["assets/dialogue/textures/font/hud_below_10_3.png"]
                )
            )
        for (y in 0 until 128) for (x in 0 until 320) assertEquals(
            source.getRGB(x, y),
            generated.getRGB(x, y),
        )
        assertTrue(bundle.hudMetrics().width("A") < bundle.metrics().width("A"))
        assertEquals(10.0, bundle.hudMetrics().lineHeight())
    }

    @Test
    fun splitAndCardBackgroundsHaveRealTransparentGaps() {
        val assets = AssetBundle.loadDefault(MinecraftPackProfile.public262()).entries()
        val images =
            listOf("hud", "hud_split", "hud_cards").associateWith { prefix ->
                val data = assets["assets/dialogue/textures/font/${prefix}_below_10_3.png"]
                assertNotNull(data, prefix)
                ImageIO.read(ByteArrayInputStream(data))
            }
        assertTrue((images.getValue("hud").getRGB(160, 68) ushr 24) > 0)
        assertEquals(0, images.getValue("hud_split").getRGB(160, 68) ushr 24)
        assertEquals(0, images.getValue("hud_cards").getRGB(160, 68) ushr 24)
        assertTrue((images.getValue("hud_split").getRGB(160, 100) ushr 24) > 0)
        assertEquals(0, images.getValue("hud_cards").getRGB(160, 100) ushr 24)
    }

    @Test
    fun cardTexturesDoNotPaintUnusedAnswerSlots() {
        val entries = AssetBundle.loadDefault(MinecraftPackProfile.public262()).entries()
        for (count in 0..3) {
            val data = entries["assets/dialogue/textures/font/hud_cards_below_10_${count}.png"]
            assertNotNull(data, "Card count $count")
            val image = ImageIO.read(ByteArrayInputStream(data))
            for (slot in 0 until 3) {
                val y = Math.round((54 + slot * 23 + 10) * 4.0 / 3).toInt()
                assertEquals(slot < count, (image.getRGB(160, y) ushr 24) > 0)
            }
        }
    }

    @Test
    fun previewTileImagesHaveBoundedDecodedMemory() {
        val bundle = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        var decoded = 0L
        for (style in listOf("normal", "selected", "back")) {
            val image =
                ImageIO.read(
                    ByteArrayInputStream(
                        bundle.entries()["assets/dialogue/textures/font/panels-$style.png"]
                    )
                )
            decoded += image.width.toLong() * image.height * 4
        }
        assertTrue(
            decoded <= 2 * 1024 * 1024,
            "Each ascent loads bitmap images; do not multiply large transparent atlases across native fonts",
        )
    }

    @Test
    fun bakedPanelsPreservePreviewFrameAndFillPixels() {
        val bundle = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        val normal =
            ImageIO.read(
                ByteArrayInputStream(
                    bundle.entries()["assets/dialogue/textures/font/panels-normal.png"]
                )
            )
        val selected =
            ImageIO.read(
                ByteArrayInputStream(
                    bundle.entries()["assets/dialogue/textures/font/panels-selected.png"]
                )
            )
        assertEquals(0, normal.getRGB(0, 0))
        assertEquals(0xff4e687f.toInt(), normal.getRGB(2, 0))
        assertEquals(0xff223445.toInt(), normal.getRGB(3, 3))
        assertEquals(0xff82683e.toInt(), selected.getRGB(3, 3))
        assertEquals(0xffffcd75.toInt(), selected.getRGB(2, 0))
        assertEquals(0xfe080f18.toInt(), normal.getRGB(2, 10))
    }

    @Test
    fun nativeManifestDescribesActualRasterAndOptionalEffects() {
        val publicBundle = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        val metadata =
            JsonParser.parseString(String(publicBundle.manifestBytes(), StandardCharsets.UTF_8))
                .asJsonObject
        assertEquals(-64, metadata.get("minAscent").asInt)
        val grounds = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        assertTrue(grounds.hasEntry("assets/dialogue/post_effect/vignette.json"))
        assertTrue(grounds.hasEntry("assets/dialogue/shaders/post/vignette.fsh"))
    }

    @Test
    fun fadeVignettesReferenceTheirStrengthAndPreserveTheFullVignette() {
        val grounds = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        val entries = grounds.entries()
        val strengths = listOf("0.03", "0.06", "0.09", "0.12")
        for ((index, strength) in strengths.withIndex()) {
            val suffix = index + 1
            val effectPath = "assets/dialogue/post_effect/vignette_fade_${suffix}.json"
            val shaderPath = "assets/dialogue/shaders/post/vignette_fade_${suffix}.fsh"
            val effect =
                JsonParser.parseString(String(entries.getValue(effectPath), StandardCharsets.UTF_8))
            assertEquals(
                "dialogue:post/vignette_fade_${suffix}",
                effect.asJsonObject
                    .getAsJsonArray("passes")[0]
                    .asJsonObject
                    .get("fragment_shader")
                    .asString,
            )
            val shader = String(entries.getValue(shaderPath), StandardCharsets.UTF_8)
            assertTrue(shader.contains("scene.rgb * (1.0 - ${strength} * edge)"), shaderPath)
        }
        assertEquals(
            """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 scene = texture(InSampler, texCoord);
    float edge = smoothstep(0.35, 1.35, length((texCoord - vec2(0.5)) * 2.0));
    fragColor = vec4(scene.rgb * (1.0 - 0.15 * edge), scene.a);
}
""",
            String(
                entries.getValue("assets/dialogue/shaders/post/vignette.fsh"),
                StandardCharsets.UTF_8,
            ),
        )
    }

    @Test
    fun groundsBundleContainsFourNativeVignetteBanksWithoutChangingPublicBundle() {
        val grounds = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        val public = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        val entries = grounds.entries()
        assertEquals(596, entries.size)
        assertEquals(578, public.entries().size)
        val keys =
            listOf(
                "vignette_250_enter_a",
                "vignette_250_exit_a",
                "vignette_250_enter_b",
                "vignette_250_exit_b",
            )
        for (key in keys) {
            val config =
                JsonParser.parseString(
                        String(
                            entries.getValue("assets/dialogue/post_effect/$key.json"),
                            StandardCharsets.UTF_8,
                        )
                    )
                    .asJsonObject
            val passes = config.getAsJsonArray("passes")
            assertEquals(4, passes.size())
            assertEquals(
                "dialogue:post/vignette_state",
                passes[0].asJsonObject.get("fragment_shader").asString,
            )
            assertEquals("scratch", passes[0].asJsonObject.get("output").asString)
            assertEquals(
                "State",
                passes[0]
                    .asJsonObject
                    .getAsJsonArray("inputs")[0]
                    .asJsonObject
                    .get("sampler_name")
                    .asString,
            )
            assertEquals(
                "dialogue:post/vignette_animated",
                passes[1].asJsonObject.get("fragment_shader").asString,
            )
            assertEquals(
                "State",
                passes[1]
                    .asJsonObject
                    .getAsJsonArray("inputs")[0]
                    .asJsonObject
                    .get("sampler_name")
                    .asString,
            )
            assertEquals(
                "In",
                passes[1]
                    .asJsonObject
                    .getAsJsonArray("inputs")[1]
                    .asJsonObject
                    .get("sampler_name")
                    .asString,
            )
            assertEquals(
                "dialogue:post/vignette_copy",
                passes[2].asJsonObject.get("fragment_shader").asString,
            )
            assertEquals("clock", passes[2].asJsonObject.get("output").asString)
            assertEquals(
                "minecraft:post/blit",
                passes[3].asJsonObject.get("fragment_shader").asString,
            )
            assertEquals("minecraft:main", passes[3].asJsonObject.get("output").asString)
            val targets = config.getAsJsonObject("targets")
            assertTrue(targets.getAsJsonObject("clock").get("persistent").asBoolean)
            val uniforms =
                passes[0].asJsonObject.getAsJsonObject("uniforms").getAsJsonArray("FadeConfig")
            assertEquals(4, uniforms.size())
            assertEquals(5.0, uniforms[0].asJsonObject.get("value").asDouble)
            assertEquals(0.0, uniforms[1].asJsonObject.get("value").asDouble)
            assertEquals(0.15, uniforms[2].asJsonObject.get("value").asDouble)
            assertEquals(
                if (key.contains("_exit_")) 1.0 else 0.0,
                uniforms[3].asJsonObject.get("value").asDouble,
            )
        }
        for (path in
            listOf(
                "assets/dialogue/shaders/include/vignette_clock.glsl",
                "assets/dialogue/shaders/post/vignette_state.fsh",
                "assets/dialogue/shaders/post/vignette_animated.fsh",
                "assets/dialogue/shaders/post/vignette_copy.fsh",
            )) {
            assertTrue(grounds.hasEntry(path), path)
        }
        val animated =
            String(
                entries.getValue("assets/dialogue/shaders/post/vignette_animated.fsh"),
                StandardCharsets.UTF_8,
            )
        assertTrue(animated.contains("#include <minecraft:globals.glsl>"))
        assertTrue(animated.contains("float progress = vignetteProgress(state);"))
        assertFalse(animated.contains("smoothstep(0.0, 1.0, vignetteProgress(state))"))
        assertTrue(animated.contains("1.0 / (1.0 - edge * strength)"))
    }

    @Test
    fun bitmapAdvancesMatchFrozenAtlas() {
        for (profile in
            listOf(MinecraftPackProfile.public262(), MinecraftPackProfile.grounds263())) {
            val bundle = AssetBundle.loadDefault(profile)
            val image =
                ImageIO.read(
                    ByteArrayInputStream(
                        bundle.entries().getValue("assets/dialogue/textures/font/letters.png")
                    )
                )
            for ((codePoint, glyph) in bundle.glyphs()) {
                val text = Character.toString(codePoint)
                var right = -1
                for (x in 0 until 16) {
                    for (y in 0 until 256) {
                        if (image.getRGB(glyph.column * 16 + x, glyph.row * 256 + y) ushr 24 != 0) {
                            right = maxOf(right, x)
                        }
                    }
                }
                val message = "${profile.id} bitmap advance for U+${codePoint.toString(16)} ($text)"
                assertEquals(glyph.advance, right + 2, message)
                assertEquals((right + 2).toDouble(), bundle.metrics().width(text), message)
            }
            assertEquals(4.0, bundle.metrics().width(" "))
            assertEquals(4.0, bundle.metrics().width("\u00a0"))
            assertThrows(IllegalArgumentException::class.java) { bundle.metrics().width("😀") }
        }
    }

    @Test
    fun deterministicBundlePinAndProfiles() {
        val first = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        val second = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        assertEquals(first.pin(), second.pin())
        assertEquals(first.pin(), first.metrics().pin())
        assertEquals(777, first.profile().protocol)
        assertEquals(97, first.profile().packMajor)
        assertEquals(1, first.profile().packMinor)
        val old = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        assertEquals(776, old.profile().protocol)
        assertFalse(old.profile().postEffects)
        assertTrue(first.entries().keys.none { it.startsWith("assets/minecraft/") })
    }

    @Test
    fun wrongPinAndTamperedAtlasAreRejected() {
        val bundle = AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
        val entries = TreeMap(bundle.entries())
        val copy = entries.getValue("assets/dialogue/textures/font/letters.png").clone()
        copy[copy.lastIndex] = (copy.last().toInt() xor 1).toByte()
        entries["assets/dialogue/textures/font/letters.png"] = copy
        assertThrows(IllegalArgumentException::class.java) {
            AssetBundle.load(bundle.profile(), bundle.manifestBytes(), entries)
        }
    }

    @Test
    fun entriesAndManifestAreDefensivelyCopied() {
        val bundle = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        val first = bundle.entries()
        val bytes = first.values.first()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        assertEquals(bundle.pin(), AssetBundle.loadDefault(MinecraftPackProfile.public262()).pin())
        assertThrows(UnsupportedOperationException::class.java) {
            (first as MutableMap)["x"] = byteArrayOf()
        }
    }

    @Test
    fun frozenGlyphAdvanceFixtures() {
        val bundle = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        assertEquals(9.0, bundle.metrics().width("A"))
        assertEquals(4.0, bundle.metrics().width(" "))
        assertTrue(bundle.entries().containsKey("assets/dialogue/font/spacing.json"))
    }
}
