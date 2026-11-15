package gg.grounds.dialogue.presentation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcLayoutTest {
  @Test
  void highlightedChoiceStillHasTheConfiguredClearGapAndSharedScrollbar() {
    var presenter =
        PresenterFixtures.presenter(
            new PresenterFixtures.Surface(), new PresenterFixtures.Surface());
    var f =
        presenter.compose(
            PresenterFixtures.view("Hello", 5, "c1", 1), "npc", PresentationPreferences.defaults());
    var speech =
        f.widgets().stream()
            .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
            .findFirst()
            .orElseThrow();
    var selected =
        f.widgets().stream().filter(WidgetFrame.Widget::selected).findFirst().orElseThrow();
    double grownLeft =
        selected.bounds().x() - selected.bounds().width() * (selected.visualScale() - 1) / 2;
    assertTrue(grownLeft - speech.bounds().x() - speech.bounds().width() >= 12);
    assertNotNull(f.scrollbar());
    double grownRight =
        selected.bounds().x() + selected.bounds().width() * (selected.visualScale() + 1) / 2;
    assertTrue(f.scrollbar().track().x() >= grownRight + 8);
  }

  @Test
  void pageButtonsUseRealOffersAndDisableAtTheEnds() {
    var presenter =
        PresenterFixtures.presenter(
            new PresenterFixtures.Surface(), new PresenterFixtures.Surface());
    var first =
        presenter.compose(
            PresenterFixtures.view("Hello", 5, "c1", 1), "npc", PresentationPreferences.defaults());
    var last =
        presenter.compose(
            PresenterFixtures.view("Hello", 5, "leave", 2),
            "npc",
            PresentationPreferences.defaults());
    var up =
        first.scrollbar().buttons().stream()
            .filter(b -> b.direction() == WidgetFrame.NavigationDirection.PREVIOUS)
            .findFirst()
            .orElseThrow();
    var down =
        first.scrollbar().buttons().stream()
            .filter(b -> b.direction() == WidgetFrame.NavigationDirection.NEXT)
            .findFirst()
            .orElseThrow();
    var choice =
        first.widgets().stream()
            .filter(w -> w.kind() == WidgetFrame.Kind.CHOICE)
            .findFirst()
            .orElseThrow();
    double center = choice.bounds().x() + choice.bounds().width() / 2;
    for (var button : first.scrollbar().buttons()) {
      assertEquals(center, button.bounds().x() + button.bounds().width() / 2);
      assertTrue(button.bounds().width() >= 64, "Navigation should be easy to aim at");
      assertTrue(button.bounds().height() >= 24);
    }
    var hint =
        first.widgets().stream().filter(w -> w.id().equals("hint")).findFirst().orElseThrow();
    assertTrue(
        hint.bounds().y() + hint.bounds().height() <= up.bounds().y() - 6,
        "Instruction text must not overlap the upper page control");
    assertEquals(
        first.scrollbar().buttons().stream().map(WidgetFrame.NavigationButton::bounds).toList(),
        last.scrollbar().buttons().stream().map(WidgetFrame.NavigationButton::bounds).toList());
    assertFalse(up.enabled());
    assertEquals("c4", down.choiceId());
    assertTrue(up.bounds().y() + up.bounds().height() < first.scrollbar().track().y());
    assertTrue(
        down.bounds().y() > first.scrollbar().track().y() + first.scrollbar().track().height());
    assertTrue(
        last.scrollbar().buttons().stream()
            .filter(b -> b.direction() == WidgetFrame.NavigationDirection.NEXT)
            .noneMatch(WidgetFrame.NavigationButton::enabled));
    assertEquals(
        "c4",
        last.scrollbar().buttons().stream()
            .filter(b -> b.direction() == WidgetFrame.NavigationDirection.PREVIOUS)
            .findFirst()
            .orElseThrow()
            .choiceId());
  }
}
