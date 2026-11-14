package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.runtime.DialogueView;
import java.util.*;

/** Synchronous projection from already loaded host translations; all output is plain text. */
@FunctionalInterface
public interface UiLabelSource {
  record LabelRequest(
      String localeTag,
      DialogueView.HintPhase phase,
      DialoguePresenter.Controls controls,
      String notice,
      Map<String, String> unavailableReasons) {
    public LabelRequest {
      Objects.requireNonNull(localeTag);
      Objects.requireNonNull(phase);
      Objects.requireNonNull(controls);
      Objects.requireNonNull(notice);
      unavailableReasons = Map.copyOf(unavailableReasons);
    }
  }

  record UiLabels(String hint, String notice, Map<String, String> choiceReasons) {
    public UiLabels {
      Objects.requireNonNull(hint);
      Objects.requireNonNull(notice);
      choiceReasons = Map.copyOf(choiceReasons);
    }
  }

  UiLabels resolve(LabelRequest request);

  static UiLabelSource defaults() {
    return request -> {
      boolean de = Locale.forLanguageTag(request.localeTag()).getLanguage().equals("de");
      String action =
          switch (request.controls().confirm()) {
            case ATTACK -> de ? "Angreifen" : "Attack";
            case USE -> de ? "Benutzen" : "Use";
            case SWAP_HANDS -> de ? "Offhandwechsel" : "Swap hands";
          };
      String verb =
          switch (request.phase()) {
            case REVEAL -> de ? "Text anzeigen" : "Reveal text";
            case CHOOSE -> de ? "Auswählen" : "Select";
            case CONTINUE -> de ? "Weiter" : "Continue";
            case FINISH -> de ? "Beenden" : "End";
            case BUSY -> de ? "Bitte warten" : "Please wait";
          };
      String hint =
          (request.controls().scroll() && request.phase() == DialogueView.HintPhase.CHOOSE
                  ? (de ? "Scrollen · " : "Scroll · ")
                  : "")
              + action
              + " · "
              + verb;
      String unavailable = de ? "Nicht verfügbar" : "Unavailable";
      String notice =
          switch (request.notice()) {
            case "CHOICES_CHANGED" -> de ? "Antworten geändert" : "Choices changed";
            case "CHOICE_UNAVAILABLE" -> unavailable;
            default -> "";
          };
      var reasons = new LinkedHashMap<String, String>();
      request.unavailableReasons().keySet().forEach(id -> reasons.put(id, unavailable));
      return new UiLabels(hint, notice, reasons);
    };
  }
}
