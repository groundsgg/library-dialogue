package gg.grounds.dialogue.core.port;

import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery;
import java.util.*;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface OutcomeSink {
  record OutcomeEvent(
      UUID eventId,
      String milestone,
      Delivery delivery,
      String audienceId,
      long hostGeneration,
      UUID sessionId,
      long nodeVisitId,
      String payloadHash) {}

  enum OutcomeResult {
    ACKNOWLEDGED,
    UNAVAILABLE,
    UNCERTAIN
  }

  CompletionStage<OutcomeResult> deliver(OutcomeEvent event);
}
