package gg.grounds.dialogue.minestom.geometry;

import gg.grounds.dialogue.minestom.input.TargetSnapshot;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;
import net.minestom.server.coordinate.*;

public final class NpcHitTest {
  private NpcHitTest() {}

  @FunctionalInterface
  public interface OcclusionTest {
    boolean visible(Point from, Point to);
  }

  private static Point intersection(
      Point eye, Vec direction, NpcPanelTransform transform, WidgetFrame.Rect bounds) {
    var plane = transform.planeFor(bounds);
    double denominator = direction.dot(plane.normal());
    if (denominator >= -1e-9) return null;
    double distance = plane.origin().sub(eye).asVec().dot(plane.normal()) / denominator;
    if (!Double.isFinite(distance) || distance <= 0) return null;
    var world = eye.add(direction.mul(distance));
    var p = plane.worldToWidget(world);
    return bounds.contains(p.x(), p.y()) ? world : null;
  }

  public static Optional<String> hit(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      TargetSnapshot targets,
      OcclusionTest occlusion) {
    for (var target : targets.choices().entrySet()) {
      var world = intersection(eye, direction, transform, target.getValue());
      if (world != null && occlusion.visible(eye, world)) return Optional.of(target.getKey());
    }
    return Optional.empty();
  }

  public static boolean inDialogueZone(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      List<WidgetFrame.Rect> fronts,
      OcclusionTest occlusion) {
    for (var bounds : fronts) {
      var world = intersection(eye, direction, transform, bounds);
      if (world != null && occlusion.visible(eye, world)) return true;
    }
    return false;
  }
}
