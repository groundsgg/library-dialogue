package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.core.internal.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.ConnectionGuard;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;

/** Native action-bar projection using the same encoded widgets as the NPC surface. */
public final class HudDialogueSurface implements DialogueSurface {
  private final Player player;
  private final long generation;
  private final ConnectionGuard guard;
  private final PackStateTracker packs;
  private final AssetBundle assets;
  private final OutputChannels channels;
  private final SurfaceInputTargets targets;
  private final DialogueScheduler clock;
  private final Consumer<String> diagnostic;
  private final MinecraftWidgetEncoder encoder = new MinecraftWidgetEncoder();

  public HudDialogueSurface(
      Player player,
      long generation,
      ConnectionGuard guard,
      PackStateTracker packs,
      AssetBundle assets,
      OutputChannels channels,
      SurfaceInputTargets targets,
      DialogueScheduler clock,
      Consumer<String> diagnostic) {
    this.player = player;
    this.generation = generation;
    this.guard = guard;
    this.packs = packs;
    this.assets = assets;
    this.channels = channels;
    this.targets = targets;
    this.clock = clock;
    this.diagnostic = diagnostic;
  }

  private boolean available() {
    return guard.isCurrent() && packs.isReady(player, generation, assets.pin());
  }

  public CompletionStage<SurfaceHandle> open(SurfaceContext context) {
    if (!available())
      return CompletableFuture.failedFuture(new DialoguePresenter.FallbackRequired("MISSING_PACK"));
    var h = new Handle(context);
    h.begin();
    return h.ready.minimalCompletionStage();
  }

  private final class Handle implements SurfaceHandle {
    final SurfaceContext context;
    final SerialCommandQueue queue;
    final OwnedScope scope;
    final AtomicBoolean closed = new AtomicBoolean();
    final CompletableFuture<SurfaceHandle> ready = new CompletableFuture<>();
    final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    final CompletionStage<Void> cleanupView = cleanup.minimalCompletionStage();
    OutputChannels.HudLease hud;
    OutputChannels.EffectLease effects;
    PresentationPreferences preferences;
    DialogueScheduler.ScheduledTask refresh;
    Component last;

    Handle(SurfaceContext c) {
      context = c;
      preferences = c.preferences();
      queue =
          new SerialCommandQueue(
              clock,
              e -> {
                context.cancellation().cancel(DialogueEndReason.FAILED);
                closeAsync();
              });
      scope = new OwnedScope(queue, clock, context.cleanupBudget());
    }

    void begin() {
      queue.execute(
          () -> {
            scope
                .prepare(
                    () ->
                        CompletableFuture.completedFuture(
                            channels.acquireHud(player, generation, 50)))
                .whenComplete(
                    (lease, error) ->
                        queue.execute(
                            () -> {
                              if (error != null) {
                                failed(error);
                                return;
                              }
                              hud = lease;
                              if (channels.supportedEffects())
                                scope
                                    .prepare(
                                        () ->
                                            CompletableFuture.completedFuture(
                                                channels.acquireEffects(player, generation)))
                                    .whenComplete(
                                        (effect, failure) ->
                                            queue.execute(
                                                () -> {
                                                  if (failure != null) failed(failure);
                                                  else {
                                                    effects = effect;
                                                    ready.complete(this);
                                                  }
                                                }));
                              else ready.complete(this);
                            }));
          });
    }

    void failed(Throwable error) {
      closeAsync()
          .whenComplete(
              (v, f) ->
                  ready.completeExceptionally(
                      f == null ? error : new OwnedResource.PreparationFailure(f)));
    }

    public CompletionStage<Void> configure(PresentationPreferences value) {
      var result = new CompletableFuture<Void>();
      queue.execute(
          () -> {
            if (closed.get()) result.completeExceptionally(new CancellationException());
            else {
              preferences = value;
              result.complete(null);
            }
          });
      return result.minimalCompletionStage();
    }

    public CompletionStage<Void> replace(WidgetFrame frame) {
      var result = new CompletableFuture<Void>();
      queue.execute(
          () -> {
            if (closed.get() || context.cancellation().isCancelled()) {
              result.completeExceptionally(new CancellationException());
              return;
            }
            if (!available()) {
              targets.invalidateCurrent(context.generation());
              result.completeExceptionally(new DialoguePresenter.FallbackRequired("MISSING_PACK"));
              return;
            }
            try {
              var encoded = encoder.encode(frame, assets);
              boolean hide = VisualOptions.enabled(preferences, "hideHud", diagnostic),
                  vignette = VisualOptions.enabled(preferences, "vignette", diagnostic);
              VisualOptions.enabled(preferences, "npcBackdrop", diagnostic);
              if (vignette && effects == null) diagnostic.accept("POST_EFFECT_UNSUPPORTED");
              double width =
                  frame.widgets().stream()
                      .mapToDouble(w -> w.bounds().x() + w.bounds().width())
                      .max()
                      .orElse(0);
              var component = HudCoverage.compose(encoded.hud(), width, hide);
              var effectWrite =
                  effects == null
                      ? CompletableFuture.<Void>completedFuture(null)
                      : effects.replace(
                          vignette ? List.of(Key.key("dialogue:vignette")) : List.of());
              effectWrite
                  .thenCompose(v -> hud.replace(component))
                  .whenComplete(
                      (v, error) ->
                          queue.execute(
                              () -> {
                                if (error != null) {
                                  result.completeExceptionally(error);
                                  return;
                                }
                                if (closed.get()
                                    || !guard.isCurrent()
                                    || context.cancellation().isCancelled()) {
                                  result.completeExceptionally(new CancellationException());
                                  return;
                                }
                                if (!hud.visible()) {
                                  targets.invalidateCurrent(context.generation());
                                  result.completeExceptionally(
                                      new DialoguePresenter.FallbackRequired("OUTPUT_SUPPRESSED"));
                                  return;
                                }
                                last = component;
                                var choices = new LinkedHashMap<String, WidgetFrame.Rect>();
                                frame.widgets().stream()
                                    .filter(w -> w.kind() == WidgetFrame.Kind.CHOICE && w.enabled())
                                    .forEach(w -> choices.put(w.choiceId(), w.hitRect()));
                                targets.publish(frame, context.generation(), choices);
                                renew();
                                result.complete(null);
                              }));
            } catch (Throwable error) {
              result.completeExceptionally(error);
            }
          });
      return result.minimalCompletionStage();
    }

    void renew() {
      if (refresh != null) refresh.cancel();
      refresh =
          clock.schedule(
              Duration.ofSeconds(1),
              () ->
                  queue.execute(
                      () -> {
                        if (closed.get()
                            || context.cancellation().isCancelled()
                            || !guard.isCurrent()) return;
                        if (available() && last != null)
                          hud.replace(last)
                              .whenComplete(
                                  (v, e) -> {
                                    if (e != null)
                                      context.cancellation().cancel(DialogueEndReason.FAILED);
                                  });
                        renew();
                      }));
    }

    public CompletionStage<Void> closeAsync() {
      if (closed.compareAndSet(false, true))
        queue.execute(
            () -> {
              if (refresh != null) refresh.cancel();
              last = null;
              targets.clear(context.generation());
              scope
                  .closeAsync()
                  .whenComplete(
                      (v, error) -> {
                        ready.completeExceptionally(new CancellationException());
                        if (error == null) cleanup.complete(null);
                        else cleanup.completeExceptionally(error);
                      });
            });
      return cleanupView;
    }
  }
}
