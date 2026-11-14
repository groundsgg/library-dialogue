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
    double gap) {
  public enum ChoicePlacement {
    BELOW,
    RIGHT
  }

  public LayoutProfile {
    if (key == null
        || placement == null
        || !Double.isFinite(width)
        || !Double.isFinite(choiceWidth)
        || !Double.isFinite(padding)
        || !Double.isFinite(gap)
        || width <= 2 * padding
        || choiceWidth <= 2 * padding
        || padding < 0
        || gap < 0
        || maxTextLines < 1
        || choiceCapacity < 1
        || maxChoiceLines < 1) throw new IllegalArgumentException("Invalid layout profile");
  }

  public static LayoutProfile hud() {
    return new LayoutProfile("hud", 320, 320, 12, 3, 3, 2, ChoicePlacement.BELOW, 6);
  }

  public static LayoutProfile npc() {
    return new LayoutProfile("npc", 320, 240, 12, 3, 3, 2, ChoicePlacement.RIGHT, 6);
  }
}
