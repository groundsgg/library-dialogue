package gg.grounds.dialogue.minestom.input;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.port.DialogueScheduler;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot;
import gg.grounds.dialogue.minestom.geometry.*;
import gg.grounds.dialogue.minestom.internal.*;
import gg.grounds.dialogue.minestom.render.*;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.*;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class InputSafetyTest {
  static <T> T done(Env env, CompletionStage<T> s) {
    assertTrue(env.tickWhile(() -> !s.toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    return s.toCompletableFuture().join();
  }

  static class Fixture implements MinestomInputAdapter.Target {
    final Player player;
    final TestConnection connection;
    final MinestomInputAdapter adapter;
    final List<DialogueInput> submitted = new ArrayList<>();
    final AtomicLong now = new AtomicLong(), impulse = new AtomicLong();
    DialogueView view = RenderFixtures.view("Hello", 5);
    TargetSnapshot targets;
    NpcDialogueSurface.Geometry geometry;
    boolean hidden;
    boolean visible = true;
    CompletionStage<InputResult> submitGate =
        CompletableFuture.completedFuture(InputResult.ACCEPTED);

    Fixture(Env env) {
      this(env, InputProfile.defaults());
    }

    Fixture(Env env, InputProfile profile) {
      connection = env.createConnection();
      player = connection.connect(env.createFlatInstance(), new Pos(0, 42, 4, 180, 0));
      var nativeClock = new MinestomDialogueScheduler(player.getInstance());
      DialogueScheduler clock =
          new DialogueScheduler() {
            public void execute(Runnable r) {
              nativeClock.execute(r);
            }

            public ScheduledTask schedule(Duration d, Runnable r) {
              return nativeClock.schedule(d, r);
            }

            public long nanoTime() {
              return now.get();
            }
          };
      targets =
          new TargetSnapshot(
              view.sessionId(),
              view.renderRevision(),
              view.nodeVisitId(),
              1,
              Map.of(
                  "ask",
                  new WidgetFrame.Rect(326, 0, 240, 40),
                  "leave",
                  new WidgetFrame.Rect(326, 46, 240, 40)));
      adapter =
          new MinestomInputAdapter(
              player,
              1,
              new ConnectionGuard(player, 1, player.getInstance()),
              profile,
              clock,
              new CancellationToken(),
              this,
              (a, b) -> visible);
    }

    public DialogueView view() {
      return view;
    }

    public TargetSnapshot targets() {
      return targets;
    }

    public NpcDialogueSurface.Geometry npcGeometry() {
      return geometry;
    }

    public String effectiveSurface() {
      return geometry == null ? "hud" : "npc";
    }

    public boolean hiddenHotbar() {
      return hidden;
    }

    public long nextImpulse() {
      return impulse.incrementAndGet();
    }

    public CompletionStage<InputResult> submit(DialogueInput input) {
      submitted.add(input);
      return submitGate;
    }

    PlayerPacketEvent packet(Env env, ClientPacket packet) {
      var e = new PlayerPacketEvent(player, packet);
      adapter.receive(e);
      env.tick();
      env.tick();
      return e;
    }

    void aim(String id) {
      var transform =
          NpcPanelTransform.from(
                  new AnchorSnapshot(new Vec(0, 42, 0), 0, new Vec(0, 43.6, 0), true),
                  player.getPosition().add(0, player.getEyeHeight(), 0))
              .withSpeechHeight(40);
      geometry =
          new NpcDialogueSurface.Geometry(
              transform, List.copyOf(targets.choices().values()), 1, targets.choices());
      var r = targets.choices().get(id);
      var target = transform.widgetToWorld(r.x() + r.width() / 2, r.y() + r.height() / 2);
      player.setView(
          player.getPosition().withLookAt(target).yaw(),
          player.getPosition().add(0, player.getEyeHeight(), 0).asPos().withLookAt(target).pitch());
    }
  }

  @Test
  void occludedSelectedNpcChoiceCannotConfirm(Env env) {
    var f = new Fixture(env);
    f.aim("ask");
    done(env, f.adapter.activate());
    f.visible = false;
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    assertTrue(f.submitted.isEmpty(), "Occlusion must block confirmation as well as hover");
    done(env, f.adapter.closeAsync());
  }

  @Test
  void backFacingSelectedNpcChoiceCannotConfirm(Env env) {
    var f = new Fixture(env);
    f.aim("ask");
    var transform = f.geometry.transform();
    var bounds = f.targets.choices().get("ask");
    var center =
        transform.widgetToWorld(bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2);
    var eye = center.sub(transform.planeFor(bounds).normal().mul(3));
    var look = eye.asPos().withLookAt(center);
    done(
        env,
        f.player.teleport(
            eye.sub(0, f.player.getEyeHeight(), 0).asPos().withView(look.yaw(), look.pitch())));
    done(env, f.adapter.activate());
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    assertTrue(f.submitted.isEmpty(), "A panel back must not confirm the selected front answer");
    done(env, f.adapter.closeAsync());
  }

  @Test
  void pointingAtUnavailableNpcChoiceCannotConfirmAnotherAnswer(Env env) {
    var f = new Fixture(env);
    f.aim("leave");
    var v = f.view;
    f.view =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            v.stateRevision(),
            v.renderRevision(),
            v.localeTag(),
            v.speaker(),
            v.text(),
            List.of(
                v.choices().getFirst(),
                new DialogueView.ChoiceView(
                    "leave", "Leave", DialogueView.Availability.UNAVAILABLE, "CHOICE_UNAVAILABLE")),
            v.selectedChoiceId(),
            v.totalGraphemes(),
            v.revealedGraphemes(),
            v.hint(),
            v.notice(),
            v.pendingAction());
    f.targets =
        new TargetSnapshot(
            v.sessionId(),
            v.renderRevision(),
            v.nodeVisitId(),
            1,
            Map.of("ask", f.targets.choices().get("ask")));
    done(env, f.adapter.activate());
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    assertTrue(
        f.submitted.isEmpty(), "Clicking an unavailable card must not confirm another answer");
    done(env, f.adapter.closeAsync());
  }

  @Test
  void queuedConfirmFromPreviousPageIsRejectedEvenWhenCoreRevisionsStayEqual(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    var gate = new CompletableFuture<InputResult>();
    f.submitGate = gate;
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    var old = f.targets;
    f.targets =
        new TargetSnapshot(
            old.sessionId(),
            old.renderRevision(),
            old.nodeVisitId(),
            old.surfaceGeneration(),
            old.choices(),
            old.stateRevision(),
            old.navigation());
    f.submitGate = CompletableFuture.completedFuture(InputResult.ACCEPTED);
    gate.complete(InputResult.ACCEPTED);
    for (int n = 0; n < 10; n++) env.tick();
    assertEquals(1, f.submitted.size(), "An old issued frame cannot confirm the new viewport");
    f.now.set(800_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 2, 0, 0));
    assertEquals(2, f.submitted.size());
    assertInstanceOf(DialogueInput.Confirm.class, f.submitted.getLast());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void inputPreparationHasDeadlineAndOwnsLateCatcher(Env env) {
    var f = new Fixture(env);
    var clock = new MinestomDialogueScheduler(f.player.getInstance());
    var guard = new ConnectionGuard(f.player, 1, f.player.getInstance());
    var gate = new CompletableFuture<Void>();
    var budget =
        new RuntimeOptions.Budgets(
            Duration.ofSeconds(2),
            Duration.ofMillis(25),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1));
    var adapter =
        new MinestomInputAdapter(
            f.player,
            1,
            guard,
            InputProfile.defaults(),
            clock,
            new CancellationToken(),
            f,
            (a, b) -> true,
            budget,
            () ->
                PrivateInteractionCatcher.open(f.player, guard)
                    .thenCompose(c -> gate.thenApply(v -> c)));
    var active = adapter.activate();
    assertTrue(env.tickWhile(() -> !active.toCompletableFuture().isDone(), Duration.ofSeconds(1)));
    assertThrows(CompletionException.class, () -> active.toCompletableFuture().join());
    var close = adapter.closeAsync();
    env.tick();
    assertFalse(close.toCompletableFuture().isDone());
    gate.complete(null);
    done(env, close);
    assertTrue(
        f.player.getInstance().getEntities().stream()
            .noneMatch(e -> e.getEntityType() == EntityType.INTERACTION));
    done(env, f.adapter.closeAsync());
  }

  @Test
  void queuedScrollWaitsUntilTargetsCatchUpWithCoreView(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    var v = f.view;
    f.view =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            2,
            2,
            v.localeTag(),
            v.speaker(),
            v.text(),
            v.choices(),
            "leave",
            v.totalGraphemes(),
            v.revealedGraphemes(),
            v.hint(),
            v.notice(),
            v.pendingAction());
    f.packet(env, new ClientHeldItemChangePacket((short) 2));
    assertEquals(1, f.submitted.size());
    f.targets = new TargetSnapshot(v.sessionId(), 2, v.nodeVisitId(), 1, f.targets.choices());
    f.adapter.tick();
    assertTrue(env.tickWhile(() -> f.submitted.size() < 2, Duration.ofSeconds(1)));
    assertEquals("ask", ((DialogueInput.Select) f.submitted.getLast()).choiceId());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void closeRepairsPredictionOfQueuedCancelledPlacement(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    var gate = new CompletableFuture<InputResult>();
    f.submitGate = gate;
    f.now.set(400_000_000);
    var acks = f.connection.trackIncoming(AcknowledgeBlockChangePacket.class);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    f.packet(
        env,
        new ClientPlayerBlockPlacementPacket(
            PlayerHand.MAIN, new Vec(0, 41, 0), BlockFace.TOP, .5f, 1, .5f, false, false, 2));
    done(env, f.adapter.closeAsync());
    assertTrue(acks.collect().stream().anyMatch(p -> p.sequence() == 2));
    gate.complete(InputResult.ACCEPTED);
  }

  @Test
  void queuedScrollWaitsForPublishedCoreSelection(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    var gate = new CompletableFuture<InputResult>();
    f.submitGate = gate;
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    f.packet(env, new ClientHeldItemChangePacket((short) 2));
    assertEquals(1, f.submitted.size());
    var v = f.view;
    f.view =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            2,
            2,
            v.localeTag(),
            v.speaker(),
            v.text(),
            v.choices(),
            "leave",
            v.totalGraphemes(),
            v.revealedGraphemes(),
            v.hint(),
            v.notice(),
            v.pendingAction());
    f.targets = new TargetSnapshot(v.sessionId(), 2, v.nodeVisitId(), 1, f.targets.choices());
    f.submitGate = CompletableFuture.completedFuture(InputResult.ACCEPTED);
    gate.complete(InputResult.ACCEPTED);
    assertTrue(env.tickWhile(() -> f.submitted.size() < 2, Duration.ofSeconds(2)));
    assertEquals("ask", ((DialogueInput.Select) f.submitted.getLast()).choiceId());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void delayedUseInteractBurstKeepsArrivalTime(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    var gate = new CompletableFuture<InputResult>();
    f.submitGate = gate;
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    f.now.incrementAndGet();
    f.packet(env, new ClientInteractEntityPacket(1, PlayerHand.MAIN, Vec.ZERO, false));
    assertEquals(1, f.submitted.size());
    f.now.set(2_000_000_000);
    f.submitGate = CompletableFuture.completedFuture(InputResult.ACCEPTED);
    gate.complete(InputResult.ACCEPTED);
    for (int i = 0; i < 5; i++) env.tick();
    assertEquals(1, f.submitted.size());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void attackIsConfigurableAndOtherActionsCannotConfirm(Env env) {
    var f =
        new Fixture(
            env,
            new InputProfile(
                Set.of(InputProfile.Trigger.ATTACK),
                Set.of(InputProfile.Trigger.ATTACK),
                InputProfile.Trigger.ATTACK,
                true,
                Duration.ofMillis(350)));
    done(env, f.adapter.activate());
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.OFF, 1, 0, 0));
    assertTrue(f.submitted.isEmpty());
    int catcherId =
        f.player.getInstance().getEntities().stream()
            .filter(e -> e.getEntityType() == EntityType.INTERACTION)
            .findFirst()
            .orElseThrow()
            .getEntityId();
    f.packet(env, new ClientAttackPacket(catcherId));
    assertEquals(1, f.submitted.size());
    assertInstanceOf(DialogueInput.Confirm.class, f.submitted.getFirst());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void coveredHotbarDoesNotKeepSendingSelectionFences(Env env) {
    var f = new Fixture(env);
    f.hidden = true;
    done(env, f.adapter.activate());
    var pings =
        f.connection.trackIncoming(
            net.minestom.server.network.packet.server.common.PingPacket.class);
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    for (int i = 0; i < 5; i++) {
      f.adapter.tick();
      env.tick();
    }
    assertTrue(pings.collect().isEmpty());
    assertEquals(0, f.player.getHeldSlot());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void airCatcherConsumesNativeInteractionWithoutHandItems(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    var entities =
        f.player.getInstance().getEntities().stream()
            .filter(e -> e.getEntityType() == EntityType.INTERACTION)
            .toList();
    assertEquals(1, entities.size());
    var meta =
        (net.minestom.server.entity.metadata.other.InteractionMeta)
            entities.getFirst().getEntityMeta();
    assertTrue(meta.getResponse());
    assertTrue(f.player.getItemInMainHand().isAir());
    assertTrue(f.player.getItemInOffHand().isAir());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void emptyHandsAirUseIsCaptured(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    assertTrue(
        f.player.getInstance().getEntities().stream()
            .filter(e -> e.getEntityType() == EntityType.INTERACTION)
            .allMatch(e -> e.getViewers().equals(Set.of(f.player))));
    f.now.set(400_000_000);
    assertTrue(f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0)).isCancelled());
    assertEquals(1, f.submitted.size());
    assertInstanceOf(DialogueInput.Confirm.class, f.submitted.getFirst());
    f.packet(env, new ClientInteractEntityPacket(1, PlayerHand.MAIN, Vec.ZERO, false));
    assertEquals(1, f.submitted.size());
    done(env, f.adapter.closeAsync());
    assertTrue(
        f.player.getInstance().getEntities().stream()
            .noneMatch(e -> e.getEntityType() == EntityType.INTERACTION));
  }

  @Test
  void scrollWrapKeepsServerSlot(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    assertTrue(f.packet(env, new ClientHeldItemChangePacket((short) 8)).isCancelled());
    assertEquals(0, f.player.getHeldSlot());
    assertEquals("leave", ((DialogueInput.Select) f.submitted.getFirst()).choiceId());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void inventoryPredictionUsesCurrentState(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    f.player.setItemInMainHand(ItemStack.of(Material.DIAMOND));
    f.player.setItemInOffHand(ItemStack.of(Material.SHIELD));
    var stacks = f.player.getInventory().getItemStacks().clone();
    var ack = f.connection.trackIncoming(AcknowledgeBlockChangePacket.class);
    f.now.set(400_000_000);
    assertTrue(
        f.packet(
                env,
                new ClientPlayerBlockPlacementPacket(
                    PlayerHand.MAIN,
                    new Vec(0, 41, 0),
                    BlockFace.TOP,
                    .5f,
                    1,
                    .5f,
                    false,
                    false,
                    4))
            .isCancelled());
    assertTrue(
        f.packet(
                env,
                new ClientPlayerActionPacket(
                    ClientPlayerActionPacket.Status.DROP_ITEM, Vec.ZERO, BlockFace.TOP, 5))
            .isCancelled());
    assertTrue(
        f.packet(
                env,
                new ClientPlayerActionPacket(
                    ClientPlayerActionPacket.Status.SWAP_ITEM_HAND, Vec.ZERO, BlockFace.TOP, 6))
            .isCancelled());
    assertTrue(
        f.packet(
                env,
                new ClientPlayerActionPacket(
                    ClientPlayerActionPacket.Status.STARTED_DIGGING,
                    new Vec(0, 41, 0),
                    BlockFace.TOP,
                    7))
            .isCancelled());
    assertArrayEquals(stacks, f.player.getInventory().getItemStacks());
    assertEquals(0, f.player.getHeldSlot());
    assertFalse(ack.collect().isEmpty());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void stationaryHoverDoesNotUndoScroll(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    f.aim("ask");
    f.adapter.tick();
    env.tick();
    env.tick();
    int before = f.submitted.size();
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    assertEquals(before + 1, f.submitted.size());
    f.adapter.tick();
    env.tick();
    env.tick();
    assertEquals(before + 1, f.submitted.size());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void confirmingAfterWheelUsesManualSelectionInsteadOfStationaryHover(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    f.aim("ask");
    f.adapter.tick();
    env.tick();
    env.tick();
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    var v = f.view;
    f.view =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            2,
            v.renderRevision(),
            v.localeTag(),
            v.speaker(),
            v.text(),
            v.choices(),
            "leave",
            v.totalGraphemes(),
            v.revealedGraphemes(),
            v.hint(),
            v.notice(),
            v.pendingAction());
    f.targets =
        new TargetSnapshot(
            v.sessionId(), v.renderRevision(), v.nodeVisitId(), 1, f.targets.choices(), 2);
    f.adapter.tick();
    env.tick();
    env.tick();
    f.submitted.clear();
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    assertEquals(1, f.submitted.size());
    var confirm = assertInstanceOf(DialogueInput.Confirm.class, f.submitted.getFirst());
    assertEquals("leave", confirm.choiceId());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void deliberateGazeMovementRestoresHoverAfterWheelSelection(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    f.aim("ask");
    f.adapter.tick();
    env.tick();
    env.tick();
    f.packet(env, new ClientHeldItemChangePacket((short) 1));
    var v = f.view;
    f.view =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            2,
            v.renderRevision(),
            v.localeTag(),
            v.speaker(),
            v.text(),
            v.choices(),
            "leave",
            v.totalGraphemes(),
            v.revealedGraphemes(),
            v.hint(),
            v.notice(),
            v.pendingAction());
    f.targets =
        new TargetSnapshot(
            v.sessionId(), v.renderRevision(), v.nodeVisitId(), 1, f.targets.choices(), 2);
    f.submitted.clear();
    f.aim("leave");
    f.adapter.tick();
    env.tick();
    env.tick();
    f.aim("ask");
    f.adapter.tick();
    env.tick();
    env.tick();
    assertEquals(1, f.submitted.size());
    assertEquals(
        "ask", assertInstanceOf(DialogueInput.Select.class, f.submitted.getFirst()).choiceId());
    done(env, f.adapter.closeAsync());
  }

  @Test
  void changedHoverCannotConfirmReplacement(Env env) {
    var f = new Fixture(env);
    done(env, f.adapter.activate());
    f.aim("leave");
    f.now.set(400_000_000);
    f.packet(env, new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
    assertInstanceOf(DialogueInput.Select.class, f.submitted.getFirst());
    assertEquals("leave", ((DialogueInput.Select) f.submitted.getFirst()).choiceId());
    assertTrue(f.submitted.stream().noneMatch(i -> i instanceof DialogueInput.Confirm));
    done(env, f.adapter.closeAsync());
  }
}
