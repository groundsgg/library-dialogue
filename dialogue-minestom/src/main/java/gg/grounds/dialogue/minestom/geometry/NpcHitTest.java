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
      Point eye, Vec direction, NpcPanelTransform t, OcclusionTest occlusion) {
    double denominator = direction.dot(t.normal());
    if (denominator >= -1e-9) return null;
    double distance = t.origin().sub(eye).asVec().dot(t.normal()) / denominator;
    if (!Double.isFinite(distance) || distance <= 0) return null;
    var p = eye.add(direction.mul(distance));
    return occlusion.visible(eye, p) ? t.worldToWidget(p) : null;
  }

  public static Optional<String> hit(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      TargetSnapshot targets,
      OcclusionTest occlusion) {
    var p = intersection(eye, direction, transform, occlusion);
    if (p == null) return Optional.empty();
    return targets.choices().entrySet().stream()
        .filter(e -> e.getValue().contains(p.x(), p.y()))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  public static boolean inDialogueZone(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      List<WidgetFrame.Rect> fronts,
      OcclusionTest occlusion) {
    var p = intersection(eye, direction, transform, occlusion);
    return p != null && fronts.stream().anyMatch(r -> r.contains(p.x(), p.y()));
  }
}
