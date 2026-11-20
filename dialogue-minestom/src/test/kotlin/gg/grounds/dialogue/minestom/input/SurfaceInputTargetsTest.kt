package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.minestom.render.RenderFixtures
import gg.grounds.dialogue.presentation.WidgetFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurfaceInputTargetsTest {
    private fun frame(render: Long): WidgetFrame {
        val frame = RenderFixtures.frame("hud", "Hello", 5)
        val view = frame.semantic()
        val next =
            DialogueView(
                view.sessionId(),
                view.contentPin(),
                view.nodeId(),
                view.nodeVisitId(),
                view.state(),
                view.stateRevision(),
                render,
                view.localeTag(),
                view.speaker(),
                view.text(),
                view.choices(),
                view.selectedChoiceId(),
                view.totalGraphemes(),
                view.revealedGraphemes(),
                view.hint(),
                view.notice(),
                view.pendingAction(),
            )
        return WidgetFrame(next, frame.widgets(), frame.fontPin(), frame.theme())
    }

    @Test
    fun cancelledCandidateDoesNotRetireRetainedHud() {
        val targets = SurfaceInputTargets()
        val rectangle = WidgetFrame.Rect(0.0, 0.0, 20.0, 10.0)
        targets.publish(frame(1), 1, mapOf("ask" to rectangle))
        targets.invalidateCurrent(1)
        targets.clear(2)
        targets.publish(frame(2), 1, mapOf("ask" to rectangle))
        assertNotNull(targets.current())
        assertEquals(1, checkNotNull(targets.current()).surfaceGeneration())
        targets.publish(frame(3), 2, mapOf("leave" to rectangle))
        assertEquals(1, checkNotNull(targets.current()).surfaceGeneration())
        targets.clear(1)
        targets.publish(frame(4), 1, mapOf("ask" to rectangle))
        assertNull(targets.current())
    }

    @Test
    fun staleFrameOrRemovedSurfaceCannotIssueTargets() {
        val targets = SurfaceInputTargets()
        val rectangle = WidgetFrame.Rect(0.0, 0.0, 20.0, 10.0)
        targets.publish(frame(5), 2, mapOf("ask" to rectangle))
        targets.publish(frame(4), 2, mapOf("leave" to rectangle))
        assertEquals(5, checkNotNull(targets.current()).renderRevision())
        assertTrue(checkNotNull(targets.current()).choices().containsKey("ask"))
        targets.clear(1)
        assertNotNull(targets.current())
        targets.clear(2)
        assertNull(targets.current())
        targets.publish(frame(6), 2, mapOf("ask" to rectangle))
        assertNull(targets.current())
        targets.publish(frame(7), 3, mapOf("ask" to rectangle))
        assertNotNull(targets.current())
    }

    @Test
    fun navigationDestinationsAreIssuedOffersAndDisabledButtonsStayScoped() {
        val targets = SurfaceInputTargets()
        val frame = frame(1)
        val track = WidgetFrame.Rect(600.0, 0.0, 2.0, 132.0)
        val up =
            WidgetFrame.NavigationButton(
                WidgetFrame.NavigationDirection.PREVIOUS,
                WidgetFrame.Rect(593.0, -20.0, 16.0, 16.0),
                null,
            )
        val forged =
            WidgetFrame.NavigationButton(
                WidgetFrame.NavigationDirection.NEXT,
                WidgetFrame.Rect(593.0, 136.0, 16.0, 16.0),
                "forged",
            )
        val bad =
            WidgetFrame(
                frame.semantic(),
                frame.widgets(),
                frame.fontPin(),
                frame.theme(),
                null,
                WidgetFrame.Scrollbar(
                    track,
                    WidgetFrame.Rect(600.0, 0.0, 2.0, 56.0),
                    listOf(up, forged),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) { targets.publish(bad, 1, emptyMap()) }
        val down = WidgetFrame.NavigationButton(forged.direction, forged.bounds, "leave")
        val good =
            WidgetFrame(
                frame.semantic(),
                frame.widgets(),
                frame.fontPin(),
                frame.theme(),
                null,
                WidgetFrame.Scrollbar(
                    track,
                    WidgetFrame.Rect(600.0, 0.0, 2.0, 56.0),
                    listOf(up, down),
                ),
            )
        targets.publish(good, 1, emptyMap())
        assertEquals(2, checkNotNull(targets.current()).navigation().size)
        assertFalse(
            checkNotNull(targets.current())
                .navigation()[WidgetFrame.NavigationDirection.PREVIOUS]!!
                .enabled()
        )
        assertEquals(
            "leave",
            checkNotNull(targets.current())
                .navigation()[WidgetFrame.NavigationDirection.NEXT]!!
                .choiceId,
        )
        targets.clear(1)
        assertNull(targets.current())
        targets.publish(good, 1, emptyMap())
        assertNull(targets.current())
    }

    @Test
    fun foreignChoiceCannotBecomeClickable() {
        val targets = SurfaceInputTargets()
        assertThrows(IllegalArgumentException::class.java) {
            targets.publish(frame(1), 1, mapOf("forged" to WidgetFrame.Rect(0.0, 0.0, 20.0, 10.0)))
        }
        assertNull(targets.current())
    }
}
