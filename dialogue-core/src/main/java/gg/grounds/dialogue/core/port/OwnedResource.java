package gg.grounds.dialogue.core.port;

import gg.grounds.dialogue.core.runtime.DialogueFailure;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface OwnedResource {
  CompletionStage<Void> closeAsync();

  /** Preparation failed after resources were acquired and cleanup could not be confirmed. */
  final class PreparationFailure extends DialogueFailure {
    public PreparationFailure(Throwable cause) {
      super("PREPARATION_CLEANUP_FAILED", cause);
    }
  }
}
