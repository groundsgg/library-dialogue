package gg.grounds.dialogue.minestom.internal;

import gg.grounds.dialogue.core.definition.DialogueDefinition;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One concrete connection's owned core runtime. Shared NPCs and service objects remain borrowed.
 */
public final class MinestomDialogueSession {
  private MinestomDialogueRequest request;
  private final ConnectionGuard guard;
  private final CancellationToken token;
  private final MinestomDialogueScheduler scheduler;
  private final DialogueRuntime runtime;
  private DialogueRenderer renderer;
  private Consumer<DialogueStartResult> ready;
  private BiConsumer<MinestomDialogueSession, Throwable> closed;
  private final AtomicBoolean finishing = new AtomicBoolean();
  private final AtomicLong impulse;
  private volatile DialogueHandle handle;
  private volatile DialogueView view;
  private DialogueStartResult.Rejected rejection;

  public MinestomDialogueSession(
      MinestomDialogueRequest request,
      MinestomDialogueOptions options,
      ConnectionGuard guard,
      DialogueRenderer renderer,
      CancellationToken token,
      Consumer<DialogueStartResult> ready,
      BiConsumer<MinestomDialogueSession, Throwable> closed) {
    this.request = request;
    this.guard = guard;
    this.renderer = renderer;
    this.token = token;
    this.ready = ready;
    this.closed = closed;
    scheduler = new MinestomDialogueScheduler(request.player().getInstance());
    runtime = DialogueRuntime.create(new RuntimeOptions(scheduler, options.budgets(), 65536, 256));
    impulse = new AtomicLong(request.openingImpulse());
  }

  public void begin() {
    if (finishing.get()) return;
    if (!guard.isCurrent() || token.isCancelled()) {
      cancel(token.isCancelled() ? token.reason() : DialogueEndReason.HOST_CHANGED);
      return;
    }
    var q = request;
    var services = q.services();
    DialogueRenderer decorated =
        prepare ->
            renderer
                .prepare(prepare)
                .thenApply(
                    surface ->
                        new DialogueRenderer.PresentationHandle() {
                          public CompletionStage<Void> replace(DialogueView value) {
                            if (!guard.isCurrent() || token.isCancelled())
                              return CompletableFuture.failedFuture(
                                  new CancellationException("Connection expired"));
                            view = value;
                            if (!validAnchor()) {
                              cancel(DialogueEndReason.HOST_CHANGED);
                              return CompletableFuture.failedFuture(
                                  new CancellationException("Anchor expired"));
                            }
                            return surface.replace(value);
                          }

                          public CompletionStage<Void> reconfigure(PresentationPreferences p) {
                            return surface.reconfigure(p);
                          }

                          public CompletionStage<Void> closeAsync() {
                            return surface.closeAsync();
                          }
                        });
    var bindings =
        new HostBindings(
            services.generation(),
            services.conditions(),
            services.actions(),
            services.outcomes(),
            services.texts(),
            decorated,
            q.actors(),
            services.capabilities());
    var preferences =
        q.preferences().surfaceKey().equals("chat")
            ? new PresentationPreferences(
                "chat",
                java.time.Duration.ZERO,
                q.preferences().reducedMotion(),
                q.preferences().effects())
            : q.preferences();
    runtime
        .start(
            new DialogueStart(
                q.requestId(),
                q.player().getUuid().toString(),
                q.hostGeneration(),
                q.content(),
                bindings,
                q.context(),
                q.locale(),
                preferences,
                token,
                q.openingImpulse()))
        .whenComplete(
            (result, error) -> {
              if (error != null) {
                finish(error);
                return;
              }
              if (result instanceof DialogueStartResult.Started started) {
                var running = started.handle();
                handle = running;
                var notify = ready;
                notify.accept(result);
                running.completion().whenComplete((end, failure) -> finish(failure));
              } else {
                rejection = (DialogueStartResult.Rejected) result;
                finish(null);
              }
            });
  }

  private boolean validAnchor() {
    var q = request;
    var current = view;
    if (q == null || current == null) return true;
    var node = q.content().definition().nodes().get(current.nodeId());
    if (!(node instanceof DialogueDefinition.Message message)) return true;
    var binding = q.speakers().get(message.speaker());
    if (binding == null) return !q.preferences().surfaceKey().equals("npc");
    try {
      return binding.instance() == q.player().getInstance() && binding.snapshot().get().valid();
    } catch (Throwable ignored) {
      return false;
    }
  }

  public void checkConnection() {
    if (!guard.isCurrent() || !validAnchor()) cancel(DialogueEndReason.HOST_CHANGED);
  }

  public void cancel(DialogueEndReason reason) {
    guard.invalidate();
    try {
      token.cancel(reason);
    } finally {
      finish(null);
    }
  }

  public DialogueView snapshot() {
    return view;
  }

  public long nextImpulse() {
    return impulse.incrementAndGet();
  }

  public CompletionStage<InputResult> submit(DialogueInput input) {
    var h = handle;
    return h == null ? CompletableFuture.completedFuture(InputResult.BUSY) : h.submit(input);
  }

  private void finish(Throwable initialError) {
    if (!finishing.compareAndSet(false, true)) return;
    guard.invalidate();
    runtime
        .closeAsync()
        .whenComplete(
            (v, cleanup) -> {
              Throwable failure = cleanup == null ? initialError : cleanup;
              var terminal = rejection;
              if (terminal != null)
                ready.accept(
                    failure == null
                        ? terminal
                        : new DialogueStartResult.Rejected("CLEANUP_FAILED", List.of()));
              if (failure == null) scheduler.releaseAfterCleanup();
              var callback = closed;
              closed = null;
              ready = null;
              if (failure == null) {
                request = null;
                renderer = null;
                view = null;
                handle = null;
              }
              callback.accept(this, failure);
            });
  }
}
