package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.core.internal.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.DialogueInputSource;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minestom.server.entity.Player;

/** One presenter, one core session; fallback is an output handoff, never a dialogue restart. */
public final class MinestomDialogueRenderer implements DialogueRenderer, DialogueInputSource {
  private static final Set<String> KNOWN =
      Set.of(
          "MISSING_PACK",
          "MISSING_GLYPH",
          "LAYOUT_OVERFLOW",
          "FONT_PIN_MISMATCH",
          "UNSUPPORTED_RASTER",
          "OUTPUT_SUPPRESSED");
  private final Map<String, DialoguePresenter.SurfaceProfile> profiles;
  private final DialogueScheduler clock;
  private final DialoguePresenter.Controls controls;
  private final UiLabelSource labels;
  private final SurfaceInputTargets targets;
  private final Consumer<PresentationStatus> status;
  private final ChatInputTokens tokens;
  private final Supplier<NpcDialogueSurface.Geometry> geometry;
  private volatile Run active;

  public MinestomDialogueRenderer(
      Map<String, DialoguePresenter.SurfaceProfile> profiles,
      DialogueScheduler clock,
      DialoguePresenter.Controls controls,
      UiLabelSource labels,
      SurfaceInputTargets targets,
      Consumer<PresentationStatus> status) {
    this(profiles, clock, controls, labels, targets, status, null, () -> null);
  }

  public MinestomDialogueRenderer(
      Map<String, DialoguePresenter.SurfaceProfile> profiles,
      DialogueScheduler clock,
      DialoguePresenter.Controls controls,
      UiLabelSource labels,
      SurfaceInputTargets targets,
      Consumer<PresentationStatus> status,
      ChatInputTokens tokens,
      Supplier<NpcDialogueSurface.Geometry> geometry) {
    this.tokens = tokens;
    this.geometry = geometry;
    this.profiles = Map.copyOf(profiles);
    this.clock = clock;
    this.controls = controls;
    this.labels = labels;
    this.targets = targets;
    this.status = status;
    if (!profiles.containsKey("chat")) throw new IllegalArgumentException("Chat fallback required");
  }

  public TargetSnapshot targets() {
    return targets.current();
  }

  public NpcDialogueSurface.Geometry npcGeometry() {
    return geometry.get();
  }

  public void refreshOutput() {
    var run = active;
    if (run != null)
      run.queue.execute(
          () -> {
            if (!run.closed.get() && run.latest != null) run.replace(run.latest);
          });
  }

  public Optional<TargetSnapshot.Target> resolveChat(Player player, long generation, String token) {
    return tokens == null ? Optional.empty() : tokens.resolve(player, generation, token);
  }

  private static String known(Throwable error) {
    for (Throwable e = error; e != null; e = e.getCause())
      if (e instanceof OwnedResource.PreparationFailure
          || e instanceof DialogueFailure f
              && (f.code().contains("CLEANUP") || f.code().contains("TIMEOUT"))) return null;
    for (Throwable e = error; e != null; e = e.getCause())
      if (e instanceof DialogueFailure f && KNOWN.contains(f.code())) return f.code();
    return null;
  }

  public CompletionStage<PresentationHandle> prepare(PresentationRequest request) {
    var run = new Run(request);
    active = run;
    run.begin();
    return run.ready.minimalCompletionStage();
  }

  private final class Run implements PresentationHandle {
    final PresentationRequest request;
    final SerialCommandQueue queue;
    final OwnedScope scope;
    final DialoguePresenter presenter;
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicLong surfaces = new AtomicLong();
    final CompletableFuture<PresentationHandle> ready = new CompletableFuture<>();
    final List<CompletableFuture<Void>> waiters = new ArrayList<>();
    final List<PendingOperation<?>> waits = new ArrayList<>();
    CompletionStage<Void> closing;
    PresentationHandle delegate;
    PresentationPreferences requested, effective;
    DialogueView latest;
    String diagnostic = "PREPARING";
    boolean changing, inflight, firstAck;
    long intent, writeEpoch;
    Throwable failure;
    CompletableFuture<Void> batch;
    long batchDeadline;

    Run(PresentationRequest r) {
      request = r;
      requested = effective = r.preferences();
      queue = new SerialCommandQueue(clock, this::fatal);
      scope = new OwnedScope(queue, clock, r.budgets().cleanup());
      var wrapped = new HashMap<String, DialoguePresenter.SurfaceProfile>();
      profiles.forEach(
          (key, p) ->
              wrapped.put(
                  key,
                  new DialoguePresenter.SurfaceProfile(
                      c -> {
                        long generation = surfaces.incrementAndGet();
                        invalidate();
                        return p.surface()
                            .open(
                                new DialogueSurface.SurfaceContext(
                                    c.sessionId(),
                                    c.audienceId(),
                                    c.hostGeneration(),
                                    c.surfaceKey(),
                                    c.profilePin(),
                                    c.cancellation(),
                                    generation,
                                    c.deadlineNanos(),
                                    c.preferences()));
                      },
                      p.layout(),
                      p.theme(),
                      p.metrics(),
                      p.semanticOnly())));
      presenter = new DialoguePresenter(wrapped, clock, controls, labels);
    }

    void invalidate() {
      var current = targets.current();
      if (current != null) targets.invalidateCurrent(current.surfaceGeneration());
      if (tokens != null) tokens.clear();
    }

    void report() {
      status.accept(
          new PresentationStatus(
              requested.surfaceKey(),
              firstAck ? effective.surfaceKey() : "",
              diagnostic,
              surfaces.get()));
    }

    PresentationPreferences chat() {
      return new PresentationPreferences(
          "chat", effective.graphemeDelay(), effective.reducedMotion(), effective.effects());
    }

    void begin() {
      queue.execute(
          () -> {
            scope.prepare(
                () ->
                    CompletableFuture.completedFuture(
                        request.cancellation().onCancel(reason -> closeAsync())));
            prepareAttempt(effective, false);
          });
    }

    void prepareAttempt(PresentationPreferences preferences, boolean fallback) {
      if (closed.get()) return;
      var work =
          scope.prepare(
              () ->
                  presenter.prepare(
                      new PresentationRequest(
                          request.sessionId(),
                          request.audienceId(),
                          request.hostGeneration(),
                          preferences,
                          request.cancellation(),
                          request.deadlineNanos(),
                          request.budgets())));
      await(
          work,
          request.deadlineNanos(),
          (handle, error) -> {
            if (closed.get()) return;
            if (error != null) {
              var code = known(error);
              if (!fallback && !preferences.surfaceKey().equals("chat") && code != null) {
                effective = chat();
                diagnostic = code;
                prepareAttempt(effective, true);
              } else failPreparation(error);
            } else {
              delegate = handle;
              effective = preferences;
              ready.complete(this);
              report();
            }
          });
    }

    <T> void await(CompletionStage<T> stage, long deadline, BiConsumer<T, Throwable> callback) {
      waits.removeIf(PendingOperation::isDone);
      waits.add(
          new PendingOperation<>(
              queue,
              clock,
              Duration.ofNanos(Math.max(1, deadline - clock.nanoTime())),
              stage,
              callback));
    }

    void failPreparation(Throwable error) {
      failure = error;
      closeAsync()
          .whenComplete(
              (v, e) ->
                  ready.completeExceptionally(
                      e == null ? error : new OwnedResource.PreparationFailure(e)));
    }

    public CompletionStage<Void> replace(DialogueView view) {
      var result = new CompletableFuture<Void>();
      queue.execute(
          () -> {
            if (closed.get()) {
              result.completeExceptionally(new CancellationException());
              return;
            }
            if (latest == null || view.stateRevision() >= latest.stateRevision()) latest = view;
            waiters.add(result);
            flush();
          });
      return result.minimalCompletionStage();
    }

    void flush() {
      if (closed.get() || changing || inflight || waiters.isEmpty() || delegate == null) return;
      if (batch == null) {
        batch = new CompletableFuture<>();
        batchDeadline =
            firstAck
                ? clock.nanoTime() + request.budgets().prepare().toNanos()
                : request.deadlineNanos();
        var currentBatch = batch;
        await(
            currentBatch,
            batchDeadline,
            (v, error) -> {
              if (error != null && batch == currentBatch) fatal(error);
            });
      }
      inflight = true;
      long epoch = ++writeEpoch;
      var frame = latest;
      call(() -> delegate.replace(frame))
          .whenComplete(
              (v, error) ->
                  queue.execute(
                      () -> {
                        if (closed.get() || epoch != writeEpoch) return;
                        inflight = false;
                        if (error != null) {
                          var code = known(error);
                          if (code != null && !effective.surfaceKey().equals("chat")) {
                            diagnostic = code;
                            switchOutput(chat(), false, null, batchDeadline);
                            return;
                          }
                          fatal(error);
                          return;
                        }
                        if (frame != latest) {
                          flush();
                          return;
                        }
                        firstAck = true;
                        if (diagnostic.equals("PREPARING")) diagnostic = "";
                        report();
                        var completed = List.copyOf(waiters);
                        waiters.clear();
                        var current = batch;
                        batch = null;
                        if (current != null) current.complete(null);
                        completed.forEach(f -> f.complete(null));
                      }));
    }

    public CompletionStage<Void> reconfigure(PresentationPreferences preferences) {
      Objects.requireNonNull(preferences);
      var result = new CompletableFuture<Void>();
      queue.execute(
          () -> {
            if (closed.get()) {
              result.completeExceptionally(new CancellationException());
              return;
            }
            requested = preferences;
            diagnostic = "";
            switchOutput(
                preferences,
                true,
                result,
                clock.nanoTime() + request.budgets().prepare().toNanos());
          });
      return result.minimalCompletionStage();
    }

    void switchOutput(
        PresentationPreferences preferences,
        boolean user,
        CompletableFuture<Void> result,
        long deadline) {
      changing = true;
      inflight = false;
      writeEpoch++;
      long change = ++intent;
      invalidate();
      var previous = effective;
      await(
          call(() -> delegate.reconfigure(preferences)),
          deadline,
          (v, error) -> {
            if (closed.get()) return;
            if (change != intent) {
              if (result != null)
                result.completeExceptionally(
                    new ReconfigurationRejected(
                        "SURFACE_SUPERSEDED", new CancellationException(), effective));
              return;
            }
            if (error != null) {
              var code = known(error);
              if (code != null && !preferences.surfaceKey().equals("chat")) {
                diagnostic = code;
                switchOutput(chat(), false, result, deadline);
                return;
              }
              changing = false;
              if (result != null) result.completeExceptionally(error);
              fatal(error);
              return;
            }
            effective = preferences;
            changing = false;
            if (latest != null) {
              firstAck = true;
              report();
            }
            if (result != null) result.complete(null);
            flush();
          });
    }

    void fatal(Throwable error) {
      if (closed.get()) return;
      failure = error;
      invalidate();
      var pending = List.copyOf(waiters);
      waiters.clear();
      pending.forEach(f -> f.completeExceptionally(error));
      request.cancellation().cancel(DialogueEndReason.FAILED);
      closeAsync();
    }

    public CompletionStage<Void> closeAsync() {
      CompletableFuture<Void> result;
      synchronized (this) {
        if (closing != null) return closing;
        result = new CompletableFuture<>();
        closing = result.minimalCompletionStage();
        closed.set(true);
      }
      queue.execute(
          () -> {
            invalidate();
            waits.forEach(PendingOperation::cancel);
            waits.clear();
            var error = new CancellationException("Renderer closed");
            waiters.forEach(f -> f.completeExceptionally(error));
            waiters.clear();
            scope
                .closeAsync()
                .whenComplete(
                    (v, cleanup) ->
                        queue.execute(
                            () -> {
                              if (active == this) active = null;
                              delegate = null;
                              latest = null;
                              ready.completeExceptionally(
                                  cleanup == null
                                      ? (failure == null ? error : failure)
                                      : new OwnedResource.PreparationFailure(cleanup));
                              if (cleanup == null) result.complete(null);
                              else result.completeExceptionally(cleanup);
                            }));
          });
      return closing;
    }
  }

  private static <T> CompletionStage<T> call(Supplier<CompletionStage<T>> supplier) {
    try {
      return Objects.requireNonNull(supplier.get());
    } catch (Throwable error) {
      return CompletableFuture.failedFuture(error);
    }
  }
}
