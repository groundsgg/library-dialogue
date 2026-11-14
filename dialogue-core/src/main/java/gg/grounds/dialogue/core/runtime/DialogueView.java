package gg.grounds.dialogue.core.runtime;

import gg.grounds.dialogue.core.port.ActionHandler.ActionRef;
import gg.grounds.dialogue.core.validation.ContentPin;
import java.util.*;

public record DialogueView(
    UUID sessionId,
    ContentPin contentPin,
    String nodeId,
    long nodeVisitId,
    State state,
    long stateRevision,
    long renderRevision,
    String localeTag,
    String speaker,
    String text,
    List<ChoiceView> choices,
    String selectedChoiceId,
    int totalGraphemes,
    int revealedGraphemes,
    HintPhase hint,
    String notice,
    ActionRef pendingAction) {
  public DialogueView {
    choices = List.copyOf(choices);
  }

  public enum State {
    PREPARING,
    REVEALING,
    WAITING_FOR_INPUT,
    WAITING_FOR_PROVIDER,
    CLOSING,
    CLOSED
  }

  public enum HintPhase {
    REVEAL,
    CHOOSE,
    CONTINUE,
    FINISH,
    BUSY
  }

  public enum Availability {
    AVAILABLE,
    UNAVAILABLE
  }

  public record ChoiceView(
      String id, String label, Availability availability, String unavailableReason) {}
}
