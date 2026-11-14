package gg.grounds.dialogue.minestom.input;

import gg.grounds.dialogue.core.internal.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.geometry.*;
import gg.grounds.dialogue.minestom.internal.ConnectionGuard;
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientPongPacket;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.server.network.packet.server.common.PingPacket;
import net.minestom.server.network.packet.server.play.*;

/** Session-owned packet routing; the core alone evaluates offers and executes actions. */
public final class MinestomInputAdapter implements OwnedResource {
  public interface Target {
    DialogueView view();

    TargetSnapshot targets();

    NpcDialogueSurface.Geometry npcGeometry();

    String effectiveSurface();

    boolean hiddenHotbar();

    long nextImpulse();

    CompletionStage<InputResult> submit(DialogueInput input);
  }

  private static final AtomicInteger FENCES = new AtomicInteger(Integer.MIN_VALUE / 2);
  private final Player player;
  private final long generation;
  private final ConnectionGuard guard;
  private final InputProfile profile;
  private final DialogueScheduler clock;
  private final CancellationToken cancellation;
  private final Target target;
  private final NpcHitTest.OcclusionTest occlusion;
  private final SerialCommandQueue queue;
  private final OwnedScope scope;
  private final ClickBurstGate burst;
  private final SlotCursor slots;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CompletableFuture<Void> cleanup = new CompletableFuture<>();
  private final CompletionStage<Void> cleanupView = cleanup.minimalCompletionStage();

  private record PendingPacket(ClientPacket packet, long arrived, TargetSnapshot admitted) {}

  private final Deque<PendingPacket> packets = new ArrayDeque<>();
  private boolean dispatching;
  private boolean activated, acquiring, scrolled;
  private int followup;
  private PrivateInteractionCatcher catcher;
  private CompletableFuture<Void> preparing;
  private String lastHover;
  private TargetSnapshot.Target observed;

  public MinestomInputAdapter(
      Player player,
      long generation,
      ConnectionGuard guard,
      InputProfile profile,
      DialogueScheduler clock,
      CancellationToken cancellation,
      Target target,
      NpcHitTest.OcclusionTest occlusion) {
    this.player = player;
    this.generation = generation;
    this.guard = guard;
    this.profile = profile;
    this.clock = clock;
    this.cancellation = cancellation;
    this.target = target;
    this.occlusion = occlusion;
    burst = new ClickBurstGate(profile.quietPeriod());
    burst.opened(clock.nanoTime());
    slots = new SlotCursor(player.getHeldSlot(), generation);
    queue =
        new SerialCommandQueue(
            clock,
            e -> {
              cancellation.cancel(DialogueEndReason.FAILED);
              closeAsync();
            });
    scope = new OwnedScope(queue, clock, Duration.ofSeconds(30));
  }

  private boolean active() {
    return !closed.get() && guard.isCurrent() && !cancellation.isCancelled();
  }

  public CompletionStage<Void> activate() {
    var result = new CompletableFuture<Void>();
    queue.execute(
        () -> {
          if (!active()) {
            result.completeExceptionally(new CancellationException());
            return;
          }
          activated = true;
          ensureCatcher()
              .whenComplete(
                  (v, error) -> {
                    if (error == null) result.complete(null);
                    else result.completeExceptionally(error);
                  });
        });
    return result.minimalCompletionStage();
  }

  private boolean graphical() {
    return target.effectiveSurface().equals("hud") || target.effectiveSurface().equals("npc");
  }

  private CompletionStage<Void> ensureCatcher() {
    boolean needed =
        activated
            && graphical()
            && (profile.confirm().contains(InputProfile.Trigger.USE)
                || profile.confirm().contains(InputProfile.Trigger.ATTACK));
    if (!needed) {
      if (catcher != null) {
        var old = catcher;
        catcher = null;
        return scope.release(old);
      }
      return preparing == null
          ? CompletableFuture.completedFuture(null)
          : preparing.minimalCompletionStage();
    }
    if (catcher != null) {
      catcher.follow();
      return CompletableFuture.completedFuture(null);
    }
    if (acquiring) return preparing.minimalCompletionStage();
    acquiring = true;
    preparing = new CompletableFuture<>();
    var promise = preparing;
    scope
        .prepare(() -> PrivateInteractionCatcher.open(player, guard))
        .whenComplete(
            (value, error) ->
                queue.execute(
                    () -> {
                      acquiring = false;
                      if (error != null) {
                        promise.completeExceptionally(error);
                        return;
                      }
                      if (!active() || !graphical()) {
                        scope
                            .release(value)
                            .whenComplete(
                                (v, e) -> {
                                  if (e == null) promise.complete(null);
                                  else promise.completeExceptionally(e);
                                });
                      } else {
                        catcher = value;
                        value.publish();
                        promise.complete(null);
                      }
                    }));
    return promise.minimalCompletionStage();
  }

  public void receive(PlayerPacketEvent event) {
    if (event.getPlayer() != player || !active()) return;
    var packet = event.getPacket();
    if (packet instanceof ClientPongPacket pong) {
      if (slots.matches(pong.id())) {
        event.setCancelled(true);
        enqueue(packet);
      }
      return;
    }
    boolean reserved =
        packet instanceof ClientHeldItemChangePacket
            || packet instanceof ClientUseItemPacket
            || packet instanceof ClientInteractEntityPacket
            || packet instanceof ClientAttackPacket
            || packet instanceof ClientPlayerBlockPlacementPacket
            || packet instanceof ClientPlayerActionPacket;
    if (!reserved) return;
    event.setCancelled(true);
    enqueue(packet);
  }

  private void enqueue(ClientPacket packet) {
    long arrived = clock.nanoTime();
    var admitted = target.targets();
    queue.execute(
        () -> {
          if (!active()) return;
          if (packets.size() >= 256) {
            cancellation.cancel(DialogueEndReason.FAILED);
            closeAsync();
            return;
          }
          packets.addLast(new PendingPacket(packet, arrived, admitted));
          pump();
        });
  }

  private void pump() {
    if (dispatching || !active() || packets.isEmpty()) return;
    var packet = packets.removeFirst();
    dispatching = true;
    CompletionStage<InputResult> stage;
    try {
      stage = route(packet);
    } catch (Throwable error) {
      stage = CompletableFuture.failedFuture(error);
    }
    stage.whenComplete(
        (v, error) ->
            queue.execute(
                () -> {
                  dispatching = false;
                  if (error != null && active()) {
                    cancellation.cancel(DialogueEndReason.FAILED);
                    closeAsync();
                  } else pump();
                }));
  }

  private static CompletionStage<InputResult> ignored() {
    return CompletableFuture.completedFuture(InputResult.STALE);
  }

  private boolean sameVisit(TargetSnapshot admitted) {
    var now = current();
    return admitted != null
        && now != null
        && admitted.sessionId().equals(now.sessionId())
        && admitted.nodeVisitId() == now.nodeVisitId()
        && admitted.surfaceGeneration() == now.surfaceGeneration();
  }

  private CompletionStage<InputResult> route(PendingPacket input) {
    var packet = input.packet();
    if (packet instanceof ClientPongPacket pong) {
      slots.pong(pong.id(), generation, player.getHeldSlot());
      return ignored();
    }
    if (packet instanceof ClientHeldItemChangePacket held) {
      int slot = held.slot();
      if (slot < 0 || slot > 8) return ignored();
      int direction = slots.scroll(slot);
      scrolled = true;
      if (!target.hiddenHotbar() && !slots.awaiting() && slot != player.getHeldSlot()) fence();
      return profile.scroll() && direction != 0 && sameVisit(input.admitted())
          ? scroll(direction)
          : ignored();
    }
    InputProfile.Trigger trigger = null;
    if (packet instanceof ClientInteractEntityPacket
        || packet instanceof ClientUseItemPacket
        || packet instanceof ClientPlayerBlockPlacementPacket) trigger = InputProfile.Trigger.USE;
    else if (packet instanceof ClientAttackPacket) trigger = InputProfile.Trigger.ATTACK;
    else if (packet instanceof ClientPlayerActionPacket action) {
      switch (action.status()) {
        case STARTED_DIGGING, STAB -> trigger = InputProfile.Trigger.ATTACK;
        case SWAP_ITEM_HAND -> {
          trigger = InputProfile.Trigger.SWAP_HANDS;
          player.getInventory().update(player);
        }
        case DROP_ITEM, DROP_ITEM_STACK -> {
          player.getInventory().update(player);
          return ignored();
        }
        default -> {}
      }
      if (action.status().name().contains("DIGGING")) {
        refreshBlock(action.blockPosition());
        player.sendPacket(new AcknowledgeBlockChangePacket(action.sequence()));
      }
    }
    repair(packet);
    if (trigger != null
        && profile.confirm().contains(trigger)
        && burst.accept(input.arrived())
        && sameVisit(input.admitted())
        && input.admitted().renderRevision() == current().renderRevision()) return confirm();
    return ignored();
  }

  private DialogueInput.InputStamp stamp(TargetSnapshot snapshot) {
    return new DialogueInput.InputStamp(
        snapshot.sessionId(), snapshot.renderRevision(), target.nextImpulse());
  }

  private TargetSnapshot current() {
    var snapshot = target.targets();
    var view = target.view();
    return snapshot != null
            && view != null
            && snapshot.sessionId().equals(view.sessionId())
            && snapshot.nodeVisitId() == view.nodeVisitId()
            && snapshot.renderRevision() == view.renderRevision()
        ? snapshot
        : null;
  }

  private CompletionStage<InputResult> scroll(int direction) {
    var snapshot = current();
    var view = target.view();
    if (snapshot == null || view == null || view.state() == DialogueView.State.WAITING_FOR_PROVIDER)
      return ignored();
    var choices =
        view.choices().stream()
            .filter(c -> c.availability() == DialogueView.Availability.AVAILABLE)
            .toList();
    if (choices.isEmpty()) return ignored();
    int selected = 0;
    for (int i = 0; i < choices.size(); i++)
      if (choices.get(i).id().equals(view.selectedChoiceId())) selected = i;
    var id = choices.get(Math.floorMod(selected + direction, choices.size())).id();
    return target.submit(new DialogueInput.Select(stamp(snapshot), id));
  }

  private Optional<String> hover(TargetSnapshot snapshot) {
    var geometry = target.npcGeometry();
    if (geometry == null || geometry.surfaceGeneration() != snapshot.surfaceGeneration())
      return Optional.empty();
    return NpcHitTest.hit(
        player.getPosition().add(0, player.getEyeHeight(), 0),
        player.getPosition().direction(),
        geometry.transform(),
        snapshot,
        occlusion);
  }

  private TargetSnapshot.Target hoverStamp(TargetSnapshot snapshot, String id) {
    return new TargetSnapshot.Target(
        snapshot.sessionId(),
        snapshot.renderRevision(),
        snapshot.nodeVisitId(),
        snapshot.surfaceGeneration(),
        id);
  }

  private CompletionStage<InputResult> confirm() {
    var snapshot = current();
    var view = target.view();
    if (snapshot == null || view == null) return ignored();
    var hovered = hover(snapshot).orElse(null);
    if (hovered != null
        && (!hovered.equals(view.selectedChoiceId())
            || !hoverStamp(snapshot, hovered).equals(observed))) {
      var selection = target.submit(new DialogueInput.Select(stamp(snapshot), hovered));
      lastHover = hovered;
      observed = hoverStamp(snapshot, hovered);
      return selection;
    }
    return target.submit(new DialogueInput.Confirm(stamp(snapshot), view.selectedChoiceId()));
  }

  public void tick() {
    queue.execute(
        () -> {
          if (!active() || !activated) return;
          ensureCatcher()
              .whenComplete(
                  (v, error) -> {
                    if (error != null && active()) cancellation.cancel(DialogueEndReason.FAILED);
                  });
          if (scrolled
              && !target.hiddenHotbar()
              && !slots.awaiting()
              && slots.slot() != player.getHeldSlot()
              && ++followup >= 2) fence();
          if (dispatching || !packets.isEmpty()) return;
          var snapshot = current();
          if (snapshot == null) return;
          String hovered = hover(snapshot).orElse(null);
          if (hovered != null) {
            observed = hoverStamp(snapshot, hovered);
            if (!hovered.equals(lastHover) && !hovered.equals(target.view().selectedChoiceId())) {
              dispatching = true;
              target
                  .submit(new DialogueInput.Select(stamp(snapshot), hovered))
                  .whenComplete(
                      (result, error) ->
                          queue.execute(
                              () -> {
                                dispatching = false;
                                if (error != null && active()) {
                                  cancellation.cancel(DialogueEndReason.FAILED);
                                  closeAsync();
                                } else pump();
                              }));
            }
          } else observed = null;
          lastHover = hovered;
        });
  }

  private void fence() {
    int id = FENCES.incrementAndGet();
    slots.fence(id);
    followup = 0;
    player.sendPackets(
        List.of(
            new BundlePacket(),
            new HeldItemChangePacket(player.getHeldSlot()),
            new PingPacket(id),
            new BundlePacket()));
  }

  private void repair(ClientPacket packet) {
    if (packet instanceof ClientUseItemPacket use) {
      player.getInventory().update(player);
      player.sendPacket(new AcknowledgeBlockChangePacket(use.sequence()));
    } else if (packet instanceof ClientPlayerBlockPlacementPacket placement) {
      player.getInventory().update(player);
      refreshBlock(placement.blockPosition());
      refreshBlock(placement.blockPosition().relative(placement.blockFace()));
      player.sendPacket(new AcknowledgeBlockChangePacket(placement.sequence()));
    }
  }

  private void refreshBlock(Point point) {
    var instance = player.getInstance();
    if (instance != null && instance.isChunkLoaded(point))
      player.sendPacket(new BlockChangePacket(point, instance.getBlock(point)));
  }

  public CompletionStage<Void> closeAsync() {
    if (closed.compareAndSet(false, true))
      queue.execute(
          () -> {
            packets.clear();
            if (scrolled && player.isOnline())
              player.sendPacket(new HeldItemChangePacket(player.getHeldSlot()));
            scope
                .closeAsync()
                .whenComplete(
                    (v, error) -> {
                      catcher = null;
                      observed = null;
                      lastHover = null;
                      if (error == null) cleanup.complete(null);
                      else cleanup.completeExceptionally(error);
                    });
          });
    return cleanupView;
  }
}
