package gg.grounds.dialogue.core.port;

import gg.grounds.dialogue.core.text.TextResolver;
import java.util.*;

public record HostBindings(
    String generation,
    Map<String, ConditionProvider> conditions,
    Map<String, ActionHandler> actions,
    OutcomeSink outcomes,
    TextResolver texts,
    DialogueRenderer renderer,
    ActorPort actors,
    Set<String> capabilities) {
  public HostBindings {
    Objects.requireNonNull(generation);
    conditions = Map.copyOf(conditions);
    actions = Map.copyOf(actions);
    Objects.requireNonNull(texts);
    Objects.requireNonNull(renderer);
    capabilities = Set.copyOf(capabilities);
  }
}
