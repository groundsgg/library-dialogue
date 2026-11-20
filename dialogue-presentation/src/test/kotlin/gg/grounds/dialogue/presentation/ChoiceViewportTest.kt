package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.DialogueView.Availability
import gg.grounds.dialogue.core.runtime.DialogueView.ChoiceView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChoiceViewportTest {
    @Test
    fun browsingKeepsSelectionOffscreenAndSelectionChangeRevealsIt() {
        val cursor = ChoiceViewport.Cursor()
        val first = PresenterFixtures.view("Hello", 5, "c1", 1)
        assertEquals(0, cursor.startFor(first, 3))
        assertTrue(cursor.browse(first, 3, 3))
        val window =
            ChoiceViewport()
                .window(first.choices(), first.selectedChoiceId(), 3, cursor.startFor(first, 3))
        assertEquals(3, window.start())
        assertEquals(-1, window.selectedIndex())
        assertEquals(listOf("c4", "c5", "c6"), window.choices().map(ChoiceView::id))
        assertEquals(3, cursor.startFor(PresenterFixtures.view("Hello", 5, "c1", 2), 3))
        assertFalse(cursor.browse(first, 3, 99))
        assertEquals(0, cursor.startFor(PresenterFixtures.view("Hello", 5, "c2", 3), 3))
    }

    @Test
    fun presenterHandleOwnsViewportWithoutMutatingCoreView() {
        val surface = PresenterFixtures.Surface()
        val presenter = PresenterFixtures.presenter(PresenterFixtures.Surface(), surface)
        val handle = presenter.prepare(PresenterFixtures.request()).toCompletableFuture().join()
        handle
            .reconfigure(PresentationPreferences("npc", Duration.ZERO, false, emptyMap()))
            .toCompletableFuture()
            .join()
        val view = PresenterFixtures.view("Hello", 5, "c1", 1)
        handle.replace(view).toCompletableFuture().join()
        assertTrue(
            (handle as ChoiceViewport.Controller)
                .setChoiceStart(view, 3)
                .toCompletableFuture()
                .join()
        )
        handle.replace(view).toCompletableFuture().join()
        val last = surface.frames.last()
        assertSame(view, last.semantic())
        assertTrue(last.widgets().none { it.selected })
        assertEquals(
            3,
            last
                .scrollbar()!!
                .buttons()
                .first { it.direction == WidgetFrame.NavigationDirection.PREVIOUS }
                .pageStart!! + 3,
        )
        handle.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun sevenChoicesThreeCards() {
        val offers =
            listOf("c1", "c2", "c3", "c4", "c5", "c6", "leave").map {
                ChoiceView(it, it, Availability.AVAILABLE, "")
            }
        val reached = hashSetOf<String>()
        val viewport = ChoiceViewport()
        for (choice in offers) {
            val window = viewport.window(offers, choice.id, 3)
            assertTrue(window.choices().size <= 3)
            assertTrue(window.choices().any { it.id == choice.id })
            reached += window.choices().map(ChoiceView::id)
        }
        assertEquals(setOf("c1", "c2", "c3", "c4", "c5", "c6", "leave"), reached)
        assertEquals(6, viewport.window(offers, "leave", 3).start())
    }
}
