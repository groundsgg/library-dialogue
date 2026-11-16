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
  void supersedingPendingSlotsReusesTheirPreparationAndOwnsRetirement(Env env) {
    for (boolean removeChoices : List.of(false, true)) {
      var gate = new CompletableFuture<Void>();
      var f = new Fixture(env, gate);
      var h = f.open(env);
      var first = RenderFixtures.frame("npc", "Hello", 5);
      var older = h.replace(first);
      assertTrue(
          env.tickWhile(
              () -> f.displays().size() < first.widgets().size() * 2, Duration.ofSeconds(2)));
      var updated = RenderFixtures.frame("npc", "Updated", 7);
      if (removeChoices)
        updated =
            new WidgetFrame(
                updated.semantic(),
                updated.widgets().stream()
                    .filter(w -> w.kind() != WidgetFrame.Kind.CHOICE)
                    .toList(),
                updated.fontPin(),
                updated.theme());
      var newer = h.replace(updated);
      for (int n = 0; n < 10; n++) env.tick();
      assertFalse(newer.toCompletableFuture().isDone(), "Await adopted slot preparation");
      assertEquals(
          first.widgets().size() * 2, f.displays().size(), "No duplicate pending displays");
      gate.complete(null);
      done(env, newer);
      assertTrue(older.toCompletableFuture().isCompletedExceptionally());
      assertEquals(
          updated.widgets().size() * 2, f.displays().size(), "Retired slots fully removed");
      assertTrue(
          f.displays().stream()
              .map(e -> (TextDisplayMeta) e.getEntityMeta())
              .anyMatch(
                  m ->
                      net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                          .plainText()
                          .serialize(m.getText())
                          .contains("Updated")));
      done(env, h.closeAsync());
      assertTrue(f.displays().isEmpty());
    }
  }

  @Test
  void retiringCandidateRestoresRetainedGeometry(Env env) {
    var f = new Fixture(env, CompletableFuture.completedFuture(null));
    var old = f.open(env);
    done(env, old.replace(RenderFixtures.frame("npc", "Hello", 5)));
    var candidate =
        done(
            env,
            f.surface.open(
                new DialogueSurface.SurfaceContext(
                    RenderFixtures.SESSION,
                    f.a.getUuid().toString(),
                    1,
                    "npc",
                    "profile",
                    new CancellationToken(),
                    2,
                    Long.MAX_VALUE,
                    new PresentationPreferences("npc", Duration.ZERO, false, Map.of()))));
    done(env, candidate.replace(RenderFixtures.frame("npc", "Hello", 5)));
    done(env, candidate.closeAsync());
    assertNotNull(f.surface.geometry());
    assertEquals(1, f.surface.geometry().surfaceGeneration());
    done(env, old.closeAsync());
  }

  @Test
  void overlappingPendingHandlesShareOneConnectionDisplayBudget(Env env) {
    var gate = new CompletableFuture<Void>();
    var f = new Fixture(env, gate);
    var handles = new ArrayList<DialogueSurface.SurfaceHandle>();
    for (int n = 0; n < 4; n++) {
      var h = f.open(env);
      handles.add(h);
      h.replace(RenderFixtures.frame("npc", "Hello", 5));
      for (int k = 0; k < 10; k++) env.tick();
      assertTrue(
          f.displays().size() <= 32, "Pending spawns count against the per-connection budget");
    }
    gate.complete(null);
    for (var h : handles) done(env, h.closeAsync());
    assertTrue(f.displays().isEmpty());
    var h = f.open(env);
    done(env, h.replace(RenderFixtures.frame("npc", "Hello", 5)));
    assertFalse(f.displays().isEmpty());
    done(env, h.closeAsync());
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
    assertNotNull(frame.scrollbar());
    var bar =
        new MinecraftWidgetEncoder()
            .encode(frame, RenderFixtures.ASSETS).widgets().stream()
                .filter(w -> w.kind() == WidgetFrame.Kind.SCROLLBAR)
                .findFirst()
                .orElseThrow();
    var barEntity =
        f.displays().stream()
            .filter(
                e -> {
                  var m = (TextDisplayMeta) e.getEntityMeta();
                  return m.getText().equals(bar.front()) && m.getLeftRotation()[1] < 0;
                })
            .findFirst()
            .orElseThrow();
    var bm = (TextDisplayMeta) barEntity.getEntityMeta();
    double bu = .025 * bm.getScale().x();
    var bc =
        rotate(
            new Vec((1 - bar.bounds().width() / 2) * bu, (2 + bar.paintBaseline()) * bu, 0),
            bm.getLeftRotation());
    var bl = bm.getTranslation().add(bc);
    var bp =
        barEntity
            .getPosition()
            .add(transform.right().mul(bl.x()))
            .add(0, bl.y(), 0)
            .add(transform.normal().mul(bl.z()));
    assertEquals(
        0,
        bp.distance(
            transform.planeFor(bar.bounds()).widgetToWorld(bar.bounds().x(), bar.bounds().y())),
        1e-6);
    var backEntity =
        f.displays().stream()
            .filter(
                e -> {
                  var m = (TextDisplayMeta) e.getEntityMeta();
                  return m.getText().equals(bar.back()) && m.getLeftRotation()[1] > 0;
                })
            .findFirst()
            .orElseThrow();
    var backMeta = (TextDisplayMeta) backEntity.getEntityMeta();
    double backUnit = .025 * backMeta.getScale().x();
    double backInk = MinecraftWidgetEncoderTest.barOffset(bar.back());
    var track = frame.scrollbar().track();
    var localBack =
        backMeta
            .getTranslation()
            .add(
                rotate(
                    new Vec(
                        (1 - bar.bounds().width() / 2 + backInk + track.width()) * backUnit,
                        (2 + bar.paintBaseline() - (track.y() - bar.bounds().y())) * backUnit,
                        0),
                    backMeta.getLeftRotation()));
    var actualBack =
        backEntity
            .getPosition()
            .add(transform.right().mul(localBack.x()))
            .add(0, localBack.y(), 0)
            .add(transform.normal().mul(localBack.z()));
    var plane = transform.planeFor(bar.bounds());
    var expectedBack = plane.widgetToWorld(track.x(), track.y()).sub(plane.normal().mul(.012));
    assertEquals(
        0,
        actualBack.distance(expectedBack),
        1e-6,
        "Both indicator faces must occupy the same folded track, separated only by back depth");
    done(env, h.closeAsync());
    assertTrue(f.displays().isEmpty());
  }

  private static Vec rotate(Vec v, float[] q) {
    var axis = new Vec(q[0], q[1], q[2]);
    var cross = axis.cross(v).mul(2);
    return v.add(cross.mul(q[3])).add(axis.cross(cross));
  }

  @Test
  void foldedDisplayCornersBackDepthAndHitPlaneAgreeAndLiveGapDoesNotInterpolate(Env env) {
    var f = new Fixture(env, CompletableFuture.completedFuture(null));
    var h = f.open(env);
    var prefs =
        new PresentationPreferences("npc", Duration.ZERO, false, Map.of("npcChoiceAngle", "30"));
    done(env, h.configure(prefs));
    var frame = RenderFixtures.frame("npc", "Hello", 5);
    done(env, h.replace(frame));
    var w = frame.widgets().stream().filter(WidgetFrame.Widget::selected).findFirst().orElseThrow();
    var entity =
        f.displays().stream()
            .filter(
                e ->
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText()
                        .serialize(((TextDisplayMeta) e.getEntityMeta()).getText())
                        .contains("Ask"))
            .findFirst()
            .orElseThrow();
    var meta = (TextDisplayMeta) entity.getEntityMeta();
    var transform = f.surface.geometry().transform();
    var plane = transform.planeFor(w.bounds());
    double unit = .025 * meta.getScale().x();
    var nativeCorner =
        rotate(new Vec((1 - w.bounds().width() / 2) * unit, 2 * unit, 0), meta.getLeftRotation());
    var local = meta.getTranslation().add(nativeCorner);
    var actual =
        entity
            .getPosition()
            .add(transform.right().mul(local.x()))
            .add(0, local.y(), 0)
            .add(transform.normal().mul(local.z()));
    var expected =
        plane.widgetToWorld(
            w.bounds().x() - w.bounds().width() * (w.visualScale() - 1) / 2,
            w.bounds().y() - w.bounds().height() * (w.visualScale() - 1) / 2);
    assertEquals(0, actual.distance(expected), 1e-6);
    var frontNormal = rotate(new Vec(0, 0, 1), meta.getLeftRotation());
    assertEquals(0, frontNormal.distance(plane.normal()), 1e-6);
    var encodedBack =
        new MinecraftWidgetEncoder()
            .encode(frame, RenderFixtures.ASSETS).widgets().stream()
                .filter(e -> e.id().equals(w.id()))
                .findFirst()
                .orElseThrow()
                .back();
    var back =
        f.displays().stream()
            .map(e -> (TextDisplayMeta) e.getEntityMeta())
            .filter(m -> m.getText().equals(encodedBack) && m.getScale().x() > .33)
            .findFirst()
            .orElseThrow();
    assertEquals(-1, rotate(new Vec(0, 0, 1), back.getLeftRotation()).dot(frontNormal), 1e-6);
    assertEquals(-.012, back.getTranslation().sub(meta.getTranslation()).dot(frontNormal), 1e-6);
    var eye = f.a.getPosition().add(0, f.a.getEyeHeight(), 0);
    var center =
        plane.widgetToWorld(
            w.bounds().x() + w.bounds().width() / 2, w.bounds().y() + w.bounds().height() / 2);
    assertEquals(
        w.choiceId(),
        gg.grounds.dialogue.minestom.geometry.NpcHitTest.hit(
                eye,
                center.sub(eye).asVec().normalize(),
                transform,
                f.targets.current(),
                (a, b) -> true)
            .orElseThrow());

    done(
        env,
        h.configure(
            new PresentationPreferences(
                "npc", Duration.ZERO, false, Map.of("npcChoiceAngle", "0"))));
    done(env, h.replace(frame));
    assertNull(f.surface.geometry().transform().fold());
    assertEquals(0, meta.getLeftRotation()[1], 1e-6);
    done(
        env,
        h.configure(
            new PresentationPreferences(
                "npc", Duration.ZERO, false, Map.of("npcChoiceAngle", "0", "npcChoiceGap", "28"))));
    var presenter =
        new DialoguePresenter(
            Map.of(
                "npc",
                new DialoguePresenter.SurfaceProfile(
                    f.surface,
                    LayoutProfile.npc(28),
                    DialogueTheme.neutral(),
                    RenderFixtures.ASSETS.metrics(),
                    false)),
            new MinestomDialogueScheduler(f.a.getInstance()));
    var moved = presenter.compose(frame.semantic(), "npc", prefs);
    done(env, h.replace(moved));
    assertEquals(
        0,
        meta.getTransformationInterpolationDuration(),
        "New hit planes must not race an interpolating gap change");
    done(env, h.closeAsync());
    assertTrue(f.displays().isEmpty());
  }

  @Test
  void followingTheNpcRetainsTheFoldAndFixedWorldSize(Env env) {
    var f = new Fixture(env, CompletableFuture.completedFuture(null));
    var h = f.open(env);
    done(env, h.replace(RenderFixtures.frame("npc", "Hello", 5)));
    var fold = f.surface.geometry().transform().fold();
    assertNotNull(fold);
    f.feet.set(new Vec(4, 42, 0));
    assertTrue(
        env.tickWhile(
            () -> f.surface.geometry().transform().origin().x() != 4, Duration.ofSeconds(2)));
    var transform = f.surface.geometry().transform();
    assertEquals(fold, transform.fold());
    assertEquals(
        240 * .008,
        transform
            .widgetToWorld(fold.pivot(), 20)
            .distance(transform.widgetToWorld(fold.pivot() + 240, 20)),
        1e-6);
    for (var display : f.displays()) assertEquals(4, display.getPosition().x(), 1e-6);
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
