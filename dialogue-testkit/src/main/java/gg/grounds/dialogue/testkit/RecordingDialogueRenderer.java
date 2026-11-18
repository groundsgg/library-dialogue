package gg.grounds.dialogue.testkit;

import gg.grounds.dialogue.core.port.DialogueRenderer;
import gg.grounds.dialogue.core.runtime.*;
import java.util.*;
import java.util.concurrent.*;

/** Thread-safe observation of the public core renderer port. */
public final class RecordingDialogueRenderer implements DialogueRenderer {
  public record PresentationSnapshot(
      UUID sessionId,
      String audienceId,
      long hostGeneration,
      List<DialogueView> views,
      List<PresentationPreferences> configurations,
      boolean closed,
      int closeCalls,
      int rejectedWrites) {
    public PresentationSnapshot {
      views = List.copyOf(views);
      configurations = List.copyOf(configurations);
    }
  }

  private final List<Entry> entries = new ArrayList<>();

  private record Entry(
      UUID sessionId, String audienceId, long hostGeneration, RecordingState<DialogueView> state) {}

  public RecordingDialogueRenderer() {}

  @Override
  public synchronized CompletionStage<PresentationHandle> prepare(PresentationRequest request) {
    Objects.requireNonNull(request);
    var state = new RecordingState<DialogueView>();
    state.configure(request.preferences());
    entries.add(
        new Entry(request.sessionId(), request.audienceId(), request.hostGeneration(), state));
    return CompletableFuture.completedFuture(
        new PresentationHandle() {
          public CompletionStage<Void> replace(DialogueView view) {
            synchronized (RecordingDialogueRenderer.this) {
              return state.write(view);
            }
          }

          public CompletionStage<Void> reconfigure(PresentationPreferences p) {
            synchronized (RecordingDialogueRenderer.this) {
              return state.configure(p);
            }
          }

          public CompletionStage<Void> closeAsync() {
            synchronized (RecordingDialogueRenderer.this) {
              return state.close();
            }
          }
        });
  }

  public synchronized List<PresentationSnapshot> snapshots() {
    return entries.stream()
        .map(
            e ->
                new PresentationSnapshot(
                    e.sessionId,
                    e.audienceId,
                    e.hostGeneration,
                    e.state.frames,
                    e.state.configurations,
                    e.state.closed,
                    e.state.closeCalls,
                    e.state.rejectedWrites))
        .toList();
  }
}
