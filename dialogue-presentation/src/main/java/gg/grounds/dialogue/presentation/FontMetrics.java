package gg.grounds.dialogue.presentation;

/** Metrics and glyph coverage are pinned to the same font asset as the eventual surface. */
public interface FontMetrics {
  double width(String grapheme);

  double lineHeight();

  String pin();

  static FontMetrics defaults() {
    return BundledFontMetrics.INSTANCE;
  }
}
