package gg.grounds.dialogue.presentation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UiLabelsTest {
  static DialogueView change(
      DialogueView old, String locale, String notice, List<DialogueView.ChoiceView> choices) {
    return new DialogueView(
        old.sessionId(),
        old.contentPin(),
        old.nodeId(),
        old.nodeVisitId(),
        old.state(),
        old.stateRevision() + 1,
        old.renderRevision() + 1,
        locale,
        old.speaker(),
        old.text(),
        choices,
        old.selectedChoiceId(),
        old.totalGraphemes(),
        old.revealedGraphemes(),
        old.hint(),
        notice,
        old.pendingAction());
  }

  static DialoguePresenter presenter(UiLabelSource labels) {
    return new DialoguePresenter(
        Map.of(
            "hud",
            new DialoguePresenter.SurfaceProfile(
                new PresenterFixtures.Surface(),
                LayoutProfile.hud(),
                DialogueTheme.neutral(),
                FontMetrics.defaults(),
                false)),
        new PresenterFixtures.Clock(),
        new DialoguePresenter.Controls(DialoguePresenter.ConfirmAction.USE, true),
        labels);
  }

  static String text(WidgetFrame frame, String id) {
    return frame.widgets().stream()
        .filter(w -> w.id().equals(id))
        .findFirst()
        .orElseThrow()
        .text()
        .plain();
  }

  @Test
  void liveLocaleRefreshUsesInjectedLabels() {
    var seen = new ArrayList<UiLabelSource.LabelRequest>();
    var p =
        presenter(
            request -> {
              seen.add(request);
              return new UiLabelSource.UiLabels(
                  request.localeTag().equals("fr") ? "Continuer" : "Continue", "", Map.of());
            });
    var view = PresenterFixtures.view("Hello", 5, "c1", 1);
    var a = p.compose(view, "hud", PresentationPreferences.defaults());
    var next = change(view, "fr", "", view.choices());
    var b = p.compose(next, "hud", PresentationPreferences.defaults());
    assertEquals("Continue", text(a, "hint"));
    assertEquals("Continuer", text(b, "hint"));
    assertEquals(view.nodeVisitId(), b.semantic().nodeVisitId());
    assertEquals(DialoguePresenter.ConfirmAction.USE, seen.getLast().controls().confirm());
  }

  @Test
  void changedOffersHaveSafeStatusText() {
    var p =
        presenter(
            request ->
                new UiLabelSource.UiLabels(
                    "Continue",
                    request.notice().equals("CHOICES_CHANGED") ? "Choices changed" : "",
                    Map.of()));
    var view = PresenterFixtures.view("Hello", 5, "c1", 1);
    var frame =
        p.compose(
            change(view, "en", "CHOICES_CHANGED", view.choices()),
            "hud",
            PresentationPreferences.defaults());
    assertEquals("Choices changed", text(frame, "status"));
    var hint =
        frame.widgets().stream().filter(w -> w.id().equals("hint")).findFirst().orElseThrow();
    var status =
        frame.widgets().stream().filter(w -> w.id().equals("status")).findFirst().orElseThrow();
    assertTrue(status.bounds().y() >= hint.bounds().y() + hint.bounds().height());
  }

  @Test
  void unavailableCardsExposeLocalizedReason() {
    var p =
        presenter(
            request -> {
              assertEquals("CHOICE_UNAVAILABLE", request.unavailableReasons().get("c1"));
              return new UiLabelSource.UiLabels("Continue", "", Map.of("c1", "Not available"));
            });
    var view = PresenterFixtures.view("Hello", 5, "c2", 1);
    var choices = new ArrayList<>(view.choices());
    choices.set(
        0,
        new DialogueView.ChoiceView(
            "c1", "Ask", DialogueView.Availability.UNAVAILABLE, "CHOICE_UNAVAILABLE"));
    var frame =
        p.compose(change(view, "en", "", choices), "hud", PresentationPreferences.defaults());
    assertEquals("Ask\nNot available", text(frame, "choice:c1"));
    assertFalse(
        frame.widgets().stream()
            .filter(w -> w.id().equals("choice:c1"))
            .findFirst()
            .orElseThrow()
            .enabled());
  }

  @Test
  void defaultsRetainExistingBehaviour() {
    var p =
        PresenterFixtures.presenter(
            new PresenterFixtures.Surface(), new PresenterFixtures.Surface());
    var view = PresenterFixtures.view("Hello", 5, "c1", 1);
    assertEquals(
        "Scroll · Swap hands · Select",
        text(p.compose(view, "hud", PresentationPreferences.defaults()), "hint"));
    assertFalse(
        p.compose(view, "hud", PresentationPreferences.defaults()).widgets().stream()
            .anyMatch(w -> w.id().equals("status")));
  }

  @Test
  void translatedStatusOverflowDiagnosesRatherThanClips() {
    var p =
        presenter(request -> new UiLabelSource.UiLabels("Continue", "A ".repeat(500), Map.of()));
    assertThrows(
        DialoguePresenter.FallbackRequired.class,
        () ->
            p.compose(
                PresenterFixtures.view("Hello", 5, "c1", 1),
                "hud",
                PresentationPreferences.defaults()));
  }
}
