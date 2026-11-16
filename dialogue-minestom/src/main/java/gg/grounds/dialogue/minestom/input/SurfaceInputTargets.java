package gg.grounds.dialogue.minestom.input;

import gg.grounds.dialogue.core.runtime.DialogueView;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Successfully published frames, owned and retired by their exact surface generation. */
public final class SurfaceInputTargets {
  private record Published(
      long state, long render, TargetSnapshot snapshot, BooleanSupplier valid) {}

  private final NavigableMap<Long, Published> live = new TreeMap<>();
  private long highWater = -1;

  public void publish(WidgetFrame frame, long generation, Map<String, WidgetFrame.Rect> choices) {
    publish(frame, generation, choices, () -> true);
  }

  public synchronized void publish(
      WidgetFrame frame,
      long generation,
      Map<String, WidgetFrame.Rect> choices,
      BooleanSupplier valid) {
    var old = live.get(generation);
    var v = frame.semantic();
    if (old == null && generation <= highWater) return;
    if (old != null
        && (v.stateRevision() < old.state()
            || (v.stateRevision() == old.state() && v.renderRevision() < old.render()))) return;
    var allowed = new HashSet<String>();
    v.choices().stream()
        .filter(c -> c.availability() == DialogueView.Availability.AVAILABLE)
        .forEach(c -> allowed.add(c.id()));
    if (!allowed.containsAll(choices.keySet()))
      throw new IllegalArgumentException("UNISSUED_CHOICE");
    var navigation =
        new EnumMap<WidgetFrame.NavigationDirection, WidgetFrame.NavigationButton>(
            WidgetFrame.NavigationDirection.class);
    if (frame.scrollbar() != null)
      for (var button : frame.scrollbar().buttons()) {
        if (button.choiceId() != null && !allowed.contains(button.choiceId()))
          throw new IllegalArgumentException("UNISSUED_NAVIGATION_CHOICE");
        if (button.pageStart() != null && button.pageStart() >= v.choices().size())
          throw new IllegalArgumentException("UNISSUED_NAVIGATION_PAGE");
        navigation.put(button.direction(), button);
      }
    live.put(
        generation,
        new Published(
            v.stateRevision(),
            v.renderRevision(),
            new TargetSnapshot(
                v.sessionId(),
                v.renderRevision(),
                v.nodeVisitId(),
                generation,
                choices,
                v.stateRevision(),
                navigation),
            Objects.requireNonNull(valid)));
    highWater = Math.max(highWater, generation);
  }

  public synchronized void invalidateCurrent(long generation) {
    var old = live.get(generation);
    if (old != null)
      live.put(generation, new Published(old.state(), old.render(), null, old.valid()));
  }

  public synchronized void clear(long generation) {
    highWater = Math.max(highWater, generation);
    live.remove(generation);
  }

  public TargetSnapshot current() {
    Published issued;
    synchronized (this) {
      issued = live.isEmpty() ? null : live.lastEntry().getValue();
    }
    if (issued == null || issued.snapshot() == null || !issued.valid().getAsBoolean()) return null;
    synchronized (this) {
      return live.get(issued.snapshot().surfaceGeneration()) == issued ? issued.snapshot() : null;
    }
  }
}
