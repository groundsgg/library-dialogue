package gg.grounds.dialogue.minestom.internal;

import static org.junit.jupiter.api.Assertions.*;

import net.minestom.server.coordinate.Pos;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class ConnectionGuardTest {
  @Test
  void invalidationImmediatelyRejectsOldGeneration(Env env) {
    var instance = env.createFlatInstance();
    var player = env.createConnection().connect(instance, new Pos(0, 42, 0));
    var guard = new ConnectionGuard(player, 1, instance);
    assertTrue(guard.isCurrent());
    guard.invalidate();
    assertFalse(guard.isCurrent());
    assertTrue(new ConnectionGuard(player, 2, instance).isCurrent());
  }

  @Test
  void instanceChangeInvalidatesBoundGuard(Env env) {
    var instance = env.createFlatInstance();
    var player = env.createConnection().connect(instance, new Pos(0, 42, 0));
    var guard = new ConnectionGuard(player, 1, instance);
    player.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
    assertFalse(guard.isCurrent());
  }
}
