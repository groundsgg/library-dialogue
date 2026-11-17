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
    return intersection(eye, direction, transform, bounds, true);
  }

  private static Point intersection(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      WidgetFrame.Rect bounds,
      boolean frontOnly) {
    var plane = transform.planeFor(bounds);
    double denominator = direction.dot(plane.normal());
    if (Math.abs(denominator) < 1e-9 || frontOnly && denominator >= -1e-9) return null;
    double distance = plane.origin().sub(eye).asVec().dot(plane.normal()) / denominator;
    if (!Double.isFinite(distance) || distance <= 0) return null;
    var world = eye.add(direction.mul(distance));
    var p = plane.worldToWidget(world);
    return bounds.contains(p.x(), p.y()) ? world : null;
  }

  public record NavigationHit(WidgetFrame.NavigationButton button, boolean usable) {}

  /** A blocked/disabled/back button still consumes confirmation; it never confirms an answer. */
  public static Optional<NavigationHit> navigationHit(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      TargetSnapshot targets,
      OcclusionTest occlusion) {
    for (var button : targets.navigation().values()) {
      var world = intersection(eye, direction, transform, button.bounds(), false);
      if (world == null) continue;
      boolean front = direction.dot(transform.planeFor(button.bounds()).normal()) < -1e-9;
      return Optional.of(
          new NavigationHit(button, front && button.enabled() && occlusion.visible(eye, world)));
    }
    return Optional.empty();
  }

  public record ChoiceHit(String choiceId, boolean usable) {}

  /** A blocked, unavailable or back-facing card consumes confirmation without choosing another. */
  public static Optional<ChoiceHit> choiceHit(
      Point eye,
      Vec direction,
      NpcPanelTransform transform,
      Map<String, WidgetFrame.Rect> choiceBounds,
      TargetSnapshot targets,
      OcclusionTest occlusion) {
    for (var choice : choiceBounds.entrySet()) {
      var world = intersection(eye, direction, transform, choice.getValue(), false);
      if (world == null) continue;
      boolean front = direction.dot(transform.planeFor(choice.getValue()).normal()) < -1e-9;
      return Optional.of(
          new ChoiceHit(
              choice.getKey(),
              front
                  && targets.choices().containsKey(choice.getKey())
                  && occlusion.visible(eye, world)));
    }
    return Optional.empty();
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
