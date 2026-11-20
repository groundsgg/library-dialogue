package gg.grounds.dialogue.minestom

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.asset.AssetBundle
import java.awt.image.BufferedImage
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.floor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
internal class HudOptionsTest {
    @Test
    fun originalHudUsesOnePanelEmbeddedNameAndArrowRows(env: Env) {
        NativeReviewRegressionTest.F(env, 7).use { fixture ->
            val ticket = fixture.start("hud", false)
            NativeReviewRegressionTest.done(env, ticket.result())
            val text = plain.serialize(fixture.hudFrames.last())
            assertEquals(
                2L,
                text.codePoints().filter { it == 0xe600 || it == 0xe601 }.count(),
                "Original HUD has one two-tile shared backdrop",
            )
            assertFalse(
                text.codePoints().anyMatch { it in 0xe400 until 0xe500 },
                "Individual NPC card frames do not belong in the prototype HUD",
            )
            assertTrue(text.contains("> Choice 0"))
        }
    }

    @Test
    fun nativeHudUsesCompactColumnsAndCentersTheWholeLeftCanvas(env: Env) {
        NativeReviewRegressionTest.F(env, 7).use { fixture ->
            val ticket = fixture.start("hud", false)
            val handle =
                (NativeReviewRegressionTest.done(env, ticket.result())
                        as DialogueStartResult.Started)
                    .handle
            val visit = handle.snapshot().nodeVisitId()
            val inspector = NativeWidths(fixture.options.assets)
            assertEquals(240.0, inspector.width(fixture.hudFrames.last()), 0.001)
            for (position in listOf("left", "right", "below")) {
                NativeReviewRegressionTest.done(
                    env,
                    handle.updatePreferences(preferences("hudChoices" to position)),
                )
                val output = fixture.hudFrames.last()
                assertEquals(
                    if (position == "below") 240.0 else 300.0,
                    inspector.width(output),
                    0.001,
                )
                assertEquals(
                    0.0,
                    inspector.leftmostInk(output),
                    0.001,
                    "Left answers must participate in native actionbar centering",
                )
                assertEquals(visit, handle.snapshot().nodeVisitId())
                assertEquals("hud", ticket.status().effectiveSurface)
            }
        }
    }

    @Test
    fun backgroundStyleAndAnswerPlacementAreIndependentLivePreferences(env: Env) {
        for (wrapped in listOf(false, true)) {
            NativeReviewRegressionTest.F(env, 7, wrapped).use { fixture ->
                val ticket = fixture.start("hud", false)
                val handle =
                    (NativeReviewRegressionTest.done(env, ticket.result())
                            as DialogueStartResult.Started)
                        .handle
                val visit = handle.snapshot().nodeVisitId()
                val selected = handle.snapshot().selectedChoiceId()
                val inspector = NativeWidths(fixture.options.assets)
                for (style in listOf("shared", "split", "cards")) {
                    for (position in positions) {
                        NativeReviewRegressionTest.done(
                            env,
                            handle.updatePreferences(
                                preferences(
                                    "hudChoices" to position,
                                    "hudStyle" to style,
                                )
                            ),
                        )
                        val frame = fixture.hudFrames.last()
                        val json = serializer.serialize(frame)
                        val prefix = if (style == "shared") "hud_" else "hud_" + style + "_"
                        val suffix = if (wrapped) "_20_3" else "_10_3"
                        assertTrue(
                            json.contains("dialogue:" + prefix + position + suffix),
                            style + "/" + position + "/wrapped=" + wrapped + " " + json,
                        )
                        val expected =
                            if (style == "cards") cardWidth(position)
                            else if (position == "below") 240.0 else 300.0
                        assertEquals(expected, inspector.width(frame), 0.001)
                        if (style == "cards") {
                            val left = if (position == "right") 174.0 else 9.0
                            assertFalse(inspector.texts.isEmpty())
                            for (text in inspector.texts) {
                                if (text.text.contains("Choice") || text.text.contains("Details")) {
                                    assertEquals(left, text.x, 0.001, text.text)
                                }
                            }
                        }
                        assertEquals(0.0, inspector.leftmostInk(frame), 0.001)
                        assertEquals(visit, handle.snapshot().nodeVisitId())
                        assertEquals(selected, handle.snapshot().selectedChoiceId())
                        assertEquals("hud", ticket.status().effectiveSurface)
                    }
                }
                val snapshot = handle.snapshot()
                val stamp =
                    DialogueInput.InputStamp(snapshot.sessionId(), snapshot.renderRevision(), 100)
                NativeReviewRegressionTest.done(
                    env,
                    handle.submit(DialogueInput.Confirm(stamp, selected)),
                )
                for (position in positions) {
                    NativeReviewRegressionTest.done(
                        env,
                        handle.updatePreferences(
                            preferences(
                                "hudChoices" to position,
                                "hudStyle" to "cards",
                            )
                        ),
                    )
                    val frame = fixture.hudFrames.last()
                    val json = serializer.serialize(frame)
                    assertTrue(json.contains("dialogue:hud_cards_" + position + "_10_0"), json)
                    assertEquals(cardWidth(position), inspector.width(frame), 0.001)
                    assertEquals("hud", ticket.status().effectiveSurface)
                }
            }
        }
    }

    @Test
    fun scrollbarIsOnlyEmittedForOverflowAndMovesWithThePublishedPage(env: Env) {
        for (answers in listOf(3, 7)) {
            NativeReviewRegressionTest.F(env, answers).use { fixture ->
                val ticket = fixture.start("hud", false)
                val handle =
                    (NativeReviewRegressionTest.done(env, ticket.result())
                            as DialogueStartResult.Started)
                        .handle
                val first = plain.serialize(fixture.hudFrames.last())
                assertEquals(answers > 3, first.codePoints().anyMatch { it in 0xe700 until 0xe780 })
                if (answers > 3) {
                    val before = serializer.serialize(fixture.hudFrames.last())
                    for (slot in 1..6) {
                        fixture.packet(ClientHeldItemChangePacket(slot.toShort()))
                        NativeReviewRegressionTest.ticks(env)
                    }
                    assertEquals("c6", handle.snapshot().selectedChoiceId())
                    val output = fixture.hudFrames.last()
                    assertNotEquals(before, serializer.serialize(output))
                    assertEquals(
                        2L,
                        plain
                            .serialize(output)
                            .codePoints()
                            .filter { it in 0xe700 until 0xe780 }
                            .count(),
                    )
                    assertEquals(240.0, NativeWidths(fixture.options.assets).width(output), 0.001)
                }
            }
        }
    }

    private fun cardWidth(position: String): Double =
        when (position) {
            "below" -> 249.0
            "left" -> 312.0
            else -> 309.0
        }

    private fun preferences(vararg effects: Pair<String, String>) =
        PresentationPreferences("hud", Duration.ZERO, false, mapOf(*effects))

    private val positions = listOf("below", "left", "right")
    private val plain = PlainTextComponentSerializer.plainText()
    private val serializer = GsonComponentSerializer.gson()

    /** Computes advances from PNG ink and bitmap definitions independently of the encoder. */
    private class NativeWidths(bundle: AssetBundle) {
        private val files = bundle.entries()
        private val fonts = HashMap<String, JsonArray>()
        private val images = HashMap<String, BufferedImage>()
        private val glyphs = HashMap<String, DoubleArray>()

        data class NativeText(val text: String, val x: Double, val width: Double)

        val texts = ArrayList<NativeText>()

        private fun glyph(font: String, codePoint: Int): DoubleArray {
            val key = font + ":" + codePoint
            glyphs[key]?.let {
                return it
            }
            val providers =
                fonts.getOrPut(font) {
                    JsonParser.parseString(
                            files
                                .getValue("assets/dialogue/font/" + font + ".json")
                                .decodeToString()
                        )
                        .asJsonObject
                        .getAsJsonArray("providers")
                }
            for (raw in providers) {
                val provider = raw.asJsonObject
                if (provider["type"].asString == "space") {
                    provider.getAsJsonObject("advances")[Character.toString(codePoint)]?.let {
                        return doubleArrayOf(it.asDouble, 0.0).also { value -> glyphs[key] = value }
                    }
                    continue
                }
                val rows = provider.getAsJsonArray("chars")
                for ((row, chars) in rows.withIndex()) {
                    val codePoints = chars.asString.codePoints().toArray()
                    for ((column, candidate) in codePoints.withIndex()) {
                        if (candidate != codePoint) continue
                        val path =
                            "assets/dialogue/textures/" +
                                provider["file"].asString.substringAfter(':')
                        val image =
                            images.getOrPut(path) {
                                files.getValue(path).inputStream().use(ImageIO::read)
                            }
                        val width = image.width / codePoints.size
                        val height = image.height / rows.size()
                        var right = -1
                        for (x in 0 until width) {
                            for (y in 0 until height) {
                                if (
                                    image.getRGB(column * width + x, row * height + y).ushr(24) != 0
                                )
                                    right = maxOf(right, x)
                            }
                        }
                        val scale = provider["height"].asDouble / height
                        val value =
                            doubleArrayOf(floor((right + 1) * scale + 0.5) + 1, (right + 1) * scale)
                        glyphs[key] = value
                        return value
                    }
                }
            }
            throw AssertionError("Unresolved native glyph " + codePoint)
        }

        private fun walk(component: Component, state: DoubleArray): Double {
            if (component is TextComponent && component.content().isNotEmpty()) {
                val font = checkNotNull(component.font()).value()
                val start = state[0]
                for (cp in component.content().codePoints().toArray()) {
                    val advance = glyph(font, cp)
                    if (advance[1] > 0) state[1] = minOf(state[1], state[0])
                    state[0] += advance[0]
                }
                if (component.content().codePoints().anyMatch(Character::isLetter)) {
                    texts.add(NativeText(component.content(), start, state[0] - start))
                }
            }
            component.children().forEach { walk(it, state) }
            return state[0]
        }

        fun width(component: Component): Double {
            texts.clear()
            return walk(component, doubleArrayOf(0.0, Double.POSITIVE_INFINITY))
        }

        fun leftmostInk(component: Component): Double {
            val state = doubleArrayOf(0.0, Double.POSITIVE_INFINITY)
            walk(component, state)
            return state[1]
        }
    }
}
