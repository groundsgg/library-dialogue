package gg.grounds.dialogue.core.validation

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.Branch
import gg.grounds.dialogue.core.definition.DialogueDefinition.Condition
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.definition.DialogueDefinition.Node
import gg.grounds.dialogue.core.definition.DialogueDefinition.Outcome
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.HostCatalog.Kind
import gg.grounds.dialogue.core.validation.HostCatalog.ProviderSpec
import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.util.Locale
import kotlin.math.abs

/** Stateless common validation for authored definitions. */
class DialogueValidator {
    fun validate(
        d: DialogueDefinition,
        texts: TextCatalog,
        host: HostCatalog,
        limits: ValidationLimits,
    ): ValidationReport {
        val check = Check(d, texts, host, limits)
        check.run()
        if (
            check.problems.none { it.severity == Severity.ERROR } &&
                ContentFingerprint.definitionBytes(d).size > limits.definitionBytes
        ) {
            check.problem("CONTENT_LIMIT", "/", null, "Definition exceeds byte budget")
        }
        val proof =
            if (check.problems.none { it.severity == Severity.ERROR }) {
                ValidatedDialogue.issue(d, texts, host, limits)
            } else null
        return ValidationReport(check.problems, proof)
    }

    private class Check(
        private val definition: DialogueDefinition,
        private val texts: TextCatalog,
        private val host: HostCatalog,
        private val limits: ValidationLimits,
    ) {
        val problems = ArrayList<Problem>()
        private var arguments = 0

        fun problem(code: String, path: String, node: String?, message: String) {
            problems.add(Problem(code, Severity.ERROR, "dialogue.json", path, node, message))
        }

        private fun id(value: String, path: String, node: String?) {
            if (
                value.isBlank() ||
                    value.codePointCount(0, value.length) > limits.idCodepoints ||
                    !IDENTIFIER.matches(value)
            ) {
                problem(
                    "ID_INVALID",
                    path,
                    node,
                    "Identifier must be a nonempty bounded ASCII content identifier",
                )
            }
        }

        private fun text(key: String, path: String, node: String) {
            id(key, path, node)
            for (tag in definition.locales().supported()) {
                if (texts.locales()[tag]?.containsKey(key) != true) {
                    problem("TEXT_MISSING", path, node, "Missing text key " + key + " for " + tag)
                }
            }
        }

        private fun condition(condition: Condition?, path: String, node: String) {
            if (condition != null)
                provider(condition.key(), condition.arguments(), host.conditions(), path, node)
        }

        private fun provider(
            key: String,
            values: Map<String, ArgumentValue>,
            specs: Map<String, ProviderSpec>,
            path: String,
            node: String,
        ) {
            id(key, path, node)
            arguments += values.size
            val spec = specs[key]
            if (spec == null) {
                problem("PROVIDER_UNKNOWN", path, node, "Unknown provider " + key)
                return
            }
            for ((name, shape) in spec.arguments()) {
                if (shape.required && name !in values) {
                    problem(
                        "ARGUMENT_REQUIRED",
                        path + "/arguments/" + name,
                        node,
                        "Missing required argument",
                    )
                }
            }
            for ((name, value) in values) {
                val argumentPath = path + "/arguments/" + name
                val shape = spec.arguments()[name]
                if (shape == null) {
                    problem("ARGUMENT_UNKNOWN", argumentPath, node, "Undeclared argument")
                    continue
                }
                val valid =
                    when (value) {
                        is ArgumentValue.Text -> shape.kind == Kind.TEXT
                        is ArgumentValue.Number -> shape.kind == Kind.NUMBER
                        is ArgumentValue.Flag -> shape.kind == Kind.BOOLEAN
                        ArgumentValue.Nil.INSTANCE -> shape.nullable || shape.kind == Kind.NULL
                    }
                if (!valid)
                    problem(
                        "ARGUMENT_TYPE",
                        argumentPath,
                        node,
                        "Argument has the wrong scalar type",
                    )
                if (
                    value is ArgumentValue.Text &&
                        (!validUnicode(value.value) ||
                            value.value.codePointCount(0, value.value.length) >
                                limits.textCodepoints)
                ) {
                    problem("TEXT_LIMIT", path, node, "Invalid or oversized scalar text")
                }
                if (value is ArgumentValue.Number) {
                    val number = value.value()
                    if (
                        number.precision() > 128 ||
                            abs(number.precision().toLong() - number.scale() - 1) > 308
                    ) {
                        problem(
                            "NUMBER_LIMIT",
                            path,
                            node,
                            "Number exceeds precision/exponent bounds",
                        )
                    }
                }
            }
        }

        fun run() {
            id(definition.id(), "/id", null)
            id(definition.entry(), "/entry", null)
            val version = definition.contentVersion()
            if (
                version.isBlank() ||
                    version.codePointCount(0, version.length) > 128 ||
                    !validUnicode(version)
            ) {
                problem(
                    "CONTENT_VERSION_INVALID",
                    "/contentVersion",
                    null,
                    "Content version must be bounded nonempty text",
                )
            }
            val nodes = definition.nodes()
            if (nodes.isEmpty() || nodes.size > limits.nodes)
                problem("NODE_LIMIT", "/nodes", null, "Node limit exceeded")
            if (definition.entry() !in nodes)
                problem("ENTRY_UNKNOWN", "/entry", null, "Entry node does not exist")

            val tags = HashSet<String>()
            for (tag in definition.locales().supported()) {
                val valid =
                    try {
                        val locale = Locale.Builder().setLanguageTag(tag).build()
                        locale.language.isNotEmpty() &&
                            locale.toLanguageTag() == tag &&
                            tags.add(tag)
                    } catch (_: RuntimeException) {
                        false
                    }
                if (!valid)
                    problem(
                        "LOCALE_INVALID",
                        "/locales",
                        null,
                        "Invalid or duplicate normalized locale " + tag,
                    )
            }
            if (
                tags.isEmpty() ||
                    tags.size > limits.locales ||
                    definition.locales().defaultTag() !in tags
            ) {
                problem(
                    "LOCALE_DEFAULT",
                    "/locales",
                    null,
                    "Default must belong to bounded supported locales",
                )
            }

            problems.addAll(texts.structuralProblems(limits))
            problems.addAll(host.structuralProblems(limits))
            val capabilities = definition.capabilities()
            capabilities.required().forEach { id(it, "/capabilities/required", null) }
            capabilities.optional().forEach { id(it, "/capabilities/optional", null) }
            val presentation = definition.presentation()
            presentation.hideHud().forEach { id(it, "/presentation/hideHud", null) }
            for (capability in capabilities.required()) {
                if (capability !in host.capabilities()) {
                    problem(
                        "CAPABILITY_MISSING",
                        "/capabilities/required",
                        null,
                        "Required capability unavailable: " + capability,
                    )
                }
            }
            val interaction = definition.interaction()
            val look = interaction.lookAway
            if (
                !look.exitAngleDegrees.isFinite() ||
                    !look.resetAngleDegrees.isFinite() ||
                    look.resetAngleDegrees < 0 ||
                    look.exitAngleDegrees > 180 ||
                    look.resetAngleDegrees >= look.exitAngleDegrees ||
                    look.exitDelayMs !in 0..60_000 ||
                    !interaction.maxDistanceBlocks.isFinite() ||
                    interaction.maxDistanceBlocks <= 0
            ) {
                problem("INTERACTION_INVALID", "/interaction", null, "Invalid look/distance policy")
            }
            if (
                presentation.focus() !in FOCUS_MODES ||
                    presentation.nearFraming() !in VISUAL_MODES ||
                    presentation.vignette() !in VISUAL_MODES
            ) {
                problem("PRESENTATION_INVALID", "/presentation", null, "Unknown presentation mode")
            }
            for ((name, node) in nodes) {
                val path = "/nodes/" + name
                id(name, path, name)
                for (target in targets(node)) {
                    if (target !in nodes)
                        problem("REFERENCE_UNKNOWN", path, name, "Unknown target " + target)
                }
                when (node) {
                    is Message -> message(node, path, name)
                    is Branch ->
                        node.cases().forEach { condition(it.condition, path + "/cases", name) }
                    is Action -> provider(node.key(), node.arguments(), host.actions(), path, name)
                    is Outcome -> id(node.milestone, path + "/milestone", name)
                    is End ->
                        if (node.reason !in END_REASONS) {
                            problem("END_REASON_INVALID", path, name, "Unknown neutral end reason")
                        }
                }
            }
            if (arguments > limits.arguments)
                problem("ARGUMENT_LIMIT", "/nodes", null, "Provider argument budget exceeded")
            graph(nodes)
        }

        private fun message(message: Message, path: String, name: String) {
            if (message.speaker() !in host.speakers()) {
                problem(
                    "SPEAKER_UNKNOWN",
                    path + "/speaker",
                    name,
                    "Unknown speaker " + message.speaker(),
                )
            }
            text(message.text(), path + "/text", name)
            val gesture = message.gesture()
            if (gesture != null && gesture !in host.gestures()) {
                problem("GESTURE_UNKNOWN", path + "/presentation/gesture", name, "Unknown gesture")
            }
            val choices = message.choices()
            if (choices.size > limits.choices)
                problem("CHOICE_LIMIT", path + "/choices", name, "Too many choices")
            if (choices.isNotEmpty() && choices.none { it.condition == null }) {
                problem(
                    "CHOICE_ALWAYS_AVAILABLE_REQUIRED",
                    path + "/choices",
                    name,
                    "At least one choice must have no condition",
                )
            }
            val seen = HashSet<String>()
            var defaults = 0
            for (choice in choices) {
                id(choice.id, path + "/choices", name)
                if (!seen.add(choice.id))
                    problem(
                        "CHOICE_DUPLICATE",
                        path + "/choices",
                        name,
                        "Duplicate choice ID " + choice.id,
                    )
                if (choice.defaultChoice) defaults++
                val choicePath = path + "/choices/" + choice.id
                text(choice.text, choicePath + "/text", name)
                condition(choice.condition, choicePath, name)
            }
            if (defaults > 1)
                problem(
                    "CHOICE_DEFAULTS",
                    path + "/choices",
                    name,
                    "Only one default choice allowed",
                )
        }

        private fun graph(nodes: Map<String, Node>) {
            val automatic = nodes.filterValues { it !is Message && it !is End }.keys
            val indegree = automatic.associateWithTo(HashMap()) { 0 }
            for (key in automatic) {
                for (target in targets(nodes.getValue(key))) {
                    if (target in automatic) indegree.merge(target, 1, Int::plus)
                }
            }
            val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
            var removed = 0
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                removed++
                for (target in targets(nodes.getValue(key))) {
                    if (target in automatic && indegree.merge(target, -1, Int::plus) == 0)
                        queue.add(target)
                }
            }
            if (removed != automatic.size)
                problem("AUTOMATIC_CYCLE", "/nodes", null, "Automatic-only cycles are not allowed")

            val reverse = HashMap<String, MutableList<String>>()
            for ((key, node) in nodes) {
                targets(node).forEach { target -> reverse.getOrPut(target, ::ArrayList).add(key) }
            }
            val canEnd = nodes.filterValues { it is End }.keys.toMutableSet()
            queue.addAll(canEnd)
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                for (predecessor in reverse[key].orEmpty()) {
                    if (canEnd.add(predecessor)) queue.add(predecessor)
                }
            }
            if (definition.entry() !in canEnd)
                problem("END_UNREACHABLE", "/entry", null, "Entry has no structural path to an end")

            val reachable = HashSet<String>()
            queue.add(definition.entry())
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                if (reachable.add(key)) nodes[key]?.let { queue.addAll(targets(it)) }
            }
            for (key in nodes.keys) {
                if (key !in reachable) {
                    problems.add(
                        Problem(
                            "NODE_UNREACHABLE",
                            Severity.WARNING,
                            "dialogue.json",
                            "/nodes/" + key,
                            key,
                            "Unreachable node",
                        )
                    )
                }
            }
        }
    }

    companion object {
        private val IDENTIFIER = Regex("[a-zA-Z0-9_.:/-]+")
        private val FOCUS_MODES = setOf("none", "soft")
        private val VISUAL_MODES = setOf("none", "subtle")
        private val END_REASONS =
            setOf(
                "completed",
                "cancelled",
                "looked_away",
                "out_of_range",
                "disconnected",
                "host_changed",
                "superseded",
                "host_closed",
                "failed",
            )

        @JvmStatic
        fun targets(n: Node): List<String> =
            when (n) {
                is Message ->
                    n.next()?.let { listOf(it) } ?: java.util.List.copyOf(n.choices().map { it.to })
                is Branch ->
                    java.util.List.copyOf(
                        n.cases().map { it.to } + listOf(n.defaultTo(), n.unavailableTo())
                    )
                is Action ->
                    java.util.List.of(
                        n.onSuccess(),
                        n.onRejected(),
                        n.onUnavailable(),
                        n.onUncertain(),
                    )
                is Outcome -> java.util.List.of(n.onAcknowledged, n.onUnavailable, n.onUncertain)
                is End -> emptyList()
            }

        @JvmStatic
        fun validUnicode(s: String): Boolean {
            var index = 0
            while (index < s.length) {
                val character = s[index]
                if (character.isHighSurrogate()) {
                    index++
                    if (index >= s.length || !s[index].isLowSurrogate()) return false
                } else if (character.isLowSurrogate()) {
                    return false
                }
                index++
            }
            return true
        }
    }
}
