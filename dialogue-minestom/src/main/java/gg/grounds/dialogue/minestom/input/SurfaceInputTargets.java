package gg.grounds.dialogue.minestom.input;

import gg.grounds.dialogue.core.runtime.DialogueView;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;

/** Only successfully published output may issue a current interaction target. */
public final class SurfaceInputTargets {
  private long invalidThrough = -1, latestGeneration = -1, lastState = -1, lastRender = -1;
  private TargetSnapshot current;

  public synchronized void publish(
      WidgetFrame frame, long generation, Map<String, WidgetFrame.Rect> choices) {
    if (generation <= invalidThrough
        || generation < latestGeneration
        || (generation == latestGeneration
            && (frame.semantic().stateRevision() < lastState
                || (frame.semantic().stateRevision() == lastState
                    && frame.semantic().renderRevision() < lastRender)))) return;
    var allowed = new HashSet<String>();
    frame.semantic().choices().stream()
        .filter(c -> c.availability() == DialogueView.Availability.AVAILABLE)
        .forEach(c -> allowed.add(c.id()));
    if (!allowed.containsAll(choices.keySet()))
      throw new IllegalArgumentException("UNISSUED_CHOICE");
    current =
        new TargetSnapshot(
            frame.semantic().sessionId(),
            frame.semantic().renderRevision(),
            frame.semantic().nodeVisitId(),
            generation,
            choices);
    latestGeneration = generation;
    lastState = frame.semantic().stateRevision();
    lastRender = frame.semantic().renderRevision();
  }

  public synchronized void clear(long generation) {
    invalidThrough = Math.max(invalidThrough, generation);
    if (current != null && current.surfaceGeneration() <= generation) current = null;
  }

  /** Null until the first successful output, and after the current generation is removed. */
  public synchronized TargetSnapshot current() {
    return current;
  }
}
