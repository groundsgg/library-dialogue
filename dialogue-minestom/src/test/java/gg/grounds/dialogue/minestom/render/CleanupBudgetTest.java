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
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class CleanupBudgetTest {
  @Test
  void nativeSurfaceUsesConfiguredCleanupDeadline(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var packs = new PackStateTracker();
    var pack = UUID.randomUUID();
    packs.expect(player, 1, pack, RenderFixtures.ASSETS.pin());
    packs.observe(
        player, 1, pack, RenderFixtures.ASSETS.pin(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    var gate = new CompletableFuture<Void>();
    OutputChannels channels =
        new OutputChannels() {
          public HudLease acquireHud(Player p, long gen, int priority) {
            return new HudLease() {
              public CompletionStage<Void> replace(Component c) {
                return CompletableFuture.completedFuture(null);
              }

              public CompletionStage<Void> closeAsync() {
                return gate;
              }
            };
          }

          public EffectLease acquireEffects(Player p, long gen) {
            throw new UnsupportedOperationException();
          }

          public boolean supportedEffects() {
            return false;
          }
        };
    var surface =
        new HudDialogueSurface(
            player,
            1,
            new ConnectionGuard(player, 1, player.getInstance()),
            packs,
            RenderFixtures.ASSETS,
            channels,
            new SurfaceInputTargets(),
            new MinestomDialogueScheduler(player.getInstance()),
            ignored -> {});
    var context =
        new DialogueSurface.SurfaceContext(
            RenderFixtures.SESSION,
            player.getUuid().toString(),
            1,
            "hud",
            "profile",
            new CancellationToken(),
            1,
            Long.MAX_VALUE,
            PresentationPreferences.defaults(),
            Duration.ofMillis(25));
    var handle = HudDialogueSurfaceTest.done(env, surface.open(context));
    HudDialogueSurfaceTest.done(env, handle.replace(RenderFixtures.frame("hud", "Hello", 5)));
    var close = handle.closeAsync();
    assertTrue(env.tickWhile(() -> !close.toCompletableFuture().isDone(), Duration.ofSeconds(1)));
    assertThrows(CompletionException.class, () -> close.toCompletableFuture().join());
    gate.complete(null);
    env.tick();
  }
}
