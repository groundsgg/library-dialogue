package example;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.minestom.asset.PackStateTracker;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.PlayerResourcePackStatusEvent;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class PackRestoreOrderingTest {
  @Test
  void applicationRefreshSeesReadinessBeforeChildHostListenerRuns(Env env) {
    var world = env.createFlatInstance();
    var player = env.createConnection().connect(world, new Pos(0, 40, 0));
    var tracker = new PackStateTracker();
    var id = UUID.randomUUID();
    tracker.expect(player, 1, id, "pin");
    var event =
        new PlayerResourcePackStatusEvent(player, id, ResourcePackStatus.SUCCESSFULLY_LOADED);
    var calls = new AtomicInteger();
    DialogueDemo.refreshAfterPackStatus(
        tracker,
        event,
        () -> {
          assertTrue(tracker.isReady(player, 1, "pin"));
          calls.incrementAndGet();
        });
    assertEquals(1, calls.get());
    tracker.observe(player, id, event.getStatus());
    assertTrue(tracker.isReady(player, 1, "pin"));
  }
}
