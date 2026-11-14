package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.runtime.DialogueView;
import gg.grounds.dialogue.core.text.ResolvedText;
import java.time.Duration;
import java.util.*;

/** Explicit interaction metadata; visual scaling never mutates hit rectangles. */
public record WidgetFrame(
    DialogueView semantic, List<Widget> widgets, String fontPin, String themeId) {
  public WidgetFrame {
    Objects.requireNonNull(semantic);
    widgets = List.copyOf(widgets);
  }

  public enum Kind {
    SPEAKER,
    TEXT,
    CHOICE,
    HINT
  }

  public record Rect(double x, double y, double width, double height) {
    public Rect {
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || !Double.isFinite(width)
          || !Double.isFinite(height)
          || width < 0
          || height < 0) throw new IllegalArgumentException("Invalid rectangle");
    }

    public boolean contains(double px, double py) {
      return px >= x && px <= x + width && py >= y && py <= y + height;
    }
  }

  public record Widget(
      String id,
      Kind kind,
      Rect bounds,
      Rect textBounds,
      ResolvedText text,
      TextLayout.TextLayoutResult layout,
      int visibleGraphemes,
      boolean selected,
      boolean enabled,
      double visualScale,
      Duration transition,
      String choiceId) {
    public Rect hitRect() {
      return bounds;
    }
  }
}
