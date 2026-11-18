package gg.grounds.dialogue.testkit;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import java.util.*;
import java.util.concurrent.*;

/** Access under the owning recorder monitor. */
final class RecordingState<T> {
  final List<T> frames = new ArrayList<>();
  final List<PresentationPreferences> configurations = new ArrayList<>();
  final CompletionStage<Void> cleanup =
      CompletableFuture.<Void>completedFuture(null).minimalCompletionStage();
  boolean closed;
  int closeCalls, rejectedWrites;

  CompletionStage<Void> write(T frame) {
    if (closed) return rejected();
    frames.add(Objects.requireNonNull(frame));
    return CompletableFuture.completedFuture(null);
  }

  CompletionStage<Void> configure(PresentationPreferences p) {
    if (closed) return rejected();
    configurations.add(Objects.requireNonNull(p));
    return CompletableFuture.completedFuture(null);
  }

  CompletionStage<Void> rejected() {
    rejectedWrites++;
    return CompletableFuture.failedFuture(new IllegalStateException("Recording handle is closed"));
  }

  CompletionStage<Void> close() {
    closeCalls++;
    closed = true;
    return cleanup;
  }
}
