package gg.grounds.dialogue.presentation;

/** Implements only the original required font methods, as an existing Java host would. */
public final class LegacyFontMetricsFixture implements FontMetrics {
    @Override
    public double width(String grapheme) { return 4.0; }

    @Override
    public double lineHeight() { return 16.0; }

    @Override
    public String pin() { return "legacy-java"; }
}
