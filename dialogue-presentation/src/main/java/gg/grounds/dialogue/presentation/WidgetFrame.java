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
    Chrome chrome,
    Scrollbar scrollbar) {
  public WidgetFrame(
      DialogueView semantic,
      List<Widget> widgets,
      String fontPin,
      DialogueTheme theme,
      Chrome chrome) {
    this(semantic, widgets, fontPin, theme, chrome, null);
  }

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

  public enum NavigationDirection {
    PREVIOUS,
    NEXT
  }

  /** Page offsets browse presentation only; the legacy choice target constructor is retained. */
  public record NavigationButton(
      NavigationDirection direction, Rect bounds, String choiceId, Integer pageStart) {
    public NavigationButton(NavigationDirection direction, Rect bounds, String choiceId) {
      this(direction, bounds, choiceId, null);
    }

    public static NavigationButton page(NavigationDirection direction, Rect bounds, Integer start) {
      return new NavigationButton(direction, bounds, null, start);
    }

    public NavigationButton {
      Objects.requireNonNull(direction);
      Objects.requireNonNull(bounds);
      if (pageStart != null && (pageStart < 0 || choiceId != null))
        throw new IllegalArgumentException("Invalid navigation page");
      if (choiceId != null && choiceId.isBlank())
        throw new IllegalArgumentException("Invalid navigation choice");
    }

    public boolean enabled() {
      return choiceId != null || pageStart != null;
    }
  }

  /** Overflow indicator and optional page controls in the shared layout coordinate system. */
  public record Scrollbar(Rect track, Rect thumb, List<NavigationButton> buttons) {
    public Scrollbar(Rect track, Rect thumb) {
      this(track, thumb, List.of());
    }

    public Scrollbar {
      buttons = List.copyOf(buttons);
      if (buttons.size() > 2
          || buttons.stream().map(NavigationButton::direction).distinct().count() != buttons.size())
        throw new IllegalArgumentException("Duplicate navigation direction");
      Objects.requireNonNull(track);
      Objects.requireNonNull(thumb);
      if (track.width() <= 0
          || track.height() <= 0
          || thumb.width() != track.width()
          || thumb.height() <= 0
          || thumb.x() != track.x()
          || thumb.y() < track.y()
          || thumb.y() + thumb.height() > track.y() + track.height())
        throw new IllegalArgumentException("Invalid scrollbar");
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
    if (scrollbar != null) {
      rects.add(scrollbar.track());
      scrollbar.buttons().forEach(b -> rects.add(b.bounds()));
    }
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
    HINT,
    SCROLLBAR
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
