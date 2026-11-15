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
}
