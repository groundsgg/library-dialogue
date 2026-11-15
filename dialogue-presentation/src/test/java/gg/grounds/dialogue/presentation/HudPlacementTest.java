package gg.grounds.dialogue.presentation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class HudPlacementTest {
  private static FontMetrics hudMetrics() {
    return new FontMetrics() {
      public double width(String g) {
        return 6;
      }

      public double lineHeight() {
        return 10;
      }

      public String pin() {
        return "fixture-hud";
      }
    };
  }

  @Test
  void prototypeHudUsesCompactRowsAndEmbeddedSpeaker() {
    for (var position : LayoutProfile.ChoicePlacement.values()) {
      var surface = new PresenterFixtures.Surface();
      var presenter =
          new DialoguePresenter(
              Map.of(
                  "hud",
                  new DialoguePresenter.SurfaceProfile(
                      surface,
                      LayoutProfile.compactHud(position),
                      DialogueTheme.neutral(),
                      hudMetrics(),
                      false)),
              new PresenterFixtures.Clock());
      var frame =
          presenter.compose(
              PresenterFixtures.view("Hello", 5, "c1", 1),
              "hud",
              PresentationPreferences.defaults());
      var speech =
          frame.widgets().stream()
              .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
              .findFirst()
              .orElseThrow();
      var answer =
          frame.widgets().stream()
              .filter(w -> w.kind() == WidgetFrame.Kind.CHOICE)
              .findFirst()
              .orElseThrow();
      assertEquals(10, answer.bounds().height());
      assertEquals(48, speech.bounds().height());
      assertNotNull(frame.chrome());
      var speaker =
          frame.widgets().stream()
              .filter(w -> w.kind() == WidgetFrame.Kind.SPEAKER)
              .findFirst()
              .orElseThrow();
      assertEquals(7, speaker.bounds().y());
      assertEquals(17, speech.textBounds().y());
      assertTrue(answer.text().plain().startsWith("> "));
      assertEquals(
          position == LayoutProfile.ChoicePlacement.BELOW ? 222 : 117, answer.bounds().width());
      assertTrue(answer.bounds().width() < speech.bounds().width());
      if (position == LayoutProfile.ChoicePlacement.BELOW) assertEquals(9, answer.bounds().x());
    }
  }

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
            hudMetrics(),
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
                        hudMetrics(),
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
      assertEquals(position.equals("below") ? 240 : 300, f.canvasBounds().width());
      for (var c : choices) {
        assertEquals(c.bounds(), c.hitRect());
        switch (position) {
          case "below" ->
              assertTrue(c.bounds().y() >= speech.bounds().y() + speech.bounds().height() + 2);
          case "left" -> assertTrue(c.bounds().x() + c.bounds().width() <= speech.bounds().x() - 3);
          case "right" ->
              assertTrue(c.bounds().x() >= speech.bounds().x() + speech.bounds().width() + 3);
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
