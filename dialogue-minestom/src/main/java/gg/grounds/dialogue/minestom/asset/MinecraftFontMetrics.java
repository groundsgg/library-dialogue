package gg.grounds.dialogue.minestom.asset;

import gg.grounds.dialogue.presentation.FontMetrics;
import java.util.*;

/** Native bitmap advances from the frozen atlas, never host-installed font metrics. */
public final class MinecraftFontMetrics implements FontMetrics {
  private final String pin;
  private final Map<Integer, Integer> advances;
  private final double height;

  MinecraftFontMetrics(String pin, Map<Integer, Integer> advances) {
    this(pin, advances, 16);
  }

  MinecraftFontMetrics(String pin, Map<Integer, Integer> advances, double height) {
    this.pin = pin;
    this.advances = Map.copyOf(advances);
    this.height = height;
  }

  public double width(String grapheme) {
    if (grapheme.codePointCount(0, grapheme.length()) != 1)
      throw new IllegalArgumentException("MISSING_GLYPH");
    var width = advances.get(grapheme.codePointAt(0));
    if (width == null) throw new IllegalArgumentException("MISSING_GLYPH");
    return width;
  }

  public double lineHeight() {
    return height;
  }

  public String pin() {
    return pin;
  }
}
