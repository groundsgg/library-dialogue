package gg.grounds.dialogue.presentation;

public record LayoutProfile(
    String key,
    double width,
    double choiceWidth,
    double padding,
    int maxTextLines,
    int choiceCapacity,
    int maxChoiceLines,
    ChoicePlacement placement,
    double gap,
    double choicePadding) {
  public LayoutProfile(
      String key,
      double width,
      double choiceWidth,
      double padding,
      int maxTextLines,
      int choiceCapacity,
      int maxChoiceLines,
      ChoicePlacement placement,
      double gap) {
    this(
        key,
        width,
        choiceWidth,
        padding,
        maxTextLines,
        choiceCapacity,
        maxChoiceLines,
        placement,
        gap,
        padding);
  }

  public enum ChoicePlacement {
    BELOW,
    RIGHT,
    LEFT
  }

  public LayoutProfile {
    if (key == null
        || placement == null
        || !Double.isFinite(width)
        || !Double.isFinite(choiceWidth)
        || !Double.isFinite(padding)
        || !Double.isFinite(gap)
        || !Double.isFinite(choicePadding)
        || width <= 2 * padding
        || choiceWidth <= 2 * choicePadding
        || padding < 0
        || gap < 0
        || choicePadding < 0
        || maxTextLines < 1
        || choiceCapacity < 1
        || maxChoiceLines < 1) throw new IllegalArgumentException("Invalid layout profile");
  }

  public static LayoutProfile hud() {
    return new LayoutProfile("hud", 320, 320, 12, 3, 3, 2, ChoicePlacement.BELOW, 6);
  }

  /** Compact HUD presets keep both side columns within a 292px composition. */
  public static LayoutProfile compactHud(ChoicePlacement placement) {
    boolean below = placement == ChoicePlacement.BELOW;
    return new LayoutProfile(
        "hud-compact", below ? 240 : 160, below ? 192 : 128, 8, 3, 3, 2, placement, 4, 4);
  }

  public static LayoutProfile npc() {
    return new LayoutProfile("npc", 320, 240, 12, 3, 3, 2, ChoicePlacement.RIGHT, 6);
  }
}
