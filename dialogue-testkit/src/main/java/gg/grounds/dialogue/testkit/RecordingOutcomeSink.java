package gg.grounds.dialogue.testkit;

import gg.grounds.dialogue.core.port.OutcomeSink;
import java.util.*;
import java.util.concurrent.*;

/** Acknowledges and records events without applying gameplay side effects. */
public final class RecordingOutcomeSink implements OutcomeSink {
  private final List<OutcomeEvent> events = new ArrayList<>();

  public RecordingOutcomeSink() {}

  @Override
  public synchronized CompletionStage<OutcomeResult> deliver(OutcomeEvent event) {
    events.add(Objects.requireNonNull(event));
    return CompletableFuture.completedFuture(OutcomeResult.ACKNOWLEDGED);
  }

  public synchronized List<OutcomeEvent> events() {
    return List.copyOf(events);
  }
}
