package gg.grounds.dialogue.testkit;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LatePreparationTest {
  @Test
  void lateActorPreparationIsClosedWithoutPublishingAfterCancellation() {
    var f = new RuntimeFixture();
    var r = new RecordingDialogueRenderer();
    var pending = new CompletableFuture<ActorPort.ActorHandle>();
    int[] effects = {0, 0};
    var binding =
        new HostBindings(
            "v1",
            Map.of(),
            Map.of(),
            null,
            TextResolver.catalogue(f.content.texts(), "en"),
            r,
            req -> pending,
            Set.of());
    var token = new CancellationToken();
    var started =
        f.runtime.start(
            new DialogueStart(
                "late",
                "a",
                1,
                f.content,
                binding,
                Map.of(),
                new LocaleContext("en", 0, "en"),
                PresentationPreferences.defaults(),
                token,
                0));
    f.clock.runUntilIdle();
    token.cancel(DialogueEndReason.CANCELLED);
    f.clock.runUntilIdle();
    pending.complete(
        new ActorPort.ActorHandle() {
          public CompletionStage<Void> activate() {
            effects[0]++;
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> cue(ActorPort.ActorCue c) {
            effects[0]++;
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> closeAsync() {
            effects[1]++;
            return CompletableFuture.completedFuture(null);
          }
        });
    f.clock.runUntilIdle();
    assertEquals(0, effects[0]);
    assertEquals(1, effects[1]);
    for (var snapshot : r.snapshots()) {
      assertTrue(snapshot.closed());
      assertTrue(snapshot.views().isEmpty());
    }
    assertTrue(started.toCompletableFuture().isDone());
  }
}
