package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.port.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DialoguePreferencesTest {
  @Test
  void completedPreferenceUpdateAlreadyPublishesNewSnapshot() {
    var r = new TestRig();
    var h = r.start();
    long before = h.snapshot().renderRevision();
    var observed =
        h.updatePreferences(
                new PresentationPreferences("npc", Duration.ofMillis(35), false, Map.of()))
            .thenApply(v -> h.snapshot().renderRevision());
    r.scheduler.drain();
    assertTrue(TestRig.done(observed) > before);
  }

  @Test
  void unknownTimedOutHandoffStopsInsteadOfRestoringAnUncertainSurface() {
    var r = new TestRig();
    DialogueRenderer renderer =
        req ->
            CompletableFuture.completedFuture(
                new DialogueRenderer.PresentationHandle() {
                  public CompletionStage<Void> replace(DialogueView v) {
                    return CompletableFuture.completedFuture(null);
                  }

                  public CompletionStage<Void> reconfigure(PresentationPreferences p) {
                    return new CompletableFuture<>();
                  }

                  public CompletionStage<Void> closeAsync() {
                    return CompletableFuture.completedFuture(null);
                  }
                });
    r.bindings =
        new HostBindings(
            "slow", Map.of(), Map.of(), null, r.bindings.texts(), renderer, null, Set.of());
    var h = r.start();
    var f =
        h.updatePreferences(
            new PresentationPreferences("npc", Duration.ofMillis(35), false, Map.of()));
    r.scheduler.drain();
    r.scheduler.advance(Duration.ofSeconds(5));
    assertTrue(f.toCompletableFuture().isCompletedExceptionally());
    assertEquals(DialogueView.State.CLOSED, h.snapshot().state());
    assertEquals(DialogueEndReason.FAILED, TestRig.done(h.completion()).reason());
  }
}
