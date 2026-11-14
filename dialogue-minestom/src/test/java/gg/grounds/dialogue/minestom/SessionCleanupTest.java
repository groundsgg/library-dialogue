package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerResourcePackStatusEvent;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class SessionCleanupTest {
  static <T> T done(Env env, CompletionStage<T> s) {
    return MinestomDialogueHostTest.done(env, s);
  }

  static class Fixture {
    final MinestomDialogueHostTest.Fixture base;
    final MinestomDialogueHost host;
    final Entity borrowed = new Entity(EntityType.ARMOR_STAND);
    final AtomicBoolean valid = new AtomicBoolean(true);
    final MinestomSpeakerBinding binding;
    final UUID pack = UUID.randomUUID();

    Fixture(Env env) {
      base = new MinestomDialogueHostTest.Fixture(env);
      done(env, base.host.closeAsync());
      borrowed.setInstance(base.player.getInstance(), new Pos(0, 42, 3, 180, 0)).join();
      binding =
          new MinestomSpeakerBinding(
              "npc1",
              borrowed.getInstance(),
              () ->
                  new MinestomSpeakerBinding.AnchorSnapshot(
                      borrowed.getPosition(),
                      borrowed.getPosition().yaw(),
                      borrowed.getPosition().add(0, 1.6, 0),
                      valid.get() && !borrowed.isRemoved()));
      host = new MinestomDialogueHost(base.options);
      host.packState().expect(base.player, 1, pack, base.options.assets().pin());
      host.packState()
          .observe(
              base.player,
              1,
              pack,
              base.options.assets().pin(),
              ResourcePackStatus.SUCCESSFULLY_LOADED);
    }

    MinestomDialogueRequest request(
        ValidatedDialogue content, DialogueServices services, ActorPort actors, String renderer) {
      var q = base.request("lifecycle", "v1");
      return new MinestomDialogueRequest(
          q.requestId(),
          q.player(),
          q.hostGeneration(),
          content,
          services,
          q.context(),
          q.locale(),
          new PresentationPreferences(renderer, Duration.ZERO, false, Map.of()),
          Map.of("npc", binding),
          actors,
          q.controls(),
          0);
    }

    long ui() {
      return base.player.getInstance().getEntities().stream()
          .filter(
              e ->
                  e.getEntityType() == EntityType.TEXT_DISPLAY
                      || e.getEntityType() == EntityType.INTERACTION)
          .count();
    }
  }

  @Test
  void invalidAnchorClosesImmediately(Env env) {
    var f = new Fixture(env);
    var t = f.host.begin(f.request(f.base.content, f.base.services, null, "npc"));
    var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
    assertTrue(f.ui() > 0);
    f.valid.set(false);
    assertEquals(DialogueEndReason.HOST_CHANGED, done(env, h.completion()).reason());
    done(env, t.cancel(DialogueEndReason.CANCELLED));
    assertEquals(0, f.ui());
    assertFalse(f.borrowed.isRemoved());
    done(env, f.host.closeAsync());
  }

  @Test
  void cleanupDrainsAllOwnedResources(Env env) {
    var f = new Fixture(env);
    var gate = new CompletableFuture<Void>();
    var closes = new AtomicInteger();
    ActorPort actors =
        q ->
            CompletableFuture.completedFuture(
                new ActorPort.ActorHandle() {
                  public CompletionStage<Void> cue(ActorPort.ActorCue c) {
                    return CompletableFuture.completedFuture(null);
                  }

                  public CompletionStage<Void> closeAsync() {
                    closes.incrementAndGet();
                    return gate;
                  }
                });
    var t = f.host.begin(f.request(f.base.content, f.base.services, actors, "npc"));
    done(env, t.result());
    var close = f.host.closeAsync();
    env.tick();
    env.tick();
    assertFalse(close.toCompletableFuture().isDone());
    assertEquals(1, closes.get());
    gate.complete(null);
    done(env, close);
    assertEquals(0, f.ui());
    assertFalse(f.borrowed.isRemoved());
    assertTrue(f.base.player.isOnline());
  }

  @Test
  void instanceChangeAndLatePackStatusCannotResurrect(Env env) {
    var f = new Fixture(env);
    var oldWorld = f.base.player.getInstance();
    var t = f.host.begin(f.request(f.base.content, f.base.services, null, "npc"));
    var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
    f.base.player.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
    EventDispatcher.call(
        new PlayerResourcePackStatusEvent(
            f.base.player, f.pack, ResourcePackStatus.SUCCESSFULLY_LOADED));
    assertEquals(DialogueEndReason.HOST_CHANGED, done(env, h.completion()).reason());
    done(env, f.host.closeAsync());
    assertTrue(
        oldWorld.getEntities().stream()
            .noneMatch(
                e ->
                    e.getEntityType() == EntityType.TEXT_DISPLAY
                        || e.getEntityType() == EntityType.INTERACTION));
    assertFalse(f.borrowed.isRemoved());
  }

  static record ActionContent(
      ValidatedDialogue content,
      DialogueServices services,
      AtomicInteger executions,
      CompletableFuture<ActionHandler.ActionResult> gate) {}

  static ActionContent content(boolean actionEntry) {
    var texts = new TextCatalog(Map.of("en", Map.of("line", "Hello", "after", "Done")));
    var builder =
        DialogueBuilder.dialogue("test:action-wait")
            .node("start", new Message("npc", "line", null, List.of(), "wait"))
            .node("wait", new Action("test:wait", Map.of(), "after", "after", "after", "after"))
            .node("after", new Message("npc", "after", null, List.of(), "end"))
            .node("end", new End("completed"));
    if (actionEntry) builder.entry("wait");
    var catalog =
        new HostCatalog(
            "v1",
            Set.of("npc"),
            Map.of(),
            Map.of("test:wait", new HostCatalog.ProviderSpec(Map.of(), false)),
            Set.of(),
            Set.of(),
            false);
    var validated =
        new DialogueValidator()
            .validate(builder.build(), texts, catalog, ValidationLimits.defaults())
            .validated();
    assertNotNull(validated);
    var executions = new AtomicInteger();
    var gate = new CompletableFuture<ActionHandler.ActionResult>();
    var handler =
        new ActionHandler() {
          public CompletionStage<ActionResult> execute(ActionRequest r) {
            executions.incrementAndGet();
            return gate;
          }

          public CompletionStage<ActionStatus> reconcile(ActionRef r) {
            return CompletableFuture.completedFuture(ActionStatus.PENDING);
          }
        };
    return new ActionContent(
        validated,
        new DialogueServices(
            "v1",
            Map.of(),
            Map.of("test:wait", handler),
            null,
            TextResolver.catalogue(texts, "en"),
            Set.of()),
        executions,
        gate);
  }

  @Test
  void npcKeepsBorrowedAnchorAcrossActionWait(Env env) {
    var f = new Fixture(env);
    var action = content(false);
    var t = f.host.begin(f.request(action.content(), action.services(), null, "npc"));
    var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
    var v = h.snapshot();
    done(
        env,
        h.submit(
            new DialogueInput.Confirm(
                new DialogueInput.InputStamp(v.sessionId(), v.renderRevision(), 1), null)));
    for (int i = 0; i < 12; i++) env.tick();
    assertFalse(h.completion().toCompletableFuture().isDone());
    assertEquals(1, action.executions().get());
    assertEquals("npc", t.status().effectiveSurface());
    action.gate().complete(ActionHandler.ActionResult.SUCCESS);
    assertTrue(env.tickWhile(() -> !h.snapshot().nodeId().equals("after"), Duration.ofSeconds(2)));
    done(env, f.host.closeAsync());
  }

  @Test
  void invalidAnchorBeforeEntryActionDoesNotDispatch(Env env) {
    var f = new Fixture(env);
    var action = content(true);
    f.valid.set(false);
    var t = f.host.begin(f.request(action.content(), action.services(), null, "hud"));
    assertInstanceOf(DialogueStartResult.Rejected.class, done(env, t.result()));
    assertEquals(0, action.executions().get());
    done(env, f.host.closeAsync());
  }
}
