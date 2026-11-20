package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DialoguePresenterTest {
    @Test
    fun profilesReuseSemanticWidgetsAndStableHitRects() {
        val presenter =
            PresenterFixtures.presenter(PresenterFixtures.Surface(), PresenterFixtures.Surface())
        val hidden =
            presenter.compose(
                PresenterFixtures.view("Hello", 0, "c1", 1),
                "npc",
                PresentationPreferences.defaults(),
            )
        val full =
            presenter.compose(
                PresenterFixtures.view("Hello", 5, "c2", 2),
                "npc",
                PresentationPreferences.defaults(),
            )
        val hiddenText = hidden.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
        val fullText = full.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
        assertEquals(hiddenText.bounds, fullText.bounds)
        val before = hidden.widgets().first { it.choiceId == "c1" }
        val after = full.widgets().first { it.choiceId == "c1" }
        assertEquals(1.08, before.visualScale)
        assertEquals(1.0, after.visualScale)
        assertEquals(before.hitRect(), after.hitRect())
        val name = full.widgets().first { it.kind == WidgetFrame.Kind.SPEAKER }
        assertEquals(
            name.bounds.x + name.bounds.width / 2,
            name.textBounds.x + name.textBounds.width / 2,
            0.00001,
        )
    }

    @Test
    fun unsupportedGlyphProducesTypedFallback() {
        val presenter =
            PresenterFixtures.presenter(PresenterFixtures.Surface(), PresenterFixtures.Surface())
        val problem =
            assertThrows(DialoguePresenter.FallbackRequired::class.java) {
                presenter.compose(
                    PresenterFixtures.view("你好", 2, "c1", 1),
                    "hud",
                    PresentationPreferences.defaults(),
                )
            }
        assertEquals("MISSING_GLYPH", problem.code())
    }
}
