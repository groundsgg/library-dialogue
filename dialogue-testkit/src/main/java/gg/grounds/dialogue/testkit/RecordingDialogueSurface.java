package gg.grounds.dialogue.testkit;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import gg.grounds.dialogue.presentation.*;
import java.util.*;
import java.util.concurrent.*;

/** Thread-safe observation of the shared presentation surface port. */
public final class RecordingDialogueSurface implements DialogueSurface {
  public record SurfaceSnapshot(
      UUID sessionId,
      String audienceId,
      long hostGeneration,
      String surfaceKey,
      long generation,
      List<WidgetFrame> frames,
      List<PresentationPreferences> configurations,
      boolean closed,
      int closeCalls,
      int rejectedWrites) {
    public SurfaceSnapshot {
      frames = List.copyOf(frames);
      configurations = List.copyOf(configurations);
    }
  }

  private final List<Entry> entries = new ArrayList<>();

  private record Entry(
      UUID sessionId,
      String audienceId,
      long hostGeneration,
      String surfaceKey,
      long generation,
      RecordingState<WidgetFrame> state) {}

  public RecordingDialogueSurface() {}

  @Override
  public synchronized CompletionStage<SurfaceHandle> open(SurfaceContext context) {
    Objects.requireNonNull(context);
    var state = new RecordingState<WidgetFrame>();
    state.configure(context.preferences());
    entries.add(
        new Entry(
            context.sessionId(),
            context.audienceId(),
            context.hostGeneration(),
            context.surfaceKey(),
            context.generation(),
            state));
    return CompletableFuture.completedFuture(
        new SurfaceHandle() {
          public CompletionStage<Void> replace(WidgetFrame frame) {
            synchronized (RecordingDialogueSurface.this) {
              return state.write(frame);
            }
          }

          public CompletionStage<Void> configure(PresentationPreferences p) {
            synchronized (RecordingDialogueSurface.this) {
              return state.configure(p);
            }
          }

          public CompletionStage<Void> closeAsync() {
            synchronized (RecordingDialogueSurface.this) {
              return state.close();
            }
          }
        });
  }

  public synchronized List<SurfaceSnapshot> snapshots() {
    return entries.stream()
        .map(
            e ->
                new SurfaceSnapshot(
                    e.sessionId,
                    e.audienceId,
                    e.hostGeneration,
                    e.surfaceKey,
                    e.generation,
                    e.state.frames,
                    e.state.configurations,
                    e.state.closed,
                    e.state.closeCalls,
                    e.state.rejectedWrites))
        .toList();
  }
}
