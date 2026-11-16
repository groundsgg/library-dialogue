package gg.grounds.dialogue.presentation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.DialogueView.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChoiceViewportTest {
  @Test
  void browsingKeepsSelectionOffscreenAndSelectionChangeRevealsIt() {
    var cursor = new ChoiceViewport.Cursor();
    var first = PresenterFixtures.view("Hello", 5, "c1", 1);
    assertEquals(0, cursor.startFor(first, 3));
    assertTrue(cursor.browse(first, 3, 3));
    var w =
        new ChoiceViewport()
            .window(first.choices(), first.selectedChoiceId(), 3, cursor.startFor(first, 3));
    assertEquals(3, w.start());
    assertEquals(-1, w.selectedIndex());
    assertEquals(List.of("c4", "c5", "c6"), w.choices().stream().map(ChoiceView::id).toList());
    assertEquals(3, cursor.startFor(PresenterFixtures.view("Hello", 5, "c1", 2), 3));
    assertFalse(cursor.browse(first, 3, 99));
    assertEquals(0, cursor.startFor(PresenterFixtures.view("Hello", 5, "c2", 3), 3));
  }

  @Test
  void presenterHandleOwnsViewportWithoutMutatingCoreView() {
    var surface = new PresenterFixtures.Surface();
    var p = PresenterFixtures.presenter(new PresenterFixtures.Surface(), surface);
    var h = p.prepare(PresenterFixtures.request()).toCompletableFuture().join();
    h.reconfigure(
            new gg.grounds.dialogue.core.runtime.PresentationPreferences(
                "npc", java.time.Duration.ZERO, false, Map.of()))
        .toCompletableFuture()
        .join();
    var v = PresenterFixtures.view("Hello", 5, "c1", 1);
    h.replace(v).toCompletableFuture().join();
    assertTrue(((ChoiceViewport.Controller) h).setChoiceStart(v, 3).toCompletableFuture().join());
    h.replace(v).toCompletableFuture().join();
    var last = surface.frames.getLast();
    assertSame(v, last.semantic());
    assertTrue(last.widgets().stream().noneMatch(WidgetFrame.Widget::selected));
    assertEquals(
        3,
        last.scrollbar().buttons().stream()
                .filter(b -> b.direction() == WidgetFrame.NavigationDirection.PREVIOUS)
                .findFirst()
                .orElseThrow()
                .pageStart()
            + 3);
    h.closeAsync().toCompletableFuture().join();
  }

  @Test
  void sevenChoicesThreeCards() {
    var offers =
        List.of("c1", "c2", "c3", "c4", "c5", "c6", "leave").stream()
            .map(id -> new ChoiceView(id, id, Availability.AVAILABLE, ""))
            .toList();
    var reached = new HashSet<String>();
    var v = new ChoiceViewport();
    for (var c : offers) {
      var window = v.window(offers, c.id(), 3);
      assertTrue(window.choices().size() <= 3);
      assertTrue(window.choices().stream().anyMatch(o -> o.id().equals(c.id())));
      window.choices().forEach(o -> reached.add(o.id()));
    }
    assertEquals(Set.of("c1", "c2", "c3", "c4", "c5", "c6", "leave"), reached);
    assertEquals(6, v.window(offers, "leave", 3).start());
  }
}
