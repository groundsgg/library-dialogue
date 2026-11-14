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

  /** Shared validation includes declarations that no current dialogue references. */
  public java.util.List<ValidationReport.Problem> structuralProblems(ValidationLimits limits) {
    var problems = new java.util.ArrayList<ValidationReport.Problem>();
    java.util.function.BiConsumer<String, String> id =
        (value, path) -> {
          if (value.isBlank()
              || value.codePointCount(0, value.length()) > limits.idCodepoints()
              || !value.matches("[a-zA-Z0-9_.:/-]+"))
            problems.add(
                new ValidationReport.Problem(
                    "ID_INVALID",
                    ValidationReport.Severity.ERROR,
                    "context/host-catalog.json",
                    path,
                    null,
                    "Invalid declaration identifier"));
        };
    if (generation.isBlank()
        || generation.codePointCount(0, generation.length()) > limits.idCodepoints()
        || !DialogueValidator.validUnicode(generation))
      problems.add(
          new ValidationReport.Problem(
              "HOST_GENERATION_INVALID",
              ValidationReport.Severity.ERROR,
              "context/host-catalog.json",
              "/generation",
              null,
              "Invalid host generation"));
    speakers.forEach(v -> id.accept(v, "/speakers"));
    gestures.forEach(v -> id.accept(v, "/gestures"));
    capabilities.forEach(v -> id.accept(v, "/capabilities"));
    java.util.Map.of("conditions", conditions, "actions", actions)
        .forEach(
            (group, providers) ->
                providers.forEach(
                    (key, spec) -> {
                      id.accept(key, "/" + group);
                      spec.arguments()
                          .keySet()
                          .forEach(v -> id.accept(v, "/" + group + "/" + key + "/arguments"));
                    }));
    return java.util.List.copyOf(problems);
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
