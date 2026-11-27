package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.util.function.Consumer

/** Shared plain preference parsing, also used by NPC panels. */
class VisualOptions private constructor() {
    companion object {
        @JvmStatic
        fun enabled(
            preferences: PresentationPreferences,
            key: String,
            diagnostic: Consumer<String>,
        ): Boolean {
            val value = preferences.effects.getOrDefault(key, "false")
            if (value != "true" && value != "false") {
                diagnostic.accept("INVALID_EFFECT_VALUE:$key")
                return false
            }
            return value == "true"
        }
    }
}
