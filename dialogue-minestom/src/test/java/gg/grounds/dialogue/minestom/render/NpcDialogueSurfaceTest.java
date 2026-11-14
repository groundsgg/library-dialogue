package gg.grounds.dialogue.minestom.render;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.*;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.entity.metadata.display.*;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class NpcDialogueSurfaceTest {
  static <T> T done(Env env, CompletionStage<T> s) {
    assertTrue(env.tickWhile(() -> !s.toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    return s.toCompletableFuture().join();
  }

  static class Fixture {
    final Player a, b;
    final SurfaceInputTargets targets = new SurfaceInputTargets();
    final java.util.concurrent.atomic.AtomicReference<Vec> feet =
        new java.util.concurrent.atomic.AtomicReference<>(new Vec(0, 42, 0));
    final NpcDialogueSurface surface;

    Fixture(Env env, CompletableFuture<Void> gate) {
      var instance = env.createFlatInstance();
      a = env.createConnection().connect(instance, new Pos(0, 42, 4, 180, 0));
      b = env.createConnection().connect(instance, new Pos(0, 42, 4));
      var packs = new PackStateTracker();
      var id = UUID.randomUUID();
      packs.expect(a, 1, id, RenderFixtures.ASSETS.pin());
      packs.observe(a, 1, id, RenderFixtures.ASSETS.pin(), ResourcePackStatus.SUCCESSFULLY_LOADED);
      var binding =
          new MinestomSpeakerBinding(
              "npc1",
              instance,
              () ->
                  new MinestomSpeakerBinding.AnchorSnapshot(
                      feet.get(), 0, feet.get().add(0, 1.6, 0), true));
      surface =
          new NpcDialogueSurface(
              a,
              1,
              new ConnectionGuard(a, 1, instance),
              packs,
              RenderFixtures.ASSETS,
              new LocalOutputChannels(
                  (p, c) -> CompletableFuture.completedFuture(null),
                  false,
                  (p, k) -> CompletableFuture.completedFuture(null)),
              targets,
              new MinestomDialogueScheduler(instance),
              v -> binding,
              ignored -> {},
              (entity, world, pos) -> entity.setInstance(world, pos).thenCompose(v -> gate));
    }

    DialogueSurface.SurfaceHandle open(Env env) {
      return done(
          env,
          surface.open(
              new DialogueSurface.SurfaceContext(
                  RenderFixtures.SESSION,
                  a.getUuid().toString(),
                  1,
                  "npc",
                  "profile",
                  new CancellationToken(),
                  1,
                  Long.MAX_VALUE,
                  new PresentationPreferences("npc", Duration.ZERO, false, Map.of()))));
    }

    List<Entity> displays() {
      return a.getInstance().getEntities().stream()
          .filter(e -> e.getEntityType() == EntityType.TEXT_DISPLAY)
          .toList();
    }
  }

  @Test
  void backdropIsBundledForBothProfiles() {
    assertTrue(RenderFixtures.ASSETS.hasEntry("assets/dialogue/font/focus.json"));
    assertTrue(
        AssetBundle.loadDefault(MinecraftPackProfile.grounds263())
            .hasEntry("assets/dialogue/textures/font/npc-focus.png"));
  }

  @Test
  void allFrontBackPairsClose(Env env) {
    var f = new Fixture(env, CompletableFuture.completedFuture(null));
    var h = f.open(env);
    var frame = RenderFixtures.frame("npc", "Hello", 5);
    done(env, h.replace(frame));
    assertEquals(frame.widgets().size() * 2, f.displays().size());
    assertTrue(f.displays().size() <= 32);
    for (var e : f.displays()) {
      assertEquals(Set.of(f.a), e.getViewers());
      assertFalse(e.getViewers().contains(f.b));
      assertEquals(
          AbstractDisplayMeta.BillboardConstraints.FIXED,
          ((TextDisplayMeta) e.getEntityMeta()).getBillboardRenderConstraints());
    }
    assertEquals(2, f.targets.current().choices().size());
    done(env, h.closeAsync());
    assertTrue(f.displays().isEmpty());
    assertNull(f.targets.current());
  }

  @Test
  void sevenChoicesUseThreeCardsAndNativeOriginMatchesHitPlane(Env env) {
    var f = new Fixture(env, CompletableFuture.completedFuture(null));
    var h = f.open(env);
    var v = RenderFixtures.view("Hello", 5);
    var choices = new ArrayList<DialogueView.ChoiceView>();
    for (int n = 0; n < 7; n++)
      choices.add(
          new DialogueView.ChoiceView(
              "c" + n, "Choice " + n, DialogueView.Availability.AVAILABLE, ""));
    var next =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            v.state(),
            2,
            2,
            "en",
            "NPC",
            "Hello",
            choices,
            "c4",
            5,
            5,
            DialogueView.HintPhase.CHOOSE,
            "",
            null);
    var presenter =
        new DialoguePresenter(
            Map.of(
                "npc",
                new DialoguePresenter.SurfaceProfile(
                    f.surface,
                    LayoutProfile.npc(),
                    DialogueTheme.neutral(),
                    RenderFixtures.ASSETS.metrics(),
                    false)),
            new MinestomDialogueScheduler(f.a.getInstance()));
    var frame =
        presenter.compose(
            next, "npc", new PresentationPreferences("npc", Duration.ZERO, false, Map.of()));
    done(env, h.replace(frame));
    assertEquals(Set.of("c3", "c4", "c5"), f.targets.current().choices().keySet());
    var front =
        f.displays().stream()
            .filter(
                e ->
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText()
                        .serialize(((TextDisplayMeta) e.getEntityMeta()).getText())
                        .contains("Hello"))
            .findFirst()
            .orElseThrow();
    var meta = (TextDisplayMeta) front.getEntityMeta();
    var transform = f.surface.geometry().transform();
    double unit = .025 * meta.getScale().x();
    var local = meta.getTranslation();
    // Actual client's one-line centering and bitmap bearing, independently of surface placement.
    var origin =
        front
            .getPosition()
            .add(transform.right().mul(local.x() + (1 - 320 / 2d) * unit))
            .add(0, local.y() + (9 - 7) * unit, 0);
    assertEquals(0, origin.distance(transform.widgetToWorld(0, 0)), 1e-6);
    var selected =
        frame.widgets().stream().filter(w -> "c4".equals(w.choiceId())).findFirst().orElseThrow();
    assertEquals(1.08, selected.visualScale());
    assertEquals(selected.bounds(), f.targets.current().choices().get("c4"));
    done(env, h.closeAsync());
  }

  @Test
  void movedAnchorIsAppliedBeforeFrameAcknowledgement(Env env) {
    var f = new Fixture(env, CompletableFuture.completedFuture(null));
    var h = f.open(env);
    var frame = RenderFixtures.frame("npc", "Hello", 5);
    done(env, h.replace(frame));
    f.feet.set(new Vec(4, 42, 0));
    done(env, h.replace(frame));
    for (var display : f.displays()) assertEquals(4, display.getPosition().x(), 1e-6);
    done(env, h.closeAsync());
  }

  @Test
  void lateSpawnIsRemoved(Env env) {
    var gate = new CompletableFuture<Void>();
    var f = new Fixture(env, gate);
    var h = f.open(env);
    var write = h.replace(RenderFixtures.frame("npc", "Hello", 5));
    assertTrue(env.tickWhile(() -> f.displays().isEmpty(), Duration.ofSeconds(2)));
    assertTrue(f.displays().stream().allMatch(e -> e.getViewers().isEmpty()));
    var close = h.closeAsync();
    env.tick();
    assertFalse(close.toCompletableFuture().isDone());
    gate.complete(null);
    done(env, close);
    assertTrue(f.displays().isEmpty());
    assertTrue(write.toCompletableFuture().isCompletedExceptionally());
  }
}
