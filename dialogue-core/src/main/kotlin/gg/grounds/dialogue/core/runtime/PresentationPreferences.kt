package gg.grounds.dialogue.core.runtime

import java.time.Duration

class PresentationPreferences(
    private val surfaceKey: String,
    private val graphemeDelay: Duration,
    private val reducedMotion: Boolean,
    effects: Map<String, String>,
) {
    private val effects = java.util.Map.copyOf(effects)

    init {
        require(!graphemeDelay.isNegative && graphemeDelay <= Duration.ofSeconds(60)) {
            "Invalid reveal delay"
        }
    }

    fun surfaceKey(): String = surfaceKey

    fun graphemeDelay(): Duration = graphemeDelay

    fun reducedMotion(): Boolean = reducedMotion

    fun effects(): Map<String, String> = effects

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
        @JvmStatic
        fun defaults(): PresentationPreferences =
            PresentationPreferences("hud", Duration.ofMillis(35), false, emptyMap())
    }
}
