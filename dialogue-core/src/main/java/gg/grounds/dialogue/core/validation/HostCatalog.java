package gg.grounds.dialogue.core.validation;

import java.util.*;

public record HostCatalog(
    String generation,
    Set<String> speakers,
    Map<String, ProviderSpec> conditions,
    Map<String, ProviderSpec> actions,
    Set<String> gestures,
    Set<String> capabilities,
    boolean durableOutcomes) {
  public HostCatalog {
    Objects.requireNonNull(generation);
    speakers = Set.copyOf(speakers);
    conditions = Map.copyOf(conditions);
    actions = Map.copyOf(actions);
    gestures = Set.copyOf(gestures);
    capabilities = Set.copyOf(capabilities);
  }

  public enum Kind {
    TEXT,
    NUMBER,
    BOOLEAN,
    NULL
  }

  public record ArgumentSpec(Kind kind, boolean required, boolean nullable) {
    public ArgumentSpec {
      Objects.requireNonNull(kind);
    }
  }

  public record ProviderSpec(Map<String, ArgumentSpec> arguments, boolean durable) {
    public ProviderSpec {
      arguments = Map.copyOf(arguments);
    }
  }
}
