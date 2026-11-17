package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.port.OwnedResource;
import gg.grounds.dialogue.core.runtime.CancellationToken;
import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

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
      PresentationPreferences preferences,
      Duration cleanupBudget) {
    public SurfaceContext {
      Objects.requireNonNull(preferences);
      Objects.requireNonNull(cleanupBudget);
      if (cleanupBudget.isNegative() || cleanupBudget.isZero())
        throw new IllegalArgumentException("cleanupBudget");
    }

    public SurfaceContext(
        UUID sessionId,
        String audienceId,
        long hostGeneration,
        String surfaceKey,
        String profilePin,
        CancellationToken cancellation,
        long generation,
        long deadlineNanos,
        PresentationPreferences preferences) {
      this(
          sessionId,
          audienceId,
          hostGeneration,
          surfaceKey,
          profilePin,
          cancellation,
          generation,
          deadlineNanos,
          preferences,
          Duration.ofSeconds(30));
    }

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
          PresentationPreferences.defaults());
    }
  }

  interface SurfaceHandle extends OwnedResource {
    CompletionStage<Void> replace(WidgetFrame frame);

    default CompletionStage<Void> configure(PresentationPreferences preferences) {
      return CompletableFuture.completedFuture(null);
    }
  }

  CompletionStage<SurfaceHandle> open(SurfaceContext context);
}
