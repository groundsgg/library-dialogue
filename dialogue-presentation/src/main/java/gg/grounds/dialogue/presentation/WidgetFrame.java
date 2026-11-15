package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.runtime.DialogueView;
import gg.grounds.dialogue.core.text.ResolvedText;
import java.time.Duration;
import java.util.*;

/** Explicit interaction metadata; visual scaling never mutates hit rectangles. */
public record WidgetFrame(
    DialogueView semantic,
    List<Widget> widgets,
    String fontPin,
    DialogueTheme theme,
    Chrome chrome) {
  public WidgetFrame(
      DialogueView semantic, List<Widget> widgets, String fontPin, DialogueTheme theme) {
    this(semantic, widgets, fontPin, theme, null);
  }

  /** Shared backdrop metadata, independent of a concrete game/client API. */
  public record Chrome(String key, Rect bounds, double baseline) {
    public Chrome {
      Objects.requireNonNull(key);
      Objects.requireNonNull(bounds);
      if (!Double.isFinite(baseline)) throw new IllegalArgumentException("Invalid chrome baseline");
    }
  }

  public WidgetFrame {
    Objects.requireNonNull(semantic);
    widgets = List.copyOf(widgets);
  }

  /** Union includes negative coordinates, e.g. choices to the left of the speaker. */
  public Rect canvasBounds() {
    var rects = new ArrayList<Rect>();
    widgets.forEach(w -> rects.add(w.bounds()));
    if (chrome != null) rects.add(chrome.bounds());
    if (rects.isEmpty()) return new Rect(0, 0, 0, 0);
    double left = rects.stream().mapToDouble(Rect::x).min().orElse(0),
        top = rects.stream().mapToDouble(Rect::y).min().orElse(0);
    double right = rects.stream().mapToDouble(r -> r.x() + r.width()).max().orElse(0),
        bottom = rects.stream().mapToDouble(r -> r.y() + r.height()).max().orElse(0);
    return new Rect(left, top, right - left, bottom - top);
  }

  public String themeId() {
    return theme.id();
  }

  public WidgetFrame(DialogueView semantic, List<Widget> widgets, String fontPin, String themeId) {
    this(semantic, widgets, fontPin, new DialogueTheme(themeId, Map.of(), 1, Duration.ZERO));
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
