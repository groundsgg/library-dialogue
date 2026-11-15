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
    double choicePadding,
    Style style,
    double columnGap,
    boolean navigationButtons) {
  public LayoutProfile(
      String key,
      double width,
      double choiceWidth,
      double padding,
      int maxTextLines,
      int choiceCapacity,
      int maxChoiceLines,
      ChoicePlacement placement,
      double gap,
      double choicePadding,
      Style style,
      double columnGap) {
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
        choicePadding,
        style,
        columnGap,
        false);
  }

  public LayoutProfile(
      String key,
      double width,
      double choiceWidth,
      double padding,
      int maxTextLines,
      int choiceCapacity,
      int maxChoiceLines,
      ChoicePlacement placement,
      double gap,
      double choicePadding,
      Style style) {
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
        choicePadding,
        style,
        gap);
  }

  public enum Style {
    PANELS,
    HUD,
    HUD_SPLIT,
    HUD_CARDS
  }

  public LayoutProfile(
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
        choicePadding,
        Style.PANELS);
  }

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
        || style == null
        || !Double.isFinite(width)
        || !Double.isFinite(choiceWidth)
        || !Double.isFinite(padding)
        || !Double.isFinite(gap)
        || !Double.isFinite(choicePadding)
        || !Double.isFinite(columnGap)
        || width <= 2 * padding
        || choiceWidth <= 2 * choicePadding
        || padding < 0
        || gap < 0
        || choicePadding < 0
        || columnGap < 0
        || maxTextLines < 1
        || choiceCapacity < 1
        || maxChoiceLines < 1) throw new IllegalArgumentException("Invalid layout profile");
  }

  public static LayoutProfile hud() {
    return new LayoutProfile("hud", 320, 320, 12, 3, 3, 2, ChoicePlacement.BELOW, 6);
  }

  /** Compact HUD columns; chrome and optional overflow indicators determine the full bounds. */
  public static LayoutProfile compactHud(ChoicePlacement placement) {
    return compactHud(placement, Style.HUD);
  }

  public static LayoutProfile compactHud(ChoicePlacement placement, Style style) {
    if (style == null || style == Style.PANELS)
      throw new IllegalArgumentException("HUD style required");
    boolean below = placement == ChoicePlacement.BELOW;
    return new LayoutProfile(
        "hud-preview", below ? 240 : 162, below ? 222 : 117, 9, 3, 3, 2, placement, 3, 0, style);
  }

  public static LayoutProfile npc() {
    return npc(12);
  }

  /** Minimum clear column gap; the presenter additionally reserves selection growth. */
  public static LayoutProfile npc(double columnGap) {
    return new LayoutProfile(
        "npc", 320, 240, 12, 3, 3, 2, ChoicePlacement.RIGHT, 6, 12, Style.PANELS, columnGap, true);
  }
}
