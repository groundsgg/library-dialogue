package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.text.TextResolver;
import java.util.*;

public record DialogueServices(
    String generation,
    Map<String, ConditionProvider> conditions,
    Map<String, ActionHandler> actions,
    OutcomeSink outcomes,
    TextResolver texts,
    Set<String> capabilities) {
  public DialogueServices {
    Objects.requireNonNull(generation);
    if (generation.isBlank()) throw new IllegalArgumentException("generation");
    conditions = Map.copyOf(conditions);
    actions = Map.copyOf(actions);
    Objects.requireNonNull(texts);
    capabilities = Set.copyOf(capabilities);
  }
}
