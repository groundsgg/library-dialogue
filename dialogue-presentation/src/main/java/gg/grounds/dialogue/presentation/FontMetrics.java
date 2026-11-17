package gg.grounds.dialogue.presentation;

/** Metrics and glyph coverage are pinned to the same font asset as the eventual surface. */
public interface FontMetrics {
  /** Foreground ink relative to the glyph origin; line advance remains independent. */
  record InkBounds(double top, double bottom) {
    public InkBounds {
      if (!Double.isFinite(top) || !Double.isFinite(bottom) || bottom < top)
        throw new IllegalArgumentException("Invalid glyph ink bounds");
    }
  }

  /** Legacy/custom metrics retain line-box alignment unless they provide ink data. */
  default InkBounds inkBounds(String grapheme) {
    return grapheme
            .codePoints()
            .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
        ? new InkBounds(0, 0)
        : new InkBounds(0, lineHeight());
  }

  double width(String grapheme);

  double lineHeight();

  String pin();

  static FontMetrics defaults() {
    return BundledFontMetrics.INSTANCE;
  }
}
