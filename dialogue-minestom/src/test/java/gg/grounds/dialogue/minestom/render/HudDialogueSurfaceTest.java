package gg.grounds.dialogue.minestom.render;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.*;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class HudDialogueSurfaceTest {
  static <T> T done(Env e, CompletionStage<T> s) {
    assertTrue(e.tickWhile(() -> !s.toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    return s.toCompletableFuture().join();
  }

  static class Fixture {
    final Player p;
    final List<Component> hud = new ArrayList<>();
    final List<List<Key>> effects = new ArrayList<>();
    final List<String> diagnostics = new ArrayList<>();
    final SurfaceInputTargets targets = new SurfaceInputTargets();
    final LocalOutputChannels channels;
    final HudDialogueSurface surface;

    Fixture(Env env, boolean supported) {
      p = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
      channels =
          new LocalOutputChannels(
              (player, c) -> {
                hud.add(c);
                return CompletableFuture.completedFuture(null);
              },
              supported,
              (player, k) -> {
                effects.add(k);
                return CompletableFuture.completedFuture(null);
              });
      var packs = new PackStateTracker();
      var id = UUID.randomUUID();
      packs.expect(p, 1, id, RenderFixtures.ASSETS.pin());
      packs.observe(p, 1, id, RenderFixtures.ASSETS.pin(), ResourcePackStatus.SUCCESSFULLY_LOADED);
      surface =
          new HudDialogueSurface(
              p,
              1,
              new ConnectionGuard(p, 1, p.getInstance()),
              packs,
              RenderFixtures.ASSETS,
              channels,
              targets,
              new MinestomDialogueScheduler(p.getInstance()),
              diagnostics::add);
    }

    DialogueSurface.SurfaceHandle open(Env env, Map<String, String> effects) {
      return done(
          env,
          surface.open(
              new DialogueSurface.SurfaceContext(
                  RenderFixtures.SESSION,
                  p.getUuid().toString(),
                  1,
                  "hud",
                  "profile",
                  new CancellationToken(),
                  1,
                  Long.MAX_VALUE,
                  new PresentationPreferences("hud", Duration.ZERO, false, effects))));
    }
  }

  @Test
  void coverageAndVignetteAreIndependent(Env env) {
    var f = new Fixture(env, true);
    var h = f.open(env, Map.of("hideHud", "true"));
    done(env, h.replace(RenderFixtures.frame("hud", "Hello", 5)));
    assertTrue(
        PlainTextComponentSerializer.plainText().serialize(f.hud.getLast()).contains("\ue200"));
    assertEquals(128, HudCoverage.fadeGuiPixels());
    assertTrue(f.effects.getLast().isEmpty());
    done(
        env,
        h.configure(
            new PresentationPreferences("hud", Duration.ZERO, false, Map.of("vignette", "true"))));
    done(env, h.replace(RenderFixtures.frame("hud", "Hello", 5)));
    assertFalse(
        PlainTextComponentSerializer.plainText().serialize(f.hud.getLast()).contains("\ue200"));
    assertEquals(List.of(Key.key("dialogue:vignette")), f.effects.getLast());
    done(env, h.closeAsync());
  }

  @Test
  void foreignHudReturnsAfterClose(Env env) {
    var f = new Fixture(env, false);
    var foreign = f.channels.acquireHud(f.p, 1, 10);
    done(env, foreign.replace(Component.text("foreign")));
    var h = f.open(env, Map.of());
    done(env, h.replace(RenderFixtures.frame("hud", "Hello", 5)));
    done(env, h.closeAsync());
    assertEquals("foreign", PlainTextComponentSerializer.plainText().serialize(f.hud.getLast()));
    done(env, foreign.closeAsync());
  }

  @Test
  void unsupportedEffectDoesNotBreakHud(Env env) {
    var f = new Fixture(env, false);
    var health = f.p.getHealth();
    var food = f.p.getFood();
    var selected = f.p.getHeldSlot();
    var h = f.open(env, Map.of("vignette", "true"));
    done(env, h.replace(RenderFixtures.frame("hud", "Hello", 5)));
    assertTrue(f.diagnostics.contains("POST_EFFECT_UNSUPPORTED"));
    assertEquals(health, f.p.getHealth());
    assertEquals(food, f.p.getFood());
    assertEquals(selected, f.p.getHeldSlot());
    done(env, h.closeAsync());
  }

  @Test
  void finalHintSaysEnd(Env env) {
    var f = new Fixture(env, false);
    var h = f.open(env, Map.of());
    var v = RenderFixtures.view("Hello", 5);
    var fin =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            1,
            DialogueView.State.WAITING_FOR_INPUT,
            2,
            2,
            "en",
            "NPC",
            "Hello",
            List.of(),
            null,
            5,
            5,
            DialogueView.HintPhase.FINISH,
            "",
            null);
    var clock = new MinestomDialogueScheduler(f.p.getInstance());
    var presenter =
        new DialoguePresenter(
            Map.of(
                "hud",
                new DialoguePresenter.SurfaceProfile(
                    f.surface,
                    LayoutProfile.hud(),
                    DialogueTheme.neutral(),
                    RenderFixtures.ASSETS.metrics(),
                    false)),
            clock);
    var frame = presenter.compose(fin, "hud", PresentationPreferences.defaults());
    done(env, h.replace(frame));
    assertTrue(PlainTextComponentSerializer.plainText().serialize(f.hud.getLast()).contains("End"));
    done(env, h.closeAsync());
  }

  @Test
  void lateFrameAfterCloseIsIgnored(Env env) {
    var f = new Fixture(env, false);
    var h = f.open(env, Map.of());
    done(env, h.closeAsync());
    int count = f.hud.size();
    assertThrows(
        CompletionException.class,
        () -> done(env, h.replace(RenderFixtures.frame("hud", "Hello", 5))));
    assertEquals(count, f.hud.size());
    assertNull(f.targets.current());
  }
}
