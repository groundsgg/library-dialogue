package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.LocaleContext;
import gg.grounds.dialogue.minestom.render.PresentationStatus;
import java.util.concurrent.*;

/** Pending starts can be cancelled without waiting for a public runtime handle. */
public interface MinestomDialogueStart {
  CompletionStage<DialogueStartResult> result();

  CompletionStage<Void> cancel(DialogueEndReason reason);

  PresentationStatus status();

  /** Retains the latest intent while preparation is still pending. */
  default CompletionStage<Void> updatePreferences(PresentationPreferences preferences) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("Pending preferences unsupported"));
  }

  default CompletionStage<RefreshResult> refreshLocale(LocaleContext locale) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("Pending locale unsupported"));
  }
}
