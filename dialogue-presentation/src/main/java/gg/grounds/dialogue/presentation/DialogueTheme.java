package gg.grounds.dialogue.presentation;

import java.time.Duration;
import java.util.*;

public record DialogueTheme(
    String id, Map<String, Integer> colours, double selectedScale, Duration transition) {
  public DialogueTheme {
    Objects.requireNonNull(id);
    colours = Map.copyOf(colours);
    Objects.requireNonNull(transition);
    if (!Double.isFinite(selectedScale)
        || selectedScale < 1
        || selectedScale > 2
        || transition.isNegative()) throw new IllegalArgumentException("Invalid visual transition");
  }

  public static DialogueTheme neutral() {
    return new DialogueTheme(
        "neutral-v1",
        Map.of("text", 0xf3eee4, "selected", 0xffcd75, "muted", 0x9ba9b7, "panel", 0x152335),
        1.08,
        Duration.ofMillis(150));
  }
}
