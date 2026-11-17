package gg.grounds.dialogue.minestom.input;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.DialogueView;
import gg.grounds.dialogue.minestom.render.RenderFixtures;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceInputTargetsTest {
  private WidgetFrame frame(long render) {
    var f = RenderFixtures.frame("hud", "Hello", 5);
    var v = f.semantic();
    var next =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            v.stateRevision(),
            render,
            v.localeTag(),
            v.speaker(),
            v.text(),
            v.choices(),
            v.selectedChoiceId(),
            v.totalGraphemes(),
            v.revealedGraphemes(),
            v.hint(),
            v.notice(),
            v.pendingAction());
    return new WidgetFrame(next, f.widgets(), f.fontPin(), f.theme());
  }

  @Test
  void cancelledCandidateDoesNotRetireRetainedHud() {
    var t = new SurfaceInputTargets();
    var r = new WidgetFrame.Rect(0, 0, 20, 10);
    t.publish(frame(1), 1, Map.of("ask", r));
    t.invalidateCurrent(1);
    t.clear(2);
    t.publish(frame(2), 1, Map.of("ask", r));
    assertNotNull(t.current());
    assertEquals(1, t.current().surfaceGeneration());
    t.publish(frame(3), 2, Map.of("leave", r));
    assertEquals(1, t.current().surfaceGeneration());
    t.clear(1);
    t.publish(frame(4), 1, Map.of("ask", r));
    assertNull(t.current());
  }

  @Test
  void staleFrameOrRemovedSurfaceCannotIssueTargets() {
    var t = new SurfaceInputTargets();
    var r = new WidgetFrame.Rect(0, 0, 20, 10);
    t.publish(frame(5), 2, Map.of("ask", r));
    t.publish(frame(4), 2, Map.of("leave", r));
    assertEquals(5, t.current().renderRevision());
    assertTrue(t.current().choices().containsKey("ask"));
    t.clear(1);
    assertNotNull(t.current());
    t.clear(2);
    assertNull(t.current());
    t.publish(frame(6), 2, Map.of("ask", r));
    assertNull(t.current());
    t.publish(frame(7), 3, Map.of("ask", r));
    assertNotNull(t.current());
  }

  @Test
  void navigationDestinationsAreIssuedOffersAndDisabledButtonsStayScoped() {
    var t = new SurfaceInputTargets();
    var f = frame(1);
    var track = new WidgetFrame.Rect(600, 0, 2, 132);
    var up =
        new WidgetFrame.NavigationButton(
            WidgetFrame.NavigationDirection.PREVIOUS, new WidgetFrame.Rect(593, -20, 16, 16), null);
    var forged =
        new WidgetFrame.NavigationButton(
            WidgetFrame.NavigationDirection.NEXT, new WidgetFrame.Rect(593, 136, 16, 16), "forged");
    var bad =
        new WidgetFrame(
            f.semantic(),
            f.widgets(),
            f.fontPin(),
            f.theme(),
            null,
            new WidgetFrame.Scrollbar(
                track, new WidgetFrame.Rect(600, 0, 2, 56), List.of(up, forged)));
    assertThrows(IllegalArgumentException.class, () -> t.publish(bad, 1, Map.of()));
    var down = new WidgetFrame.NavigationButton(forged.direction(), forged.bounds(), "leave");
    var good =
        new WidgetFrame(
            f.semantic(),
            f.widgets(),
            f.fontPin(),
            f.theme(),
            null,
            new WidgetFrame.Scrollbar(
                track, new WidgetFrame.Rect(600, 0, 2, 56), List.of(up, down)));
    t.publish(good, 1, Map.of());
    assertEquals(2, t.current().navigation().size());
    assertFalse(t.current().navigation().get(WidgetFrame.NavigationDirection.PREVIOUS).enabled());
    assertEquals(
        "leave", t.current().navigation().get(WidgetFrame.NavigationDirection.NEXT).choiceId());
    t.clear(1);
    assertNull(t.current());
    t.publish(good, 1, Map.of());
    assertNull(t.current());
  }

  @Test
  void foreignChoiceCannotBecomeClickable() {
    var t = new SurfaceInputTargets();
    assertThrows(
        IllegalArgumentException.class,
        () -> t.publish(frame(1), 1, Map.of("forged", new WidgetFrame.Rect(0, 0, 20, 10))));
    assertNull(t.current());
  }
}
