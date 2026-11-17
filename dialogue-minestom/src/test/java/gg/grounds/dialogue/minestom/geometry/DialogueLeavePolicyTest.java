package gg.grounds.dialogue.minestom.geometry;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.runtime.DialogueEndReason;
import java.time.Duration;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class DialogueLeavePolicyTest {
  DialogueLeavePolicy policy(boolean enabled) {
    return new DialogueLeavePolicy(
        new Interaction(new LookAway(enabled, 110, 600, 95), 6), Duration.ofMillis(600));
  }

  @Test
  void ordinaryLookingDoesNotCancel() {
    var p = policy(true);
    assertTrue(p.observe(80, 3, false, 0).isEmpty());
    assertTrue(p.observe(111, 3, false, 1).isEmpty());
    assertTrue(p.observe(100, 3, false, 300_000_001).isEmpty());
    assertTrue(p.observe(94, 3, false, 400_000_001).isEmpty());
    assertTrue(p.observe(111, 3, false, 500_000_001).isEmpty());
    assertTrue(p.observe(111, 3, false, 1_099_000_001).isEmpty());
    assertEquals(
        DialogueEndReason.LOOKED_AWAY, p.observe(111, 3, false, 1_100_000_001).orElseThrow());
  }

  @Test
  void firstSampleAtZeroCounts() {
    var p = policy(true);
    assertTrue(p.observe(111, 3, false, 0).isEmpty());
    assertTrue(p.observe(111, 3, false, 599_000_000).isEmpty());
    assertEquals(
        DialogueEndReason.LOOKED_AWAY, p.observe(100, 3, false, 600_000_000).orElseThrow());
  }

  @Test
  void answersProtectAngleButNotDistance() {
    var p = policy(true);
    assertTrue(p.observe(150, 3, true, 0).isEmpty());
    assertTrue(p.observe(150, 6.1, true, 100_000_000).isEmpty());
    assertTrue(p.observe(150, 6.1, true, 699_000_000).isEmpty());
    assertEquals(
        DialogueEndReason.OUT_OF_RANGE, p.observe(150, 6.1, true, 700_000_000).orElseThrow());
  }

  @Test
  void disabledAngleStillEnforcesDistanceAndInvalidNumbers() {
    var p = policy(false);
    assertTrue(p.observe(180, 3, false, 0).isEmpty());
    assertTrue(p.observe(180, 3, false, 9_000_000_000L).isEmpty());
    assertTrue(p.observe(0, 7, false, 10_000_000_000L).isEmpty());
    assertEquals(
        DialogueEndReason.OUT_OF_RANGE, p.observe(0, 7, false, 10_600_000_000L).orElseThrow());
    assertEquals(DialogueEndReason.HOST_CHANGED, p.observe(Double.NaN, 3, false, 0).orElseThrow());
    assertEquals(
        DialogueEndReason.HOST_CHANGED,
        p.observe(0, Double.POSITIVE_INFINITY, false, 0).orElseThrow());
  }

  @Test
  void nativeOcclusionRespectsBlockShapes(Env env) {
    var world = env.createFlatInstance();
    world.loadChunk(0, 0).join();
    var a = new Vec(.5, 43.5, .5);
    var b = new Vec(.5, 43.5, 3.5);
    assertTrue(MinestomOcclusion.visible(world, a, b));
    world.setBlock(0, 43, 2, Block.STONE);
    assertFalse(MinestomOcclusion.visible(world, a, b));
    world.setBlock(0, 43, 2, Block.STONE_SLAB);
    assertTrue(MinestomOcclusion.visible(world, new Vec(.5, 43.8, .5), new Vec(.5, 43.8, 3.5)));
  }
}
