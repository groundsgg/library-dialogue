package gg.grounds.dialogue.minestom.input;

import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;

public record TargetSnapshot(
    UUID sessionId,
    long renderRevision,
    long nodeVisitId,
    long surfaceGeneration,
    Map<String, WidgetFrame.Rect> choices,
    long stateRevision) {
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
  }

  public record Target(
      UUID sessionId,
      long renderRevision,
      long nodeVisitId,
      long surfaceGeneration,
      String choiceId) {}
}
