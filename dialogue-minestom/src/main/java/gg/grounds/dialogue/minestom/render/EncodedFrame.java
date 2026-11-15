package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.presentation.WidgetFrame;
import java.time.Duration;
import java.util.*;
import net.kyori.adventure.text.Component;

/** Same encoded widgets feed worldspace surfaces; the screen surface uses the combined canvas. */
public record EncodedFrame(List<EncodedWidget> widgets, Component hud) {
  public EncodedFrame {
    widgets = List.copyOf(widgets);
    Objects.requireNonNull(hud);
  }

  public record EncodedWidget(
      String id,
      WidgetFrame.Kind kind,
      Component front,
      Component back,
      WidgetFrame.Rect bounds,
      String choiceId,
      boolean enabled,
      double visualScale,
      Duration transition,
      double paintBaseline) {
    public EncodedWidget(
        String id,
        WidgetFrame.Kind kind,
        Component front,
        Component back,
        WidgetFrame.Rect bounds,
        String choiceId,
        boolean enabled,
        double visualScale,
        Duration transition) {
      this(id, kind, front, back, bounds, choiceId, enabled, visualScale, transition, 0);
    }
  }
}
