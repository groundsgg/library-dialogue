package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.runtime.DialogueView.ChoiceView;
import java.util.*;

public final class ChoiceViewport {
  public record ChoiceWindow(int start, List<ChoiceView> choices, int selectedIndex, int total) {
    public ChoiceWindow {
      choices = List.copyOf(choices);
    }
  }

  public ChoiceWindow window(List<ChoiceView> choices, String selectedId, int capacity) {
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
    int start = Math.max(0, selected) / capacity * capacity;
    return new ChoiceWindow(
        start,
        choices.subList(start, Math.min(start + capacity, choices.size())),
        selected < 0 ? -1 : selected - start,
        choices.size());
  }
}
