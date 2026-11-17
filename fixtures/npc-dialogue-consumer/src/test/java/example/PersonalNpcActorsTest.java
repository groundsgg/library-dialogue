package example;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.minestom.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.presentation.*;
import gg.grounds.npc.core.math.NpcTransform;
import gg.grounds.npc.core.math.NpcVector3;
import gg.grounds.npc.minestom.*;
import gg.grounds.npc.minestom.NpcHandle;
import gg.grounds.npc.minestom.NpcPrepareResult;
import gg.grounds.npc.minestom.NpcRenderer;
import gg.grounds.npc.minestom.NpcSpawnRequest;
import gg.grounds.npc.minestom.presentation.NpcPresentationChannel;
import gg.grounds.npc.minestom.presentation.NpcPresentationHandle;
import gg.grounds.npc.minestom.presentation.NpcPresentationOptions;
import gg.grounds.npc.minestom.presentation.NpcPresentationResult;
import gg.grounds.npc.minestom.presentation.NpcPresentationSnapshot;
import gg.grounds.npc.minestom.presentation.NpcPresentationStart;
import gg.grounds.npc.minestom.transform.NpcRenderTransform;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class PersonalNpcActorsTest {
  static <T> T done(Env env, CompletionStage<T> s) {
    assertTrue(env.tickWhile(() -> !s.toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    return s.toCompletableFuture().join();
  }

  static class Fixture {
    final Player a, b, observer;
    final NpcHandle parent;
    final IdentityHashMap<Player, Integer> sounds = new IdentityHashMap<>();
    final List<NpcPresentationHandle> prepared = new ArrayList<>();

    Fixture(Env env) {
      var world = env.createFlatInstance();
      a = env.createConnection().connect(world, new Pos(0, 40, 4, 180, 0));
      b = env.createConnection().connect(world, new Pos(0, 40, 4, 180, 0));
      observer = env.createConnection().connect(world, new Pos(0, 40, 4));
      parent =
          ((NpcPrepareResult.Ready)
                  done(
                      env,
                      NpcRenderer.prepare(
                              NpcSpawnRequest.builder(
                                      world,
                                      DemoNpcModel.model(),
                                      new NpcRenderTransform(
                                          new NpcTransform(new NpcVector3(0, 40, 0)), null))
                                  .build())
                          .ready()))
              .getHandle();
      done(env, parent.publish());
    }

    PersonalNpcActors actors(Player player) {
      return new PersonalNpcActors(
          player,
          Map.of("mechanic", parent),
          p ->
              NpcPresentationOptions.builder(p)
                  .publicationGuard(
                      operation -> {
                        operation.run();
                        return true;
                      })
                  .sounds((recipient, origin, sound) -> sounds.merge(recipient, 1, Integer::sum))
                  .build(),
          (npc, options) -> {
            var start = npc.preparePresentation(options);
            start
                .ready()
                .thenAccept(
                    r -> {
                      if (r instanceof NpcPresentationResult.Ready ready)
                        prepared.add(ready.getHandle());
                    });
            return start;
          });
    }

    ActorPort.ActorRequest request(Player p) {
      return new ActorPort.ActorRequest(
          UUID.randomUUID(),
          p.getUuid().toString(),
          1,
          Set.of("mechanic"),
          new CancellationToken(),
          System.nanoTime() + 5_000_000_000L);
    }

    List<Entity> parts() {
      return a.getInstance().getEntities().stream()
          .filter(e -> e.getEntityType() == EntityType.ITEM_DISPLAY)
          .toList();
    }
  }

  @Test
  void privateActorIsolationAndRestoration(Env env) {
    var f = new Fixture(env);
    var before = f.parts().stream().map(e -> Set.copyOf(e.getViewers())).toList();
    var a = done(env, f.actors(f.a).prepare(f.request(f.a)));
    var b = done(env, f.actors(f.b).prepare(f.request(f.b)));
    assertEquals(6, f.parts().size());
    done(env, a.activate());
    done(env, b.activate());
    done(env, a.cue(new ActorPort.ActorCue("mechanic", "wave", 1, "cue-a")));
    done(env, b.cue(new ActorPort.ActorCue("mechanic", "look", 1, "cue-b")));
    assertEquals(1, f.sounds.get(f.a));
    assertNull(f.sounds.get(f.b));
    assertNull(f.sounds.get(f.observer));
    assertEquals(NpcPresentationChannel.PRIVATE, f.prepared.getFirst().snapshot().getClipMode());
    assertEquals(
        NpcPresentationChannel.FOLLOW_SHARED, f.prepared.getLast().snapshot().getClipMode());
    assertEquals(NpcPresentationChannel.PRIVATE, f.prepared.getLast().snapshot().getHeadMode());
    assertEquals(2, f.parts().stream().filter(e -> e.getViewers().equals(Set.of(f.a))).count());
    assertEquals(2, f.parts().stream().filter(e -> e.getViewers().equals(Set.of(f.b))).count());
    done(env, a.closeAsync());
    done(env, b.closeAsync());
    assertEquals(2, f.parts().size());
    for (var part : f.parts()) assertEquals(before.getFirst(), part.getViewers());
    done(env, f.parent.applyHeadLook(0, 0));
    done(env, f.parent.closeAsync());
  }

  @Test
  void activationDoesNotReplayAcrossRenderers(Env env) {
    var f = new Fixture(env);
    var assets = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    var budgets =
        new RuntimeOptions.Budgets(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30));
    var host =
        new MinestomDialogueHost(
            new MinestomDialogueOptions(
                budgets,
                assets.profile(),
                assets,
                new LocalOutputChannels(
                    (p, c) -> CompletableFuture.completedFuture(null),
                    false,
                    (p, k) -> CompletableFuture.completedFuture(null)),
                UiLabelSource.defaults(),
                Duration.ofMillis(600)));
    var pack = UUID.randomUUID();
    host.packState().expect(f.a, 1, pack, assets.pin());
    host.packState().observe(f.a, 1, pack, assets.pin(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    var texts = new TextCatalog(Map.of("en", Map.of("hello", "Hello")));
    var content =
        new DialogueValidator()
            .validate(
                DialogueBuilder.dialogue("test:personal")
                    .node("start", new Message("mechanic", "hello", "wave", List.of(), "end"))
                    .node("end", new End("completed"))
                    .build(),
                texts,
                new HostCatalog(
                    "v1",
                    Set.of("mechanic"),
                    Map.of(),
                    Map.of(),
                    Set.of("wave"),
                    Set.of("actor.gesture"),
                    false),
                ValidationLimits.defaults())
            .validated();
    assertNotNull(content);
    var q =
        new MinestomDialogueRequest(
            "personal",
            f.a,
            1,
            content,
            new DialogueServices(
                "v1",
                Map.of(),
                Map.of(),
                null,
                TextResolver.catalogue(texts, "en"),
                Set.of("actor.gesture")),
            Map.of(),
            new LocaleContext("en", 0, "en"),
            new PresentationPreferences("hud", Duration.ZERO, false, Map.of()),
            Map.of(
                "mechanic",
                new MinestomSpeakerBinding(
                    "v1",
                    f.a.getInstance(),
                    () ->
                        new MinestomSpeakerBinding.AnchorSnapshot(
                            new Vec(0, 40, 0), 0, new Vec(0, 41.6, 0), true))),
            f.actors(f.a),
            InputProfile.defaults(),
            0);
    var ticket = host.begin(q);
    var handle = ((DialogueStartResult.Started) done(env, ticket.result())).handle();
    done(
        env,
        handle.updatePreferences(
            new PresentationPreferences("npc", Duration.ZERO, false, Map.of())));
    done(
        env,
        handle.updatePreferences(
            new PresentationPreferences("chat", Duration.ZERO, false, Map.of())));
    assertEquals(1, f.sounds.get(f.a));
    assertEquals(1, f.prepared.size());
    done(env, host.closeAsync());
    assertEquals(2, f.parts().size());
    done(env, f.parent.closeAsync());
  }

  @Test
  void personalCloseFailurePropagates(Env env) {
    var f = new Fixture(env);
    var failure = new IllegalStateException("personal close failed");
    var fake =
        new NpcPresentationHandle() {
          public CompletionStage<Void> publish() {
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> playClip(String id) {
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> stopClip() {
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> followSharedClip() {
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> applyHeadLook(double y, double p) {
            return CompletableFuture.completedFuture(null);
          }

          public CompletionStage<Void> followSharedHeadLook() {
            return CompletableFuture.completedFuture(null);
          }

          public NpcPresentationSnapshot snapshot() {
            throw new UnsupportedOperationException();
          }

          public CompletionStage<Void> closed() {
            return new CompletableFuture<>();
          }

          public CompletionStage<Void> closeAsync() {
            return CompletableFuture.failedFuture(failure);
          }
        };
    var actors =
        new PersonalNpcActors(
            f.a,
            Map.of("mechanic", f.parent),
            viewerConnection -> NpcPresentationOptions.builder(viewerConnection).build(),
            (parent, options) ->
                new NpcPresentationStart() {
                  public CompletionStage<NpcPresentationResult> ready() {
                    return CompletableFuture.completedFuture(new NpcPresentationResult.Ready(fake));
                  }

                  public CompletionStage<Void> cancel() {
                    return fake.closeAsync();
                  }
                });
    var handle = done(env, actors.prepare(f.request(f.a)));
    done(env, handle.activate());
    assertThrows(CompletionException.class, () -> done(env, handle.closeAsync()));
    assertEquals(2, f.parts().size());
    done(env, f.parent.closeAsync());
  }

  @Test
  void cancellationDuringPreparationOwnsReturnedStart(Env env) {
    var f = new Fixture(env);
    var request = f.request(f.a);
    var actors =
        new PersonalNpcActors(
            f.a,
            Map.of("mechanic", f.parent),
            viewerConnection -> NpcPresentationOptions.builder(viewerConnection).build(),
            (parent, options) -> {
              var start = parent.preparePresentation(options);
              request.cancellation().cancel(DialogueEndReason.CANCELLED);
              return start;
            });
    var result = actors.prepare(request);
    assertThrows(CompletionException.class, () -> done(env, result));
    assertEquals(2, f.parts().size());
    done(env, f.parent.closeAsync());
  }

  @Test
  void unexpectedPersonalExpiryCancelsActorRequest(Env env) {
    var f = new Fixture(env);
    var request = f.request(f.a);
    var handle = done(env, f.actors(f.a).prepare(request));
    done(env, handle.activate());
    done(env, f.prepared.getFirst().closeAsync());
    assertTrue(request.cancellation().isCancelled());
    assertEquals(DialogueEndReason.HOST_CHANGED, request.cancellation().reason());
    done(env, handle.closeAsync());
    assertEquals(2, f.parts().size());
    done(env, f.parent.closeAsync());
  }

  @Test
  void publishedArtifactsAreUsed() {
    for (var type : List.of(DialogueRuntime.class, MinestomDialogueHost.class, NpcRenderer.class)) {
      var url = type.getProtectionDomain().getCodeSource().getLocation().toString();
      assertTrue(url.endsWith(".jar"), url);
      assertFalse(url.contains("build/classes"));
    }
  }
}
