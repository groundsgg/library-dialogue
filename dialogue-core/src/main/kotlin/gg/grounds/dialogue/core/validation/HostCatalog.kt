package gg.grounds.dialogue.core.validation

import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity

class HostCatalog(
    private val generation: String,
    speakers: Set<String>,
    conditions: Map<String, ProviderSpec>,
    actions: Map<String, ProviderSpec>,
    gestures: Set<String>,
    capabilities: Set<String>,
    private val durableOutcomes: Boolean,
) {
    private val speakers = java.util.Set.copyOf(speakers)
    private val conditions = java.util.Map.copyOf(conditions)
    private val actions = java.util.Map.copyOf(actions)
    private val gestures = java.util.Set.copyOf(gestures)
    private val capabilities = java.util.Set.copyOf(capabilities)

    fun generation(): String = generation

    fun speakers(): Set<String> = speakers

    fun conditions(): Map<String, ProviderSpec> = conditions

    fun actions(): Map<String, ProviderSpec> = actions

    fun gestures(): Set<String> = gestures

    fun capabilities(): Set<String> = capabilities

    fun durableOutcomes(): Boolean = durableOutcomes

    override fun equals(other: Any?): Boolean =
        other is HostCatalog &&
            generation == other.generation &&
            speakers == other.speakers &&
            conditions == other.conditions &&
            actions == other.actions &&
            gestures == other.gestures &&
            capabilities == other.capabilities &&
            durableOutcomes == other.durableOutcomes

    override fun hashCode(): Int {
        var hash = generation.hashCode()
        hash = 31 * hash + speakers.hashCode()
        hash = 31 * hash + conditions.hashCode()
        hash = 31 * hash + actions.hashCode()
        hash = 31 * hash + gestures.hashCode()
        hash = 31 * hash + capabilities.hashCode()
        hash = 31 * hash + durableOutcomes.hashCode()
        return hash
    }

    override fun toString(): String =
        "HostCatalog[generation=$generation, speakers=$speakers, conditions=$conditions, actions=$actions, gestures=$gestures, capabilities=$capabilities, durableOutcomes=$durableOutcomes]"

    /** Shared validation includes declarations that no current dialogue references. */
    fun structuralProblems(limits: ValidationLimits): List<Problem> {
        val problems = ArrayList<Problem>()
        fun report(code: String, path: String, message: String) {
            problems.add(
                Problem(code, Severity.ERROR, "context/host-catalog.json", path, null, message)
            )
        }
        fun id(value: String, path: String) {
            if (
                value.isBlank() ||
                    value.codePointCount(0, value.length) > limits.idCodepoints ||
                    !IDENTIFIER.matches(value)
            ) {
                report("ID_INVALID", path, "Invalid declaration identifier")
            }
        }
        if (
            generation.isBlank() ||
                generation.codePointCount(0, generation.length) > limits.idCodepoints ||
                !DialogueValidator.validUnicode(generation)
        ) {
            report("HOST_GENERATION_INVALID", "/generation", "Invalid host generation")
        }
        speakers.forEach { id(it, "/speakers") }
        gestures.forEach { id(it, "/gestures") }
        capabilities.forEach { id(it, "/capabilities") }
        java.util.Map.of("conditions", conditions, "actions", actions).forEach { (group, providers)
            ->
            providers.forEach { (key, spec) ->
                id(key, "/$group")
                spec.arguments().keys.forEach { id(it, "/$group/$key/arguments") }
            }
        }
        return java.util.List.copyOf(problems)
    }

    enum class Kind {
        TEXT,
        NUMBER,
        BOOLEAN,
        NULL,
    }

    @JvmRecord data class ArgumentSpec(val kind: Kind, val required: Boolean, val nullable: Boolean)

    class ProviderSpec(
        arguments: Map<String, ArgumentSpec>,
        private val durable: Boolean,
    ) {
        private val arguments = java.util.Map.copyOf(arguments)

        fun arguments(): Map<String, ArgumentSpec> = arguments

        fun durable(): Boolean = durable

        override fun equals(other: Any?): Boolean =
            other is ProviderSpec && arguments == other.arguments && durable == other.durable

        override fun hashCode(): Int {
            var hash = arguments.hashCode()
            hash = 31 * hash + durable.hashCode()
            return hash
        }

        override fun toString(): String = "ProviderSpec[arguments=$arguments, durable=$durable]"
    }

    private companion object {
        val IDENTIFIER = Regex("[a-zA-Z0-9_.:/-]+")
    }
}
