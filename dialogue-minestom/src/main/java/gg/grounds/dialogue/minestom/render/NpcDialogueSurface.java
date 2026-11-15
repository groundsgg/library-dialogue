package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.core.internal.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.geometry.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.ConnectionGuard;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.entity.metadata.display.*;
import net.minestom.server.instance.Instance;

/** Private world panels projected from shared widgets; shared NPC entities remain borrowed. */
public final class NpcDialogueSurface implements DialogueSurface {
  @FunctionalInterface
  interface SpawnOperation {
    CompletionStage<Void> spawn(Entity entity, Instance instance, Pos position);
  }

  public record Geometry(
      NpcPanelTransform transform, List<WidgetFrame.Rect> fronts, long surfaceGeneration) {
    public Geometry {
      fronts = List.copyOf(fronts);
    }
  }

  private final Player player;
  private final long generation;
  private final ConnectionGuard guard;
  private final PackStateTracker packs;
  private final AssetBundle assets;
  private final OutputChannels channels;
  private final SurfaceInputTargets targets;
  private final DialogueScheduler clock;
  private final Function<DialogueView, MinestomSpeakerBinding> speaker;
  private final Consumer<String> diagnostic;
  private final SpawnOperation spawn;
  private final MinecraftWidgetEncoder encoder = new MinecraftWidgetEncoder();
  private final Map<Long, Geometry> geometries = new ConcurrentHashMap<>();
  private static final Map<Player, AtomicInteger> COUNTS = new WeakHashMap<>();
  private final AtomicInteger displayCount;

  public NpcDialogueSurface(
      Player player,
      long generation,
      ConnectionGuard guard,
      PackStateTracker packs,
      AssetBundle assets,
      OutputChannels channels,
      SurfaceInputTargets targets,
      DialogueScheduler clock,
      Function<DialogueView, MinestomSpeakerBinding> speaker,
      Consumer<String> diagnostic) {
    this(
        player,
        generation,
        guard,
        packs,
        assets,
        channels,
        targets,
        clock,
        speaker,
        diagnostic,
        (e, i, p) -> e.setInstance(i, p));
  }

  NpcDialogueSurface(
      Player player,
      long generation,
      ConnectionGuard guard,
      PackStateTracker packs,
      AssetBundle assets,
      OutputChannels channels,
      SurfaceInputTargets targets,
      DialogueScheduler clock,
      Function<DialogueView, MinestomSpeakerBinding> speaker,
      Consumer<String> diagnostic,
      SpawnOperation spawn) {
    this.player = player;
    synchronized (COUNTS) {
      displayCount = COUNTS.computeIfAbsent(player, p -> new AtomicInteger());
    }
    this.generation = generation;
    this.guard = guard;
    this.packs = packs;
    this.assets = assets;
    this.channels = channels;
    this.targets = targets;
    this.clock = clock;
    this.speaker = speaker;
    this.diagnostic = diagnostic;
    this.spawn = spawn;
  }

  public Geometry geometry() {
    var snapshot = targets.current();
    return snapshot == null ? null : geometries.get(snapshot.surfaceGeneration());
  }

  private boolean available() {
    return guard.isCurrent() && packs.isReady(player, generation, assets.pin());
  }

  public CompletionStage<SurfaceHandle> open(SurfaceContext context) {
    if (!available())
      return CompletableFuture.failedFuture(new DialoguePresenter.FallbackRequired("MISSING_PACK"));
    return CompletableFuture.completedFuture(new Handle(context));
  }

  private final class Display implements OwnedResource {
    final Entity entity;
    final AtomicBoolean removed = new AtomicBoolean();

    Display() {
      if (displayCount.incrementAndGet() > 32) {
        displayCount.decrementAndGet();
        throw new DialogueFailure("DISPLAY_LIMIT", "NPC connection display budget exceeded");
      }
      entity = new Entity(EntityType.TEXT_DISPLAY);
      entity.setAutoViewable(false);
      entity.setNoGravity(true);
      entity.setHasPhysics(false);
      var meta = (TextDisplayMeta) entity.getEntityMeta();
      meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.FIXED);
      meta.setBrightness(15, 15);
      meta.setLineWidth(32767);
      meta.setAlignLeft(true);
      meta.setUseDefaultBackground(false);
      meta.setBackgroundColor(0);
      meta.setShadow(false);
      meta.setSeeThrough(false);
      meta.setWidth(12);
      meta.setHeight(8);
      meta.setText(Component.empty());
    }

    public CompletionStage<Void> closeAsync() {
      if (removed.compareAndSet(false, true)) {
        entity.removeViewer(player);
        entity.remove();
        displayCount.decrementAndGet();
      }
      return CompletableFuture.completedFuture(null);
    }
  }

  private final class Handle implements SurfaceHandle {
    final SurfaceContext context;
    final SerialCommandQueue queue;
    final OwnedScope scope;
    final AtomicBoolean closed = new AtomicBoolean();
    final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    final CompletionStage<Void> cleanupView = cleanup.minimalCompletionStage();
    final Map<String, List<Display>> panels = new LinkedHashMap<>();
    final List<CompletableFuture<Void>> writes = new ArrayList<>();
    PresentationPreferences preferences;
    WidgetFrame frame;
    MinestomSpeakerBinding binding;
    NpcPanelTransform transform;
    double appliedAngle = Double.NaN;
    double hingePosition, appliedHinge = Double.NaN;
    DialogueScheduler.ScheduledTask follow;
    Display backdrop;
    OutputChannels.HudLease hud;
    OutputChannels.EffectLease effects;
    long epoch;
    CompletionStage<Void> movement = CompletableFuture.completedFuture(null);

    Handle(SurfaceContext context) {
      this.context = context;
      preferences = context.preferences();
      queue =
          new SerialCommandQueue(
              clock,
              e -> {
                writes.forEach(f -> f.completeExceptionally(e));
                context.cancellation().cancel(DialogueEndReason.FAILED);
                closeAsync();
              });
      scope = new OwnedScope(queue, clock, context.cleanupBudget());
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

    CompletionStage<Display> create(Pos position) {
      return scope.prepare(
          () -> {
            var display = new Display();
            var future = new CompletableFuture<Display>();
            try {
              spawn
                  .spawn(display.entity, player.getInstance(), position)
                  .whenComplete(
                      (v, error) -> {
                        if (error == null) future.complete(display);
                        else
                          display
                              .closeAsync()
                              .whenComplete(
                                  (u, cleanupError) ->
                                      future.completeExceptionally(
                                          cleanupError == null
                                              ? error
                                              : new OwnedResource.PreparationFailure(
                                                  cleanupError)));
                      });
            } catch (Throwable error) {
              display.closeAsync();
              future.completeExceptionally(error);
            }
            return future;
          });
    }

    public CompletionStage<Void> replace(WidgetFrame next) {
      var result = new CompletableFuture<Void>();
      queue.execute(
          () -> {
            writes.removeIf(CompletableFuture::isDone);
            writes.add(result);
            if (closed.get() || context.cancellation().isCancelled()) {
              result.completeExceptionally(new CancellationException());
              return;
            }
            if (!available()) {
              result.completeExceptionally(new DialoguePresenter.FallbackRequired("MISSING_PACK"));
              return;
            }
            try {
              binding =
                  Objects.requireNonNull(
                      speaker.apply(next.semantic()), "Missing logical speaker binding");
              if (binding.instance() != player.getInstance() || !binding.snapshot().get().valid()) {
                context.cancellation().cancel(DialogueEndReason.HOST_CHANGED);
                throw new CancellationException("NPC anchor changed");
              }
              var encoded = encoder.encode(next, assets);
              boolean focus = VisualOptions.enabled(preferences, "npcBackdrop", diagnostic);
              boolean hide = VisualOptions.enabled(preferences, "hideHud", diagnostic);
              boolean vignette = VisualOptions.enabled(preferences, "vignette", diagnostic);
              if (encoded.widgets().size() * 2 + (focus ? 1 : 0) > 32)
                throw new DialogueFailure("DISPLAY_LIMIT", "NPC display budget exceeded");
              long operation = ++epoch;
              if (follow != null) {
                follow.cancel();
                follow = null;
              }
              targets.invalidateCurrent(context.generation());
              double height =
                  next.widgets().stream()
                      .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
                      .mapToDouble(w -> w.bounds().height())
                      .findFirst()
                      .orElse(72);
              double hinge =
                  next.widgets().stream()
                      .filter(
                          w ->
                              w.kind() == WidgetFrame.Kind.CHOICE
                                  || w.kind() == WidgetFrame.Kind.HINT && w.bounds().x() >= 320)
                      .mapToDouble(w -> w.bounds().x())
                      .min()
                      .orElse(320);
              hingePosition = hinge;
              transform =
                  NpcPanelTransform.from(binding.snapshot().get(), eye())
                      .withSpeechHeight(height)
                      .withChoiceFold(hinge, NpcPanelOptions.from(preferences).choiceAngle());
              var wanted = new HashSet<String>();
              encoded.widgets().forEach(w -> wanted.add(w.id()));
              var pending = new ArrayList<CompletableFuture<?>>();
              var obsolete = panels.keySet().stream().filter(id -> !wanted.contains(id)).toList();
              for (var id : obsolete)
                for (var d : panels.remove(id)) pending.add(scope.release(d).toCompletableFuture());
              if (!focus && backdrop != null) {
                pending.add(scope.release(backdrop).toCompletableFuture());
                backdrop = null;
              }
              if (!hide && hud != null) {
                pending.add(scope.release(hud).toCompletableFuture());
                hud = null;
              }
              var released = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
              released.whenComplete(
                  (unused, releaseError) ->
                      queue.execute(
                          () -> {
                            if (releaseError != null) {
                              result.completeExceptionally(releaseError);
                              return;
                            }
                            if (closed.get() || operation != epoch) {
                              result.completeExceptionally(new CancellationException());
                              return;
                            }
                            var acquisitions = new ArrayList<CompletableFuture<?>>();
                            for (var widget : encoded.widgets())
                              if (!panels.containsKey(widget.id())) {
                                List<Display> pair = new ArrayList<>(Arrays.asList(null, null));
                                panels.put(widget.id(), pair);
                                for (int side = 0; side < 2; side++) {
                                  int index = side;
                                  acquisitions.add(
                                      create(transform.position())
                                          .thenAccept(d -> pair.set(index, d))
                                          .toCompletableFuture());
                                }
                              }
                            if (focus && backdrop == null)
                              acquisitions.add(
                                  create(transform.position())
                                      .thenAccept(d -> backdrop = d)
                                      .toCompletableFuture());
                            if (hide && hud == null)
                              acquisitions.add(
                                  scope
                                      .prepare(
                                          () ->
                                              CompletableFuture.completedFuture(
                                                  channels.acquireHud(player, generation, 50)))
                                      .thenAccept(l -> hud = l)
                                      .toCompletableFuture());
                            if (channels.supportedEffects() && effects == null)
                              acquisitions.add(
                                  scope
                                      .prepare(
                                          () ->
                                              CompletableFuture.completedFuture(
                                                  channels.acquireEffects(player, generation)))
                                      .thenAccept(l -> effects = l)
                                      .toCompletableFuture());
                            CompletableFuture.allOf(acquisitions.toArray(CompletableFuture[]::new))
                                .whenComplete(
                                    (u, error) ->
                                        queue.execute(
                                            () -> {
                                              if (error != null) {
                                                result.completeExceptionally(error);
                                                return;
                                              }
                                              if (closed.get()
                                                  || operation != epoch
                                                  || context.cancellation().isCancelled()) {
                                                result.completeExceptionally(
                                                    new CancellationException());
                                                return;
                                              }
                                              if (!available()) {
                                                result.completeExceptionally(
                                                    new DialoguePresenter.FallbackRequired(
                                                        "MISSING_PACK"));
                                                return;
                                              }
                                              var outputs = new ArrayList<CompletableFuture<?>>();
                                              outputs.add(
                                                  positionDisplays(transform.position())
                                                      .toCompletableFuture());
                                              if (hud != null)
                                                outputs.add(
                                                    hud.replace(
                                                            HudCoverage.compose(
                                                                Component.empty(), 320, hide))
                                                        .toCompletableFuture());
                                              if (effects != null)
                                                outputs.add(
                                                    effects
                                                        .replace(
                                                            vignette
                                                                ? List.of(
                                                                    Key.key("dialogue:vignette"))
                                                                : List.of())
                                                        .toCompletableFuture());
                                              else if (vignette)
                                                diagnostic.accept("POST_EFFECT_UNSUPPORTED");
                                              CompletableFuture.allOf(
                                                      outputs.toArray(CompletableFuture[]::new))
                                                  .whenComplete(
                                                      (a, error2) ->
                                                          queue.execute(
                                                              () -> {
                                                                if (error2 != null) {
                                                                  result.completeExceptionally(
                                                                      error2);
                                                                  return;
                                                                }
                                                                if (closed.get()
                                                                    || operation != epoch
                                                                    || context
                                                                        .cancellation()
                                                                        .isCancelled()) {
                                                                  result.completeExceptionally(
                                                                      new CancellationException());
                                                                  return;
                                                                }
                                                                if (!available()) {
                                                                  result.completeExceptionally(
                                                                      new DialoguePresenter
                                                                          .FallbackRequired(
                                                                          "MISSING_PACK"));
                                                                  return;
                                                                }
                                                                frame = next;
                                                                apply(encoded);
                                                                publish(next);
                                                                startFollow();
                                                                result.complete(null);
                                                              }));
                                            }));
                          }));
            } catch (Throwable error) {
              result.completeExceptionally(error);
            }
          });
      return result.minimalCompletionStage();
    }

    Point eye() {
      return player.getPosition().add(0, player.getEyeHeight(), 0);
    }

    void apply(EncodedFrame encoded) {
      double nextAngle = transform.fold() == null ? 0 : transform.fold().degrees();
      boolean poseChanged =
          Double.compare(appliedAngle, nextAngle) != 0
              || Double.compare(appliedHinge, hingePosition) != 0;
      for (var w : encoded.widgets()) {
        var pair = panels.get(w.id());
        double factor = w.visualScale(), scale = .32 * factor;
        var b = w.bounds();
        double angle = transform.planeFor(b) == transform ? 0 : Math.toRadians(nextAngle);
        for (int side = 0; side < 2; side++) {
          var d = pair.get(side);
          boolean back = side == 1;
          var meta = (TextDisplayMeta) d.entity.getEntityMeta();
          meta.setText(back ? w.back() : w.front());
          meta.setTransformationInterpolationStartDelta(-1);
          meta.setTransformationInterpolationDuration(
              poseChanged ? 0 : (int) Math.ceil(w.transition().toMillis() / 50d));
          double turn = (back ? Math.PI : 0) - angle;
          meta.setLeftRotation(
              new float[] {0, (float) Math.sin(turn / 2), 0, (float) Math.cos(turn / 2)});
          meta.setScale(new Vec(scale, scale, scale));
          // Native one-line origin: x=1-width/2; y=-9 + bitmap bearing 7 = -2.
          double x =
              (b.x() + b.width() / 2 - 160 + (back ? factor : -factor)) * NpcPanelTransform.UNIT;
          double z = back ? -.012 : 0;
          double hinge =
              transform.fold() == null
                  ? 0
                  : (transform.fold().pivot() - 160) * NpcPanelTransform.UNIT;
          double rotatedX =
              angle == 0 ? x : hinge + (x - hinge) * Math.cos(angle) - z * Math.sin(angle);
          double rotatedZ = angle == 0 ? z : (x - hinge) * Math.sin(angle) + z * Math.cos(angle);
          meta.setTranslation(
              new Vec(
                  rotatedX,
                  (transform.speechHeight()
                          - b.y()
                          + b.height() * (factor - 1) / 2
                          - (2 + w.paintBaseline()) * factor)
                      * NpcPanelTransform.UNIT,
                  rotatedZ));
          meta.setTransformationInterpolationStartDelta(0);
          d.entity.addViewer(player);
        }
      }
      appliedAngle = nextAngle;
      appliedHinge = hingePosition;
      if (backdrop != null) {
        var meta = (TextDisplayMeta) backdrop.entity.getEntityMeta();
        meta.setScale(new Vec(.6, .6, .6));
        meta.setTranslation(new Vec(-.0075, -.93, -.55));
        meta.setText(
            Component.text("\ue300")
                .font(Key.key("dialogue:focus"))
                .color(TextColor.color(0xffffff))
                .shadowColor(ShadowColor.none()));
        backdrop.entity.addViewer(player);
      }
    }

    void publish(WidgetFrame value) {
      var choices = new LinkedHashMap<String, WidgetFrame.Rect>();
      var fronts = new ArrayList<WidgetFrame.Rect>();
      for (var w : value.widgets()) {
        if (w.kind() != WidgetFrame.Kind.HINT) fronts.add(w.hitRect());
        if (w.kind() == WidgetFrame.Kind.CHOICE && w.enabled())
          choices.put(w.choiceId(), w.hitRect());
      }
      if (value.scrollbar() != null) fronts.add(value.scrollbar().track());
      geometries.put(context.generation(), new Geometry(transform, fronts, context.generation()));
      targets.publish(value, context.generation(), choices, () -> !closed.get() && available());
    }

    CompletionStage<Void> positionDisplays(Pos position) {
      var result = new CompletableFuture<Void>();
      var previous = movement;
      movement = result.minimalCompletionStage();
      previous.whenComplete(
          (v, oldError) ->
              queue.execute(
                  () -> {
                    if (oldError != null) {
                      result.completeExceptionally(oldError);
                      return;
                    }
                    if (closed.get()
                        || !guard.isCurrent()
                        || context.cancellation().isCancelled()) {
                      result.completeExceptionally(new CancellationException());
                      return;
                    }
                    var moves = new ArrayList<CompletableFuture<?>>();
                    try {
                      for (var pair : panels.values())
                        for (var d : pair)
                          if (!d.entity.getPosition().equals(position))
                            moves.add(d.entity.teleport(position));
                      if (backdrop != null && !backdrop.entity.getPosition().equals(position))
                        moves.add(backdrop.entity.teleport(position));
                      CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
                          .whenComplete(
                              (u, error) -> {
                                if (error == null) result.complete(null);
                                else result.completeExceptionally(error);
                              });
                    } catch (Throwable error) {
                      result.completeExceptionally(error);
                    }
                  }));
      return result.minimalCompletionStage();
    }

    void startFollow() {
      if (follow != null) follow.cancel();
      long followingEpoch = epoch;
      follow =
          clock.schedule(
              Duration.ofMillis(50),
              () ->
                  queue.execute(
                      () -> {
                        if (closed.get()
                            || context.cancellation().isCancelled()
                            || followingEpoch != epoch) return;
                        try {
                          if (!guard.isCurrent()
                              || binding.instance() != player.getInstance()
                              || !binding.snapshot().get().valid()) {
                            context.cancellation().cancel(DialogueEndReason.HOST_CHANGED);
                            return;
                          }
                          var next =
                              NpcPanelTransform.from(binding.snapshot().get(), eye())
                                  .withSpeechHeight(transform.speechHeight())
                                  .withFold(transform.fold());
                          positionDisplays(next.position())
                              .whenComplete(
                                  (v, error) ->
                                      queue.execute(
                                          () -> {
                                            if (closed.get() || followingEpoch != epoch) return;
                                            if (error != null) {
                                              context
                                                  .cancellation()
                                                  .cancel(DialogueEndReason.HOST_CHANGED);
                                              return;
                                            }
                                            transform = next;
                                            geometries.computeIfPresent(
                                                context.generation(),
                                                (g, old) ->
                                                    new Geometry(transform, old.fronts(), g));
                                            startFollow();
                                          }));
                        } catch (Throwable error) {
                          context.cancellation().cancel(DialogueEndReason.HOST_CHANGED);
                        }
                      }));
    }

    public CompletionStage<Void> closeAsync() {
      if (closed.compareAndSet(false, true))
        queue.execute(
            () -> {
              epoch++;
              writes.forEach(f -> f.completeExceptionally(new CancellationException()));
              writes.clear();
              if (follow != null) follow.cancel();
              targets.clear(context.generation());
              geometries.remove(context.generation());
              scope
                  .closeAsync()
                  .whenComplete(
                      (v, error) -> {
                        panels.clear();
                        frame = null;
                        binding = null;
                        transform = null;
                        if (error == null) cleanup.complete(null);
                        else cleanup.completeExceptionally(error);
                      });
            });
      return cleanupView;
    }
  }
}
