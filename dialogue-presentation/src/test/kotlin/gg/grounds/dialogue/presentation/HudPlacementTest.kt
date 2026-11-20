package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HudPlacementTest {
    @Test
    fun prototypeHudUsesCompactRowsAndEmbeddedSpeaker() {
        for (position in LayoutProfile.ChoicePlacement.entries) {
            val frame =
                presenter(position)
                    .compose(
                        PresenterFixtures.view("Hello", 5, "c1", 1),
                        "hud",
                        PresentationPreferences.defaults(),
                    )
            val speech = frame.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
            val answer = frame.widgets().first { it.kind == WidgetFrame.Kind.CHOICE }
            assertEquals(10.0, answer.bounds.height)
            assertEquals(48.0, speech.bounds.height)
            assertNotNull(frame.chrome())
            val speaker = frame.widgets().first { it.kind == WidgetFrame.Kind.SPEAKER }
            assertEquals(7.0, speaker.bounds.y)
            assertEquals(17.0, speech.textBounds.y)
            assertTrue(answer.text.plain.startsWith("> "))
            assertEquals(
                if (position == LayoutProfile.ChoicePlacement.BELOW) 222.0 else 117.0,
                answer.bounds.width,
            )
            assertTrue(answer.bounds.width < speech.bounds.width)
            if (position == LayoutProfile.ChoicePlacement.BELOW) assertEquals(9.0, answer.bounds.x)
        }
    }

    @Test
    fun individualCardsCenterTextVerticallyAndKeepItLeftAligned() {
        for (position in LayoutProfile.ChoicePlacement.entries) {
            val frame =
                presenter(position, LayoutProfile.Style.HUD_CARDS)
                    .compose(
                        PresenterFixtures.view("Hello", 5, "c1", 1),
                        "hud",
                        PresentationPreferences.defaults(),
                    )
            frame
                .widgets()
                .filter { it.kind == WidgetFrame.Kind.CHOICE }
                .forEach {
                    assertEquals(20.0, it.bounds.height)
                    assertEquals(it.bounds.x + 9, it.textBounds.x, .001)
                    assertEquals(
                        it.bounds.y + it.bounds.height / 2,
                        it.textBounds.y + it.textBounds.height / 2,
                        .001,
                    )
                }
        }
    }

    @Test
    fun overflowingAnswersExposeAProportionalScrollbarAcrossAllHudStyles() {
        for (style in
            listOf(
                LayoutProfile.Style.HUD,
                LayoutProfile.Style.HUD_SPLIT,
                LayoutProfile.Style.HUD_CARDS,
            )) {
            for (position in LayoutProfile.ChoicePlacement.entries) {
                val presenter = presenter(position, style)
                val first =
                    presenter.compose(
                        PresenterFixtures.view("Hello", 5, "c1", 1),
                        "hud",
                        PresentationPreferences.defaults(),
                    )
                val last =
                    presenter.compose(
                        PresenterFixtures.view("Hello", 5, "leave", 2),
                        "hud",
                        PresentationPreferences.defaults(),
                    )
                if (style == LayoutProfile.Style.HUD_CARDS) {
                    for (frame in listOf(first, last)) {
                        val right =
                            frame
                                .widgets()
                                .filter { it.kind == WidgetFrame.Kind.CHOICE }
                                .maxOf { it.bounds.x + it.bounds.width }
                        assertTrue(frame.scrollbar()!!.track().x >= right + 5)
                    }
                    assertTrue(last.chrome()!!.key.endsWith("_1"))
                }
                assertNotNull(first.scrollbar())
                assertNotNull(last.scrollbar())
                assertEquals(first.scrollbar()!!.track().y, first.scrollbar()!!.thumb().y, .001)
                assertTrue(last.scrollbar()!!.thumb().y > first.scrollbar()!!.thumb().y)
                assertEquals(
                    last.scrollbar()!!.track().y + last.scrollbar()!!.track().height,
                    last.scrollbar()!!.thumb().y + last.scrollbar()!!.thumb().height,
                    .001,
                )
                assertTrue(first.scrollbar()!!.thumb().height < first.scrollbar()!!.track().height)
                assertTrue(last.scrollbar()!!.thumb().height <= first.scrollbar()!!.thumb().height)
                assertEquals(first.canvasBounds().width, last.canvasBounds().width)
            }
        }
    }

    @Test
    fun cardsKeepAStableThreeSlotViewportWithoutPaintingMissingAnswers() {
        for (position in LayoutProfile.ChoicePlacement.entries) {
            val presenter = presenter(position, LayoutProfile.Style.HUD_CARDS)
            for (count in 0..3) {
                val base = PresenterFixtures.view("Hello", 5, "c1", 1)
                val view =
                    copyView(base, base.choices().subList(0, count), if (count == 0) null else "c1")
                val frame = presenter.compose(view, "hud", PresentationPreferences.defaults())
                assertEquals(count, frame.widgets().count { it.kind == WidgetFrame.Kind.CHOICE })
                assertTrue(frame.chrome()!!.key.endsWith("_$count"))
                assertNull(frame.scrollbar())
                assertEquals(
                    if (position == LayoutProfile.ChoicePlacement.BELOW) 120.0 else 66.0,
                    frame.chrome()!!.bounds.height,
                )
                assertEquals(
                    if (position == LayoutProfile.ChoicePlacement.BELOW) 249.0
                    else if (position == LayoutProfile.ChoicePlacement.LEFT) 312.0 else 309.0,
                    frame.canvasBounds().width,
                )
            }
        }
    }

    @Test
    fun aWrappedAnswerOnAnotherPageStillReservesTheSameCardHeight() {
        val presenter =
            presenter(LayoutProfile.ChoicePlacement.BELOW, LayoutProfile.Style.HUD_CARDS)
        val frames =
            listOf("c1", "leave").map { selected ->
                val base = PresenterFixtures.view("Hello", 5, selected, 1)
                val choices =
                    base.choices().map {
                        DialogueView.ChoiceView(
                            it.id,
                            if (it.id == "c2") "First\nSecond" else it.label,
                            it.availability,
                            it.unavailableReason,
                        )
                    }
                presenter.compose(
                    copyView(base, choices, selected),
                    "hud",
                    PresentationPreferences.defaults(),
                )
            }
        assertEquals(frames[0].chrome()!!.bounds, frames[1].chrome()!!.bounds)
        assertEquals(frames[0].chrome()!!.baseline, frames[1].chrome()!!.baseline)
    }

    @Test
    fun preparationPinIdentifiesTheResolvedLayout() {
        val pins = arrayListOf<String>()
        val surface = DialogueSurface { context ->
            pins += context.profilePin
            CompletableFuture.completedFuture(PresenterFixtures.Surface().handle())
        }
        val profile =
            DialoguePresenter.SurfaceProfile(
                surface,
                LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.BELOW),
                DialogueTheme.neutral(),
                hudMetrics(),
                false,
                java.util.function.Function { preferences ->
                    LayoutProfile.compactHud(
                        LayoutProfile.ChoicePlacement.valueOf(
                            preferences.effects()["hudChoices"]!!.uppercase(Locale.ROOT)
                        )
                    )
                },
            )
        val presenter = DialoguePresenter(mapOf("hud" to profile), PresenterFixtures.Clock())
        for (position in listOf("left", "right")) {
            val request =
                DialogueRenderer.PresentationRequest(
                    PresenterFixtures.SESSION,
                    "player",
                    1,
                    PresentationPreferences(
                        "hud",
                        Duration.ZERO,
                        false,
                        mapOf("hudChoices" to position),
                    ),
                    CancellationToken(),
                    5_000_000_000L,
                )
            presenter
                .prepare(request)
                .toCompletableFuture()
                .join()
                .closeAsync()
                .toCompletableFuture()
                .join()
        }
        assertEquals(2, pins.size)
        assertNotEquals(pins[0], pins[1])
    }

    @Test
    fun compactChoicesCanSitBelowLeftOrRightWithoutChangingNpcLayout() {
        val presenter =
            DialoguePresenter(
                mapOf(
                    "hud" to
                        DialoguePresenter.SurfaceProfile(
                            PresenterFixtures.Surface(),
                            LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.BELOW),
                            DialogueTheme.neutral(),
                            hudMetrics(),
                            false,
                            java.util.function.Function { preferences ->
                                LayoutProfile.compactHud(
                                    LayoutProfile.ChoicePlacement.valueOf(
                                        preferences
                                            .effects()
                                            .getOrDefault("hudChoices", "below")
                                            .uppercase(Locale.ROOT)
                                    )
                                )
                            },
                        ),
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            PresenterFixtures.Surface(),
                            LayoutProfile.npc(),
                            DialogueTheme.neutral(),
                            FontMetrics.defaults(),
                            false,
                        ),
                ),
                PresenterFixtures.Clock(),
            )
        val view = PresenterFixtures.view("Hello", 5, "c4", 2)
        for (position in listOf("below", "left", "right")) {
            val preferences =
                PresentationPreferences(
                    "hud",
                    Duration.ZERO,
                    false,
                    mapOf("hudChoices" to position),
                )
            val frame = presenter.compose(view, "hud", preferences)
            val speech = frame.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
            val choices = frame.widgets().filter { it.kind == WidgetFrame.Kind.CHOICE }
            assertEquals(listOf("c4", "c5", "c6"), choices.map { it.choiceId })
            assertEquals(3, choices.size)
            assertTrue(speech.bounds.width < 320)
            assertEquals(if (position == "below") 240.0 else 300.0, frame.canvasBounds().width)
            choices.forEach {
                assertEquals(it.bounds, it.hitRect())
                when (position) {
                    "below" -> assertTrue(it.bounds.y >= speech.bounds.y + speech.bounds.height + 2)
                    "left" -> assertTrue(it.bounds.x + it.bounds.width <= speech.bounds.x - 3)
                    "right" -> assertTrue(it.bounds.x >= speech.bounds.x + speech.bounds.width + 3)
                }
            }
            assertTrue(choices[1].bounds.y > choices[0].bounds.y)
            val npc = presenter.compose(view, "npc", preferences)
            assertEquals(
                320.0,
                npc.widgets().first { it.kind == WidgetFrame.Kind.TEXT }.bounds.width,
            )
        }
    }

    companion object {
        fun hudMetrics(): FontMetrics =
            object : FontMetrics {
                override fun width(grapheme: String): Double = 6.0

                override fun lineHeight(): Double = 10.0

                override fun pin(): String = "fixture-hud"
            }

        fun presenter(
            position: LayoutProfile.ChoicePlacement,
            style: LayoutProfile.Style = LayoutProfile.Style.HUD,
        ): DialoguePresenter =
            DialoguePresenter(
                mapOf(
                    "hud" to
                        DialoguePresenter.SurfaceProfile(
                            PresenterFixtures.Surface(),
                            LayoutProfile.compactHud(position, style),
                            DialogueTheme.hudPrototype(),
                            hudMetrics(),
                            false,
                        )
                ),
                PresenterFixtures.Clock(),
            )

        fun copyView(
            base: DialogueView,
            choices: List<DialogueView.ChoiceView>,
            selected: String?,
        ): DialogueView =
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
                selected,
                base.totalGraphemes(),
                base.revealedGraphemes(),
                base.hint(),
                base.notice(),
                base.pendingAction(),
            )
    }
}
