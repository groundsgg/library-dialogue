package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.presentation.DialoguePresenter

/** Native NPC answer-column options are per-viewer preferences. */
@JvmRecord
data class NpcPanelOptions(val choiceGap: Double, val choiceAngle: Double) {
    init {
        require(
            choiceGap.isFinite() &&
                choiceGap in 0.0..64.0 &&
                choiceGap * 2 == Math.rint(choiceGap * 2) &&
                choiceAngle.isFinite() &&
                choiceAngle in 0.0..35.0
        ) {
            "Invalid NPC presentation options"
        }
    }

    companion object {
        @JvmStatic
        fun from(preferences: PresentationPreferences): NpcPanelOptions =
            try {
                NpcPanelOptions(
                    preferences.effects().getOrDefault("npcChoiceGap", "12").toDouble(),
                    preferences.effects().getOrDefault("npcChoiceAngle", "12").toDouble(),
                )
            } catch (_: IllegalArgumentException) {
                throw DialoguePresenter.FallbackRequired("UNSUPPORTED_RASTER")
            }
    }
}
