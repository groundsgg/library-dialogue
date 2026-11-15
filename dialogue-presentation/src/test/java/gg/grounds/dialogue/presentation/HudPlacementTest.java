package gg.grounds.dialogue.presentation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class HudPlacementTest {
  @Test
  void preparationPinIdentifiesTheResolvedLayout() {
    var pins = new ArrayList<String>();
    DialogueSurface surface =
        context -> {
          pins.add(context.profilePin());
          return java.util.concurrent.CompletableFuture.completedFuture(
              new PresenterFixtures.Surface().handle());
        };
    var profile =
        new DialoguePresenter.SurfaceProfile(
            surface,
            LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.BELOW),
            DialogueTheme.neutral(),
            FontMetrics.defaults(),
            false,
            p ->
                LayoutProfile.compactHud(
                    LayoutProfile.ChoicePlacement.valueOf(
                        p.effects().get("hudChoices").toUpperCase(Locale.ROOT))));
    var presenter = new DialoguePresenter(Map.of("hud", profile), new PresenterFixtures.Clock());
    for (String position : List.of("left", "right")) {
      var request =
          new gg.grounds.dialogue.core.port.DialogueRenderer.PresentationRequest(
              PresenterFixtures.SESSION,
              "player",
              1,
              new PresentationPreferences(
                  "hud", Duration.ZERO, false, Map.of("hudChoices", position)),
              new gg.grounds.dialogue.core.runtime.CancellationToken(),
              5_000_000_000L);
      presenter
          .prepare(request)
          .toCompletableFuture()
          .join()
          .closeAsync()
          .toCompletableFuture()
          .join();
    }
    assertEquals(2, pins.size());
    assertNotEquals(pins.get(0), pins.get(1));
  }

  @Test
  void compactChoicesCanSitBelowLeftOrRightWithoutChangingNpcLayout() {
    var hud = new PresenterFixtures.Surface();
    var npc = new PresenterFixtures.Surface();
    var presenter =
        new DialoguePresenter(
            Map.of(
                "hud",
                    new DialoguePresenter.SurfaceProfile(
                        hud,
                        LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.BELOW),
                        DialogueTheme.neutral(),
                        FontMetrics.defaults(),
                        false,
                        p ->
                            LayoutProfile.compactHud(
                                LayoutProfile.ChoicePlacement.valueOf(
                                    p.effects()
                                        .getOrDefault("hudChoices", "below")
                                        .toUpperCase(Locale.ROOT)))),
                "npc",
                    new DialoguePresenter.SurfaceProfile(
                        npc,
                        LayoutProfile.npc(),
                        DialogueTheme.neutral(),
                        FontMetrics.defaults(),
                        false)),
            new PresenterFixtures.Clock());
    var view = PresenterFixtures.view("Hello", 5, "c4", 2);
    for (var position : List.of("below", "left", "right")) {
      var prefs =
          new PresentationPreferences("hud", Duration.ZERO, false, Map.of("hudChoices", position));
      var f = presenter.compose(view, "hud", prefs);
      var speech =
          f.widgets().stream()
              .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
              .findFirst()
              .orElseThrow();
      var choices = f.widgets().stream().filter(w -> w.kind() == WidgetFrame.Kind.CHOICE).toList();
      assertEquals(
          List.of("c4", "c5", "c6"), choices.stream().map(WidgetFrame.Widget::choiceId).toList());
      assertEquals(3, choices.size());
      assertTrue(speech.bounds().width() < 320);
      assertEquals(position.equals("below") ? 240 : 308, f.canvasBounds().width());
      for (var c : choices) {
        assertEquals(c.bounds(), c.hitRect());
        switch (position) {
          case "below" ->
              assertTrue(c.bounds().y() >= speech.bounds().y() + speech.bounds().height() + 4);
          case "left" -> assertTrue(c.bounds().x() + c.bounds().width() <= speech.bounds().x() - 4);
          case "right" ->
              assertTrue(c.bounds().x() >= speech.bounds().x() + speech.bounds().width() + 4);
        }
      }
      assertTrue(choices.get(1).bounds().y() > choices.get(0).bounds().y());
      var n = presenter.compose(view, "npc", prefs);
      var body =
          n.widgets().stream()
              .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
              .findFirst()
              .orElseThrow();
      assertEquals(320, body.bounds().width());
    }
  }
}
