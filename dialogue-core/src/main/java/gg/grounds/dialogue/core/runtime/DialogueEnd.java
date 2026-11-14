package gg.grounds.dialogue.core.runtime;

import gg.grounds.dialogue.core.port.ActionHandler.ActionRef;
import java.util.*;

public record DialogueEnd(
    UUID sessionId, DialogueEndReason reason, List<ActionRef> pendingActions) {
  public DialogueEnd {
    pendingActions = List.copyOf(pendingActions);
  }
}
