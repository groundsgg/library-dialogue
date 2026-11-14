package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.port.OwnedResource;
import gg.grounds.dialogue.core.runtime.CancellationToken;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** An open surface is hidden until its first frame. Close prevents all later mutations. */
@FunctionalInterface
public interface DialogueSurface {
  record SurfaceContext(
      UUID sessionId,
      String audienceId,
      long hostGeneration,
      String surfaceKey,
      String profilePin,
      CancellationToken cancellation,
      long generation,
      long deadlineNanos,
      gg.grounds.dialogue.core.runtime.PresentationPreferences preferences) {
    public SurfaceContext(
        UUID sessionId,
        String audienceId,
        long hostGeneration,
        String surfaceKey,
        String profilePin,
        CancellationToken cancellation,
        long generation,
        long deadlineNanos) {
      this(
          sessionId,
          audienceId,
          hostGeneration,
          surfaceKey,
          profilePin,
          cancellation,
          generation,
          deadlineNanos,
          gg.grounds.dialogue.core.runtime.PresentationPreferences.defaults());
    }
  }

  interface SurfaceHandle extends OwnedResource {
    CompletionStage<Void> replace(WidgetFrame frame);

    default CompletionStage<Void> configure(
        gg.grounds.dialogue.core.runtime.PresentationPreferences preferences) {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
  }

  CompletionStage<SurfaceHandle> open(SurfaceContext context);
}
