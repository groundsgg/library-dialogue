package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.runtime.DialogueView;
import gg.grounds.dialogue.core.runtime.DialogueView.ChoiceView;
import java.util.*;
import java.util.concurrent.CompletionStage;

public final class ChoiceViewport {
  /** Optional presentation capability; browsing never submits a core selection. */
  public interface Controller {
    CompletionStage<Boolean> setChoiceStart(DialogueView expected, int start);
  }

  /** Owned by one presentation; reveal/locale refreshes preserve an explicit page. */
  public static final class Cursor {
    private UUID session;
    private long visit;
    private String selection;
    private int start;

    public int startFor(DialogueView view, int capacity) {
      var viewport = new ChoiceViewport();
      if (!view.sessionId().equals(session)
          || view.nodeVisitId() != visit
          || !Objects.equals(selection, view.selectedChoiceId()))
        start = viewport.window(view.choices(), view.selectedChoiceId(), capacity).start();
      else
        start = viewport.window(view.choices(), view.selectedChoiceId(), capacity, start).start();
      session = view.sessionId();
      visit = view.nodeVisitId();
      selection = view.selectedChoiceId();
      return start;
    }

    public boolean browse(DialogueView view, int capacity, int next) {
      startFor(view, capacity);
      if (next < 0 || next % capacity != 0 || next >= view.choices().size()) return false;
      start = next;
      return true;
    }
  }

  public record ChoiceWindow(int start, List<ChoiceView> choices, int selectedIndex, int total) {
    public ChoiceWindow {
      choices = List.copyOf(choices);
    }
  }

  public ChoiceWindow window(List<ChoiceView> choices, String selectedId, int capacity) {
    return window(choices, selectedId, capacity, null);
  }

  public ChoiceWindow window(
      List<ChoiceView> choices, String selectedId, int capacity, Integer requestedStart) {
    if (capacity < 1) throw new IllegalArgumentException("Positive viewport capacity required");
    if (choices.isEmpty()) return new ChoiceWindow(0, List.of(), -1, 0);
    int selected = -1;
    for (int i = 0; i < choices.size(); i++)
      if (Objects.equals(choices.get(i).id(), selectedId)) {
        selected = i;
        break;
      }
    if (selected < 0 && selectedId != null)
      throw new IllegalArgumentException("Selection is not offered");
    int start =
        requestedStart == null
            ? Math.max(0, selected) / capacity * capacity
            : Math.min(
                Math.max(0, requestedStart) / capacity * capacity,
                (choices.size() - 1) / capacity * capacity);
    return new ChoiceWindow(
        start,
        choices.subList(start, Math.min(start + capacity, choices.size())),
        selected < start || selected >= start + capacity ? -1 : selected - start,
        choices.size());
  }
}
