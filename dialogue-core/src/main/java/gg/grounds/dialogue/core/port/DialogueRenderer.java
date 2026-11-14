package gg.grounds.dialogue.core.port;

import gg.grounds.dialogue.core.runtime.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface DialogueRenderer {
  /** A rejected reconfiguration has left the previous presentation intact. */
  class ReconfigurationRejected extends DialogueFailure {
    private final PresentationPreferences activePreferences;

    public ReconfigurationRejected(String code, Throwable cause) {
      this(code, cause, null);
    }

    public ReconfigurationRejected(
        String code, Throwable cause, PresentationPreferences activePreferences) {
      super(code, cause);
      this.activePreferences = activePreferences;
    }

    public PresentationPreferences activePreferences() {
      return activePreferences;
    }
  }

  record PresentationRequest(
      UUID sessionId,
      String audienceId,
      long hostGeneration,
      PresentationPreferences preferences,
      CancellationToken cancellation,
      long deadlineNanos,
      RuntimeOptions.Budgets budgets) {
    public PresentationRequest {
      Objects.requireNonNull(budgets);
    }

    public PresentationRequest(
        UUID sessionId,
        String audienceId,
        long hostGeneration,
        PresentationPreferences preferences,
        CancellationToken cancellation,
        long deadlineNanos) {
      this(
          sessionId,
          audienceId,
          hostGeneration,
          preferences,
          cancellation,
          deadlineNanos,
          new RuntimeOptions.Budgets(
              java.time.Duration.ofSeconds(2),
              java.time.Duration.ofSeconds(5),
              java.time.Duration.ofSeconds(10),
              java.time.Duration.ofSeconds(30)));
    }
  }

  interface PresentationHandle extends OwnedResource {
    CompletionStage<Void> replace(DialogueView view);

    CompletionStage<Void> reconfigure(PresentationPreferences preferences);
  }

  CompletionStage<PresentationHandle> prepare(PresentationRequest request);
}
