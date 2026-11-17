package example;

import gg.grounds.dialogue.core.port.ActorPort;
import gg.grounds.dialogue.core.runtime.DialogueEndReason;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.TaskSchedule;

/** Explicit demo probe: a real prepared actor is delivered late after a real interruption. */
final class DemoPreparationProbe implements ActorPort {
  private final ActorPort delegate;
  private final Supplier<CompletionStage<Void>> interrupt;
  private final Consumer<String> progress;
  private final String mode;
  final CompletableFuture<Void> prepared = new CompletableFuture<>();
  final CompletableFuture<Void> closed = new CompletableFuture<>();
  final CompletableFuture<Void> interrupted = new CompletableFuture<>();
  private final AtomicInteger activations = new AtomicInteger(), cues = new AtomicInteger();
  private volatile DialogueEndReason reasonAtHandoff;
  private volatile String phase = "PREPARING";
  private volatile boolean handed;

  DemoPreparationProbe(
      String mode,
      ActorPort delegate,
      Supplier<CompletionStage<Void>> interrupt,
      Consumer<String> progress) {
    this.mode = mode;
    this.delegate = delegate;
    this.interrupt = interrupt;
    this.progress = progress;
  }

  public CompletionStage<ActorHandle> prepare(ActorRequest request) {
    return delegate
        .prepare(request)
        .thenCompose(
            actor -> {
              var late = new CompletableFuture<ActorHandle>();
              phase = "HOLDING_PREPARED_ACTOR";
              var observed =
                  new ActorHandle() {
                    public CompletionStage<Void> activate() {
                      activations.incrementAndGet();
                      return actor.activate();
                    }

                    public CompletionStage<Void> cue(ActorCue cue) {
                      cues.incrementAndGet();
                      return actor.cue(cue);
                    }

                    public CompletionStage<Void> closeAsync() {
                      try {
                        var release = actor.closeAsync();
                        release.whenComplete(
                            (v, error) -> {
                              if (error == null) closed.complete(null);
                              else closed.completeExceptionally(error);
                            });
                        return release;
                      } catch (Throwable error) {
                        closed.completeExceptionally(error);
                        return CompletableFuture.failedFuture(error);
                      }
                    }
                  };
              MinecraftServer.getSchedulerManager()
                  .buildTask(
                      () -> {
                        reasonAtHandoff = request.cancellation().reason();
                        handed = true;
                        phase = "LATE_HANDOFF";
                        late.complete(observed);
                      })
                  .delay(TaskSchedule.duration(Duration.ofSeconds(3)))
                  .schedule();
              MinecraftServer.getSchedulerManager()
                  .buildTask(
                      () -> {
                        phase = "INTERRUPTING";
                        try {
                          interrupt
                              .get()
                              .whenComplete(
                                  (v, error) -> {
                                    if (error == null) interrupted.complete(null);
                                    else interrupted.completeExceptionally(error);
                                  });
                        } catch (Throwable error) {
                          interrupted.completeExceptionally(error);
                        }
                      })
                  .delay(TaskSchedule.duration(Duration.ofSeconds(1)))
                  .schedule();
              prepared.complete(null);
              progress.accept(
                  "Actor prepared; interrupt in 1s, delayed handoff in 3s (" + mode + ").");
              return late.minimalCompletionStage();
            });
  }

  boolean validHandoff() {
    boolean expected =
        switch (mode) {
          case "cancel" -> reasonAtHandoff == DialogueEndReason.CANCELLED;
          case "instance" -> reasonAtHandoff == DialogueEndReason.HOST_CHANGED;
          case "disconnect" ->
              reasonAtHandoff == DialogueEndReason.DISCONNECTED
                  || reasonAtHandoff == DialogueEndReason.HOST_CHANGED;
          default -> false;
        };
    return handed && expected && activations.get() == 0 && cues.get() == 0;
  }

  boolean passed() {
    return validHandoff()
        && closed.isDone()
        && !closed.isCompletedExceptionally()
        && interrupted.isDone()
        && !interrupted.isCompletedExceptionally();
  }

  String status() {
    return "mode="
        + mode
        + "; phase="
        + phase
        + "; reason="
        + reasonAtHandoff
        + "; activated="
        + activations.get()
        + "; cues="
        + cues.get()
        + "; closed="
        + (closed.isDone() && !closed.isCompletedExceptionally())
        + "; PASS="
        + passed();
  }
}
