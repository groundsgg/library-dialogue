package gg.grounds.dialogue.minestom.asset;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.coordinate.Pos;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class PackStateTrackerTest {
  @Test
  void retiredPackUuidCannotBeRegisteredAgain(Env env) {
    var p = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var t = new PackStateTracker();
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    t.expect(p, 1, a, "old");
    t.expect(p, 1, b, "middle");
    assertThrows(IllegalArgumentException.class, () -> t.expect(p, 2, a, "new"));
    t.observe(p, a, ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertFalse(t.isReady(p, 1, "middle"));
    t.expect(p, 1, b, "middle");
    t.observe(p, b, ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertTrue(t.isReady(p, 1, "middle"));
  }

  @Test
  void wrongPackAndOldConnectionNeverEnableGraphics(Env env) {
    var a = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var b = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var t = new PackStateTracker();
    var id = UUID.randomUUID();
    var other = UUID.randomUUID();
    t.expect(a, 1, id, "pin");
    t.observe(a, 1, other, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertFalse(t.isReady(a, 1, "pin"));
    t.observe(b, 1, id, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertFalse(t.isReady(a, 1, "pin"));
    t.observe(a, 1, id, "wrong", ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertFalse(t.isReady(a, 1, "pin"));
    t.observe(a, 1, id, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertTrue(t.isReady(a, 1, "pin"));
    t.expect(a, 2, other, "pin");
    t.observe(a, 1, id, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertFalse(t.isReady(a, 2, "pin"));
    t.observe(a, 2, other, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertTrue(t.isReady(a, 2, "pin"));
    t.forget(a);
    assertFalse(t.isReady(a, 2, "pin"));
  }
}
