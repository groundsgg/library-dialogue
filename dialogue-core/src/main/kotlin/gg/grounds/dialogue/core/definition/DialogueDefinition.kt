package gg.grounds.dialogue.core.definition

import java.util.Collections

/** Immutable author model shared by builders and the JSON codec. */
@JvmSuppressWildcards
class DialogueDefinition(
    private val id: String,
    private val contentVersion: String,
    private val entry: String,
    private val locales: LocaleSpec,
    private val capabilities: Capabilities,
    private val interaction: Interaction,
    private val presentation: Presentation,
    nodes: Map<String, Node>,
) {
    private val nodes = Collections.unmodifiableMap(LinkedHashMap(nodes))

    fun id(): String = id

    fun contentVersion(): String = contentVersion

    fun entry(): String = entry

    fun locales(): LocaleSpec = locales

    fun capabilities(): Capabilities = capabilities

    fun interaction(): Interaction = interaction

    fun presentation(): Presentation = presentation

    fun nodes(): Map<String, Node> = nodes

    override fun equals(other: Any?): Boolean =
        other is DialogueDefinition &&
            id == other.id &&
            contentVersion == other.contentVersion &&
            entry == other.entry &&
            locales == other.locales &&
            capabilities == other.capabilities &&
            interaction == other.interaction &&
            presentation == other.presentation &&
            nodes == other.nodes

    override fun hashCode(): Int {
        var hash = id.hashCode()
        hash = 31 * hash + contentVersion.hashCode()
        hash = 31 * hash + entry.hashCode()
        hash = 31 * hash + locales.hashCode()
        hash = 31 * hash + capabilities.hashCode()
        hash = 31 * hash + interaction.hashCode()
        hash = 31 * hash + presentation.hashCode()
        hash = 31 * hash + nodes.hashCode()
        return hash
    }

    override fun toString(): String =
        "DialogueDefinition[id=$id, contentVersion=$contentVersion, entry=$entry, locales=$locales, capabilities=$capabilities, interaction=$interaction, presentation=$presentation, nodes=$nodes]"

    class LocaleSpec(
        private val defaultTag: String,
        supported: List<String>,
    ) {
        private val supported = java.util.List.copyOf(supported)

        fun defaultTag(): String = defaultTag

        fun supported(): List<String> = supported

        override fun equals(other: Any?): Boolean =
            other is LocaleSpec && defaultTag == other.defaultTag && supported == other.supported

        override fun hashCode(): Int {
            var hash = defaultTag.hashCode()
            hash = 31 * hash + supported.hashCode()
            return hash
        }

        override fun toString(): String = "LocaleSpec[defaultTag=$defaultTag, supported=$supported]"
    }

    class Capabilities(
        required: Set<String>,
        optional: Set<String>,
    ) {
        private val required = java.util.Set.copyOf(required)
        private val optional = java.util.Set.copyOf(optional)

        fun required(): Set<String> = required

        fun optional(): Set<String> = optional

        override fun equals(other: Any?): Boolean =
            other is Capabilities && required == other.required && optional == other.optional

        override fun hashCode(): Int {
            var hash = required.hashCode()
            hash = 31 * hash + optional.hashCode()
            return hash
        }

        override fun toString(): String = "Capabilities[required=$required, optional=$optional]"
    }

    @JvmRecord
    data class LookAway(
        val enabled: Boolean,
        val exitAngleDegrees: Double,
        val exitDelayMs: Long,
        val resetAngleDegrees: Double,
    )

    @JvmRecord data class Interaction(val lookAway: LookAway, val maxDistanceBlocks: Double)

    class Presentation(
        private val focus: String,
        private val nearFraming: String,
        private val vignette: String,
        hideHud: Set<String>,
    ) {
        private val hideHud = java.util.Set.copyOf(hideHud)

        fun focus(): String = focus

        fun nearFraming(): String = nearFraming

        fun vignette(): String = vignette

        fun hideHud(): Set<String> = hideHud

        override fun equals(other: Any?): Boolean =
            other is Presentation &&
                focus == other.focus &&
                nearFraming == other.nearFraming &&
                vignette == other.vignette &&
                hideHud == other.hideHud

        override fun hashCode(): Int {
            var hash = focus.hashCode()
            hash = 31 * hash + nearFraming.hashCode()
            hash = 31 * hash + vignette.hashCode()
            hash = 31 * hash + hideHud.hashCode()
            return hash
        }

        override fun toString(): String =
            "Presentation[focus=$focus, nearFraming=$nearFraming, vignette=$vignette, hideHud=$hideHud]"
    }

    sealed interface Node

    class Message(
        private val speaker: String,
        private val text: String,
        private val gesture: String?,
        choices: List<Choice>,
        private val next: String?,
    ) : Node {
        private val choices = java.util.List.copyOf(choices)

        init {
            require(choices.isEmpty() != (next == null)) { "MESSAGE_CONTINUATION" }
        }

        fun speaker(): String = speaker

        fun text(): String = text

        fun gesture(): String? = gesture

        fun choices(): List<Choice> = choices

        fun next(): String? = next

        override fun equals(other: Any?): Boolean =
            other is Message &&
                speaker == other.speaker &&
                text == other.text &&
                gesture == other.gesture &&
                choices == other.choices &&
                next == other.next

        override fun hashCode(): Int {
            var hash = speaker.hashCode()
            hash = 31 * hash + text.hashCode()
            hash = 31 * hash + (gesture?.hashCode() ?: 0)
            hash = 31 * hash + choices.hashCode()
            hash = 31 * hash + (next?.hashCode() ?: 0)
            return hash
        }

        override fun toString(): String =
            "Message[speaker=$speaker, text=$text, gesture=$gesture, choices=$choices, next=$next]"
    }

    @JvmRecord
    data class Choice(
        val id: String,
        val text: String,
        val to: String,
        val defaultChoice: Boolean,
        val condition: Condition?,
    )

    @JvmSuppressWildcards
    class Condition(
        private val key: String,
        arguments: Map<String, ArgumentValue>,
        private val negated: Boolean,
    ) {
        private val arguments = java.util.Map.copyOf(arguments)

        fun key(): String = key

        fun arguments(): Map<String, ArgumentValue> = arguments

        fun negated(): Boolean = negated

        override fun equals(other: Any?): Boolean =
            other is Condition &&
                key == other.key &&
                arguments == other.arguments &&
                negated == other.negated

        override fun hashCode(): Int {
            var hash = key.hashCode()
            hash = 31 * hash + arguments.hashCode()
            hash = 31 * hash + negated.hashCode()
            return hash
        }

        override fun toString(): String =
            "Condition[key=$key, arguments=$arguments, negated=$negated]"
    }

    @JvmRecord data class Case(val condition: Condition, val to: String)

    class Branch(
        cases: List<Case>,
        private val defaultTo: String,
        private val unavailableTo: String,
    ) : Node {
        private val cases = java.util.List.copyOf(cases)

        fun cases(): List<Case> = cases

        fun defaultTo(): String = defaultTo

        fun unavailableTo(): String = unavailableTo

        override fun equals(other: Any?): Boolean =
            other is Branch &&
                cases == other.cases &&
                defaultTo == other.defaultTo &&
                unavailableTo == other.unavailableTo

        override fun hashCode(): Int {
            var hash = cases.hashCode()
            hash = 31 * hash + defaultTo.hashCode()
            hash = 31 * hash + unavailableTo.hashCode()
            return hash
        }

        override fun toString(): String =
            "Branch[cases=$cases, defaultTo=$defaultTo, unavailableTo=$unavailableTo]"
    }

    @JvmSuppressWildcards
    class Action(
        private val key: String,
        arguments: Map<String, ArgumentValue>,
        private val onSuccess: String,
        private val onRejected: String,
        private val onUnavailable: String,
        private val onUncertain: String,
    ) : Node {
        private val arguments = java.util.Map.copyOf(arguments)

        fun key(): String = key

        fun arguments(): Map<String, ArgumentValue> = arguments

        fun onSuccess(): String = onSuccess

        fun onRejected(): String = onRejected

        fun onUnavailable(): String = onUnavailable

        fun onUncertain(): String = onUncertain

        override fun equals(other: Any?): Boolean =
            other is Action &&
                key == other.key &&
                arguments == other.arguments &&
                onSuccess == other.onSuccess &&
                onRejected == other.onRejected &&
                onUnavailable == other.onUnavailable &&
                onUncertain == other.onUncertain

        override fun hashCode(): Int {
            var hash = key.hashCode()
            hash = 31 * hash + arguments.hashCode()
            hash = 31 * hash + onSuccess.hashCode()
            hash = 31 * hash + onRejected.hashCode()
            hash = 31 * hash + onUnavailable.hashCode()
            hash = 31 * hash + onUncertain.hashCode()
            return hash
        }

        override fun toString(): String =
            "Action[key=$key, arguments=$arguments, onSuccess=$onSuccess, onRejected=$onRejected, onUnavailable=$onUnavailable, onUncertain=$onUncertain]"
    }

    enum class Delivery {
        LOCAL,
        DURABLE,
    }

    @JvmRecord
    data class Outcome(
        val milestone: String,
        val delivery: Delivery,
        val onAcknowledged: String,
        val onUnavailable: String,
        val onUncertain: String,
    ) : Node

    @JvmRecord data class End(val reason: String) : Node
}
