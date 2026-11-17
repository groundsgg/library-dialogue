package example;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.npc.minestom.*;
import gg.grounds.npc.minestom.NpcHandle;
import gg.grounds.npc.minestom.presentation.NpcPresentationHandle;
import gg.grounds.npc.minestom.presentation.NpcPresentationOptions;
import gg.grounds.npc.minestom.presentation.NpcPresentationResult;
import gg.grounds.npc.minestom.presentation.NpcPresentationStart;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import net.minestom.server.entity.Player;

/**
 * Example bridge only: all rendering, private copies, clips and sound isolation stay in npc 0.2.
 */
public final class PersonalNpcActors implements ActorPort {
  private final Player player;
  private final Map<String, NpcHandle> parents;
  private final Function<Player, NpcPresentationOptions> options;
  private final BiFunction<NpcHandle, NpcPresentationOptions, NpcPresentationStart> prepare;
  private final AtomicReference<Composite> active = new AtomicReference<>();

  public PersonalNpcActors(
      Player player,
      Map<String, NpcHandle> parents,
      Function<Player, NpcPresentationOptions> options) {
    this(player, parents, options, NpcHandle::preparePresentation);
  }

  PersonalNpcActors(
      Player player,
      Map<String, NpcHandle> parents,
      Function<Player, NpcPresentationOptions> options,
      BiFunction<NpcHandle, NpcPresentationOptions, NpcPresentationStart> prepare) {
    this.player = Objects.requireNonNull(player);
    this.parents = Map.copyOf(parents);
    this.options = Objects.requireNonNull(options);
    this.prepare = Objects.requireNonNull(prepare);
  }

  public CompletionStage<ActorHandle> prepare(ActorRequest request) {
    if (!player.getUuid().toString().equals(request.audienceId()))
      return CompletableFuture.failedFuture(new IllegalArgumentException("Wrong actor connection"));
    var composite = new Composite(request);
    composite.begin();
    return composite.ready.minimalCompletionStage();
  }

  public CompletionStage<Void> wave() {
    var current = active.get();
    return current == null
        ? CompletableFuture.failedFuture(new IllegalStateException("Open a dialogue first"))
        : current.cue(new ActorCue("mechanic", "wave", 0, UUID.randomUUID().toString()));
  }

  private final class Composite implements ActorHandle {
    final Object lock = new Object();
    final ActorRequest request;
    final List<CompletableFuture<NpcPresentationStart>> starts = new ArrayList<>();
    final Map<String, NpcPresentationHandle> handles = new ConcurrentHashMap<>();
    final CompletableFuture<ActorHandle> ready = new CompletableFuture<>();
    final CompletableFuture<Void> closed = new CompletableFuture<>();
    final CompletionStage<Void> closeView = closed.minimalCompletionStage();
    volatile boolean closing;
    CompletableFuture<Void> activation;
    CompletionStage<Void> activationView;
    OwnedResource subscription;
    Throwable preparationFailure;

    Composite(ActorRequest request) {
      this.request = request;
    }

    void begin() {
      var listener = request.cancellation().onCancel(reason -> closeAsync());
      boolean release;
      synchronized (lock) {
        release = closing;
        if (!release) subscription = listener;
      }
      if (release) listener.closeAsync();
      var acquired = new ArrayList<CompletableFuture<?>>();
      for (var speaker : request.speakerIds()) {
        var slot = new CompletableFuture<NpcPresentationStart>();
        synchronized (lock) {
          if (closing) break;
          starts.add(slot);
        }
        try {
          var parent =
              Objects.requireNonNull(parents.get(speaker), "Missing borrowed NPC " + speaker);
          var base = options.apply(player);
          if (base.getPlayer() != player)
            throw new IllegalArgumentException("Wrong presentation connection");
          var guarded =
              NpcPresentationOptions.builder(player)
                  .publicationGuard(
                      operation ->
                          !closing
                              && !request.cancellation().isCancelled()
                              && base.getPublicationGuard().runIfCurrent(operation))
                  .sounds(base.getSounds())
                  .idleClip(base.getIdleClip())
                  .sampleIntervalMillis(base.getSampleIntervalMillis())
                  .clock(base.getClock())
                  .build();
          slot.complete(Objects.requireNonNull(prepare.apply(parent, guarded)));
        } catch (Throwable error) {
          slot.completeExceptionally(error);
        }
        acquired.add(
            slot.thenCompose(NpcPresentationStart::ready)
                .thenAccept(
                    value -> {
                      if (!(value instanceof NpcPresentationResult.Ready result))
                        throw new IllegalStateException("Personal NPC rejected: " + value);
                      if (!closing) {
                        var handle = result.getHandle();
                        handles.put(speaker, handle);
                        handle
                            .closed()
                            .whenComplete(
                                (v, error) -> {
                                  if (!closing)
                                    request
                                        .cancellation()
                                        .cancel(
                                            gg.grounds.dialogue.core.runtime.DialogueEndReason
                                                .HOST_CHANGED);
                                });
                      }
                    })
                .toCompletableFuture());
      }
      CompletableFuture.allOf(acquired.toArray(CompletableFuture[]::new))
          .whenComplete(
              (v, error) -> {
                if (error != null) {
                  synchronized (lock) {
                    preparationFailure = error;
                  }
                  closeAsync();
                } else if (closing || request.cancellation().isCancelled()) closeAsync();
                else {
                  boolean admit;
                  synchronized (lock) {
                    admit = !closing;
                    if (admit) active.set(this);
                  }
                  if (admit) ready.complete(this);
                  else closeAsync();
                }
              });
    }

    public CompletionStage<Void> activate() {
      CompletableFuture<Void> result;
      synchronized (lock) {
        if (activation != null) return activationView;
        activation = new CompletableFuture<>();
        activationView = activation.minimalCompletionStage();
        result = activation;
      }
      if (closing || request.cancellation().isCancelled()) {
        result.completeExceptionally(new CancellationException());
        return activationView;
      }
      var publications =
          handles.values().stream()
              .map(h -> h.publish().toCompletableFuture())
              .toArray(CompletableFuture[]::new);
      CompletableFuture.allOf(publications)
          .whenComplete(
              (v, error) -> {
                if (error != null) result.completeExceptionally(error);
                else if (closing || request.cancellation().isCancelled())
                  result.completeExceptionally(new CancellationException());
                else result.complete(null);
              });
      return activationView;
    }

    public CompletionStage<Void> cue(ActorCue cue) {
      var activated = activation;
      if (closing
          || request.cancellation().isCancelled()
          || activated == null
          || !activated.isDone()
          || activated.isCompletedExceptionally())
        return CompletableFuture.failedFuture(new CancellationException("Actor not active"));
      var handle = handles.get(cue.speaker());
      if (handle == null)
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Unknown logical speaker"));
      return cue.gesture().equals("look")
          ? handle.applyHeadLook(35, -15)
          : handle.playClip(cue.gesture());
    }

    public CompletionStage<Void> closeAsync() {
      List<CompletableFuture<NpcPresentationStart>> pending;
      OwnedResource listener;
      synchronized (lock) {
        if (closing) return closeView;
        closing = true;
        pending = List.copyOf(starts);
        listener = subscription;
        subscription = null;
      }
      active.compareAndSet(this, null);
      var cancellation = new ArrayList<CompletableFuture<?>>();
      for (var slot : pending)
        cancellation.add(
            slot.handle(
                    (start, error) ->
                        error == null
                            ? start.cancel()
                            : CompletableFuture.<Void>completedFuture(null))
                .thenCompose(stage -> stage)
                .toCompletableFuture());
      if (listener != null) cancellation.add(listener.closeAsync().toCompletableFuture());
      CompletableFuture.allOf(cancellation.toArray(CompletableFuture[]::new))
          .whenComplete(
              (v, error) -> {
                handles.clear();
                var rejected =
                    error == null
                        ? (preparationFailure == null
                            ? new CancellationException("Personal actor closed")
                            : preparationFailure)
                        : new OwnedResource.PreparationFailure(error);
                ready.completeExceptionally(rejected);
                var activating = activation;
                if (activating != null && !activating.isDone())
                  activating.completeExceptionally(rejected);
                if (error == null) closed.complete(null);
                else closed.completeExceptionally(error);
              });
      return closeView;
    }
  }
}
