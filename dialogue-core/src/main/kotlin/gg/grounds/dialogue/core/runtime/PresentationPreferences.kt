package gg.grounds.dialogue.core.runtime

import java.time.Duration

class PresentationPreferences(
    @get:JvmName("surfaceKey") val surfaceKey: String = "hud",
    @get:JvmName("graphemeDelay") val graphemeDelay: Duration = Duration.ofMillis(35),
    @get:JvmName("reducedMotion") val reducedMotion: Boolean = false,
    effects: Map<String, String> = emptyMap(),
) {
    @get:JvmName("effects") val effects: Map<String, String> = java.util.Map.copyOf(effects)

    init {
        require(!graphemeDelay.isNegative && graphemeDelay <= Duration.ofSeconds(60)) {
            "Invalid reveal delay"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PresentationPreferences &&
            surfaceKey == other.surfaceKey &&
            graphemeDelay == other.graphemeDelay &&
            reducedMotion == other.reducedMotion &&
            effects == other.effects

    override fun hashCode(): Int {
        var hash = surfaceKey.hashCode()
        hash = 31 * hash + graphemeDelay.hashCode()
        hash = 31 * hash + reducedMotion.hashCode()
        hash = 31 * hash + effects.hashCode()
        return hash
    }

    override fun toString(): String =
        "PresentationPreferences[surfaceKey=$surfaceKey, graphemeDelay=$graphemeDelay, reducedMotion=$reducedMotion, effects=$effects]"

    companion object {
        @JvmStatic fun defaults(): PresentationPreferences = PresentationPreferences()
    }
}
