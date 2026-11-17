package gg.grounds.dialogue.core.port;

import gg.grounds.dialogue.core.runtime.CancellationToken;
import java.util.*;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ActorPort {
  record ActorRequest(
      UUID sessionId,
      String audienceId,
      long hostGeneration,
      Set<String> speakerIds,
      CancellationToken cancellation,
      long deadlineNanos) {
    public ActorRequest {
      speakerIds = Set.copyOf(speakerIds);
    }
  }

  record ActorCue(String speaker, String gesture, long nodeVisitId, String cueId) {}

  interface ActorHandle extends OwnedResource {
    /** Publish the prepared personal actor once before any node effects or cues. */
    default CompletionStage<Void> activate() {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    CompletionStage<Void> cue(ActorCue cue);
  }

  CompletionStage<ActorHandle> prepare(ActorRequest request);
}
