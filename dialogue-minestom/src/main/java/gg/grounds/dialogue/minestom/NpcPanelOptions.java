package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import gg.grounds.dialogue.presentation.DialoguePresenter;

/** Native NPC answer-column options; values are per-viewer preferences. */
public record NpcPanelOptions(double choiceGap, double choiceAngle) {
  public NpcPanelOptions {
    if (!Double.isFinite(choiceGap)
        || choiceGap < 0
        || choiceGap > 64
        || choiceGap * 2 != Math.rint(choiceGap * 2)
        || !Double.isFinite(choiceAngle)
        || choiceAngle < 0
        || choiceAngle > 35) throw new IllegalArgumentException("Invalid NPC presentation options");
  }

  public static NpcPanelOptions from(PresentationPreferences preferences) {
    try {
      return new NpcPanelOptions(
          Double.parseDouble(preferences.effects().getOrDefault("npcChoiceGap", "12")),
          Double.parseDouble(preferences.effects().getOrDefault("npcChoiceAngle", "12")));
    } catch (IllegalArgumentException error) {
      throw new DialoguePresenter.FallbackRequired("UNSUPPORTED_RASTER");
    }
  }
}
