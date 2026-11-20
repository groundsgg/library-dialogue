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
