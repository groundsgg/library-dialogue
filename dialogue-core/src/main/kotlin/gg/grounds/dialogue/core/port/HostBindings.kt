package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.text.TextResolver

@JvmSuppressWildcards
class HostBindings(
    private val generation: String,
    conditions: Map<String, ConditionProvider>,
    actions: Map<String, ActionHandler>,
    private val outcomes: OutcomeSink?,
    private val texts: TextResolver,
    private val renderer: DialogueRenderer,
    private val actors: ActorPort?,
    capabilities: Set<String>,
) {
    private val conditions = java.util.Map.copyOf(conditions)
    private val actions = java.util.Map.copyOf(actions)
    private val capabilities = java.util.Set.copyOf(capabilities)

    fun generation(): String = generation

    fun conditions(): Map<String, ConditionProvider> = conditions

    fun actions(): Map<String, ActionHandler> = actions

    fun outcomes(): OutcomeSink? = outcomes

    fun texts(): TextResolver = texts

    fun renderer(): DialogueRenderer = renderer

    fun actors(): ActorPort? = actors

    fun capabilities(): Set<String> = capabilities

    override fun equals(other: Any?): Boolean =
        other is HostBindings &&
            generation == other.generation &&
            conditions == other.conditions &&
            actions == other.actions &&
            outcomes == other.outcomes &&
            texts == other.texts &&
            renderer == other.renderer &&
            actors == other.actors &&
            capabilities == other.capabilities

    override fun hashCode(): Int {
        var hash = generation.hashCode()
        hash = 31 * hash + conditions.hashCode()
        hash = 31 * hash + actions.hashCode()
        hash = 31 * hash + (outcomes?.hashCode() ?: 0)
        hash = 31 * hash + texts.hashCode()
        hash = 31 * hash + renderer.hashCode()
        hash = 31 * hash + (actors?.hashCode() ?: 0)
        hash = 31 * hash + capabilities.hashCode()
        return hash
    }

    override fun toString(): String =
        "HostBindings[generation=$generation, conditions=$conditions, actions=$actions, outcomes=$outcomes, texts=$texts, renderer=$renderer, actors=$actors, capabilities=$capabilities]"
}
