package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import java.util.function.Consumer;

/** Shared plain preference parsing, also used by NPC panels. */
final class VisualOptions {
  static boolean enabled(PresentationPreferences p, String key, Consumer<String> diagnostic) {
    String value = p.effects().getOrDefault(key, "false");
    if (!value.equals("true") && !value.equals("false")) {
      diagnostic.accept("INVALID_EFFECT_VALUE:" + key);
      return false;
    }
    return value.equals("true");
  }
}
