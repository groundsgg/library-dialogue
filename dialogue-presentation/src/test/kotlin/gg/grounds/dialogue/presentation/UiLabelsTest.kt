package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiLabelsTest {
    @Test
    fun legacyMetricsInheritOptOutThroughJvmDefaultMethod() {
        val legacy =
            object : FontMetrics {
                override fun width(grapheme: String) = 4.0

                override fun lineHeight() = 16.0

                override fun pin() = "legacy"
            }
        assertFalse(legacy.supportsControlGlyphs())
        assertFalse(FontMetrics.defaults().supportsControlGlyphs())
        assertTrue(FontMetrics::class.java.getMethod("supportsControlGlyphs").isDefault)
        val legacyJava = LegacyFontMetricsFixture()
        assertFalse(legacyJava.supportsControlGlyphs())
        assertFalse(legacyJava.javaClass.declaredMethods.any { it.name == "supportsControlGlyphs" })
    }

    @Test
    fun presenterUsesActualMetricsCapabilityBeforeLayoutForPanelsAndHud() {
        for (enabled in listOf(false, true)) for (layout in
            listOf(
                LayoutProfile.hud(),
                LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.BELOW),
            )) {
            val metrics =
                object : FontMetrics {
                    override fun supportsControlGlyphs() = enabled

                    override fun width(grapheme: String) = 4.0

                    override fun lineHeight() =
                        if (layout.style == LayoutProfile.Style.PANELS) 16.0 else 10.0

                    override fun pin() = "test"
                }
            val presenter =
                DialoguePresenter(
                    mapOf(
                        "hud" to
                            DialoguePresenter.SurfaceProfile(
                                PresenterFixtures.Surface(),
                                layout,
                                DialogueTheme.neutral(),
                                metrics,
                                false,
                            )
                    ),
                    PresenterFixtures.Clock(),
                    DialoguePresenter.Controls(DialoguePresenter.ConfirmAction.USE, true),
                )
            val hint =
                text(
                    presenter.compose(
                        PresenterFixtures.view("Hello", 5, "c1", 1),
                        "hud",
                        PresentationPreferences.defaults(),
                    ),
                    "hint",
                )
            assertEquals(if (enabled) "\ue792 · \ue791 · Select" else "Scroll · Use · Select", hint)
        }
    }

    @Test
    fun controlIconsReplaceOnlyInputsForEveryLocalePhaseAndAction() {
        for (locale in listOf("en", "de")) for (phase in DialogueView.HintPhase.entries) {
            for ((action, glyph) in
                listOf(
                    DialoguePresenter.ConfirmAction.ATTACK to "\ue790",
                    DialoguePresenter.ConfirmAction.USE to "\ue791",
                    DialoguePresenter.ConfirmAction.SWAP_HANDS to "\ue793",
                )) {
                val controls = DialoguePresenter.Controls(action, true)
                val plain =
                    UiLabelSource.defaults()
                        .resolve(
                            UiLabelSource.LabelRequest(
                                locale,
                                phase,
                                controls,
                                "CHOICES_CHANGED",
                                emptyMap(),
                            )
                        )
                val icons =
                    UiLabelSource.defaults()
                        .resolve(
                            UiLabelSource.LabelRequest(
                                locale,
                                phase,
                                controls,
                                "CHOICES_CHANGED",
                                emptyMap(),
                                true,
                            )
                        )
                val verb = plain.hint().substringAfterLast(" · ")
                assertEquals(
                    (if (phase == DialogueView.HintPhase.CHOOSE) "\ue792 · " else "") +
                        glyph +
                        " · " +
                        verb,
                    icons.hint(),
                )
                assertEquals(plain.notice(), icons.notice())
                assertFalse(plain.hint().any { it in '\ue790'..'\ue793' })
            }
        }
    }

    @Test
    fun liveLocaleRefreshUsesInjectedLabels() {
        val seen = arrayListOf<UiLabelSource.LabelRequest>()
        val presenter = presenter { request ->
            seen += request
            UiLabelSource.UiLabels(
                if (request.localeTag() == "fr") "Continuer" else "Continue",
                "",
                emptyMap(),
            )
        }
        val view = PresenterFixtures.view("Hello", 5, "c1", 1)
        val first = presenter.compose(view, "hud", PresentationPreferences.defaults())
        val next = change(view, "fr", "", view.choices())
        val second = presenter.compose(next, "hud", PresentationPreferences.defaults())
        assertEquals("Continue", text(first, "hint"))
        assertEquals("Continuer", text(second, "hint"))
        assertEquals(view.nodeVisitId(), second.semantic().nodeVisitId())
        assertEquals(DialoguePresenter.ConfirmAction.USE, seen.last().controls().confirm)
    }

    @Test
    fun changedOffersHaveSafeStatusText() {
        val presenter = presenter { request ->
            UiLabelSource.UiLabels(
                "Continue",
                if (request.notice() == "CHOICES_CHANGED") "Choices changed" else "",
                emptyMap(),
            )
        }
        val view = PresenterFixtures.view("Hello", 5, "c1", 1)
        val frame =
            presenter.compose(
                change(view, "en", "CHOICES_CHANGED", view.choices()),
                "hud",
                PresentationPreferences.defaults(),
            )
        assertEquals("Choices changed", text(frame, "status"))
        val hint = frame.widgets().first { it.id == "hint" }
        val status = frame.widgets().first { it.id == "status" }
        assertTrue(status.bounds.y >= hint.bounds.y + hint.bounds.height)
    }

    @Test
    fun unavailableCardsExposeLocalizedReason() {
        val presenter = presenter { request ->
            assertEquals("CHOICE_UNAVAILABLE", request.unavailableReasons()["c1"])
            UiLabelSource.UiLabels("Continue", "", mapOf("c1" to "Not available"))
        }
        val view = PresenterFixtures.view("Hello", 5, "c2", 1)
        val choices = ArrayList(view.choices())
        choices[0] =
            DialogueView.ChoiceView(
                "c1",
                "Ask",
                DialogueView.Availability.UNAVAILABLE,
                "CHOICE_UNAVAILABLE",
            )
        val frame =
            presenter.compose(
                change(view, "en", "", choices),
                "hud",
                PresentationPreferences.defaults(),
            )
        assertEquals("Ask\nNot available", text(frame, "choice:c1"))
        assertFalse(frame.widgets().first { it.id == "choice:c1" }.enabled)
    }

    @Test
    fun defaultsRetainExistingBehaviour() {
        val presenter =
            PresenterFixtures.presenter(PresenterFixtures.Surface(), PresenterFixtures.Surface())
        val view = PresenterFixtures.view("Hello", 5, "c1", 1)
        assertEquals(
            "Scroll · Swap hands · Select",
            text(presenter.compose(view, "hud", PresentationPreferences.defaults()), "hint"),
        )
        assertFalse(
            presenter.compose(view, "hud", PresentationPreferences.defaults()).widgets().any {
                it.id == "status"
            }
        )
    }

    @Test
    fun translatedStatusOverflowDiagnosesRatherThanClips() {
        val presenter = presenter {
            UiLabelSource.UiLabels("Continue", "A ".repeat(500), emptyMap())
        }
        assertThrows(DialoguePresenter.FallbackRequired::class.java) {
            presenter.compose(
                PresenterFixtures.view("Hello", 5, "c1", 1),
                "hud",
                PresentationPreferences.defaults(),
            )
        }
    }

    companion object {
        fun change(
            old: DialogueView,
            locale: String,
            notice: String,
            choices: List<DialogueView.ChoiceView>,
        ): DialogueView =
            DialogueView(
                old.sessionId(),
                old.contentPin(),
                old.nodeId(),
                old.nodeVisitId(),
                old.state(),
                old.stateRevision() + 1,
                old.renderRevision() + 1,
                locale,
                old.speaker(),
                old.text(),
                choices,
                old.selectedChoiceId(),
                old.totalGraphemes(),
                old.revealedGraphemes(),
                old.hint(),
                notice,
                old.pendingAction(),
            )

        fun presenter(labels: UiLabelSource): DialoguePresenter =
            DialoguePresenter(
                mapOf(
                    "hud" to
                        DialoguePresenter.SurfaceProfile(
                            PresenterFixtures.Surface(),
                            LayoutProfile.hud(),
                            DialogueTheme.neutral(),
                            FontMetrics.defaults(),
                            false,
                        )
                ),
                PresenterFixtures.Clock(),
                DialoguePresenter.Controls(DialoguePresenter.ConfirmAction.USE, true),
                labels,
            )

        fun text(frame: WidgetFrame, id: String): String =
            frame.widgets().first { it.id == id }.text.plain
    }
}
