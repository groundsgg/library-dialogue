package gg.grounds.dialogue.minestom.input;

import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;

public record TargetSnapshot(
    UUID sessionId,
    long renderRevision,
    long nodeVisitId,
    long surfaceGeneration,
    Map<String, WidgetFrame.Rect> choices,
    long stateRevision,
    Map<WidgetFrame.NavigationDirection, WidgetFrame.NavigationButton> navigation) {
  public TargetSnapshot(
      UUID sessionId,
      long renderRevision,
      long nodeVisitId,
      long surfaceGeneration,
      Map<String, WidgetFrame.Rect> choices,
      long stateRevision) {
    this(
        sessionId,
        renderRevision,
        nodeVisitId,
        surfaceGeneration,
        choices,
        stateRevision,
        Map.of());
  }

  public TargetSnapshot(
      UUID sessionId,
      long renderRevision,
      long nodeVisitId,
      long surfaceGeneration,
      Map<String, WidgetFrame.Rect> choices) {
    this(sessionId, renderRevision, nodeVisitId, surfaceGeneration, choices, -1);
  }

  public TargetSnapshot {
    Objects.requireNonNull(sessionId);
    choices = Map.copyOf(choices);
    navigation = Map.copyOf(navigation);
  }

  public record Target(
      UUID sessionId,
      long renderRevision,
      long nodeVisitId,
      long surfaceGeneration,
      String choiceId) {}
}
