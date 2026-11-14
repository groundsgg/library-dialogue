package gg.grounds.dialogue.core.port;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import java.util.*;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ConditionProvider {
  record ConditionRequest(
      String key,
      Map<String, ArgumentValue> arguments,
      String audienceId,
      long hostGeneration,
      UUID sessionId,
      long nodeVisitId) {
    public ConditionRequest {
      arguments = Map.copyOf(arguments);
    }
  }

  enum ConditionResult {
    TRUE,
    FALSE,
    UNAVAILABLE
  }

  CompletionStage<ConditionResult> evaluate(ConditionRequest request);
}
