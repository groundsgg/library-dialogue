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
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.Locale
import kotlin.math.abs

/** Versioned content fingerprint with no dependency on a JSON implementation. */
class ContentFingerprint private constructor() {
    companion object {
        @JvmStatic
        fun pin(definition: DialogueDefinition, texts: TextCatalog): ContentPin =
            hash(
                canonicalBytes(
                    mapOf(
                        "fingerprintVersion" to 1,
                        "definition" to definitionData(definition),
                        "textsManifest" to texts.contentPin().sha256,
                    )
                )
            )

        @JvmStatic
        fun hash(bytes: ByteArray): ContentPin =
            try {
                ContentPin(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                )
            } catch (failure: NoSuchAlgorithmException) {
                throw AssertionError(failure)
            }

        @JvmStatic
        fun definitionBytes(definition: DialogueDefinition): ByteArray =
            canonicalBytes(definitionData(definition))

        @JvmStatic
        fun argumentData(
            values: Map<String, @JvmSuppressWildcards ArgumentValue>
        ): Map<String, Any?> = values.mapValues { (_, value) ->
            when (value) {
                is ArgumentValue.Text -> value.value
                is ArgumentValue.Flag -> value.value
                is ArgumentValue.Number -> value.value()
                ArgumentValue.Nil.INSTANCE -> null
            }
        }

        @JvmStatic
        fun definitionData(d: DialogueDefinition): Map<String, Any> {
            val lookAway = d.interaction().lookAway
            val presentation = d.presentation()
            return mapOf(
                "schemaVersion" to 1,
                "id" to d.id(),
                "contentVersion" to d.contentVersion(),
                "entry" to d.entry(),
                "locales" to
                    mapOf(
                        "default" to d.locales().defaultTag(),
                        "supported" to d.locales().supported(),
                    ),
                "capabilities" to
                    mapOf(
                        "required" to d.capabilities().required().sorted(),
                        "optional" to d.capabilities().optional().sorted(),
                    ),
                "interaction" to
                    mapOf(
                        "lookAway" to
                            mapOf(
                                "enabled" to lookAway.enabled,
                                "exitAngleDegrees" to lookAway.exitAngleDegrees,
                                "exitDelayMs" to lookAway.exitDelayMs,
                                "resetAngleDegrees" to lookAway.resetAngleDegrees,
                            ),
                        "maxDistanceBlocks" to d.interaction().maxDistanceBlocks,
                    ),
                "presentation" to
                    mapOf(
                        "focus" to presentation.focus(),
                        "nearFraming" to presentation.nearFraming(),
                        "vignette" to presentation.vignette(),
                        "hideHud" to presentation.hideHud().sorted(),
                    ),
                "nodes" to d.nodes().mapValues { (_, node) -> nodeData(node) },
            )
        }

        private fun condition(condition: Condition) =
            mapOf(
                "key" to condition.key(),
                "arguments" to argumentData(condition.arguments()),
                "negated" to condition.negated(),
            )

        private fun nodeData(node: Node): Map<String, Any> =
            when (node) {
                is Message ->
                    buildMap {
                        put("type", "message")
                        put("speaker", node.speaker())
                        put("text", node.text())
                        node.gesture()?.let { put("presentation", mapOf("gesture" to it)) }
                        node.next()?.let { put("next", it) }
                        if (node.choices().isNotEmpty()) {
                            put(
                                "choices",
                                node.choices().map { choice ->
                                    buildMap {
                                        put("id", choice.id)
                                        put("text", choice.text)
                                        put("to", choice.to)
                                        put("default", choice.defaultChoice)
                                        choice.condition?.let { put("condition", condition(it)) }
                                    }
                                },
                            )
                        }
                    }
                is Branch ->
                    mapOf(
                        "type" to "branch",
                        "cases" to
                            node.cases().map {
                                mapOf("condition" to condition(it.condition), "to" to it.to)
                            },
                        "defaultTo" to node.defaultTo(),
                        "unavailableTo" to node.unavailableTo(),
                    )
                is Action ->
                    mapOf(
                        "type" to "action",
                        "action" to node.key(),
                        "arguments" to argumentData(node.arguments()),
                        "onSuccess" to node.onSuccess(),
                        "onRejected" to node.onRejected(),
                        "onUnavailable" to node.onUnavailable(),
                        "onUncertain" to node.onUncertain(),
                    )
                is Outcome ->
                    mapOf(
                        "type" to "outcome",
                        "milestone" to node.milestone,
                        "delivery" to node.delivery.name.lowercase(Locale.ROOT),
                        "onAcknowledged" to node.onAcknowledged,
                        "onUnavailable" to node.onUnavailable,
                        "onUncertain" to node.onUncertain,
                    )
                is End -> mapOf("type" to "end", "reason" to node.reason)
            }

        @JvmStatic
        fun canonicalBytes(value: Any?): ByteArray =
            buildString { write(value, this, 0) }.toByteArray()

        private fun compareCodePoints(a: String, b: String): Int {
            var x = 0
            var y = 0
            while (x < a.length && y < b.length) {
                val p = a.codePointAt(x)
                val q = b.codePointAt(y)
                if (p != q) return p.compareTo(q)
                x += Character.charCount(p)
                y += Character.charCount(q)
            }
            return (a.length - x).compareTo(b.length - y)
        }

        private fun write(value: Any?, out: StringBuilder, depth: Int) {
            require(depth <= 64) { "CANONICAL_DEPTH" }
            when (value) {
                null -> out.append("null")
                is String -> quote(value, out)
                is Boolean -> out.append(value)
                is Number -> {
                    val decimal =
                        (value as? BigDecimal ?: BigDecimal(value.toString())).stripTrailingZeros()
                    require(
                        decimal.precision() <= 128 &&
                            abs(decimal.precision().toLong() - decimal.scale() - 1) <= 308
                    ) {
                        "NUMBER_LIMIT"
                    }
                    out.append(if (decimal.signum() == 0) "0" else decimal.toPlainString())
                }
                is Map<*, *> -> {
                    val keys =
                        value.keys
                            .map { key ->
                                require(key is String) { "Nonstring map key" }
                                key
                            }
                            .sortedWith(::compareCodePoints)
                    out.append('{')
                    keys.forEachIndexed { index, key ->
                        if (index > 0) out.append(',')
                        quote(key, out)
                        out.append(':')
                        write(value[key], out, depth + 1)
                    }
                    out.append('}')
                }
                is List<*> -> {
                    out.append('[')
                    value.forEachIndexed { index, item ->
                        if (index > 0) out.append(',')
                        write(item, out, depth + 1)
                    }
                    out.append(']')
                }
                else ->
                    throw IllegalArgumentException(
                        "Not a canonical scalar or collection: " + value.javaClass.name
                    )
            }
        }

        private fun quote(value: String, out: StringBuilder) {
            require(DialogueValidator.validUnicode(value)) { "UNICODE_INVALID" }
            out.append('"')
            for (character in value) {
                when (character) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\b' -> out.append("\\b")
                    '\u000c' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else ->
                        if (character.code < 32)
                            out.append(String.format(Locale.ROOT, "\\u%04x", character.code))
                        else out.append(character)
                }
            }
            out.append('"')
        }
    }
}
