package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NpcLayoutTest {
    @Test
    fun highlightedChoiceStillHasTheConfiguredClearGapAndSharedScrollbar() {
        val presenter =
            PresenterFixtures.presenter(PresenterFixtures.Surface(), PresenterFixtures.Surface())
        val frame =
            presenter.compose(
                PresenterFixtures.view("Hello", 5, "c1", 1),
                "npc",
                PresentationPreferences.defaults(),
            )
        val speech = frame.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
        val selected = frame.widgets().first { it.selected }
        val grownLeft = selected.bounds.x - selected.bounds.width * (selected.visualScale - 1) / 2
        assertTrue(grownLeft - speech.bounds.x - speech.bounds.width >= 12)
        assertNotNull(frame.scrollbar())
        val grownRight = selected.bounds.x + selected.bounds.width * (selected.visualScale + 1) / 2
        assertTrue(frame.scrollbar()!!.track().x >= grownRight + 8)
    }

    @Test
    fun pageButtonsUseRealOffersAndDisableAtTheEnds() {
        val presenter =
            PresenterFixtures.presenter(PresenterFixtures.Surface(), PresenterFixtures.Surface())
        val first =
            presenter.compose(
                PresenterFixtures.view("Hello", 5, "c1", 1),
                "npc",
                PresentationPreferences.defaults(),
            )
        val last =
            presenter.compose(
                PresenterFixtures.view("Hello", 5, "leave", 2),
                "npc",
                PresentationPreferences.defaults(),
            )
        val up =
            first.scrollbar()!!.buttons().first {
                it.direction == WidgetFrame.NavigationDirection.PREVIOUS
            }
        val down =
            first.scrollbar()!!.buttons().first {
                it.direction == WidgetFrame.NavigationDirection.NEXT
            }
        val choice = first.widgets().first { it.kind == WidgetFrame.Kind.CHOICE }
        val center = choice.bounds.x + choice.bounds.width / 2
        for (button in first.scrollbar()!!.buttons()) {
            assertEquals(center, button.bounds.x + button.bounds.width / 2)
            assertTrue(button.bounds.width >= 64, "Navigation should be easy to aim at")
            assertTrue(button.bounds.height >= 24)
        }
        val hint = first.widgets().first { it.id == "hint" }
        assertTrue(
            hint.bounds.y + hint.bounds.height <= up.bounds.y - 6,
            "Instruction text must not overlap the upper page control",
        )
        assertEquals(
            first.scrollbar()!!.buttons().map { it.bounds },
            last.scrollbar()!!.buttons().map { it.bounds },
        )
        assertFalse(up.enabled())
        assertEquals(3, down.pageStart)
        assertTrue(up.bounds.y + up.bounds.height < first.scrollbar()!!.track().y)
        assertTrue(
            down.bounds.y > first.scrollbar()!!.track().y + first.scrollbar()!!.track().height
        )
        assertTrue(
            last
                .scrollbar()!!
                .buttons()
                .filter { it.direction == WidgetFrame.NavigationDirection.NEXT }
                .none { it.enabled() }
        )
        assertEquals(
            3,
            last
                .scrollbar()!!
                .buttons()
                .first { it.direction == WidgetFrame.NavigationDirection.PREVIOUS }
                .pageStart,
        )
    }
}
