package gg.grounds.dialogue.core.runtime;
import java.util.*;
import gg.grounds.dialogue.core.port.ActionHandler.ActionRef;
public record DialogueEnd(UUID sessionId,DialogueEndReason reason,List<ActionRef> pendingActions) {public DialogueEnd {pendingActions=List.copyOf(pendingActions);}}
