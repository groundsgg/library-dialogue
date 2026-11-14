package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class MinestomDialogueHostTest {
  static class Surface implements DialogueRenderer.PresentationHandle {
    int frames, closes;
    CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);

    public CompletionStage<Void> replace(DialogueView view) {
      frames++;
      return CompletableFuture.completedFuture(null);
    }

    public CompletionStage<Void> reconfigure(PresentationPreferences p) {
      return CompletableFuture.completedFuture(null);
    }

    public CompletionStage<Void> closeAsync() {
      closes++;
      return cleanup;
    }
  }

  static class Fixture {
    final Player player;
    final ValidatedDialogue content;
    final DialogueServices services;
    final MinestomDialogueOptions options;
    final AtomicInteger prepares = new AtomicInteger();
    final List<Surface> surfaces = new ArrayList<>();
    CompletableFuture<DialogueRenderer.PresentationHandle> prepareGate;
    final MinestomDialogueHost host;

    Fixture(Env env) {
      player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
      var texts = new TextCatalog(Map.of("en", Map.of("line", "Hello")));
      var d =
          DialogueBuilder.dialogue("test:minestom")
              .node("start", new Message("npc", "line", null, List.of(), "end"))
              .node("end", new End("completed"))
              .build();
      content =
          new DialogueValidator()
              .validate(
                  d,
                  texts,
                  new HostCatalog(
                      "v1", Set.of("npc"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                  ValidationLimits.defaults())
              .validated();
      services =
          new DialogueServices(
              "v1", Map.of(), Map.of(), null, TextResolver.catalogue(texts, "en"), Set.of());
      var budgets =
          new RuntimeOptions.Budgets(
              Duration.ofSeconds(2),
              Duration.ofSeconds(5),
              Duration.ofSeconds(10),
              Duration.ofSeconds(30));
      options =
          new MinestomDialogueOptions(
              budgets,
              MinecraftPackProfile.public262(),
              AssetBundle.loadDefault(MinecraftPackProfile.public262()),
              new LocalOutputChannels(
                  (p, c) -> CompletableFuture.completedFuture(null),
                  false,
                  (p, e) -> CompletableFuture.completedFuture(null)),
              UiLabelSource.defaults(),
              Duration.ofMillis(600));
      host =
          new MinestomDialogueHost(
              options,
              (request, guard, status) ->
                  r -> {
                    prepares.incrementAndGet();
                    var s = new Surface();
                    surfaces.add(s);
                    return prepareGate != null ? prepareGate : CompletableFuture.completedFuture(s);
                  });
    }

    MinestomDialogueRequest request(String id, String binding) {
      return new MinestomDialogueRequest(
          id,
          player,
          1,
          content,
          new DialogueServices(
              binding,
              services.conditions(),
              services.actions(),
              services.outcomes(),
              services.texts(),
              services.capabilities()),
          Map.of(),
          new LocaleContext("en", 0, "en"),
          new PresentationPreferences("hud", Duration.ZERO, false, Map.of()),
          Map.of(
              "npc",
              new MinestomSpeakerBinding(
                  "anchor-1",
                  player.getInstance(),
                  () ->
                      new MinestomSpeakerBinding.AnchorSnapshot(
                          player.getPosition(), 0, player.getPosition().add(0, 1.6, 0), true))),
          null,
          InputProfile.defaults(),
          0);
    }
  }

  static <T> T done(Env env, CompletionStage<T> stage) {
    assertTrue(env.tickWhile(() -> !stage.toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    return stage.toCompletableFuture().join();
  }

  @Test
  void noInstanceAtAdmissionRejectsRatherThanHangs(Env env) {
    var f = new Fixture(env);
    var request = f.request("departed", "v1");
    f.player.remove();
    var ticket = f.host.begin(request);
    assertEquals(
        "HOST_CHANGED", ((DialogueStartResult.Rejected) done(env, ticket.result())).code());
    assertEquals(0, f.prepares.get());
    done(env, f.host.closeAsync());
  }

  @Test
  void admissionWorldCannotChangeWhilePreviousCleanupWaits(Env env) {
    var f = new Fixture(env);
    done(env, f.host.begin(f.request("first-world", "v1")).result());
    var gate = new CompletableFuture<Void>();
    f.surfaces.getFirst().cleanup = gate;
    var next = f.host.begin(f.request("queued-world", "v1"));
    f.player.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
    gate.complete(null);
    assertEquals("HOST_CHANGED", ((DialogueStartResult.Rejected) done(env, next.result())).code());
    assertEquals(1, f.prepares.get(), "No new preparation or actions in another world");
    done(env, f.host.closeAsync());
  }

  @Test
  void duplicatePendingRequestReturnsSameTicket(Env env) {
    var f = new Fixture(env);
    f.prepareGate = new CompletableFuture<>();
    var q = f.request("same", "v1");
    var a = f.host.begin(q);
    assertSame(a, f.host.begin(q));
    env.tick();
    env.tick();
    assertEquals(1, f.prepares.get());
    var close = a.cancel(DialogueEndReason.CANCELLED);
    f.prepareGate.complete(f.surfaces.getFirst());
    done(env, close);
    assertEquals(1, f.surfaces.getFirst().closes);
    done(env, f.host.closeAsync());
  }

  @Test
  void changedBindingGenerationConflicts(Env env) {
    var f = new Fixture(env);
    var a = f.host.begin(f.request("conflict", "v1"));
    var other = f.host.begin(f.request("conflict", "v2"));
    assertEquals(
        "START_REQUEST_CONFLICT",
        ((DialogueStartResult.Rejected) done(env, other.result())).code());
    assertInstanceOf(DialogueStartResult.Started.class, done(env, a.result()));
    done(env, f.host.closeAsync());
  }

  @Test
  void pendingCancelCleansLateRenderer(Env env) {
    var f = new Fixture(env);
    f.prepareGate = new CompletableFuture<>();
    var a = f.host.begin(f.request("late", "v1"));
    assertTrue(env.tickWhile(() -> f.prepares.get() == 0, Duration.ofSeconds(2)));
    var close = a.cancel(DialogueEndReason.DISCONNECTED);
    env.tick();
    assertFalse(close.toCompletableFuture().isDone());
    f.prepareGate.complete(f.surfaces.getFirst());
    done(env, close);
    assertEquals("DISCONNECTED", ((DialogueStartResult.Rejected) done(env, a.result())).code());
    assertEquals(1, f.surfaces.getFirst().closes);
    done(env, f.host.closeAsync());
  }

  @Test
  void nextStartWaitsPreviousCleanup(Env env) {
    var f = new Fixture(env);
    var first = f.host.begin(f.request("first", "v1"));
    done(env, first.result());
    var gate = new CompletableFuture<Void>();
    f.surfaces.getFirst().cleanup = gate;
    var next = f.host.begin(f.request("next", "v1"));
    env.tick();
    env.tick();
    assertEquals(1, f.prepares.get());
    assertFalse(next.result().toCompletableFuture().isDone());
    gate.complete(null);
    assertInstanceOf(DialogueStartResult.Started.class, done(env, next.result()));
    assertEquals(2, f.prepares.get());
    done(env, f.host.closeAsync());
  }

  @Test
  void disconnectAndInstanceChangeInvalidate(Env env) {
    var f = new Fixture(env);
    var a = f.host.begin(f.request("disconnect", "v1"));
    var h = ((DialogueStartResult.Started) done(env, a.result())).handle();
    EventDispatcher.call(new PlayerDisconnectEvent(f.player));
    assertEquals(DialogueEndReason.DISCONNECTED, done(env, h.completion()).reason());
    var b = f.host.begin(f.request("world", "v1"));
    var bh = ((DialogueStartResult.Started) done(env, b.result())).handle();
    f.player.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
    assertEquals(DialogueEndReason.HOST_CHANGED, done(env, bh.completion()).reason());
    done(env, f.host.closeAsync());
  }

  @Test
  void startAndShutdownObserversCannotCancelOwnedOperations(Env env) {
    var f = new Fixture(env);
    var a = f.host.begin(f.request("observer", "v1"));
    a.result().toCompletableFuture().cancel(false);
    assertInstanceOf(DialogueStartResult.Started.class, done(env, a.result()));
    var close = f.host.closeAsync();
    close.toCompletableFuture().cancel(false);
    done(env, f.host.closeAsync());
  }
}
