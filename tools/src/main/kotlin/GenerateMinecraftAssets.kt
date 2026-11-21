import gg.grounds.dialogue.core.validation.ContentFingerprint
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import java.util.TreeSet
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Maintenance-only generator. Normal library builds use the checked-in bytes. */
object GenerateMinecraftAssets {
    private fun json(value: Any): ByteArray = ContentFingerprint.canonicalBytes(value)

    private fun bitmap(
        file: String,
        height: Int,
        ascent: Int,
        chars: List<String>,
    ): Map<String, Any> =
        mapOf(
            "type" to "bitmap",
            "file" to file,
            "height" to height,
            "ascent" to ascent,
            "chars" to chars,
        )

    private fun put(files: MutableMap<String, ByteArray>, path: String, data: Any) {
        files[path] = json(data)
    }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }

    private fun previewPanel(width: Int, height: Int, selected: Boolean): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        pixelPanel(graphics, 0, 0, width, height, selected)
        graphics.dispose()
        return image
    }

    private fun pixelPanel(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, selected: Boolean) {
        g.color = if (selected) Color(25, 40, 55, 240) else Color(9, 15, 24, 240)
        g.fillRect(x + 2, y, w - 4, h)
        g.fillRect(x, y + 2, w, h - 4)
        g.color = if (selected) Color(255, 205, 117) else Color(78, 104, 127)
        g.drawPolyline(
            intArrayOf(x + 2, x + w - 3, x + w - 1, x + w - 1, x + w - 3, x + 2, x, x, x + 2),
            intArrayOf(y, y, y + 2, y + h - 3, y + h - 1, y + h - 1, y + h - 3, y + 2, y),
            9,
        )
        g.color = if (selected) Color(130, 104, 62) else Color(34, 52, 69)
        g.drawRect(x + 3, y + 3, w - 7, h - 7)
    }

    private fun hudAssets(
        files: MutableMap<String, ByteArray>,
        source: Path,
        baseSpaces: Map<String, Any>,
    ) {
        val original = ImageIO.read(source.resolve("letters.png").toFile())
        val panel = ImageIO.read(source.resolve("panel.png").toFile())
        val rows = Files.readAllLines(source.resolve("glyphs.txt"))
        val letters = BufferedImage(original.width, rows.size * 256, BufferedImage.TYPE_INT_ARGB)
        val chars = mutableListOf<String>()
        val inkTops = TreeSet<Double>()
        val inkBottoms = TreeSet<Double>()
        val properties = StringBuilder("lineHeight=10\nglyph.32=3\nglyph.160=3\n")
        for (row in rows.indices) {
            val grid = rows[row].split(" ")
            val text = StringBuilder()
            for (col in grid.indices) {
                val cp = grid[col].toInt(16)
                text.appendCodePoint(if (cp == 160) 0 else cp)
                var right = -1
                var top = 128
                var bottom = 0
                for (y in 0 until 128) {
                    for (x in 0 until 12) {
                        val pixel = original.getRGB(col * 12 + x, row * 128 + y)
                        letters.setRGB(col * 12 + x, row * 256 + y, pixel)
                        if (pixel ushr 24 != 0) {
                            right = max(right, x)
                            top = min(top, y)
                            bottom = max(bottom, y + 1)
                        }
                    }
                }
                if (cp != 0 && cp != 160) {
                    properties
                        .append("glyph.")
                        .append(cp)
                        .append('=')
                        .append(((right + 1) * .75f).roundToInt() + 1)
                        .append('\n')
                    val inkTop = if (right < 0) 0.0 else top * .75
                    val inkBottom = if (right < 0) 0.0 else bottom * .75
                    properties
                        .append("ink.")
                        .append(cp)
                        .append('=')
                        .append(inkTop)
                        .append(',')
                        .append(inkBottom)
                        .append('\n')
                    if (inkBottom > inkTop) {
                        inkTops.add(inkTop)
                        inkBottoms.add(inkBottom)
                    }
                }
            }
            chars.add(text.toString())
        }
        files["assets/dialogue/textures/font/hud_letters.png"] = png(letters)
        files["assets/dialogue/font-source/prototype_hud_panel.png"] =
            Files.readAllBytes(source.resolve("panel.png"))
        val spaces = TreeMap<String, Any>(baseSpaces)
        spaces[" "] = 3
        spaces["\u00a0"] = 3
        val spacing: Map<String, Any> = mapOf("type" to "space", "advances" to spaces)
        val ascents = TreeSet(listOf(0, -10, -17, -27, -37, 11, 1, -3, -13, -23))
        for (placement in listOf("below", "left", "right")) {
            for (rowHeight in listOf(10, 20)) {
                val below = placement == "below"
                val logicalHeight =
                    if (below) 84 + 3 * (rowHeight - 10) else if (rowHeight == 10) 51 else 75
                val baseline = logicalHeight + 10
                ascents.add(baseline - 7)
                for (line in 0 until 3) ascents.add(baseline - 17 - line * 10)
                for (slot in 0 until 3) {
                    for (line in 0 until rowHeight / 10) {
                        ascents.add(
                            baseline - (if (below) 50 else 7) - slot * rowHeight - line * 10
                        )
                    }
                }
                val key = "hud_${placement}_${rowHeight}_3"
                val width = if (below) 320 else 400
                val image = BufferedImage(width, 256, BufferedImage.TYPE_INT_ARGB)
                if (below) {
                    for (y in 0 until 128 + if (rowHeight == 20) 40 else 0) {
                        for (x in 0 until 320) {
                            val from =
                                if (rowHeight == 10 || y < 103) y else if (y < 143) 100 else y - 40
                            image.setRGB(x, y, panel.getRGB(x, from))
                        }
                    }
                } else {
                    val height = if (rowHeight == 10) 68 else 100
                    val g = image.createGraphics()
                    g.color = Color(9, 15, 24, 220)
                    g.fillRoundRect(0, 0, width, height, 10, 10)
                    g.color = Color(78, 104, 127, 230)
                    g.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
                    val speech = if (placement == "left") 184 else 0
                    val answers = if (placement == "left") 0 else 220
                    g.color = Color(25, 40, 55, 220)
                    g.fillRoundRect(speech + 6, 7, 204, height - 16, 6, 6)
                    g.color = Color(15, 27, 39, 230)
                    g.fillRoundRect(answers + 6, 7, 168, height - 16, 6, 6)
                    g.dispose()
                }
                hudBackdrop(files, key, image, baseline, spacing, properties)
            }
        }
        for (style in listOf("split", "cards")) {
            for (placement in listOf("below", "left", "right")) {
                for (rowHeight in listOf(10, 20)) {
                    val counts = if (style == "cards") listOf(0, 1, 2, 3) else listOf(3)
                    for (count in counts) {
                        val below = placement == "below"
                        val cards = style == "cards"
                        val answerHeight =
                            if (cards) 3 * (rowHeight + 10) + 6 else 3 * rowHeight + 14
                        val logicalHeight = if (below) 54 + answerHeight else max(48, answerHeight)
                        val baseline = logicalHeight + 10
                        val step = if (cards) rowHeight + 13 else rowHeight
                        ascents.add(baseline - 7)
                        for (line in 0 until 3) ascents.add(baseline - 17 - line * 10)
                        for (slot in 0 until count) {
                            for (line in 0 until rowHeight / 10) {
                                ascents.add(
                                    baseline - (if (below) 61 else 7) - slot * step - line * 10
                                )
                            }
                        }
                        if (cards) {
                            ascents.add(-5)
                            ascents.add(-15)
                            for (slot in 0 until count) {
                                for (lines in 1..rowHeight / 10) {
                                    val inset = (rowHeight + 10 - lines * 10) / 2
                                    for (line in 0 until lines) {
                                        ascents.add(
                                            baseline -
                                                (if (below) 54 else 0) -
                                                slot * step -
                                                inset -
                                                line * 10
                                        )
                                    }
                                }
                            }
                        }
                        if (cards) {
                            val insets = TreeSet<Int>()
                            for (lineCount in 1..rowHeight / 10) {
                                for (firstLine in 0 until lineCount) {
                                    for (lastLine in firstLine until lineCount) {
                                        for (inkTop in inkTops) {
                                            for (inkBottom in inkBottoms) {
                                                val first = firstLine * 10 + inkTop
                                                val last = lastLine * 10 + inkBottom
                                                if (last > first)
                                                    insets.add(
                                                        ((rowHeight + 10) / 2.0 -
                                                                (first + last) / 2)
                                                            .roundToInt()
                                                    )
                                            }
                                        }
                                    }
                                }
                            }
                            for (inset in insets) {
                                for (line in 0 until rowHeight / 10) ascents.add(-inset - line * 10)
                            }
                            for (slot in 0 until count) {
                                for (inset in insets) {
                                    for (line in 0 until rowHeight / 10) {
                                        ascents.add(
                                            baseline -
                                                (if (below) 54 else 0) -
                                                slot * step -
                                                inset -
                                                line * 10
                                        )
                                    }
                                }
                            }
                        }
                        val key = "hud_${style}_${placement}_${rowHeight}_${count}"
                        val width =
                            if (cards) {
                                if (below) 332 else if (placement == "left") 416 else 412
                            } else if (below) 320 else 400
                        val image = BufferedImage(width, 256, BufferedImage.TYPE_INT_ARGB)
                        val g = image.createGraphics()
                        g.scale(4.0 / 3, 4.0 / 3)
                        val speechX = if (placement == "left") if (cards) 150.0 else 138.0 else 0.0
                        val answerX = if (below) 0.0 else if (placement == "left") 0.0 else 165.0
                        val answerY = if (below) 54.0 else 0.0
                        hudRegion(g, speechX, 0.0, if (below) 240.0 else 162.0, 48.0, false)
                        if (cards) {
                            for (slot in 0 until count) {
                                hudRegion(
                                    g,
                                    answerX,
                                    answerY + slot * step,
                                    if (below) 240.0 else 135.0,
                                    rowHeight + 10.0,
                                    true,
                                )
                            }
                        } else {
                            hudRegion(
                                g,
                                answerX,
                                answerY,
                                if (below) 240.0 else 135.0,
                                answerHeight.toDouble(),
                                true,
                            )
                        }
                        g.dispose()
                        hudBackdrop(files, key, image, baseline, spacing, properties)
                    }
                }
            }
        }
        for (ascent in ascents) {
            put(
                files,
                "assets/dialogue/font/hud_a_${ascent}.json",
                mapOf(
                    "providers" to
                        listOf(bitmap("dialogue:font/hud_letters.png", 192, ascent, chars), spacing)
                ),
            )
        }
        val bars = BufferedImage(256, 192, BufferedImage.TYPE_INT_ARGB)
        val barChars = StringBuilder()
        for (height in 1..128) {
            barChars.appendCodePoint(0xe700 + height - 1)
            for (y in 0 until height) for (x in 0 until 2) bars.setRGB((height - 1) * 2 + x, y, -1)
        }
        files["assets/dialogue/textures/font/hud_scrollbar.png"] = png(bars)
        for (ascent in 0..192) {
            put(
                files,
                "assets/dialogue/font/hud_scroll_a_${ascent}.json",
                mapOf(
                    "providers" to
                        listOf(
                            bitmap(
                                "dialogue:font/hud_scrollbar.png",
                                192,
                                ascent,
                                listOf(barChars.toString()),
                            )
                        )
                ),
            )
        }
        files["assets/dialogue/hud_metrics.properties"] =
            properties.toString().toByteArray(StandardCharsets.UTF_8)
        println("Frozen prototype HUD fonts=${ascents.size}")
    }

    private fun hudBackdrop(
        files: MutableMap<String, ByteArray>,
        key: String,
        image: BufferedImage,
        baseline: Int,
        spacing: Any,
        metrics: StringBuilder,
    ) {
        files["assets/dialogue/textures/font/${key}.png"] = png(image)
        put(
            files,
            "assets/dialogue/font/${key}.json",
            mapOf(
                "providers" to
                    listOf(
                        bitmap("dialogue:font/${key}.png", 192, baseline, listOf("\ue600\ue601")),
                        spacing,
                    )
            ),
        )
        val cellWidth = image.width / 2
        for (half in 0 until 2) {
            var right = -1
            for (x in 0 until cellWidth) {
                for (y in 0 until image.height) {
                    if (image.getRGB(half * cellWidth + x, y) ushr 24 != 0) right = max(right, x)
                }
            }
            val advance = ((right + 1) * (192f / image.height)).roundToInt() + 1
            metrics
                .append("backdrop.")
                .append(key)
                .append('.')
                .append(half)
                .append('=')
                .append(advance)
                .append('\n')
        }
    }

    private fun hudRegion(
        g: Graphics2D,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        answer: Boolean,
    ) {
        g.color = Color(9, 15, 24, 220)
        g.fill(java.awt.geom.RoundRectangle2D.Double(x, y, w, h, 7.5, 7.5))
        g.color = Color(78, 104, 127, 230)
        g.draw(java.awt.geom.RoundRectangle2D.Double(x, y, w - .75, h - .75, 7.5, 7.5))
        g.color = if (answer) Color(15, 27, 39, 230) else Color(25, 40, 55, 220)
        g.fill(java.awt.geom.RoundRectangle2D.Double(x + 4.5, y + 3.75, w - 9, h - 7.5, 4.5, 4.5))
    }

    private fun navigationChevrons(): BufferedImage {
        val image = BufferedImage(32, 256, BufferedImage.TYPE_INT_ARGB)
        val rows =
            listOf(
                ".......##.......",
                "......####......",
                ".....######.....",
                "....###..###....",
                "...###....###...",
                "..###......###..",
                ".###........###.",
                "###..........###",
            )
        for (y in rows.indices) {
            for (x in 0 until 16) {
                if (rows[y][x] == '#') {
                    image.setRGB(x, y, -1)
                    image.setRGB(16 + x, 7 - y, -1)
                }
            }
        }
        return image
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val source = Path.of(args[0])
        val output = Path.of(args[1])
        val points = TreeSet<Int>()
        for (c in 33..255) if (c != 160) points.add(c)
        points.addAll(
            listOf(0x2013, 0x2014, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x20ac, 0x2191, 0x2193)
        )
        val font = Font.createFont(Font.TRUETYPE_FONT, source.toFile()).deriveFont(12f)
        points.removeIf { !font.canDisplay(it) }
        val codes = ArrayList(points)
        val rowCount = (codes.size + 15) / 16
        val atlas = BufferedImage(256, rowCount * 256, BufferedImage.TYPE_INT_ARGB)
        val graphics = atlas.createGraphics()
        graphics.font = font
        graphics.color = Color.WHITE
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        )
        val chars = mutableListOf<String>()
        val glyphs = TreeMap<String, Any>()
        val metrics =
            StringBuilder(
                "# Frozen native bitmap advances; source generator is maintenance-only.\nlineHeight=16\nglyph.32=4\nglyph.160=4\n"
            )
        for (row in 0 until rowCount) {
            val line = StringBuilder()
            for (col in 0 until 16) {
                val index = row * 16 + col
                if (index >= codes.size) {
                    line.append('\u0000')
                    continue
                }
                val cp = codes[index]
                line.appendCodePoint(cp)
                val tileX = col * 16
                val tileY = row * 256
                // Negative left bearings must not affect the previous glyph's ink or advance.
                graphics.setClip(tileX, tileY, 16, 256)
                graphics.drawString(Character.toString(cp), tileX, tileY + 12)
                var right = -1
                for (x in 0 until 16) {
                    for (y in 0 until 256) {
                        if (atlas.getRGB(tileX + x, tileY + y) ushr 24 != 0) right = max(right, x)
                    }
                }
                val advance = right + 2
                glyphs[cp.toString()] = mapOf("column" to col, "row" to row, "advance" to advance)
                metrics.append("glyph.").append(cp).append('=').append(advance).append('\n')
            }
            chars.add(line.toString())
        }
        graphics.dispose()
        val spaces = TreeMap<String, Any>()
        spaces[" "] = 4
        spaces["\u00a0"] = 4
        spaces["\uf800"] = -1
        spaces["\uf801"] = 0.5
        spaces["\uf802"] = -0.5
        for (i in 0 until 16) {
            spaces[Character.toString(0xf810 + i)] = 1 shl i
            spaces[Character.toString(0xf830 + i)] = -(1 shl i)
        }
        val mask = BufferedImage(256, 200, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 200) {
            val t = min(1.0, y / 128.0)
            val e = t * t * (3 - 2 * t)
            val alpha = (255 * e * e).roundToInt()
            for (x in 0 until 256) {
                var a = alpha
                if (alpha > 0 && alpha < 32)
                    a = if ((x * 157 + y * 67 and 255) < alpha * 8) 32 else 0
                mask.setRGB(x, y, a shl 24)
            }
        }
        for (profile in listOf("public-26.2", "grounds-26.3")) {
            val files = TreeMap<String, ByteArray>()
            files["assets/dialogue/textures/font/letters.png"] = png(atlas)
            files["assets/dialogue/textures/font/navigation.png"] = png(navigationChevrons())
            files["assets/dialogue/textures/font/hud-mask.png"] = png(mask)
            files["assets/dialogue/font-source/dejavu_sans.ttf"] = Files.readAllBytes(source)
            files["assets/dialogue/font-source/font-license.txt"] =
                Files.readAllBytes(source.resolveSibling("FONT-LICENSE.txt"))
            files["assets/dialogue/metrics.properties"] =
                metrics.toString().toByteArray(StandardCharsets.UTF_8)
            val focusImage = BufferedImage(192, 256, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 256) {
                for (x in 0 until 192) {
                    val edge = min(min(x / 40.0, (191 - x) / 40.0), min(y / 40.0, (255 - y) / 40.0))
                    val t = edge.coerceIn(0.0, 1.0)
                    var alpha = (64 * t * t * (3 - 2 * t)).roundToInt()
                    if (alpha > 0 && alpha < 32)
                        alpha = if ((x * 157 + y * 67 and 255) < alpha * 8) 32 else 0
                    focusImage.setRGB(x, y, alpha shl 24)
                }
            }
            files["assets/dialogue/textures/font/npc-focus.png"] = png(focusImage)
            put(
                files,
                "assets/dialogue/font/focus.json",
                mapOf(
                    "providers" to
                        listOf(bitmap("dialogue:font/npc-focus.png", 256, 128, listOf("\ue300")))
                ),
            )
            val panelChars = mutableListOf<List<String>>()
            for (style in 0 until 3) {
                val image = BufferedImage(8, 48 * 256, BufferedImage.TYPE_INT_ARGB)
                val rowsOfChars = mutableListOf<String>()
                for (hi in 0 until 8) {
                    val h = intArrayOf(28, 40, 56, 72, 24, 32, 48, 64)[hi]
                    val original = previewPanel(320, h, style == 1)
                    if (style == 2) {
                        for (y in 0 until h) {
                            for (x in 0 until 320) {
                                val pixel = original.getRGB(x, y)
                                val alpha = pixel ushr 24
                                if (alpha != 0 && alpha != 255)
                                    original.setRGB(
                                        x,
                                        y,
                                        if ((x + y) % 12 == 0) 0xff101b28.toInt()
                                        else 0xff0b1420.toInt(),
                                    )
                            }
                        }
                    }
                    for (part in 0 until 6) {
                        val index = hi * 6 + part
                        val w = if (part < 2) 4 else 1 shl (part - 2)
                        val from = if (part == 0) 0 else if (part == 1) 316 else 6
                        val dy = index * 256
                        for (y in 0 until h) for (x in 0 until w) image.setRGB(
                            x,
                            dy + y,
                            original.getRGB(from + x, y),
                        )
                    }
                }
                for (row in 0 until 48) rowsOfChars.add(
                    Character.toString(0xe400 + style * 48 + row)
                )
                panelChars.add(rowsOfChars)
                files[
                    "assets/dialogue/textures/font/panels-${arrayOf("normal", "selected", "back")[style]}.png"] =
                    png(image)
            }
            val spacing: Map<String, Any> = mapOf("type" to "space", "advances" to spaces)
            put(files, "assets/dialogue/font/spacing.json", mapOf("providers" to listOf(spacing)))
            put(
                files,
                "assets/dialogue/font/mask.json",
                mapOf(
                    "providers" to
                        listOf(
                            bitmap("dialogue:font/hud-mask.png", 200, 106, listOf("\ue200")),
                            spacing,
                        )
                ),
            )
            for (a in -64..256 step 2) {
                val providers = mutableListOf<Any>()
                providers.add(bitmap("dialogue:font/letters.png", 256, a, chars))
                for (style in 0 until 3) providers.add(
                    bitmap(
                        "dialogue:font/panels-${arrayOf("normal", "selected", "back")[style]}.png",
                        256,
                        a,
                        panelChars[style],
                    )
                )
                providers.add(
                    bitmap("dialogue:font/navigation.png", 256, a, listOf("\ue780\ue781"))
                )
                providers.add(spacing)
                put(files, "assets/dialogue/font/a_${a}.json", mapOf("providers" to providers))
            }
            if (profile == "grounds-26.3") {
                files["assets/dialogue/post_effect/vignette.json"] =
                    """{"targets":{"swap":{}},"passes":[{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"dialogue:post/vignette","inputs":[{"sampler_name":"In","target":"minecraft:main"}],"output":"swap"},{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"minecraft:post/blit","inputs":[{"sampler_name":"In","target":"swap"}],"uniforms":{"BlitConfig":[{"name":"ColorModulate","type":"vec4","value":[1,1,1,1]}]},"output":"minecraft:main"}]}
"""
                        .toByteArray(StandardCharsets.UTF_8)
                files["assets/dialogue/shaders/post/vignette.fsh"] =
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
"""
                        .toByteArray(StandardCharsets.UTF_8)
                for ((index, strength) in listOf("0.03", "0.06", "0.09", "0.12").withIndex()) {
                    val suffix = index + 1
                    files["assets/dialogue/post_effect/vignette_fade_${suffix}.json"] =
                        """{"targets":{"swap":{}},"passes":[{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"dialogue:post/vignette_fade_${suffix}","inputs":[{"sampler_name":"In","target":"minecraft:main"}],"output":"swap"},{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"minecraft:post/blit","inputs":[{"sampler_name":"In","target":"swap"}],"uniforms":{"BlitConfig":[{"name":"ColorModulate","type":"vec4","value":[1,1,1,1]}]},"output":"minecraft:main"}]}
"""
                            .toByteArray(StandardCharsets.UTF_8)
                    files["assets/dialogue/shaders/post/vignette_fade_${suffix}.fsh"] =
                        """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 scene = texture(InSampler, texCoord);
    float edge = smoothstep(0.35, 1.35, length((texCoord - vec2(0.5)) * 2.0));
    fragColor = vec4(scene.rgb * (1.0 - ${strength} * edge), scene.a);
}
"""
                            .toByteArray(StandardCharsets.UTF_8)
                }
            }
            hudAssets(
                files,
                if (args.size > 2) Path.of(args[2]) else Path.of("tools/prototype-hud"),
                spaces,
            )
            val hashes = TreeMap<String, String>()
            files.forEach { (key, value) -> hashes[key] = ContentFingerprint.hash(value).sha256 }
            val pin =
                ContentFingerprint.hash(
                        json(mapOf("profile" to profile, "files" to hashes, "glyphs" to glyphs))
                    )
                    .sha256
            val manifest =
                mapOf(
                    "schemaVersion" to 1,
                    "profile" to profile,
                    "fontSourceSha256" to
                        ContentFingerprint.hash(Files.readAllBytes(source)).sha256,
                    "files" to hashes,
                    "glyphs" to glyphs,
                    "pin" to pin,
                    "minAscent" to -64,
                    "maxAscent" to 256,
                    "maxRectangleHeight" to 256,
                )
            val root = output.resolve(profile)
            val hudFonts = root.resolve("assets/dialogue/font")
            if (Files.isDirectory(hudFonts)) {
                Files.list(hudFonts).use { existing ->
                    existing.toList().forEach { path ->
                        if (
                            path.fileName.toString().matches(Regex("hud_a_-?[0-9]+\\.json")) &&
                                !files.containsKey("assets/dialogue/font/${path.fileName}")
                        )
                            Files.delete(path)
                    }
                }
            }
            for ((path, bytes) in files) {
                val target = root.resolve(path)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
            Files.write(root.resolve("manifest.json"), json(manifest))
            println("${profile} fontPin=${pin} glyphs=${glyphs.size}")
        }
    }
}
