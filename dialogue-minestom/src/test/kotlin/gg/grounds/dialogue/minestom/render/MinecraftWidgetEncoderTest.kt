package gg.grounds.dialogue.minestom.render

import com.google.gson.JsonParser
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.WidgetFrame
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.stream.Stream
import javax.imageio.ImageIO
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.ShadowColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinecraftWidgetEncoderTest {
    companion object {
        @JvmStatic
        fun plain(component: Component): String = buildString {
            if (component is TextComponent) append(component.content())
            component.children().forEach { append(plain(it)) }
        }

        @JvmStatic
        fun barOffset(component: Component): Double {
            var cursor = 0.0
            for (part in parts(component).toList()) {
                if (part !is TextComponent) continue
                for (codePoint in part.content().codePoints().toArray()) {
                    if (part.font()?.asString() == "dialogue:spacing") {
                        when {
                            codePoint in 0xf810 until 0xf820 ->
                                cursor += (1 shl (codePoint - 0xf810))
                            codePoint in 0xf830 until 0xf840 ->
                                cursor -= (1 shl (codePoint - 0xf830))
                            codePoint == 0xf801 -> cursor += 0.5
                            codePoint == 0xf802 -> cursor -= 0.5
                            codePoint == 0xf800 -> cursor--
                        }
                    } else if (codePoint in 0xe700 until 0xe780) {
                        return cursor
                    }
                }
            }
            throw AssertionError("No scrollbar ink in component")
        }

        @JvmStatic
        fun parts(component: Component): Stream<Component> =
            Stream.concat(
                Stream.of(component),
                component.children().stream().flatMap(::parts),
            )

        private fun actualHudInk(bundle: AssetBundle, text: String): DoubleArray {
            val specification =
                JsonParser.parseString(
                        String(
                            bundle.entries()["assets/dialogue/font/hud_a_0.json"]!!,
                            StandardCharsets.UTF_8,
                        )
                    )
                    .asJsonObject
                    .getAsJsonArray("providers")[0]
                    .asJsonObject
            val image =
                ImageIO.read(
                    ByteArrayInputStream(
                        bundle.entries()["assets/dialogue/textures/font/hud_letters.png"]!!
                    )
                )
            val scale = specification["height"].asDouble / 256
            var minimum = Double.POSITIVE_INFINITY
            var maximum = Double.NEGATIVE_INFINITY
            for (codePoint in text.codePoints().toArray()) {
                for (row in 0 until specification.getAsJsonArray("chars").size()) {
                    val codes =
                        specification.getAsJsonArray("chars")[row].asString.codePoints().toArray()
                    for (column in codes.indices) {
                        if (codes[column] != codePoint) continue
                        for (y in 0 until 256) {
                            for (x in 0 until 12) {
                                if ((image.getRGB(column * 12 + x, row * 256 + y) ushr 24) != 0) {
                                    minimum = minOf(minimum, y * scale)
                                    maximum = maxOf(maximum, (y + 1) * scale)
                                }
                            }
                        }
                    }
                }
            }
            return doubleArrayOf(minimum, maximum)
        }
    }

    @Test
    fun hudCardsCenterActualBitmapInkRatherThanEmptyLineBoxPadding() {
        for (profile in
            listOf(MinecraftPackProfile.public262(), MinecraftPackProfile.grounds263())) {
            val assets = AssetBundle.loadDefault(profile)
            for (position in LayoutProfile.ChoicePlacement.entries) {
                for (wrapped in listOf(false, true)) {
                    val base = RenderFixtures.view("Hello", 5)
                    val texts =
                        if (wrapped) {
                            listOf("Über diesen\nOrt", "Aufgabe\nabwarten", "> Noch ein\nGruß")
                        } else {
                            listOf("Über diesen Ort", "Aufgabe abwarten", "Noch ein Gruß")
                        }
                    val choices = texts.mapIndexed { index, text ->
                        DialogueView.ChoiceView(
                            "c$index",
                            text,
                            DialogueView.Availability.AVAILABLE,
                            "",
                        )
                    }
                    val view =
                        DialogueView(
                            base.sessionId(),
                            base.contentPin(),
                            base.nodeId(),
                            base.nodeVisitId(),
                            base.state(),
                            base.stateRevision(),
                            base.renderRevision(),
                            base.localeTag(),
                            base.speaker(),
                            base.text(),
                            choices,
                            "c2",
                            base.totalGraphemes(),
                            base.revealedGraphemes(),
                            base.hint(),
                            base.notice(),
                            base.pendingAction(),
                        )
                    val clock =
                        object : DialogueScheduler {
                            override fun execute(command: Runnable) = command.run()

                            override fun schedule(
                                delay: Duration,
                                command: Runnable,
                            ): DialogueScheduler.ScheduledTask = DialogueScheduler.ScheduledTask {}

                            override fun nanoTime(): Long = 0
                        }
                    val presenter =
                        DialoguePresenter(
                            mapOf(
                                "hud" to
                                    DialoguePresenter.SurfaceProfile(
                                        {
                                            java.util.concurrent.CompletableFuture.failedFuture(
                                                UnsupportedOperationException()
                                            )
                                        },
                                        LayoutProfile.compactHud(
                                            position,
                                            LayoutProfile.Style.HUD_CARDS,
                                        ),
                                        DialogueTheme.hudPrototype(),
                                        assets.hudMetrics(),
                                        false,
                                    )
                            ),
                            clock,
                        )
                    val frame = presenter.compose(view, "hud", PresentationPreferences.defaults())
                    val encoded = MinecraftWidgetEncoder().encode(frame, assets)
                    for (widget in frame.widgets().filter { it.kind == WidgetFrame.Kind.CHOICE }) {
                        val lines = widget.layout.visible(widget.visibleGraphemes)
                        var minimum = Double.POSITIVE_INFINITY
                        var maximum = Double.NEGATIVE_INFINITY
                        for (part in parts(encoded.hud()).toList()) {
                            if (
                                part is TextComponent &&
                                    lines.contains(part.content()) &&
                                    part.font()?.asString()?.startsWith("dialogue:hud_a_") == true
                            ) {
                                val ascent =
                                    part.font()!!.value().substring("hud_a_".length).toDouble()
                                val ink = actualHudInk(assets, part.content())
                                minimum =
                                    minOf(
                                        minimum,
                                        checkNotNull(frame.chrome()).baseline - ascent + ink[0],
                                    )
                                maximum =
                                    maxOf(
                                        maximum,
                                        checkNotNull(frame.chrome()).baseline - ascent + ink[1],
                                    )
                            }
                        }
                        assertTrue(minimum.isFinite())
                        assertEquals(
                            widget.bounds.y + widget.bounds.height / 2,
                            (minimum + maximum) / 2,
                            0.501,
                            "$position visible ink should be vertically centered: ${widget.text.plain}",
                        )
                        assertEquals(widget.bounds.x + 9, widget.textBounds.x, 0.001)
                    }
                }
            }
        }
    }

    @Test
    fun scrollbarBackMatchesFrontTrackAfterNativeHalfTurn() {
        val base = RenderFixtures.frame("npc", "Hello", 5)
        val scrollbar =
            WidgetFrame.Scrollbar(
                WidgetFrame.Rect(600.0, 0.0, 2.0, 132.0),
                WidgetFrame.Rect(600.0, 0.0, 2.0, 56.0),
                listOf(
                    WidgetFrame.NavigationButton.page(
                        WidgetFrame.NavigationDirection.PREVIOUS,
                        WidgetFrame.Rect(430.0, -28.0, 64.0, 24.0),
                        null,
                    )
                ),
            )
        val frame =
            WidgetFrame(
                base.semantic(),
                base.widgets(),
                base.fontPin(),
                base.theme(),
                null,
                scrollbar,
            )
        val bar =
            MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS).widgets().first {
                it.kind == WidgetFrame.Kind.SCROLLBAR
            }
        assertTrue(plain(bar.front).codePoints().anyMatch { it in 0xe700 until 0xe780 })
        assertTrue(
            plain(bar.back).codePoints().anyMatch { it in 0xe700 until 0xe780 },
            "The indicator must have a visible back",
        )
        val frontInk = barOffset(bar.front)
        val backInk = barOffset(bar.back)
        assertEquals(
            bar.bounds.width - frontInk - 2,
            backInk,
            1e-8,
            "Native yaw180 must return the back ink to the front track's world X",
        )
        assertFalse(plain(bar.back).contains("\ue780"))
    }

    @Test
    fun panelsUseBakedPreviewTexturesWithoutOverlappingStripGlyphs() {
        for (key in listOf("hud", "npc")) {
            val encoded =
                MinecraftWidgetEncoder()
                    .encode(
                        RenderFixtures.frame(key, "Hello", 5),
                        RenderFixtures.ASSETS,
                    )
            for (widget in encoded.widgets()) {
                if (widget.kind == WidgetFrame.Kind.HINT) continue
                for (face in listOf(widget.front, widget.back)) {
                    val codes = plain(face).codePoints().toArray()
                    assertFalse(
                        codes.any { it in 0xe001..0xe100 },
                        "Overlapping coloured column glyphs cause the photographed striped panels",
                    )
                    val tiles = codes.count { it in 0xe400 until 0xe500 }
                    assertTrue(
                        tiles in 3..68,
                        "A panel must use the bounded precomposed preview tiles",
                    )
                }
            }
        }
    }

    @Test
    fun lettersExplicitlyReceiveDepthSeparatingShadow() {
        val encoded =
            MinecraftWidgetEncoder()
                .encode(
                    RenderFixtures.frame("npc", "Hello", 5),
                    RenderFixtures.ASSETS,
                )
        val text = encoded.widgets().first { it.id == "text" }
        val letters =
            parts(text.front)
                .filter { it is TextComponent && it.content() == "Hello" }
                .findFirst()
                .orElseThrow()
        assertEquals(ShadowColor.shadowColor(0xff000000.toInt()), letters.shadowColor())
        assertTrue(
            parts(text.back)
                .filter { it is TextComponent && it.content().isNotEmpty() }
                .allMatch { ShadowColor.none() == it.shadowColor() }
        )
    }

    @Test
    fun sameWidgetsEncodeForHudAndNpc() {
        val encoder = MinecraftWidgetEncoder()
        for (key in listOf("hud", "npc")) {
            val frame = RenderFixtures.frame(key, "Hello", 5)
            val encoded = encoder.encode(frame, RenderFixtures.ASSETS)
            assertEquals(frame.widgets().map { it.id }, encoded.widgets().map { it.id })
            assertEquals("ask", encoded.widgets().first { it.id == "choice:ask" }.choiceId)
        }
    }

    @Test
    fun revealChangesOnlyGlyphPrefix() {
        val encoder = MinecraftWidgetEncoder()
        val first = encoder.encode(RenderFixtures.frame("hud", "Hello", 1), RenderFixtures.ASSETS)
        val complete =
            encoder.encode(RenderFixtures.frame("hud", "Hello", 5), RenderFixtures.ASSETS)
        val firstWidget = first.widgets().first()
        val completeWidget = complete.widgets().first()
        assertEquals(firstWidget.bounds, completeWidget.bounds)
        assertFalse(plain(firstWidget.front).contains("Hello"))
        assertTrue(plain(completeWidget.front).contains("Hello"))
        assertFalse(plain(completeWidget.back).contains("Hello"))
    }

    @Test
    fun namesStayCentered() {
        val frame = RenderFixtures.frame("npc", "Hello", 5)
        val name = frame.widgets().first { it.id == "speaker" }
        assertEquals(
            name.bounds.x + name.bounds.width / 2,
            name.textBounds.x + name.textBounds.width / 2,
            0.001,
        )
        val encoded = MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS)
        assertEquals(name.bounds, encoded.widgets().first { it.id == "speaker" }.bounds)
    }

    @Test
    fun paletteAndShadowAreApplied() {
        val frame = RenderFixtures.frame("hud", "Hello", 5)
        val altered =
            WidgetFrame(
                frame.semantic(),
                frame.widgets(),
                frame.fontPin(),
                DialogueTheme(
                    "other",
                    mapOf(
                        "text" to 0x123456,
                        "panel" to 0x010203,
                        "selected" to 0xabcdef,
                        "muted" to 0x555555,
                    ),
                    1.08,
                    Duration.ofMillis(150),
                ),
            )
        assertNotEquals(
            MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS).hud(),
            MinecraftWidgetEncoder().encode(altered, RenderFixtures.ASSETS).hud(),
        )
    }

    @Test
    fun unsupportedRasterDiagnoses() {
        val frame = RenderFixtures.frame("hud", "Hello", 5)
        assertThrows(DialoguePresenter.FallbackRequired::class.java) {
            MinecraftWidgetEncoder()
                .encode(
                    WidgetFrame(frame.semantic(), frame.widgets(), "wrong-pin", frame.theme()),
                    RenderFixtures.ASSETS,
                )
        }
        val old = frame.widgets().first()
        val bad =
            WidgetFrame.Widget(
                old.id,
                old.kind,
                WidgetFrame.Rect(0.0, 0.0, 320.0, 300.0),
                old.textBounds,
                old.text,
                old.layout,
                old.visibleGraphemes,
                false,
                true,
                1.0,
                old.transition,
                null,
            )
        assertThrows(DialoguePresenter.FallbackRequired::class.java) {
            MinecraftWidgetEncoder()
                .encode(
                    WidgetFrame(frame.semantic(), listOf(bad), frame.fontPin(), frame.theme()),
                    RenderFixtures.ASSETS,
                )
        }
    }
}
