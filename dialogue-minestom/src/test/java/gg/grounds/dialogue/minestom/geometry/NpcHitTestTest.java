package gg.grounds.dialogue.minestom.geometry;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot;
import gg.grounds.dialogue.minestom.input.TargetSnapshot;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;
import net.minestom.server.coordinate.*;
import org.junit.jupiter.api.Test;

class NpcHitTestTest {
  AnchorSnapshot anchor = new AnchorSnapshot(Vec.ZERO, 0, new Vec(0, 1.6, 0), true);

  @Test
  void frontBoundedYawKeepsWorldSize() {
    var a = NpcPanelTransform.from(anchor, new Vec(-10, 2, 0));
    assertEquals(15, a.bodyfrontYaw(), 1e-6);
    assertEquals(.32, a.scale(), 1e-9);
    for (var eye : List.of(new Vec(0, 2, 2), new Vec(50, 2, 20))) {
      var t = NpcPanelTransform.from(anchor, eye);
      var w = t.widgetToWorld(123, 45);
      var gui = t.worldToWidget(w);
      assertEquals(123, gui.x(), 1e-6);
      assertEquals(45, gui.y(), 1e-6);
      assertEquals(320 * .008, t.widgetToWorld(0, 0).distance(t.widgetToWorld(320, 0)), 1e-6);
    }
  }

  @Test
  void hitUsesUnscaledBounds() {
    var eye = new Vec(0, 2, 4);
    var t = NpcPanelTransform.from(anchor, eye);
    var rect = new WidgetFrame.Rect(326, 0, 240, 40);
    var target = new TargetSnapshot(UUID.randomUUID(), 1, 1, 1, Map.of("yes", rect));
    var center = t.widgetToWorld(400, 20);
    var ray = center.sub(eye).asVec().normalize();
    assertEquals("yes", NpcHitTest.hit(eye, ray, t, target, (a, b) -> true).orElseThrow());
    assertEquals("yes", NpcHitTest.hit(eye, ray, t, target, (a, b) -> true).orElseThrow());
    var outside = t.widgetToWorld(570, 20).sub(eye).asVec().normalize();
    assertTrue(NpcHitTest.hit(eye, outside, t, target, (a, b) -> true).isEmpty());
  }

  @Test
  void backAndOccludedTargetsCannotHover() {
    var eye = new Vec(0, 2, 4);
    var t = NpcPanelTransform.from(anchor, eye);
    var r = new WidgetFrame.Rect(0, 0, 320, 40);
    var target = new TargetSnapshot(UUID.randomUUID(), 1, 1, 1, Map.of("yes", r));
    var p = t.widgetToWorld(160, 20);
    var back = p.sub(t.normal().mul(3));
    assertTrue(NpcHitTest.hit(back, t.normal(), t, target, (a, b) -> true).isEmpty());
    assertTrue(
        NpcHitTest.hit(eye, p.sub(eye).asVec().normalize(), t, target, (a, b) -> false).isEmpty());
  }

  @Test
  void disabledFrontProtectsZoneButNeverSelects() {
    var eye = new Vec(0, 2, 4);
    var t = NpcPanelTransform.from(anchor, eye);
    var r = new WidgetFrame.Rect(326, 0, 240, 40);
    var ray = t.widgetToWorld(400, 20).sub(eye).asVec().normalize();
    var targets = new TargetSnapshot(UUID.randomUUID(), 1, 1, 1, Map.of());
    assertTrue(NpcHitTest.hit(eye, ray, t, targets, (a, b) -> true).isEmpty());
    assertTrue(NpcHitTest.inDialogueZone(eye, ray, t, List.of(r), (a, b) -> true));
    assertFalse(NpcHitTest.inDialogueZone(eye, ray, t, List.of(r), (a, b) -> false));
  }
}
